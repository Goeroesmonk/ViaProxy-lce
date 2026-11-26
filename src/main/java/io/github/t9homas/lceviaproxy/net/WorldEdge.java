package io.github.t9homas.lceviaproxy.net;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_8to1_9.packet.ClientboundPackets1_8;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import net.raphimc.vialegacy.protocol.release.r1_7_6_10tor1_8.Protocolr1_7_6_10Tor1_8;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import net.raphimc.viaproxy.util.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.Deflater;

/**
 * The edge of an LCE world: LCE worlds are a fixed number of chunks wide (from the login packet), centered on 0,0.
 * <ul>
 *     <li>a world border at the edge (1.8+ clients)</li>
 *     <li>outside the overworld the LCE client shows endless calm sea (stone up to sea level - 10, water to sea level;
 *     grass on superflat worlds, MultiPlayerChunkCache): the plugin sends such chunks around the player</li>
 * </ul>
 */
final class WorldEdge {

    private static final int RADIUS = 8; // chunks of sea around the player

    private final Channel channel;
    private final ByteBufAllocator alloc;
    private final boolean debug;
    private int xzSize;           // overworld width in chunks
    private int hellScale = 8;
    private int seaLevel = 63;
    private boolean flat;
    private int dimension = Integer.MIN_VALUE;
    private int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
    private final Set<Long> sent = new HashSet<>();
    private byte[] seaChunk;      // zlib compressed 1.6.4 chunk data
    private int seaMask;
    private boolean borderPending;
    private long resendAt;

    WorldEdge(final Channel channel, final ByteBufAllocator alloc, final boolean debug) {
        this.channel = channel;
        this.alloc = alloc;
        this.debug = debug;
    }

    void world(final int xzSize, final int hellScale, final boolean newSeaLevel, final boolean flat) {
        this.xzSize = xzSize;
        this.hellScale = hellScale > 0 ? hellScale : 8;
        this.seaLevel = newSeaLevel ? 63 : 64;
        this.flat = flat;
        this.seaChunk = null;
    }

    /** Width of a dimension in chunks, 0 = no edge known. */
    private int size(final int dim) {
        if (this.xzSize <= 0) return 0;
        if (dim == 0) return this.xzSize;
        if (dim == -1) return (int) Math.ceil(this.xzSize / (double) this.hellScale);
        return 0; // the End: no edge
    }

    /** A chunk outside the overworld, where the plugin shows the sea (server chunks there are dropped). */
    boolean drawsSea(final int dim, final int cx, final int cz) {
        final int size = size(dim);
        return dim == 0 && size > 0 && outside(size, cx, cz);
    }

    private boolean outside(final int size, final int cx, final int cz) {
        final int half = size / 2;
        return cx < -half || cx >= size - half || cz < -half || cz >= size - half;
    }

    /**
     * Called with every client position. Sends the border after a login / dimension change and the sea chunks near
     * the player.
     */
    void playerAt(final int dim, final double x, final double z, final Consumer<ByteBuf> toClient) {
        if (dim != this.dimension) {
            this.dimension = dim;
            this.sent.clear(); // the client dropped all chunks
            this.lastCx = this.lastCz = Integer.MIN_VALUE;
            this.borderPending = true;
            // the client drops chunks that come before it knows its view area: send the sea again a bit later
            this.resendAt = System.currentTimeMillis() + 2000;
        }
        if (this.resendAt != 0 && System.currentTimeMillis() >= this.resendAt) {
            this.resendAt = 0;
            this.sent.clear();
            this.lastCx = this.lastCz = Integer.MIN_VALUE;
        }
        final int size = size(dim);
        if (size == 0) return;
        if (this.borderPending) {
            this.borderPending = false;
            sendBorder(size);
        }
        if (dim != 0) return; // only the overworld has the sea
        final int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
        if (cx == this.lastCx && cz == this.lastCz) return;
        this.lastCx = cx;
        this.lastCz = cz;
        for (Iterator<Long> it = this.sent.iterator(); it.hasNext(); ) {
            final long k = it.next();
            final int sx = (int) (k >> 32), sz = (int) k;
            if (Math.abs(sx - cx) > RADIUS + 2 || Math.abs(sz - cz) > RADIUS + 2) {
                it.remove();
                toClient.accept(ChunkConverter.unloadChunk(this.alloc, sx, sz));
            }
        }
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                final int ox = cx + dx, oz = cz + dz;
                if (!outside(size, ox, oz)) continue;
                final long k = ((long) ox << 32) | (oz & 0xFFFFFFFFL);
                if (this.sent.add(k)) toClient.accept(seaChunk(ox, oz));
            }
        }
    }

    void respawn() {
        this.dimension = Integer.MIN_VALUE; // next position resends everything
    }

    // ------------------------------------------------------------------ sea chunk (1.6.4 Packet51MapChunk)

    private ByteBuf seaChunk(final int cx, final int cz) {
        if (this.seaChunk == null) buildSeaChunk();
        final ByteBuf b = this.alloc.buffer(this.seaChunk.length + 20);
        b.writeByte(0x33);
        b.writeInt(cx);
        b.writeInt(cz);
        b.writeBoolean(true);           // full chunk
        b.writeShort(this.seaMask);     // sections with data
        b.writeShort(0);                // add bitmask
        b.writeInt(this.seaChunk.length);
        b.writeBytes(this.seaChunk);
        return b;
    }

    private void buildSeaChunk() {
        final int top = this.flat ? 4 : this.seaLevel;    // first air block
        final int sections = (top + 15) >> 4;
        this.seaMask = (1 << sections) - 1;
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int s = 0; s < sections; s++) {            // block ids
            final byte[] blocks = new byte[4096];
            for (int i = 0; i < 4096; i++) {
                final int y = (s << 4) | (i >> 8);
                blocks[i] = (byte) block(y);
            }
            raw.writeBytes(blocks);
        }
        for (int s = 0; s < sections; s++) raw.writeBytes(new byte[2048]); // metadata
        for (int s = 0; s < sections; s++) raw.writeBytes(new byte[2048]); // block light
        for (int s = 0; s < sections; s++) {            // sky light
            final byte[] light = new byte[2048];
            for (int i = 0; i < 4096; i++) {
                final int y = (s << 4) | (i >> 8);
                final int l = y >= top - 1 ? 15 : Math.max(0, 15 - (top - 1 - y) * 2);
                light[i >> 1] |= (byte) ((i & 1) == 0 ? l : l << 4);
            }
            raw.writeBytes(light);
        }
        final byte[] biomes = new byte[256];
        java.util.Arrays.fill(biomes, (byte) (this.flat ? 1 : 0)); // plains / ocean
        raw.writeBytes(biomes);

        final Deflater deflater = new Deflater();
        deflater.setInput(raw.toByteArray());
        deflater.finish();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf));
        deflater.end();
        this.seaChunk = out.toByteArray();
    }

    private int block(final int y) {
        if (this.flat) return y == 3 ? 2 : y <= 2 ? 3 : 0;          // grass on dirt
        if (y <= this.seaLevel - 10) return 1;                       // stone
        return y < this.seaLevel ? 9 : 0;                            // calm water
    }

    // ------------------------------------------------------------------ world border

    private void sendBorder(final int size) {
        final ProxyConnection proxy = ProxyConnection.fromChannel(this.channel);
        final UserConnection user = proxy == null ? null : proxy.getUserConnection();
        if (user == null || !user.getProtocolInfo().getPipeline().contains(Protocolr1_7_6_10Tor1_8.class)) return; // 1.7: no border
        if (user.getProtocolInfo().getClientState() != State.PLAY) {
            // still configuring (1.20.2+): try again shortly
            this.channel.eventLoop().schedule(() -> {
                if (this.channel.isActive()) sendBorder(size);
            }, 500, TimeUnit.MILLISECONDS);
            return;
        }
        final int half = size / 2;
        final double center = (size - 2 * half) * 8.0; // 0 for an even width
        try {
            final PacketWrapper p = PacketWrapper.create(ClientboundPackets1_8.SET_BORDER, user);
            p.write(Types.VAR_INT, 3);              // initialize
            p.write(Types.DOUBLE, center);
            p.write(Types.DOUBLE, center);
            p.write(Types.DOUBLE, size * 16.0);     // old diameter
            p.write(Types.DOUBLE, size * 16.0);     // new diameter
            p.write(Types.VAR_LONG, 0L);            // no resizing
            p.write(Types.VAR_INT, 29999984);       // portal teleport boundary
            p.write(Types.VAR_INT, 0);              // warning time
            p.write(Types.VAR_INT, 0);              // warning blocks
            p.send(Protocolr1_7_6_10Tor1_8.class);
            if (this.debug) Logger.LOGGER.info("[LCE] world border: " + size * 16 + " blocks");
        } catch (Exception e) {
            Logger.LOGGER.warn("LCE: could not send the world border: " + e);
        }
    }

}
