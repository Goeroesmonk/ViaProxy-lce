package io.github.t9homas.lceviaproxy.net;

import io.github.t9homas.lceviaproxy.LceConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.raphimc.viaproxy.util.logging.Logger;

import java.util.concurrent.TimeUnit;

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
    private static final int MAX_PENDING = 32 * 1024 * 1024;
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

    // skin sent to the server (Java -> LCE)
    private JavaSkin.Skin skin;
    private boolean loginHadSkin;
    private boolean skinAnnounced;

    // the last server packets, for the log when the stream gets out of sync
    private static final int HISTORY = 8;
    private final int[] historyIds = new int[HISTORY];
    private final byte[][] historyBytes = new byte[HISTORY][];
    private int historyPos;

    public LceCodec(final LceConfig config) {
        this.config = config;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) {
        this.net = ctx.alloc().buffer();
        this.stream = ctx.alloc().buffer();
        this.toJava = new LceToJava(ctx.alloc(), this.config.debug());
        if (this.config.locatorBar()) this.toJava.locator = new Locator(ctx.channel(), this.config.debug());
        if (this.config.lceSkins()) this.toJava.skins = new PlayerSkins(ctx.channel(), this.config.debug());
        this.toLce = new JavaToLce(ctx.alloc(), this.toJava);
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
                final int start = this.stream.readerIndex();
                final int id = this.stream.readUnsignedByte();
                try {
                    if (!handleServerPacket(ctx, id)) {
                        throw new IllegalStateException("Unknown LCE packet id " + id + " (0x" + Integer.toHexString(id) + ")" + history());
                    }
                } catch (IndexOutOfBoundsException incomplete) {
                    this.stream.resetReaderIndex();
                    // no packet is this big: a misread length, the game would just freeze
                    if (this.stream.readableBytes() > MAX_PENDING) {
                        this.stream.skipBytes(1); // history() expects to be right after the id
                        throw new IllegalStateException("LCE packet " + id + " (0x" + Integer.toHexString(id) + ") never completes, stream out of sync" + history());
                    }
                    break;
                }
                if (this.stream != null) remember(id, start);
            }
            if (this.stream != null) this.stream.discardSomeReadBytes();
        } catch (Throwable t) {
            Logger.LOGGER.error("LCE connection error", t);
            fail(ctx, "§cLCE protocol error: " + t.getMessage());
        }
    }

    private void remember(final int id, final int start) {
        final int len = Math.min(this.stream.readerIndex() - start, 96);
        final byte[] b = new byte[Math.max(len, 0)];
        this.stream.getBytes(start, b);
        this.historyIds[this.historyPos] = id;
        this.historyBytes[this.historyPos] = b;
        this.historyPos = (this.historyPos + 1) % HISTORY;
    }

    private String history() {
        final StringBuilder sb = new StringBuilder("\nLast packets before it (oldest first, max 96 bytes each):");
        for (int i = 0; i < HISTORY; i++) {
            final int k = (this.historyPos + i) % HISTORY;
            if (this.historyBytes[k] == null) continue;
            sb.append("\n  0x").append(Integer.toHexString(this.historyIds[k])).append(": ").append(ByteBufUtil.hexDump(this.historyBytes[k]));
        }
        final int r = this.stream.readerIndex() - 1;
        final int n = Math.min(this.stream.writerIndex() - r, 96);
        sb.append("\n  unknown: ").append(ByteBufUtil.hexDump(this.stream, r, Math.max(n, 0)));
        return sb.toString();
    }

    /**
     * @return false for unknown packet ids
     */
    private boolean handleServerPacket(final ChannelHandlerContext ctx, final int id) throws Exception {
        if ((id == 0x9A || id == 0xA0) && this.state != State.PING) {
            return handleTextureRequest(ctx, id);
        }
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
                    this.toJava.selfName = this.userName;
                    if (this.toJava.skins != null) this.toJava.skins.self(this.userName);
                    if (this.skin != null && !this.loginHadSkin) announceSkin(ctx);
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
        b.writeInt(this.skin != null ? this.skin.skinId() : 0); // skin
        this.loginHadSkin = this.skin != null;
        b.writeInt(0);              // cape
        b.writeBoolean(false);      // guest
        b.writeBoolean(false);      // new sea level
        b.writeInt(0);              // game privileges
        b.writeShort(320);          // xz size
        b.writeByte(8);             // hell scale
        toServer(ctx, b);
        this.state = State.LOGIN;
    }

    // ------------------------------------------------------------------ skin

    private void onSkin(final ChannelHandlerContext ctx, final JavaSkin.Skin skin) {
        if (skin == null || this.state == State.CLOSED || !ctx.channel().isActive()) return;
        this.skin = skin;
        Logger.LOGGER.info("LCE: sending your skin to the server as " + skin.path() + " (" + skin.png().length + " bytes)");
        if (this.state == State.PLAY) announceSkin(ctx);
        // HANDSHAKE / PRE_LOGIN: the login packet carries it; LOGIN: announced when the login finishes
    }

    /**
     * TextureChange: tells the server (and through it everybody) our skin; the server then asks for the image.
     */
    private void announceSkin(final ChannelHandlerContext ctx) {
        if (this.skinAnnounced) return;
        this.skinAnnounced = true;
        final ByteBuf b = ctx.alloc().buffer();
        b.writeByte(0x9D);
        b.writeInt(this.toJava.serverEntityId);
        b.writeByte(0); // e_TextureChange_Skin
        Types.writeJavaUtf(b, this.skin.path());
        toServer(ctx, b);
    }

    /**
     * Texture (0x9A) / TextureAndGeometry (0xA0). With no data it is a request: answer it when it asks for our skin.
     * Images of other players are dropped (Java clients can't use them).
     */
    private boolean handleTextureRequest(final ChannelHandlerContext ctx, final int id) {
        final String name = Types.readJavaUtf(this.stream);
        if (id == 0x9A) {
            final int n = this.stream.readShort();
            if (n > 0) this.stream.skipBytes(n);
            if (n == 0) answerTexture(ctx, id, name);
        } else {
            this.stream.readInt(); // skin id
            final int n = this.stream.readShort();
            if (n > 0) this.stream.skipBytes(n);
            this.stream.readInt(); // anim override
            final int boxes = this.stream.readShort();
            if (boxes > 0) this.stream.skipBytes(boxes * (2 + 8 * 4));
            if (n == 0) answerTexture(ctx, id, name);
        }
        return true;
    }

    private void answerTexture(final ChannelHandlerContext ctx, final int id, final String name) {
        if (this.skin == null || !name.equals(this.skin.path())) {
            if (this.config.debug()) Logger.LOGGER.info("[LCE] server asked for unknown texture " + name);
            return;
        }
        if (this.config.debug()) Logger.LOGGER.info("[LCE] server asked for our skin " + name + ", sending it");
        final byte[] png = this.skin.png();
        final ByteBuf b = ctx.alloc().buffer(png.length + 32);
        b.writeByte(id);
        Types.writeJavaUtf(b, name);
        if (id == 0xA0) b.writeInt(this.skin.skinId());
        b.writeShort(png.length);
        b.writeBytes(png);
        if (id == 0xA0) {
            b.writeInt(0);   // anim override
            b.writeShort(0); // no extra boxes
        }
        toServer(ctx, b);
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
            final boolean doorTop = id == 0x35 && isDoorTop(packet);
            final int idLen = id >= 0x80 ? 2 : 1;
            final ByteBuf framed = ctx.alloc().buffer(packet.readableBytes() + idLen + 5);
            Types.writeVarInt(framed, packet.readableBytes() + idLen);
            Types.writeVarInt(framed, id);
            framed.writeBytes(packet);
            if (doorTop) {
                // LCE answers a click on a door's top half with that (unchanged) top half first and sends the changed
                // bottom half a tick later. Java clients draw the door from the bottom half, so the door flicked back.
                // Hand the top half over after the bottom half.
                ctx.executor().schedule(() -> {
                    if (ctx.channel().isActive() && this.state == State.PLAY) {
                        ctx.fireChannelRead(framed);
                    } else {
                        framed.release();
                    }
                }, DOOR_TOP_DELAY_MS, TimeUnit.MILLISECONDS);
                return;
            }
            ctx.fireChannelRead(framed);
        } finally {
            packet.release();
        }
    }

    private static final long DOOR_TOP_DELAY_MS = 120;

    /**
     * 1.6.4 block change (after the id): int x, byte y, int z, short block, byte meta.
     */
    private static boolean isDoorTop(final ByteBuf p) {
        final int i = p.readerIndex();
        if (p.readableBytes() < 12) return false;
        final int block = p.getShort(i + 9);
        final int meta = p.getUnsignedByte(i + 11);
        return (block == 64 || block == 71) && (meta & 8) != 0;
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
                JavaSkin.load(this.config.skin(), this.userName)
                        .thenAccept(s -> ctx.channel().eventLoop().execute(() -> onSkin(ctx, s)));
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
        if (this.config.debug() && id != 0x0A && id != 0x0B && id != 0x0C && id != 0x0D && id != 0x00) {
            Logger.LOGGER.info("[LCE] client 0x" + Integer.toHexString(id) + (lce == null ? " dropped" : " -> LCE " + ByteBufUtil.hexDump(lce, lce.readerIndex(), Math.min(lce.readableBytes(), 48))));
        }
        if (lce != null) {
            out.writeInt(lce.readableBytes());
            out.writeBytes(lce);
            lce.release();
        }
    }

}
