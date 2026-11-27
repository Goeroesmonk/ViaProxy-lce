package io.github.goeroesmonk.lceviaproxy.net;

import io.github.goeroesmonk.lceviaproxy.LceConfig;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.GameProfile;
import net.raphimc.vialegacy.protocol.release.r1_7_6_10tor1_8.Protocolr1_7_6_10Tor1_8;
import net.raphimc.vialegacy.protocol.release.r1_7_6_10tor1_8.storage.TablistStorage;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
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
    private static final int HISTORY = 32;
    private final int[] historyIds = new int[HISTORY];
    private final byte[][] historyBytes = new byte[HISTORY][];
    private final int[] historyLens = new int[HISTORY];
    private int historyPos;

    public LceCodec(final LceConfig config) {
        this.config = config;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) {
        this.ctx = ctx;
        this.net = ctx.alloc().buffer();
        this.stream = ctx.alloc().buffer();
        this.toJava = new LceToJava(ctx.alloc(), this.config.debug());
        if (this.config.locatorBar()) this.toJava.locator = new Locator(ctx.channel(), this.config.debug());
        if (this.config.lceSkins()) this.toJava.skins = new PlayerSkins(ctx.channel(), this.config.debug());
        if (this.config.worldBorder()) this.toJava.edge = new WorldEdge(ctx.channel(), ctx.alloc(), this.config.debug());
        if (this.config.joinLeaveMessages() || this.toJava.skins != null) {
            this.toJava.presence = new Presence(this.config.joinLeaveMessages() ? io.github.goeroesmonk.lceviaproxy.LcePlugin.messages() : null,
                    this.toJava.skins);
        }
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
        if (this.toJava != null) this.toJava.release();
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
        process(ctx);
    }

    /** Parses the buffered server data (also called again after waiting for our skin at login). */
    private void process(final ChannelHandlerContext ctx) {
        if (this.net == null || this.state == State.CLOSED) return;
        try {
            if (!this.gotSmallId) {
                if (!this.net.isReadable()) return;
                final int smallId = this.net.readUnsignedByte();
                this.gotSmallId = true;
                if (this.config.debug()) Logger.LOGGER.info("[LCE] Assigned small id " + smallId);
                if (this.toJava.presence != null) this.toJava.presence.selfSmallId = smallId;
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
                    if (incomplete instanceof LceToJava.VelocityWait && !this.retryScheduled) {
                        // the server may send nothing more for a while: decide after the wait anyway
                        this.retryScheduled = true;
                        ctx.executor().schedule(() -> {
                            this.retryScheduled = false;
                            process(ctx);
                        }, LceToJava.velocityWaitMs() + 5, TimeUnit.MILLISECONDS);
                    }
                    if (this.config.debug() && this.stream.readableBytes() > 256 * 1024) {
                        Logger.LOGGER.info("[LCE] still waiting for packet 0x" + Integer.toHexString(id) + " with " + this.stream.readableBytes() + " bytes buffered: " + incomplete);
                    }
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
        final int len = Math.max(Math.min(this.stream.readerIndex() - start, 96), 0);
        byte[] b = this.historyBytes[this.historyPos];
        if (b == null) b = this.historyBytes[this.historyPos] = new byte[96]; // reused: no allocation per packet
        this.stream.getBytes(start, b, 0, len);
        this.historyIds[this.historyPos] = id;
        this.historyLens[this.historyPos] = len;
        this.historyPos = (this.historyPos + 1) % HISTORY;
    }

    private String history() {
        final StringBuilder sb = new StringBuilder("\nLast packets before it (oldest first, max 96 bytes each):");
        for (int i = 0; i < HISTORY; i++) {
            final int k = (this.historyPos + i) % HISTORY;
            if (this.historyBytes[k] == null) continue;
            sb.append("\n  0x").append(Integer.toHexString(this.historyIds[k])).append(": ").append(ByteBufUtil.hexDump(this.historyBytes[k], 0, this.historyLens[k]));
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
                    if (waitForSkin(ctx)) throw new IndexOutOfBoundsException("waiting for our skin");
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
                    this.stream.skipBytes(4 + 1 + 4 + 4 + 1); // instance, player index, skin, cape, guest
                    final boolean newSeaLevel = this.stream.readBoolean();
                    final int privileges = this.stream.readInt();
                    final int xzSize = this.stream.readShort();
                    final int hellScale = this.stream.readUnsignedByte();
                    if (this.toJava.edge != null) this.toJava.edge.world(xzSize, hellScale, newSeaLevel, levelType.equalsIgnoreCase("flat"));
                    this.state = State.PLAY;
                    logPrivileges(privileges);
                    Logger.LOGGER.info("LCE: joined as " + this.userName + " (entity " + entityId + ", game mode " + gameMode + ", dimension " + dimension + ")");
                    installTablist(ctx);
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
                final String text = io.github.goeroesmonk.lceviaproxy.data.LceText.disconnect(reason);
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

    /**
     * The host's per player privileges (Player::EPlayerGamePrivileges). They only apply when the host turned "Trust
     * Players" off, and belong to the XUID: with xuid=auto the server sees a new player.
     */
    private void logPrivileges(final int p) {
        final StringBuilder no = new StringBuilder();
        if ((p & 1) != 0) no.append(" mine,");
        if ((p & (1 << 1)) != 0) no.append(" build,");
        if ((p & (1 << 11)) == 0) no.append(" use doors / switches,");
        if ((p & (1 << 12)) == 0) no.append(" use containers,");
        Logger.LOGGER.info("LCE: privileges 0x" + Integer.toHexString(p) + (no.length() == 0 ? ""
                : " - if the host has \"Trust Players\" off you can't" + no.substring(0, no.length() - 1)
                + " (the host can change this; privileges belong to your xuid, see the config)"));
    }

    /** Our player list storage in ViaLegacy (keeps our own entry, with our skin). */
    private void installTablist(final ChannelHandlerContext ctx) {
        try {
            final ProxyConnection proxy = ProxyConnection.fromChannel(ctx.channel());
            final UserConnection user = proxy == null ? null : proxy.getUserConnection();
            if (user == null || !user.getProtocolInfo().getPipeline().contains(Protocolr1_7_6_10Tor1_8.class)) return;
            final TablistStorage old =
                    user.get(TablistStorage.class);
            // ViaLegacy looks it up by its own class
            user.getStoredObjects().put(TablistStorage.class, new LceTablist(user, old, () -> this.selfTexture != null
                    ? new GameProfile.Property("textures", this.selfTexture[0], this.selfTexture[1]) : null));
        } catch (Throwable t) {
            Logger.LOGGER.warn("LCE: could not set up the player list: " + t);
        }
    }

    // ------------------------------------------------------------------ skin

    /**
     * The client keeps the first player list entry it gets for itself, so the login waits (up to 3 s) until our skin
     * was looked up.
     */
    private boolean waitForSkin(final ChannelHandlerContext ctx) {
        if (this.skinDone) return false;
        final long now = System.currentTimeMillis();
        if (this.loginWaitStart == 0) {
            this.loginWaitStart = now;
            ctx.executor().schedule(() -> process(ctx), SKIN_WAIT_MS + 50, TimeUnit.MILLISECONDS);
        }
        return now - this.loginWaitStart < SKIN_WAIT_MS;
    }

    private static final long SKIN_WAIT_MS = 2000;
    private boolean retryScheduled;
    private boolean skinDone;
    /** Our signed Mojang textures (value, signature) for our own player list entry, or null. */
    private String[] selfTexture;
    private long loginWaitStart;

    private void onSkin(final ChannelHandlerContext ctx, final JavaSkin.Skin skin) {
        this.skinDone = true;
        if (this.state == State.CLOSED || !ctx.channel().isActive()) return;
        if (skin != null && skin.texture() != null) {
            this.selfTexture = skin.texture();
            if (this.toJava.skins != null) this.toJava.skins.selfTexture(skin.texture()[0], skin.texture()[1]);
        }
        final boolean upload = skin != null && skin.png() != null; // skin=none: only shown to ourselves
        if (upload) {
            this.skin = skin;
            Logger.LOGGER.info("LCE: sending your skin to the server as " + skin.path() + " (" + skin.png().length + " bytes)");
        }
        if (this.state == State.LOGIN && this.loginWaitStart != 0) {
            process(ctx); // the login waited for it (and announces the skin)
        } else if (upload && this.state == State.PLAY) {
            announceSkin(ctx);
        }
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
            if (doorTop || id == 0x85) {
                // Sign editor (0x85): the LCE server opens it before it sends the placed sign block, and newer clients
                // close a sign editor right away when there is no sign. Hand it over after the block.
                // Door top (0x35): LCE answers a click on a door's top half with that (unchanged) top half first and sends the changed
                // bottom half a tick later. Java clients draw the door from the bottom half, so the door flicked back.
                // Hand the top half over after the bottom half.
                ctx.executor().schedule(() -> {
                    if (ctx.channel().isActive() && this.state == State.PLAY) {
                        ctx.fireChannelRead(framed);
                    } else {
                        framed.release();
                    }
                }, doorTop ? DOOR_TOP_DELAY_MS : SIGN_EDITOR_DELAY_MS, TimeUnit.MILLISECONDS);
                return;
            }
            final boolean ackNow = this.pendingBlockAck != null && (id == 0x35 || id == 0x34) && updatesTarget(id, framed);
            ctx.fireChannelRead(framed);
            if (ackNow) runBlockAck(); // the update of the clicked block is there now
        } finally {
            packet.release();
        }
    }

    private static final long DOOR_TOP_DELAY_MS = 120;
    private static final long BLOCK_ACK_MAX_MS = 1000;
    private Runnable pendingBlockAck;
    private ChannelHandlerContext ctx;

    /** Blocks the pending confirmation waits for (x, y, z, ...), null = not known yet (any block update). */
    private int[] ackTargets;

    /** Called on the event loop by {@link DelayedBlockAck}: a newer confirmation covers the older ones. */
    void queueBlockAck(final Runnable ack) {
        final boolean scheduled = this.pendingBlockAck != null;
        this.pendingBlockAck = ack;
        this.ackTargets = null; // the client packet that caused it comes next (see handleClientPacket)
        if (!scheduled && this.ctx != null) {
            this.ctx.executor().schedule(() -> {
                if (this.pendingBlockAck != null) runBlockAck();
            }, BLOCK_ACK_MAX_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void runBlockAck() {
        final Runnable ack = this.pendingBlockAck;
        this.pendingBlockAck = null;
        this.ackTargets = null;
        ack.run();
    }

    /** The 1.6.4 client packet that came with a confirmation: which blocks to wait for, or confirm at once. */
    private void ackTargetsFrom(final int id, final ByteBuf p) {
        final int r = p.readerIndex();
        if (id == 0x0F && p.readableBytes() >= 11) { // use item (on a block)
            final int x = p.getInt(r), y = p.getUnsignedByte(r + 4), z = p.getInt(r + 5), face = p.getUnsignedByte(r + 9);
            if (face == 255) {
                runBlockAck(); // eating, a bow, ...: no block change
                return;
            }
            final int dx = face == 4 ? -1 : face == 5 ? 1 : 0, dy = face == 0 ? -1 : face == 1 ? 1 : 0, dz = face == 2 ? -1 : face == 3 ? 1 : 0;
            this.ackTargets = new int[]{x, y, z, x + dx, y + dy, z + dz};
        } else if (id == 0x0E && p.readableBytes() >= 11) { // dig
            final int status = p.getUnsignedByte(r);
            if (status != 0 && status != 2) {
                runBlockAck(); // drop item, release use item, ...
                return;
            }
            this.ackTargets = new int[]{p.getInt(r + 1), p.getUnsignedByte(r + 5), p.getInt(r + 6)};
        }
    }

    /** Does this 1.6.4 block change (0x35) / multi block change (0x34) update a block the confirmation waits for? */
    private boolean updatesTarget(final int id, final ByteBuf framed) {
        final int[] t = this.ackTargets;
        if (t == null) return true;
        final ByteBuf b = framed.duplicate();
        Types.readVarInt(b); // length
        Types.readVarInt(b); // id
        if (id == 0x35) {
            final int x = b.readInt(), y = b.readUnsignedByte(), z = b.readInt();
            for (int i = 0; i < t.length; i += 3) if (t[i] == x && t[i + 1] == y && t[i + 2] == z) return true;
            return false;
        }
        final int cx = b.readInt(), cz = b.readInt(), count = b.readShort();
        b.readInt(); // data size
        for (int n = 0; n < count; n++) {
            final int rec = b.readInt();
            final int x = (cx << 4) + ((rec >>> 28) & 15), z = (cz << 4) + ((rec >>> 24) & 15), y = (rec >>> 16) & 0xFF;
            for (int i = 0; i < t.length; i += 3) if (t[i] == x && t[i + 1] == y && t[i + 2] == z) return true;
        }
        return false;
    }
    private static final long SIGN_EDITOR_DELAY_MS = 200;

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

    // ------------------------------------------------------------------ goodbye message

    private boolean goodbyeSent;
    /** The client's disconnect, held back until after the goodbye message. */
    private boolean quitPending;
    private static final long GOODBYE_DELAY_MS = 300;

    /** Sends the held back disconnect (once); closes with {@code promise} afterwards when given. */
    private boolean sendQuit(final ChannelHandlerContext ctx, final ChannelPromise promise) {
        if (!this.quitPending || !ctx.channel().isActive()) return false;
        this.quitPending = false;
        final ByteBuf quit = ctx.alloc().buffer(9);
        quit.writeInt(5).writeByte(0xFF).writeInt(1); // disconnect: quitting
        if (promise != null) {
            ctx.writeAndFlush(quit).addListener(g -> ctx.close(promise));
        } else {
            ctx.writeAndFlush(quit);
        }
        return true;
    }

    /** The goodbye chat message (config goodbye-message), once, while playing. */
    private void writeGoodbye(final ChannelHandlerContext ctx, final ByteBuf out) {
        if (this.goodbyeSent || this.state != State.PLAY || !this.config.goodbyeMessage()) return;
        this.goodbyeSent = true;
        final io.github.goeroesmonk.lceviaproxy.LceMessages messages = io.github.goeroesmonk.lceviaproxy.LcePlugin.messages();
        final String text = messages == null ? "" : messages.get("goodbye", this.userName);
        if (text.isEmpty()) return;
        final ByteBuf chat = this.toLce.chat(text);
        out.writeInt(chat.readableBytes());
        out.writeBytes(chat);
        chat.release();
    }

    @Override
    public void close(final ChannelHandlerContext ctx, final ChannelPromise promise) throws Exception {
        // the Java client left without a disconnect packet: say goodbye first
        if (!this.goodbyeSent && this.state == State.PLAY && this.config.goodbyeMessage() && ctx.channel().isActive()) {
            final ByteBuf out = ctx.alloc().buffer();
            writeGoodbye(ctx, out);
            if (out.isReadable()) {
                ctx.writeAndFlush(out).addListener(f -> ctx.executor().schedule(() -> ctx.close(promise), GOODBYE_DELAY_MS, TimeUnit.MILLISECONDS));
                return;
            }
            out.release();
        }
        // ViaProxy closes right after writing the client's last packets (disconnect, goodbye) without flushing them;
        // Netty drops unflushed data on close, so flush first. After a goodbye wait a little, so the server handles
        // the chat message before the disconnect.
        if (ctx.channel().isActive()) {
            final long delay = this.goodbyeSent ? GOODBYE_DELAY_MS : 0;
            ctx.writeAndFlush(io.netty.buffer.Unpooled.EMPTY_BUFFER).addListener(f -> ctx.executor().schedule(() -> {
                if (!sendQuit(ctx, promise)) ctx.close(promise);
            }, delay, TimeUnit.MILLISECONDS));
            return;
        }
        super.close(ctx, promise);
    }

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
        if (this.toJava.edge != null && (id == 0x0B || id == 0x0D) && packet.readableBytes() >= 32) {
            final int r = packet.readerIndex();
            this.toJava.edge.playerAt(this.toJava.dimension, packet.getDouble(r), packet.getDouble(r + 24), b -> toVia(ctx, b));
        }
        if (this.pendingBlockAck != null && this.ackTargets == null && (id == 0x0F || id == 0x0E)) ackTargetsFrom(id, packet);
        if (id == 0xFF && !this.goodbyeSent) {
            writeGoodbye(ctx, out); // leaving: the goodbye message goes first...
            if (out.isReadable()) {
                // ...and the disconnect a moment later, so the server shows the message first. ViaProxy waits for the
                // server to close the connection after the disconnect, so send it ourselves.
                this.quitPending = true;
                ctx.executor().schedule(() -> sendQuit(ctx, null), GOODBYE_DELAY_MS, TimeUnit.MILLISECONDS);
                return;
            }
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
