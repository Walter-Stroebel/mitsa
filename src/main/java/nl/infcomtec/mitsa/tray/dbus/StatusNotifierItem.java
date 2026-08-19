/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa.tray.dbus;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Exposes a MITSA tray icon as an org.kde.StatusNotifierItem D-Bus object,
 * for desktops (this machine's Cinnamon/Mint build among them) where AWT's
 * java.awt.SystemTray reports unsupported because the legacy XEmbed tray
 * protocol it speaks isn't serviced, while StatusNotifierItem is.
 *
 * Registers itself at /StatusNotifierItem on a caller-supplied connection,
 * answers Properties.Get/GetAll and the Activate method, and calls
 * RegisterStatusNotifierItem on org.kde.StatusNotifierWatcher. Left-click
 * (Activate) is the only interaction wired up for v1; the separate
 * com.canonical.dbusmenu protocol for a native right-click menu is not
 * implemented - callers should open their own Swing popup from onActivate.
 */
public class StatusNotifierItem {

    private static final String OBJECT_PATH = "/StatusNotifierItem";
    private static final String IFACE = "org.kde.StatusNotifierItem";
    private static final String PROPERTIES_IFACE = "org.freedesktop.DBus.Properties";

    public interface ActivateListener {

        void onActivate(int x, int y);
    }

    private final DBusConnection conn;
    private final String id;
    private final String title;
    private byte[] iconPixmapBody;
    private volatile ActivateListener activateListener;

    public StatusNotifierItem(DBusConnection conn, String id, String title) {
        this.conn = conn;
        this.id = id;
        this.title = title;
    }

    public void setIcon(Image image, Dimension size, Color tint) {
        BufferedImage argb = toArgb(image, size);
        iconPixmapBody = encodeIconPixmapArray(argb);
    }

    public void setActivateListener(ActivateListener listener) {
        this.activateListener = listener;
    }

    /** Registers the D-Bus object and announces it to the watcher. Call after setIcon. */
    public void register() throws IOException {
        conn.setMethodCallHandler(new Dispatcher());
        String busName = "org.mitsa.trayicon" + ProcessHandle.current().pid();
        DBusMessage req = DBusMessage.methodCall("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName");
        req.arg('s', busName);
        req.arg('u', 0);
        conn.call(req, 3000);

        DBusMessage regMsg = DBusMessage.methodCall("org.kde.StatusNotifierWatcher", "/StatusNotifierWatcher", "org.kde.StatusNotifierWatcher", "RegisterStatusNotifierItem");
        regMsg.arg('s', conn.uniqueName());
        conn.call(regMsg, 3000);
    }

    private BufferedImage toArgb(Image image, Dimension size) {
        BufferedImage bi = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        bi.createGraphics().drawImage(image, 0, 0, size.width, size.height, null);
        return bi;
    }

    /** Encodes one ARRAY of STRUCT(INT32 width, INT32 height, ARRAY BYTE argb) - the IconPixmap property's body, "a(iiay)". ARGB32 bytes are big-endian per the StatusNotifierItem spec, unlike the rest of this D-Bus connection's little-endian wire format. */
    private byte[] encodeIconPixmapArray(BufferedImage img) {
        ByteArrayOutputStream structBytes = new ByteArrayOutputStream();
        writeInt32(structBytes, img.getWidth());
        writeInt32(structBytes, img.getHeight());
        int pixelCount = img.getWidth() * img.getHeight();
        writeInt32(structBytes, pixelCount * 4);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                structBytes.write((argb >> 24) & 0xFF);
                structBytes.write((argb >> 16) & 0xFF);
                structBytes.write((argb >> 8) & 0xFF);
                structBytes.write(argb & 0xFF);
            }
        }
        byte[] structs = structBytes.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUInt32(out, structs.length);
        padTo(out, 8); // array of struct is 8-byte aligned
        out.write(structs, 0, structs.length);
        return out.toByteArray();
    }

    private void writeInt32(ByteArrayOutputStream out, int v) {
        writeUInt32(out, v);
    }

    private void writeUInt32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private void padTo(ByteArrayOutputStream out, int boundary) {
        int pad = (boundary - (out.size() % boundary)) % boundary;
        for (int i = 0; i < pad; i++) {
            out.write(0);
        }
    }

    private class Dispatcher implements DBusConnection.MethodCallHandler {

        @Override
        public DBusMessage handle(DBusMessage call) {
            if (!OBJECT_PATH.equals(call.path)) {
                return null;
            }
            if ("org.freedesktop.DBus.Introspectable".equals(call.iface) && "Introspect".equals(call.member)) {
                return handleIntrospect();
            }
            if ("org.freedesktop.DBus.Peer".equals(call.iface) && "Ping".equals(call.member)) {
                DBusMessage reply = new DBusMessage();
                reply.messageType = DBusMessage.TYPE_METHOD_RETURN;
                return reply;
            }
            if (PROPERTIES_IFACE.equals(call.iface)) {
                return handleProperties(call);
            }
            if (IFACE.equals(call.iface) && "Activate".equals(call.member)) {
                return handleActivate(call);
            }
            if (IFACE.equals(call.iface) && ("ContextMenu".equals(call.member) || "SecondaryActivate".equals(call.member) || "Scroll".equals(call.member))) {
                DBusMessage reply = new DBusMessage();
                reply.messageType = DBusMessage.TYPE_METHOD_RETURN;
                return reply;
            }
            return null;
        }

        private DBusMessage handleIntrospect() {
            String xml = "<!DOCTYPE node PUBLIC \"-//freedesktop//DTD D-BUS Object Introspection 1.0//EN\" "
                    + "\"http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd\">"
                    + "<node><interface name=\"org.kde.StatusNotifierItem\">"
                    + "<property name=\"Category\" type=\"s\" access=\"read\"/>"
                    + "<property name=\"Id\" type=\"s\" access=\"read\"/>"
                    + "<property name=\"Title\" type=\"s\" access=\"read\"/>"
                    + "<property name=\"Status\" type=\"s\" access=\"read\"/>"
                    + "<property name=\"IconPixmap\" type=\"a(iiay)\" access=\"read\"/>"
                    + "<method name=\"Activate\"><arg type=\"i\" direction=\"in\"/><arg type=\"i\" direction=\"in\"/></method>"
                    + "<method name=\"ContextMenu\"><arg type=\"i\" direction=\"in\"/><arg type=\"i\" direction=\"in\"/></method>"
                    + "<method name=\"SecondaryActivate\"><arg type=\"i\" direction=\"in\"/><arg type=\"i\" direction=\"in\"/></method>"
                    + "<method name=\"Scroll\"><arg type=\"i\" direction=\"in\"/><arg type=\"s\" direction=\"in\"/></method>"
                    + "</interface></node>";
            DBusMessage reply = new DBusMessage();
            reply.messageType = DBusMessage.TYPE_METHOD_RETURN;
            reply.arg('s', xml);
            return reply;
        }

        private DBusMessage handleActivate(DBusMessage call) {
            int x = call.args.size() > 0 ? (Integer) call.args.get(0) : 0;
            int y = call.args.size() > 1 ? (Integer) call.args.get(1) : 0;
            ActivateListener listener = activateListener;
            if (listener != null) {
                listener.onActivate(x, y);
            }
            DBusMessage reply = new DBusMessage();
            reply.messageType = DBusMessage.TYPE_METHOD_RETURN;
            return reply;
        }

        private DBusMessage handleProperties(DBusMessage call) {
            if ("Get".equals(call.member)) {
                String propName = call.args.size() > 1 ? (String) call.args.get(1) : "";
                return replyGet(propName);
            }
            if ("GetAll".equals(call.member)) {
                return replyGetAll();
            }
            return null;
        }

        private DBusMessage replyGet(String propName) {
            DBusMessage reply = new DBusMessage();
            reply.messageType = DBusMessage.TYPE_METHOD_RETURN;
            switch (propName) {
                case "Category":
                    reply.arg('v', new DBusMessage.Variant("s", "ApplicationStatus"));
                    break;
                case "Id":
                    reply.arg('v', new DBusMessage.Variant("s", id));
                    break;
                case "Title":
                    reply.arg('v', new DBusMessage.Variant("s", title));
                    break;
                case "Status":
                    reply.arg('v', new DBusMessage.Variant("s", "Active"));
                    break;
                case "IconPixmap":
                    reply.signature = "v";
                    reply.rawBody("v", wrapVariant("a(iiay)", iconPixmapBody));
                    break;
                default:
                    reply.arg('v', new DBusMessage.Variant("s", ""));
                    break;
            }
            return reply;
        }

        private DBusMessage replyGetAll() {
            ByteArrayOutputStream dict = new ByteArrayOutputStream();
            ByteArrayOutputStream entries = new ByteArrayOutputStream();
            writeDictEntryString(entries, "Category", "ApplicationStatus");
            writeDictEntryString(entries, "Id", id);
            writeDictEntryString(entries, "Title", title);
            writeDictEntryString(entries, "Status", "Active");
            writeDictEntryIconPixmap(entries);
            byte[] entryBytes = entries.toByteArray();

            writeUInt32(dict, entryBytes.length);
            padTo(dict, 8);
            dict.write(entryBytes, 0, entryBytes.length);

            DBusMessage reply = new DBusMessage();
            reply.messageType = DBusMessage.TYPE_METHOD_RETURN;
            reply.rawBody("a{sv}", dict.toByteArray());
            return reply;
        }

        private void writeDictEntryString(ByteArrayOutputStream out, String key, String value) {
            padTo(out, 8); // dict-entry (struct-like) alignment
            writeString(out, key);
            writeVariantSignature(out, "s");
            padTo(out, 4); // variant's inner value (STRING) alignment
            writeString(out, value);
        }

        private void writeDictEntryIconPixmap(ByteArrayOutputStream out) {
            padTo(out, 8); // dict-entry (struct-like) alignment
            writeString(out, "IconPixmap");
            writeVariantSignature(out, "a(iiay)");
            // iconPixmapBody = [UINT32 length][pad to 8][struct elements...];
            // its own internal padding assumed an 8-aligned start (array-of-struct
            // needs the length prefix 4-aligned, then contents 8-aligned) - align
            // to 8 directly here, which satisfies both.
            padTo(out, 8);
            if (iconPixmapBody != null) {
                out.write(iconPixmapBody, 0, iconPixmapBody.length);
            } else {
                writeUInt32(out, 0);
            }
        }

        private void writeVariantSignature(ByteArrayOutputStream out, String sig) {
            byte[] b = sig.getBytes();
            out.write(b.length);
            out.write(b, 0, b.length);
            out.write(0);
        }

        private void writeString(ByteArrayOutputStream out, String s) {
            byte[] b = s.getBytes();
            writeUInt32(out, b.length);
            out.write(b, 0, b.length);
            out.write(0);
        }

        private byte[] wrapVariant(String innerSig, byte[] innerBody) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] sig = innerSig.getBytes();
            out.write(sig.length);
            out.write(sig, 0, sig.length);
            out.write(0);
            padTo(out, 8);
            if (innerBody != null) {
                out.write(innerBody, 0, innerBody.length);
            }
            return out.toByteArray();
        }
    }
}
