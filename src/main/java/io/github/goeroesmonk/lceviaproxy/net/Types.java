package io.github.goeroesmonk.lceviaproxy.net;

import io.netty.buffer.ByteBuf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Field helpers. LCE and Java 1.6.4 share the same primitive encodings (big endian, strings = short length + UTF-16BE).
 * The differences handled here: LCE sends NBT uncompressed, 1.6.4 sends it gzipped.
 */
public final class Types {

    public static final int MAX_STRING = 32767;

    private Types() {
    }

    // ------------------------------------------------------------------ strings

    public static String readString(final ByteBuf in) {
        final int len = in.readShort();
        if (len < 0) {
            throw new IllegalArgumentException("Negative string length " + len);
        }
        final char[] chars = new char[len];
        for (int i = 0; i < len; i++) {
            chars[i] = in.readChar();
        }
        return new String(chars);
    }

    public static void writeString(final ByteBuf out, String s) {
        if (s.length() > MAX_STRING) {
            s = s.substring(0, MAX_STRING);
        }
        out.writeShort(s.length());
        for (int i = 0; i < s.length(); i++) {
            out.writeChar(s.charAt(i));
        }
    }

    /**
     * java.io.DataInput#readUTF format (unsigned short length + modified UTF-8), used by a few LCE-only packets.
     */
    public static void skipJavaUtf(final ByteBuf in) {
        in.skipBytes(in.readUnsignedShort());
    }

    /** DataInput.readUTF style string (u16 byte length + UTF-8), used for texture names. */
    public static String readJavaUtf(final ByteBuf in) {
        final int len = in.readUnsignedShort();
        return in.readCharSequence(len, java.nio.charset.StandardCharsets.UTF_8).toString();
    }

    public static void writeJavaUtf(final ByteBuf out, final String s) {
        final byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeShort(b.length);
        out.writeBytes(b);
    }

    // ------------------------------------------------------------------ var ints (ViaProxy side framing)

    public static int readVarInt(final ByteBuf in) {
        int value = 0;
        int shift = 0;
        byte b;
        do {
            b = in.readByte();
            value |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) {
                throw new IllegalArgumentException("VarInt too big");
            }
        } while ((b & 0x80) != 0);
        return value;
    }

    public static void writeVarInt(final ByteBuf out, int value) {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    // ------------------------------------------------------------------ NBT

    /**
     * LCE NBT (short length + raw NBT) -> 1.6.4 NBT (short length + gzipped NBT).
     */
    public static void nbtLceToJava(final ByteBuf in, final ByteBuf out) {
        final short len = in.readShort();
        if (len < 0) {
            out.writeShort(-1);
            return;
        }
        final byte[] raw = new byte[len];
        in.readBytes(raw);
        final byte[] gz = gzip(raw);
        out.writeShort(gz.length);
        out.writeBytes(gz);
    }

    /**
     * 1.6.4 NBT (gzipped) -> LCE NBT (raw).
     */
    public static void nbtJavaToLce(final ByteBuf in, final ByteBuf out) {
        final short len = in.readShort();
        if (len < 0) {
            out.writeShort(-1);
            return;
        }
        final byte[] gz = new byte[len];
        in.readBytes(gz);
        final byte[] raw = gunzip(gz);
        out.writeShort(raw.length);
        out.writeBytes(raw);
    }

    public static byte[] gzip(final byte[] raw) {
        try {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 2 + 32);
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(raw);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] gunzip(final byte[] data) {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return gz.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ items

    public static void itemLceToJava(final ByteBuf in, final ByteBuf out) {
        final short id = in.readShort();
        out.writeShort(id);
        if (id >= 0) {
            out.writeByte(in.readByte());   // count
            out.writeShort(in.readShort()); // damage
            nbtLceToJava(in, out);
        }
    }

    public static void itemJavaToLce(final ByteBuf in, final ByteBuf out) {
        final short id = in.readShort();
        out.writeShort(id);
        if (id >= 0) {
            out.writeByte(in.readByte());
            out.writeShort(in.readShort());
            nbtJavaToLce(in, out);
        }
    }

    // ------------------------------------------------------------------ entity metadata

    /**
     * Same layout on both sides (type << 5 | index, 0x7F end marker); only items need their NBT converted.
     */
    public static void metadataLceToJava(final ByteBuf in, final ByteBuf out) {
        metadataLceToJava(in, out, false);
    }

    /**
     * @param bat LCE sends the bat's hanging flag (index 16) as an int, Java as a byte
     */
    public static void metadataLceToJava(final ByteBuf in, final ByteBuf out, final boolean bat) {
        while (true) {
            final int header = in.readUnsignedByte();
            if (header == ((2 << 5) | 13)) {
                in.readInt(); // LCE-only int at index 13 (Java 1.6.4 has nothing there)
                continue;
            }
            if (bat && header == ((2 << 5) | 16)) {
                out.writeByte(16);
                out.writeByte(in.readInt());
                continue;
            }
            out.writeByte(header);
            if (header == 0x7F) {
                return;
            }
            switch (header >> 5) {
                case 0 -> {
                    final byte v = in.readByte();
                    // entity flags (index 0): LCE uses bits 0x40 / 0x80 for its own things, newer Java clients read
                    // them as glowing / elytra flying
                    out.writeByte(header == 0x00 ? v & 0x3F : v);
                }
                case 1 -> out.writeShort(in.readShort());
                case 2, 3 -> out.writeInt(in.readInt());
                case 4 -> writeString(out, readString(in));
                case 5 -> itemLceToJava(in, out);
                case 6 -> {
                    out.writeInt(in.readInt());
                    out.writeInt(in.readInt());
                    out.writeInt(in.readInt());
                }
                default -> throw new IllegalArgumentException("Unknown metadata type " + (header >> 5));
            }
        }
    }

}
