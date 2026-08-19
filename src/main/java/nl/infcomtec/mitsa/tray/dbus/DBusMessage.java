/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.mitsa.tray.dbus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * A single D-Bus message: header fields plus a body, marshalled per the
 * D-Bus wire protocol (little-endian only, this implementation never sends
 * big-endian). Supports only the type subset MITSA's tray actually needs:
 * BYTE, BOOLEAN, INT32, UINT32, STRING, OBJECT_PATH, VARIANT, ARRAY, STRUCT.
 */
public class DBusMessage {

    public static final byte TYPE_METHOD_CALL = 1;
    public static final byte TYPE_METHOD_RETURN = 2;
    public static final byte TYPE_ERROR = 3;
    public static final byte TYPE_SIGNAL = 4;

    public static final byte FIELD_PATH = 1;
    public static final byte FIELD_INTERFACE = 2;
    public static final byte FIELD_MEMBER = 3;
    public static final byte FIELD_ERROR_NAME = 4;
    public static final byte FIELD_REPLY_SERIAL = 5;
    public static final byte FIELD_DESTINATION = 6;
    public static final byte FIELD_SENDER = 7;
    public static final byte FIELD_SIGNATURE = 8;

    public byte messageType;
    public byte flags;
    public int serial;
    public String path;
    public String iface;
    public String member;
    public String errorName;
    public Integer replySerial;
    public String destination;
    public String sender;
    public String signature = "";
    public final List<Object> args = new ArrayList<>();

    public static DBusMessage methodCall(String destination, String path, String iface, String member) {
        DBusMessage m = new DBusMessage();
        m.messageType = TYPE_METHOD_CALL;
        m.destination = destination;
        m.path = path;
        m.iface = iface;
        m.member = member;
        return m;
    }

    /** Appends one argument, extending the signature by one type code. */
    public DBusMessage arg(char typeCode, Object value) {
        signature += typeCode;
        args.add(value);
        return this;
    }

    private byte[] rawBody;

    /**
     * Sets a pre-marshalled body directly, bypassing the per-argument marshaller.
     * For signatures this class's generic marshaller doesn't support (STRUCT,
     * dict-entries) - used by StatusNotifierItem for its IconPixmap/GetAll
     * replies, which are each one fixed, hand-built shape rather than a general
     * case worth teaching the marshaller.
     */
    public DBusMessage rawBody(String sig, byte[] body) {
        signature = sig;
        rawBody = body;
        return this;
    }

    // ---- marshalling ----

    byte[] marshal() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (rawBody != null) {
            body.write(rawBody, 0, rawBody.length);
        } else {
            marshalBody(body);
        }
        byte[] bodyBytes = body.toByteArray();

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write('l'); // little-endian
        header.write(messageType);
        header.write(flags);
        header.write(1); // protocol version

        writeUInt32(header, bodyBytes.length);
        writeUInt32(header, serial);

        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        if (path != null) {
            writeHeaderField(fields, FIELD_PATH, 'o', path);
        }
        if (iface != null) {
            writeHeaderField(fields, FIELD_INTERFACE, 's', iface);
        }
        if (member != null) {
            writeHeaderField(fields, FIELD_MEMBER, 's', member);
        }
        if (errorName != null) {
            writeHeaderField(fields, FIELD_ERROR_NAME, 's', errorName);
        }
        if (replySerial != null) {
            align(fields, 8);
            fields.write(FIELD_REPLY_SERIAL);
            writeSignatureValue(fields, "u");
            writeUInt32(fields, replySerial);
        }
        if (destination != null) {
            writeHeaderField(fields, FIELD_DESTINATION, 's', destination);
        }
        if (!signature.isEmpty()) {
            align(fields, 8);
            fields.write(FIELD_SIGNATURE);
            writeSignatureValue(fields, "g");
            writeSignatureValue(fields, signature);
        }
        byte[] fieldBytes = fields.toByteArray();
        writeUInt32(header, fieldBytes.length);
        header.write(fieldBytes, 0, fieldBytes.length);
        padTo(header, 8);

        byte[] headerBytes = header.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(headerBytes, 0, headerBytes.length);
        out.write(bodyBytes, 0, bodyBytes.length);
        return out.toByteArray();
    }

    private void writeHeaderField(ByteArrayOutputStream out, byte code, char sigChar, String value) {
        align(out, 8);
        out.write(code);
        // header field value is STRUCT(BYTE code, VARIANT value); the variant's
        // own inner signature is the field's actual type (o/s), not literally "g" -
        // only the FIELD_SIGNATURE field's variant legitimately contains "g".
        writeSignatureValue(out, String.valueOf(sigChar));
        if (sigChar == 'o' || sigChar == 's') {
            writeString(out, value);
        }
    }

    private void writeSignatureValue(ByteArrayOutputStream out, String sig) {
        byte[] b = sig.getBytes();
        out.write(b.length);
        writeBytes(out, b);
        out.write(0);
    }

    private void marshalBody(ByteArrayOutputStream out) {
        int i = 0;
        int argIdx = 0;
        while (i < signature.length()) {
            char c = signature.charAt(i);
            writeValue(out, c, args.get(argIdx++));
            i++;
        }
    }

    private void writeValue(ByteArrayOutputStream out, char type, Object value) {
        switch (type) {
            case 'y':
                out.write(((Number) value).intValue() & 0xFF);
                break;
            case 'b':
                align(out, 4);
                writeUInt32(out, ((Boolean) value) ? 1 : 0);
                break;
            case 'i':
            case 'u':
                align(out, 4);
                writeUInt32(out, ((Number) value).intValue());
                break;
            case 's':
            case 'o':
                writeString(out, (String) value);
                break;
            case 'g':
                writeSignatureValue(out, (String) value);
                break;
            case 'v': {
                Variant variant = (Variant) value;
                writeSignatureValue(out, variant.signature);
                writeValue(out, variant.signature.charAt(0), variant.value);
                break;
            }
            case 'a':
                writeArray(out, value);
                break;
            default:
                throw new IllegalArgumentException("Unsupported D-Bus type code: " + type);
        }
    }

    @SuppressWarnings("unchecked")
    private void writeArray(ByteArrayOutputStream out, Object value) {
        if (value instanceof DBusArray) {
            DBusArray a = (DBusArray) value;
            align(out, 4);
            ByteArrayOutputStream elems = new ByteArrayOutputStream();
            char elemType = a.elementSignature.charAt(0);
            if (elemType == 'y') {
                // byte array: no per-element alignment/padding
                byte[] raw = (byte[]) a.elements;
                writeUInt32(out, raw.length);
                writeBytes(out, raw);
                return;
            }
            for (Object el : (List<Object>) a.elements) {
                writeValue(elems, elemType, el);
            }
            byte[] elemBytes = elems.toByteArray();
            writeUInt32(out, elemBytes.length);
            padTo(out, alignmentFor(elemType));
            writeBytes(out, elemBytes);
        } else {
            throw new IllegalArgumentException("Array argument must be a DBusArray");
        }
    }

    private void writeString(ByteArrayOutputStream out, String s) {
        align(out, 4);
        byte[] b = s.getBytes();
        writeUInt32(out, b.length);
        writeBytes(out, b);
        out.write(0);
    }

    private void writeBytes(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }

    private void writeUInt32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private int alignmentFor(char type) {
        switch (type) {
            case 'y':
                return 1;
            case 'b':
            case 'i':
            case 'u':
            case 's':
            case 'o':
            case 'a':
                return 4;
            default:
                return 8;
        }
    }

    private void align(ByteArrayOutputStream out, int boundary) {
        padTo(out, boundary);
    }

    private void padTo(ByteArrayOutputStream out, int boundary) {
        int pos = out.size();
        int pad = (boundary - (pos % boundary)) % boundary;
        for (int i = 0; i < pad; i++) {
            out.write(0);
        }
    }

    // ---- unmarshalling ----

    /** Reads one complete message from the channel, blocking until it arrives. */
    public static DBusMessage read(SocketChannel in) throws IOException {
        byte[] fixedHeader = readFully(in, 12);
        if (fixedHeader[0] != 'l') {
            throw new IOException("Only little-endian D-Bus messages are supported");
        }
        DBusMessage m = new DBusMessage();
        m.messageType = fixedHeader[1];
        m.flags = fixedHeader[2];
        int bodyLength = readInt32LE(fixedHeader, 4);
        m.serial = readInt32LE(fixedHeader, 8);

        byte[] fieldsLenBytes = readFully(in, 4);
        int fieldsLen = readInt32LE(fieldsLenBytes, 0);
        byte[] fields = readFully(in, fieldsLen);
        int consumed = 12 + 4 + fieldsLen;
        int pad = (8 - (consumed % 8)) % 8;
        readFully(in, pad);

        Cursor fc = new Cursor(fields);
        while (fc.pos < fields.length) {
            fc.align(8);
            if (fc.pos >= fields.length) {
                break;
            }
            byte code = fields[fc.pos++];
            String sig = fc.readSignature();
            char t = sig.charAt(0);
            Object val = fc.readValue(t);
            switch (code) {
                case FIELD_PATH:
                    m.path = (String) val;
                    break;
                case FIELD_INTERFACE:
                    m.iface = (String) val;
                    break;
                case FIELD_MEMBER:
                    m.member = (String) val;
                    break;
                case FIELD_ERROR_NAME:
                    m.errorName = (String) val;
                    break;
                case FIELD_REPLY_SERIAL:
                    m.replySerial = (Integer) val;
                    break;
                case FIELD_DESTINATION:
                    m.destination = (String) val;
                    break;
                case FIELD_SENDER:
                    m.sender = (String) val;
                    break;
                case FIELD_SIGNATURE:
                    m.signature = (String) val;
                    break;
                default:
                    // unknown field, already consumed via readValue
                    break;
            }
        }

        byte[] body = readFully(in, bodyLength);
        Cursor bc = new Cursor(body);
        int i = 0;
        while (i < m.signature.length()) {
            char c = m.signature.charAt(i);
            bc.align(bc.alignmentFor(c));
            m.args.add(bc.readValue(c));
            i++;
        }
        return m;
    }

    private static int readInt32LE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static byte[] readFully(SocketChannel in, int n) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(n);
        while (buf.hasRemaining()) {
            int r = in.read(buf);
            if (r < 0) {
                throw new IOException("D-Bus connection closed mid-message");
            }
        }
        return buf.array();
    }

    /** Helper cursor for unmarshalling a byte[] region with D-Bus alignment rules. */
    private static class Cursor {

        final byte[] data;
        int pos;

        Cursor(byte[] data) {
            this.data = data;
        }

        void align(int boundary) {
            int pad = (boundary - (pos % boundary)) % boundary;
            pos += pad;
        }

        int alignmentFor(char type) {
            switch (type) {
                case 'y':
                case 'g':
                    return 1;
                case 'b':
                case 'i':
                case 'u':
                case 's':
                case 'o':
                case 'a':
                    return 4;
                default:
                    return 8;
            }
        }

        String readSignature() {
            int len = data[pos++] & 0xFF;
            String s = new String(data, pos, len);
            pos += len + 1; // skip trailing NUL
            return s;
        }

        int readUInt32() {
            int v = readInt32LE(data, pos);
            pos += 4;
            return v;
        }

        String readString() {
            int len = readUInt32();
            String s = new String(data, pos, len);
            pos += len + 1; // skip trailing NUL
            return s;
        }

        Object readValue(char type) {
            switch (type) {
                case 'y':
                    return data[pos++];
                case 'b':
                    align(4);
                    return readUInt32() != 0;
                case 'i':
                case 'u':
                    align(4);
                    return readUInt32();
                case 's':
                case 'o':
                    align(4);
                    return readString();
                case 'g':
                    return readSignature();
                case 'v': {
                    String sig = readSignature();
                    Object v = readValue(sig.charAt(0));
                    return new Variant(sig, v);
                }
                case 'a':
                    return readArray();
                default:
                    throw new IllegalArgumentException("Unsupported D-Bus type code: " + type);
            }
        }

        DBusArray readArray() {
            align(4);
            int len = readUInt32();
            // element signature is not self-describing here; caller (readValue for 'a')
            // does not know it — MITSA only ever reads arrays it already expects as opaque
            // byte blobs or skips them, so treat unknown arrays as raw bytes.
            align(1);
            int end = pos + len;
            byte[] raw = new byte[len];
            System.arraycopy(data, pos, raw, 0, len);
            pos = end;
            return new DBusArray("y", raw);
        }
    }

    /** A typed variant value: D-Bus signature string plus the boxed value. */
    public static class Variant {

        public final String signature;
        public final Object value;

        public Variant(String signature, Object value) {
            this.signature = signature;
            this.value = value;
        }
    }

    /** A typed array: element signature plus either a byte[] (for "ay") or a List (otherwise). */
    public static class DBusArray {

        public final String elementSignature;
        public final Object elements;

        public DBusArray(String elementSignature, Object elements) {
            this.elementSignature = elementSignature;
            this.elements = elements;
        }
    }
}
