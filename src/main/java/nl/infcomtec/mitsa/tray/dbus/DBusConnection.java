/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa.tray.dbus;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal D-Bus session-bus client: connects to the Unix socket named by
 * $DBUS_SESSION_BUS_ADDRESS, performs the AUTH EXTERNAL handshake, sends
 * Hello, and dispatches method calls/replies. Deliberately narrow — only
 * what MITSA's StatusNotifierItem tray backend needs. No abstract-namespace
 * or TCP transport support; those aren't used by any desktop this targets.
 *
 * Reads and writes go straight through SocketChannel's own ByteBuffer-based
 * read/write, not the java.io.Channels stream adapters: those adapters
 * serialize on internal channel-blocking state and deadlock a concurrent
 * reader thread against a writer thread on the same channel, which this
 * class needs (one reader thread dispatching replies, callers writing calls
 * from whatever thread they're on).
 */
public class DBusConnection implements AutoCloseable {

    private final SocketChannel channel;
    private final Object writeLock = new Object();
    private final AtomicInteger serialCounter = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, LinkedBlockingQueue<DBusMessage>> pending = new ConcurrentHashMap<>();
    private volatile MethodCallHandler methodCallHandler;
    private volatile boolean closed;
    private String uniqueName;
    private Thread readerThread;

    public interface MethodCallHandler {

        /** Called on the reader thread for incoming method calls; return the reply message, or null to send no reply. */
        DBusMessage handle(DBusMessage call);
    }

    private DBusConnection(SocketChannel channel) {
        this.channel = channel;
    }

    /**
     * Connects and authenticates. Returns null (not an exception) if no
     * session bus is reachable at all — headless environments and most
     * non-desktop contexts — so callers can fail fast into a non-D-Bus
     * fallback rather than partially initializing.
     */
    public static DBusConnection connectSessionBus() {
        String address = System.getenv("DBUS_SESSION_BUS_ADDRESS");
        if (address == null || address.isEmpty()) {
            return null;
        }
        Path socketPath = parseUnixPath(address);
        if (socketPath == null) {
            return null;
        }
        try {
            SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            ch.connect(UnixDomainSocketAddress.of(socketPath));
            DBusConnection conn = new DBusConnection(ch);
            conn.authenticate();
            conn.startReaderThread();
            conn.sayHello();
            return conn;
        } catch (IOException ex) {
            return null;
        }
    }

    private static Path parseUnixPath(String address) {
        for (String part : address.split(";")) {
            if (part.startsWith("unix:")) {
                for (String kv : part.substring(5).split(",")) {
                    if (kv.startsWith("path=")) {
                        return Path.of(kv.substring(5));
                    }
                }
            }
        }
        return null;
    }

    private void authenticate() throws IOException {
        writeRaw(new byte[]{0}); // initial NUL byte required before AUTH
        long uid = uid();
        String hex = toHex(String.valueOf(uid).getBytes());
        writeLine("AUTH EXTERNAL " + hex);
        String response = readLine();
        if (!response.startsWith("OK")) {
            throw new IOException("D-Bus AUTH EXTERNAL rejected: " + response);
        }
        writeLine("BEGIN");
    }

    private static long uid() {
        try {
            Process p = new ProcessBuilder("id", "-u").start();
            String s = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return Long.parseLong(s);
        } catch (Exception ex) {
            throw new RuntimeException("Could not determine uid for D-Bus AUTH EXTERNAL", ex);
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private void writeLine(String s) throws IOException {
        writeRaw((s + "\r\n").getBytes());
    }

    private void writeRaw(byte[] bytes) throws IOException {
        channel.write(ByteBuffer.wrap(bytes));
    }

    /** Reads one byte at a time until \n, used only for the pre-binary AUTH handshake. */
    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        ByteBuffer one = ByteBuffer.allocate(1);
        while (true) {
            one.clear();
            int r = channel.read(one);
            if (r < 0) {
                throw new IOException("D-Bus connection closed during AUTH");
            }
            char c = (char) one.get(0);
            if (c == '\r') {
                continue;
            }
            if (c == '\n') {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private void sayHello() throws IOException {
        DBusMessage hello = DBusMessage.methodCall("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "Hello");
        DBusMessage reply = call(hello, 5000);
        if (reply == null || reply.args.isEmpty()) {
            throw new IOException("D-Bus Hello did not return a unique name");
        }
        uniqueName = (String) reply.args.get(0);
    }

    public String uniqueName() {
        return uniqueName;
    }

    /** True if a service with the given well-known name currently owns it on the bus. */
    public boolean nameHasOwner(String name) throws IOException {
        DBusMessage msg = DBusMessage.methodCall("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "NameHasOwner");
        msg.arg('s', name);
        DBusMessage reply = call(msg, 3000);
        return reply != null && !reply.args.isEmpty() && Boolean.TRUE.equals(reply.args.get(0));
    }

    /** Sends a method call and blocks for its reply, up to timeoutMs. Returns null on timeout. */
    public DBusMessage call(DBusMessage msg, long timeoutMs) throws IOException {
        int serial = serialCounter.getAndIncrement();
        msg.serial = serial;
        LinkedBlockingQueue<DBusMessage> replyQueue = new LinkedBlockingQueue<>(1);
        pending.put(serial, replyQueue);
        try {
            send(msg);
            DBusMessage reply = replyQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (reply != null && reply.messageType == DBusMessage.TYPE_ERROR) {
                throw new IOException("D-Bus error: " + reply.errorName);
            }
            return reply;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            pending.remove(serial);
        }
    }

    /** Sends a message without waiting for a reply (signals, or fire-and-forget calls). */
    public void send(DBusMessage msg) throws IOException {
        if (msg.serial == 0) {
            msg.serial = serialCounter.getAndIncrement();
        }
        byte[] bytes = msg.marshal();
        synchronized (writeLock) {
            channel.write(ByteBuffer.wrap(bytes));
        }
    }

    public void setMethodCallHandler(MethodCallHandler handler) {
        this.methodCallHandler = handler;
    }

    private void startReaderThread() {
        readerThread = new Thread(this::readLoop, "mitsa-dbus-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        try {
            while (!closed) {
                DBusMessage msg = DBusMessage.read(channel);
                if (msg.messageType == DBusMessage.TYPE_METHOD_RETURN || msg.messageType == DBusMessage.TYPE_ERROR) {
                    if (msg.replySerial != null) {
                        LinkedBlockingQueue<DBusMessage> q = pending.get(msg.replySerial);
                        if (q != null) {
                            q.offer(msg);
                        }
                    }
                } else if (msg.messageType == DBusMessage.TYPE_METHOD_CALL) {
                    replyToCall(msg);
                }
                // signals (TYPE_SIGNAL) are ignored for now; MITSA's tray doesn't need any yet
            }
        } catch (IOException ex) {
            // connection closed or peer gone; nothing more to read
        }
    }

    /**
     * Every incoming method call gets a reply, even an unhandled one - a call
     * this connection doesn't answer at all leaves the caller blocked on
     * NoReply and, per this D-Bus daemon's actual observed behavior, gets
     * this whole connection dropped from the bus. A handler throwing must
     * not kill the reader thread either, for the same reason.
     */
    private void replyToCall(DBusMessage call) throws IOException {
        MethodCallHandler h = methodCallHandler;
        DBusMessage reply = null;
        try {
            if (h != null) {
                reply = h.handle(call);
            }
        } catch (RuntimeException ex) {
            reply = errorReply(call, "org.freedesktop.DBus.Error.Failed", ex.toString());
        }
        if (reply == null) {
            reply = errorReply(call, "org.freedesktop.DBus.Error.UnknownMethod", "No such method: " + call.iface + "." + call.member);
        }
        reply.replySerial = call.serial;
        reply.destination = call.sender;
        send(reply);
    }

    private DBusMessage errorReply(DBusMessage call, String errorName, String message) {
        DBusMessage reply = new DBusMessage();
        reply.messageType = DBusMessage.TYPE_ERROR;
        reply.errorName = errorName;
        reply.arg('s', message);
        return reply;
    }

    @Override
    public void close() {
        closed = true;
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }
}
