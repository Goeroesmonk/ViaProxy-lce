package io.github.t9homas.lceviaproxy.net;

import io.github.t9homas.lceviaproxy.LceConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.raphimc.viaproxy.util.logging.Logger;

/**
 * Sits between the socket and ViaProxy's "sizer" (in place of ViaLegacy's pre-netty splitter).
 * <p>
 * Wire format of the LCE Windows64 / Revelations network layer:
 * <ul>
 *     <li>server -> client: one byte "small id" right after the TCP connection is accepted</li>
 *     <li>then both directions: frames of [u32 big endian length][bytes]; the frames together form a stream of
 *     LCE packets (1 byte id + fields, Java 1.6.4 style, no length prefix)</li>
 * </ul>
 * Login: client PreLogin(2) -> server PreLogin(2) -> client Login(1) -> server Login(1) -> play.
 */
public final class LceCodec extends ChannelDuplexHandler {

    public static final String NAME = "lce-codec";

    private static final int MAX_FRAME = 4 * 1024 * 1024;
    private static final int JAVA_PROTOCOL = 78; // 1.6.4

    private enum State {HANDSHAKE, PRE_LOGIN, LOGIN, PLAY, PING, CLOSED}

    private final LceConfig config;
    private ByteBuf net;
    private ByteBuf stream;
    private boolean gotSmallId;
    private State state = State.HANDSHAKE;
    private String userName = "Player";
    private LceToJava toJava;
    private JavaToLce toLce;

    public LceCodec(final LceConfig config) {
        this.config = config;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) {
        this.net = ctx.alloc().buffer();
        this.stream = ctx.alloc().buffer();
        this.toJava = new LceToJava(ctx.alloc(), this.config.debug());
        this.toLce = new JavaToLce(ctx.alloc());
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) {
        release();
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        release();
        super.channelInactive(ctx);
    }

    private void release() {
        if (this.net != null) {
            this.net.release();
            this.net = null;
        }
        if (this.stream != null) {
            this.stream.release();
            this.stream = null;
        }
    }

    // ================================================================== server -> proxy

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (!(msg instanceof ByteBuf buf)) {
            super.channelRead(ctx, msg);
            return;
        }
        try {
            if (this.net == null || this.state == State.CLOSED) {
                return;
            }
            this.net.writeBytes(buf);
        } finally {
            buf.release();
        }

        try {
            if (!this.gotSmallId) {
                if (!this.net.isReadable()) return;
                final int smallId = this.net.readUnsignedByte();
                this.gotSmallId = true;
                if (this.config.debug()) Logger.LOGGER.info("[LCE] Assigned small id " + smallId);
            }
            while (this.net.readableBytes() >= 4) {
                final int len = this.net.getInt(this.net.readerIndex());
                if (len <= 0 || len > MAX_FRAME) {
                    throw new IllegalStateException("Invalid LCE frame length " + len);
                }
                if (this.net.readableBytes() < 4 + len) break;
                this.net.skipBytes(4);
                this.stream.writeBytes(this.net, len);
            }
            this.net.discardSomeReadBytes();

            while (this.stream.isReadable() && this.state != State.CLOSED) {
                this.stream.markReaderIndex();
                final int id = this.stream.readUnsignedByte();
                try {
                    if (!handleServerPacket(ctx, id)) {
                        throw new IllegalStateException("Unknown LCE packet id " + id + " (0x" + Integer.toHexString(id) + ")");
                    }
                } catch (IndexOutOfBoundsException incomplete) {
                    this.stream.resetReaderIndex();
                    break;
                }
            }
            if (this.stream != null) this.stream.discardSomeReadBytes();
        } catch (Throwable t) {
            Logger.LOGGER.error("LCE connection error", t);
            fail(ctx, "§cLCE protocol error: " + t.getMessage());
        }
    }

    /**
     * @return false for unknown packet ids
     */
    private boolean handleServerPacket(final ChannelHandlerContext ctx, final int id) throws Exception {
        switch (this.state) {
            case PLAY -> {
                return this.toJava.translate(id, this.stream, b -> toVia(ctx, b));
            }
            case PRE_LOGIN, PING -> {
                if (id == 0x02) {
                    this.stream.readShort(); // netcode version
                    Types.readString(this.stream);
                    this.stream.skipBytes(1 + 4);
                    final int players = this.stream.readUnsignedByte();
                    this.stream.skipBytes(players * 8 + 14 + 4 + 1 + 4);
                    if (this.state == State.PING) {
                        answerPing(ctx, "§aLCE Revelations server", players);
                    } else {
                        sendLogin(ctx);
                    }
                    return true;
                }
                return handleLoginPhase(ctx, id);
            }
            case LOGIN -> {
                if (id == 0x01) {
                    final int entityId = this.stream.readInt();
                    Types.readString(this.stream); // user name (empty)
                    final String levelType = Types.readString(this.stream);
                    this.stream.readLong(); // seed
                    final int gameMode = this.stream.readInt();
                    final int dimension = this.stream.readByte();
                    this.stream.readByte(); // map height
                    final int maxPlayers = this.stream.readUnsignedByte();
                    this.stream.skipBytes(8 + 8 + 1 + 4);
                    final int difficulty = this.stream.readByte();
                    this.stream.skipBytes(4 + 1 + 4 + 4 + 1 + 1 + 4 + 2 + 1);
                    this.state = State.PLAY;
                    Logger.LOGGER.info("LCE: joined as " + this.userName + " (entity " + entityId + ", game mode " + gameMode + ", dimension " + dimension + ")");
                    toVia(ctx, this.toJava.javaLogin(entityId, levelType, gameMode & 0x7, dimension, difficulty, maxPlayers));
                    return true;
                }
                return handleLoginPhase(ctx, id);
            }
            default -> {
                // HANDSHAKE: the server should not talk before our PreLogin; skip known harmless packets
                return handleLoginPhase(ctx, id);
            }
        }
    }

    private boolean handleLoginPhase(final ChannelHandlerContext ctx, final int id) throws Exception {
        switch (id) {
            case 0x00 -> { // keep alive: answer it ourselves
                final int value = this.stream.readInt();
                final ByteBuf b = ctx.alloc().buffer(5);
                b.writeByte(0x00);
                b.writeInt(value);
                toServer(ctx, b);
                return true;
            }
            case 0xFF -> {
                final int reason = this.stream.readInt();
                final String text = io.github.t9homas.lceviaproxy.data.LceText.disconnect(reason);
                if (this.state == State.PING) {
                    answerPing(ctx, "§c" + text, 0);
                } else {
                    fail(ctx, "§c" + text);
                }
                return true;
            }
            case 0xFA -> {
                Types.readString(this.stream);
                final int len = this.stream.readShort();
                if (len > 0 && len < Short.MAX_VALUE) this.stream.skipBytes(len);
                return true;
            }
            default -> {
                // Anything else before the login finished: translate and drop (keeps the stream in sync)
                return this.toJava.translate(id, this.stream, ByteBuf::release);
            }
        }
    }

    private void sendLogin(final ChannelHandlerContext ctx) {
        final long xuid = this.config.xuidFor(this.userName);
        final ByteBuf b = ctx.alloc().buffer();
        b.writeByte(0x01);
        b.writeInt(JAVA_PROTOCOL);
        Types.writeString(b, this.userName);
        Types.writeString(b, "");   // level type
        b.writeLong(0);             // seed
        b.writeInt(0);              // game type
        b.writeByte(0);             // dimension
        b.writeByte(0);             // map height
        b.writeByte(0);             // max players
        b.writeLong(xuid);          // offline xuid
        b.writeLong(xuid);          // online xuid
        b.writeBoolean(false);      // friends only UGC
        b.writeInt(0);              // UGC players version
        b.writeByte(1);             // difficulty
        b.writeInt(0);              // multiplayer instance id
        b.writeByte(0);             // player index
        b.writeInt(0);              // skin
        b.writeInt(0);              // cape
        b.writeBoolean(false);      // guest
        b.writeBoolean(false);      // new sea level
        b.writeInt(0);              // game privileges
        b.writeShort(320);          // xz size
        b.writeByte(8);             // hell scale
        toServer(ctx, b);
        this.state = State.LOGIN;
    }

    private void sendPreLogin(final ChannelHandlerContext ctx, final String name) {
        final ByteBuf b = ctx.alloc().buffer();
        b.writeByte(0x02);
        b.writeShort(this.config.netcodeVersion());
        Types.writeString(b, name);
        b.writeByte(0);             // friends only bits
        b.writeInt(0);              // UGC players version
        b.writeByte(0);             // player xuid count
        b.writeZero(14);            // unique save name
        b.writeInt(0);              // server settings
        b.writeByte(0);             // host index
        b.writeInt(0);              // texture pack id
        toServer(ctx, b);
    }

    private void answerPing(final ChannelHandlerContext ctx, final String motd, final int players) {
        final String response = "§1\0" + JAVA_PROTOCOL + "\0" + "LCE Revelations" + "\0" + motd + "\0" + players + "\0" + 8;
        toVia(ctx, LceToJava.kick(ctx.alloc(), response));
        this.state = State.CLOSED;
        ctx.channel().close();
    }

    private void fail(final ChannelHandlerContext ctx, final String reason) {
        if (this.state == State.CLOSED) return;
        final State old = this.state;
        this.state = State.CLOSED;
        if (old == State.PING) {
            toVia(ctx, LceToJava.kick(ctx.alloc(), "§1\0" + JAVA_PROTOCOL + "\0LCE Revelations\0" + reason + "\00\00"));
        } else {
            toVia(ctx, LceToJava.kick(ctx.alloc(), reason));
        }
        ctx.channel().close();
    }

    /**
     * Hands a 1.6.4 packet (id + data) to ViaProxy, with the VarInt length the "sizer" expects.
     */
    private void toVia(final ChannelHandlerContext ctx, final ByteBuf packet) {
        try {
            if (this.config.debug()) {
                Logger.LOGGER.info("[LCE] -> via 0x" + Integer.toHexString(packet.getUnsignedByte(packet.readerIndex())) + " (" + packet.readableBytes() + " bytes)");
            }
            // Via reads the packet id as a VarInt, pre-netty ids are one unsigned byte
            final int id = packet.readUnsignedByte();
            final int idLen = id >= 0x80 ? 2 : 1;
            final ByteBuf framed = ctx.alloc().buffer(packet.readableBytes() + idLen + 5);
            Types.writeVarInt(framed, packet.readableBytes() + idLen);
            Types.writeVarInt(framed, id);
            framed.writeBytes(packet);
            ctx.fireChannelRead(framed);
        } finally {
            packet.release();
        }
    }

    private void toServer(final ChannelHandlerContext ctx, final ByteBuf packet) {
        ctx.writeAndFlush(frame(ctx, packet));
    }

    private static ByteBuf frame(final ChannelHandlerContext ctx, final ByteBuf packet) {
        try {
            final ByteBuf framed = ctx.alloc().buffer(packet.readableBytes() + 4);
            framed.writeInt(packet.readableBytes());
            framed.writeBytes(packet);
            return framed;
        } finally {
            packet.release();
        }
    }

    // ================================================================== proxy -> server

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
        if (!(msg instanceof ByteBuf buf)) {
            super.write(ctx, msg, promise);
            return;
        }
        final ByteBuf out = ctx.alloc().buffer();
        try {
            while (buf.isReadable()) {
                final int len = Types.readVarInt(buf);
                final ByteBuf packet = buf.readSlice(len);
                final int id = Types.readVarInt(packet);
                this.handleClientPacket(ctx, id, packet, out);
            }
        } catch (Throwable t) {
            out.release();
            buf.release();
            Logger.LOGGER.error("LCE: could not translate client packet", t);
            promise.setFailure(t);
            return;
        }
        buf.release();
        if (out.isReadable() && this.state != State.CLOSED) {
            ctx.write(out, promise);
        } else {
            out.release();
            promise.setSuccess();
        }
    }

    private void handleClientPacket(final ChannelHandlerContext ctx, final int id, final ByteBuf packet, final ByteBuf out) {
        if (this.state == State.HANDSHAKE) {
            if (id == 0x02) {
                packet.readUnsignedByte(); // protocol
                this.userName = Types.readString(packet);
                this.state = State.PRE_LOGIN;
                ctx.channel().eventLoop().execute(() -> sendPreLogin(ctx, this.userName));
            } else if (id == 0xFE) {
                this.state = State.PING;
                ctx.channel().eventLoop().execute(() -> sendPreLogin(ctx, "ViaProxy"));
            }
            return;
        }
        if (this.state != State.PLAY) {
            return;
        }
        final ByteBuf lce = this.toLce.translate(id, packet);
        if (lce != null) {
            out.writeInt(lce.readableBytes());
            out.writeBytes(lce);
            lce.release();
        }
    }

}
