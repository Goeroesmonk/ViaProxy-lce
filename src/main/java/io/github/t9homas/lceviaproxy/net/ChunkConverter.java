package io.github.t9homas.lceviaproxy.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Converts LCE BlockRegionUpdate data (zlib + a byte RLE) to Java 1.6.4 chunk packets.
 * <p>
 * LCE full chunk buffer (ys = height actually sent):
 * <pre>
 *   blocks      [ys*256]          index y*256 + z*16 + x
 *   data        lower + upper     for x, for z, for y pairs: (y low nibble | y+1 high nibble); lower = y 0..127, upper = y 128..255
 *   block light lower + upper     same layout
 *   sky light   lower + upper     same layout
 *   biomes      [256]
 * </pre>
 * Partial regions use x, z, y loop order for blocks, then data nibbles (no lighting), one chunk column after the other.
 */
public final class ChunkConverter {

    private static final int SECTION_HEIGHT = 128;

    private ChunkConverter() {
    }

    // ------------------------------------------------------------------ decompression

    public static byte[] decompress(final byte[] compressed) throws DataFormatException {
        final Inflater inflater = new Inflater();
        final ByteArrayOutputStream rle = new ByteArrayOutputStream(compressed.length * 4);
        try {
            inflater.setInput(compressed);
            final byte[] tmp = new byte[16384];
            while (!inflater.finished()) {
                final int n = inflater.inflate(tmp);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                rle.write(tmp, 0, n);
            }
        } finally {
            inflater.end();
        }
        final byte[] in = rle.toByteArray();
        // RLE: 255, n < 3 -> n+1 times 255; 255, n, b -> n+1 times b
        byte[] out = new byte[Math.max(in.length * 3, 1024)];
        int o = 0;
        int i = 0;
        while (i < in.length) {
            final int b = in[i++] & 0xFF;
            int count = 0;
            int value = b;
            if (b == 255) {
                if (i >= in.length) throw new DataFormatException("chunk RLE data ends early");
                count = in[i++] & 0xFF;
                if (count >= 3) {
                    if (i >= in.length) throw new DataFormatException("chunk RLE data ends early");
                    value = in[i++] & 0xFF;
                }
            }
            if (o + count + 1 > out.length) out = java.util.Arrays.copyOf(out, Math.max(out.length * 2, o + count + 1));
            java.util.Arrays.fill(out, o, o + count + 1, (byte) value);
            o += count + 1;
        }
        return java.util.Arrays.copyOf(out, o);
    }

    // ------------------------------------------------------------------ full chunk

    /**
     * Writes a 1.6.4 Map Chunk (0x33) packet (with id).
     */
    public static ByteBuf fullChunk(final ByteBufAllocator alloc, final int chunkX, final int chunkZ, final int ys, final byte[] buf, final boolean skyLight) {
        final int tileCount = 256 * ys;
        final int lowerSpan = Math.min(ys, SECTION_HEIGHT);
        final int upperSpan = Math.max(0, ys - SECTION_HEIGHT);
        final int lowerNibbles = 256 * (lowerSpan / 2);
        final int upperNibbles = 256 * (upperSpan / 2);
        final int nibbleBlock = lowerNibbles + upperNibbles;
        final int dataOff = tileCount;
        final int blockLightOff = dataOff + nibbleBlock;
        final int skyLightOff = blockLightOff + nibbleBlock;
        final int biomeOff = skyLightOff + nibbleBlock;

        // Which 16-high sections contain something
        int mask = 0;
        for (int y = 0; y < ys; y++) {
            final int base = y * 256;
            for (int i = 0; i < 256; i++) {
                if (base + i < buf.length && buf[base + i] != 0) {
                    mask |= 1 << (y >> 4);
                    break;
                }
            }
        }

        if (mask == 0) {
            mask = 1; // ground-up with an empty mask means "unload" in 1.6.4, so send one air section
        }
        final int sections = Integer.bitCount(mask);
        final byte[] raw = new byte[sections * (4096 + 2048 + 2048 + (skyLight ? 2048 : 0)) + 256];
        int p = 0;
        // blocks
        for (int s = 0; s < 16; s++) {
            if ((mask & (1 << s)) == 0) continue;
            for (int ly = 0; ly < 16; ly++) {
                final int y = (s << 4) + ly;
                if (y < ys) {
                    System.arraycopy(buf, y * 256, raw, p + ly * 256, 256);
                }
            }
            p += 4096;
        }
        p = writeNibbles(buf, dataOff, lowerSpan, upperSpan, lowerNibbles, ys, mask, raw, p, 0);
        p = writeNibbles(buf, blockLightOff, lowerSpan, upperSpan, lowerNibbles, ys, mask, raw, p, 0);
        if (skyLight) {
            p = writeNibbles(buf, skyLightOff, lowerSpan, upperSpan, lowerNibbles, ys, mask, raw, p, 15);
        }
        if (biomeOff + 256 <= buf.length) {
            System.arraycopy(buf, biomeOff, raw, p, 256);
        }

        final byte[] deflated = deflate(raw);
        final ByteBuf out = alloc.buffer(deflated.length + 20);
        out.writeByte(0x33);
        out.writeInt(chunkX);
        out.writeInt(chunkZ);
        out.writeBoolean(true);
        out.writeShort(mask);
        out.writeShort(0);
        out.writeInt(deflated.length);
        out.writeBytes(deflated);
        return out;
    }

    /**
     * LCE nibble block -> 1.6.4 per-section nibble arrays for the sections in mask.
     * 1.6.4 index: (y&15)<<8 | z<<4 | x, byte = index >> 1, low nibble when index is even.
     */
    private static int writeNibbles(final byte[] buf, final int off, final int lowerSpan, final int upperSpan, final int lowerNibbles,
                                    final int ys, final int mask, final byte[] raw, int p, final int fill) {
        for (int s = 0; s < 16; s++) {
            if ((mask & (1 << s)) == 0) continue;
            for (int ly = 0; ly < 16; ly++) {
                final int y = (s << 4) + ly;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int v = fill;
                        if (y < ys) {
                            final int idx;
                            if (y < SECTION_HEIGHT) {
                                idx = off + ((x * 16 + z) * (lowerSpan / 2)) + (y >> 1);
                            } else {
                                idx = off + lowerNibbles + ((x * 16 + z) * (upperSpan / 2)) + ((y - SECTION_HEIGHT) >> 1);
                            }
                            if (idx < buf.length && ((y < SECTION_HEIGHT ? y : y - SECTION_HEIGHT) >> 1) < ((y < SECTION_HEIGHT ? lowerSpan : upperSpan) / 2)) {
                                v = (y & 1) == 0 ? buf[idx] & 0x0F : (buf[idx] >> 4) & 0x0F;
                            }
                        }
                        final int i = (ly << 8) | (z << 4) | x;
                        final int bi = p + (i >> 1);
                        if ((i & 1) == 0) {
                            raw[bi] = (byte) ((raw[bi] & 0xF0) | v);
                        } else {
                            raw[bi] = (byte) ((raw[bi] & 0x0F) | (v << 4));
                        }
                    }
                }
            }
            p += 2048;
        }
        return p;
    }

    /**
     * 1.6.4 chunk unload (empty ground-up chunk).
     */
    private static final byte[] EMPTY_DEFLATED = deflate(new byte[0]);

    public static ByteBuf unloadChunk(final ByteBufAllocator alloc, final int chunkX, final int chunkZ) {
        final byte[] deflated = EMPTY_DEFLATED;
        final ByteBuf out = alloc.buffer(32);
        out.writeByte(0x33);
        out.writeInt(chunkX);
        out.writeInt(chunkZ);
        out.writeBoolean(true);
        out.writeShort(0);
        out.writeShort(0);
        out.writeInt(deflated.length);
        out.writeBytes(deflated);
        return out;
    }

    // ------------------------------------------------------------------ partial region

    /**
     * Turns a partial LCE region into 1.6.4 Multi Block Change (0x34) packets, one per chunk column.
     */
    public static void partialRegion(final ByteBufAllocator alloc, final int x, final int y, final int z, final int xs, final int ys, final int zs,
                                     final byte[] buf, final Consumer<ByteBuf> sink) {
        if (ys <= 0) {
            return;
        }
        final int xc0 = x >> 4, zc0 = z >> 4, xc1 = (x + xs - 1) >> 4, zc1 = (z + zs - 1) >> 4;
        final int y0 = Math.max(y, 0);
        final int y1 = Math.min(y + ys, 256);
        int p = 0;
        for (int xc = xc0; xc <= xc1; xc++) {
            final int x0 = Math.max(x - xc * 16, 0), x1 = Math.min(x + xs - xc * 16, 16);
            for (int zc = zc0; zc <= zc1; zc++) {
                final int z0 = Math.max(z - zc * 16, 0), z1 = Math.min(z + zs - zc * 16, 16);
                final int w = x1 - x0, d = z1 - z0;
                final int lowY1 = Math.min(SECTION_HEIGHT, y1);
                final int upY0 = Math.max(y0 - SECTION_HEIGHT, 0), upY1 = y1 - SECTION_HEIGHT;
                final boolean lower = y0 < SECTION_HEIGHT, upper = y1 > SECTION_HEIGHT;

                final int[] ids = new int[w * d * 256];
                final int[] metas = new int[w * d * 256];
                java.util.Arrays.fill(ids, -1);
                // blocks
                if (lower) {
                    for (int lx = x0; lx < x1; lx++) for (int lz = z0; lz < z1; lz++) for (int ly = y0; ly < lowY1; ly++) {
                        ids[slot(lx - x0, lz - z0, ly, d)] = p < buf.length ? buf[p] & 0xFF : 0;
                        p++;
                    }
                }
                if (upper) {
                    for (int lx = x0; lx < x1; lx++) for (int lz = z0; lz < z1; lz++) for (int ly = upY0; ly < upY1; ly++) {
                        ids[slot(lx - x0, lz - z0, ly + SECTION_HEIGHT, d)] = p < buf.length ? buf[p] & 0xFF : 0;
                        p++;
                    }
                }
                // data nibbles
                if (lower) p = readNibbleRegion(buf, p, x0, x1, z0, z1, y0, lowY1, 0, d, metas);
                if (upper) p = readNibbleRegion(buf, p, x0, x1, z0, z1, upY0, upY1, SECTION_HEIGHT, d, metas);

                int count = 0;
                for (final int id : ids) if (id >= 0) count++;
                if (count == 0) continue;
                final ByteBuf out = alloc.buffer(15 + count * 4);
                out.writeByte(0x34);
                out.writeInt(xc);
                out.writeInt(zc);
                out.writeShort(count);
                out.writeInt(count * 4);
                for (int lx = 0; lx < w; lx++) for (int lz = 0; lz < d; lz++) for (int ly = 0; ly < 256; ly++) {
                    final int s = slot(lx, lz, ly, d);
                    if (ids[s] < 0) continue;
                    out.writeInt(((lx + x0) << 28) | ((lz + z0) << 24) | (ly << 16) | ((ids[s] & 0xFFF) << 4) | (metas[s] & 0xF));
                }
                sink.accept(out);
            }
        }
    }

    private static int slot(final int lx, final int lz, final int y, final int d) {
        return ((lx * d) + lz) * 256 + y;
    }

    private static int readNibbleRegion(final byte[] buf, int p, final int x0, final int x1, final int z0, final int z1, final int ya, final int yb,
                                        final int yOffset, final int d, final int[] metas) {
        final int yy0 = ya & ~1;
        final int len = (yb - ya) / 2;
        for (int lx = x0; lx < x1; lx++) {
            for (int lz = z0; lz < z1; lz++) {
                for (int i = 0; i < len; i++) {
                    final int yy = yy0 + i * 2;
                    final int b = p < buf.length ? buf[p] & 0xFF : 0;
                    p++;
                    if (yy + yOffset < 256) metas[slot(lx - x0, lz - z0, yy + yOffset, d)] = b & 0x0F;
                    if (yy + 1 + yOffset < 256) metas[slot(lx - x0, lz - z0, yy + 1 + yOffset, d)] = (b >> 4) & 0x0F;
                }
            }
        }
        return p;
    }

    // ------------------------------------------------------------------ util

    /**
     * zlib without compression: the data only goes to ViaLegacy in the same process, which unpacks it right away.
     */
    public static byte[] deflate(final byte[] raw) {
        final Deflater deflater = new Deflater(Deflater.NO_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            final ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length + raw.length / 1000 * 5 + 64);
            final byte[] tmp = new byte[16384];
            while (!deflater.finished()) {
                final int n = deflater.deflate(tmp);
                out.write(tmp, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

}
