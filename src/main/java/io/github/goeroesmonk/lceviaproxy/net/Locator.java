package io.github.goeroesmonk.lceviaproxy.net;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_5to1_21_6.Protocol1_21_5To1_21_6;
import com.viaversion.viaversion.protocols.v1_21_5to1_21_6.packet.ClientboundPackets1_21_6;
import io.netty.channel.Channel;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import net.raphimc.viaproxy.util.logging.Logger;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shows the other players on the locator bar of 1.21.6+ clients (LCE has no locator bar, so the plugin sends the
 * waypoints itself). Each player gets a color made from their name. Only players the server has spawned for us (in view
 * distance) are known.
 */
public final class Locator {

    private static final int TRACK = 0, UNTRACK = 1, UPDATE = 2;

    private static final class Waypoint {
        final UUID uuid;
        final int rgb;
        int x, y, z;          // 1/32 block
        int bx, by, bz;       // last sent block
        boolean sent;

        Waypoint(final String name, final int x, final int y, final int z) {
            // the UUID of the player's list entry, so mods that draw heads on the locator bar find the skin
            this.uuid = io.github.goeroesmonk.lceviaproxy.skin.LceSkins.javaUuid(name);
            this.rgb = colorOf(name);
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final Channel channel;
    private final boolean debug;
    private final Map<Integer, Waypoint> players = new HashMap<>();
    private Boolean supported;
    private boolean retryScheduled;

    public Locator(final Channel channel, final boolean debug) {
        this.channel = channel;
        this.debug = debug;
    }

    /** A stable, bright color per name. */
    static int colorOf(final String name) {
        final int h = name.toLowerCase().hashCode() * 0x9E3779B1;
        return Color.HSBtoRGB((h >>> 8) / (float) (1 << 24), 0.8F, 1F) & 0xFFFFFF;
    }

    void add(final int eid, final String name, final int x, final int y, final int z) {
        final Waypoint old = this.players.put(eid, new Waypoint(name, x, y, z));
        if (old != null && old.sent) send(UNTRACK, old);
        flush();
    }

    void move(final int eid, final int dx, final int dy, final int dz) {
        final Waypoint w = this.players.get(eid);
        if (w == null) return;
        w.x += dx;
        w.y += dy;
        w.z += dz;
        update(w);
    }

    void teleport(final int eid, final int x, final int y, final int z) {
        final Waypoint w = this.players.get(eid);
        if (w == null) return;
        w.x = x;
        w.y = y;
        w.z = z;
        update(w);
    }

    void remove(final int eid) {
        final Waypoint w = this.players.remove(eid);
        if (w != null && w.sent) send(UNTRACK, w);
    }

    void clear() {
        for (Waypoint w : this.players.values()) {
            if (w.sent) send(UNTRACK, w);
        }
        this.players.clear();
    }

    private void update(final Waypoint w) {
        if (!w.sent) {
            flush();
            return;
        }
        if ((w.x >> 5) != w.bx || (w.y >> 5) != w.by || (w.z >> 5) != w.bz) send(UPDATE, w);
    }

    /** Sends the waypoints that could not be sent yet (the client was still logging in / configuring). */
    private void flush() {
        for (Waypoint w : this.players.values()) {
            if (!w.sent) send(TRACK, w);
        }
    }

    private UserConnection user() {
        final ProxyConnection proxy = ProxyConnection.fromChannel(this.channel);
        return proxy == null ? null : proxy.getUserConnection();
    }

    private void send(final int op, final Waypoint w) {
        final UserConnection user = user();
        if (user == null) return;
        if (this.supported == null) {
            this.supported = user.getProtocolInfo().getPipeline().contains(Protocol1_21_5To1_21_6.class);
            if (this.debug) Logger.LOGGER.info("[LCE] locator bar " + (this.supported ? "on" : "off (client older than 1.21.6)"));
        }
        if (!this.supported) return;
        if (user.getProtocolInfo().getClientState() != State.PLAY) {
            // the client is still logging in / configuring: try again shortly
            if (!this.retryScheduled && this.channel.isActive()) {
                this.retryScheduled = true;
                this.channel.eventLoop().schedule(() -> {
                    this.retryScheduled = false;
                    flush();
                }, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            return;
        }
        try {
            final PacketWrapper p = PacketWrapper.create(ClientboundPackets1_21_6.TRACKED_WAYPOINT, user);
            p.write(Types.VAR_INT, op);
            p.write(Types.BOOLEAN, true); // identifier is a UUID
            p.write(Types.UUID, w.uuid);
            p.write(Types.STRING, "minecraft:default");
            p.write(Types.BOOLEAN, true); // has color
            p.write(Types.UNSIGNED_BYTE, (short) ((w.rgb >> 16) & 0xFF));
            p.write(Types.UNSIGNED_BYTE, (short) ((w.rgb >> 8) & 0xFF));
            p.write(Types.UNSIGNED_BYTE, (short) (w.rgb & 0xFF));
            if (op == UNTRACK) {
                p.write(Types.VAR_INT, 0); // empty
            } else {
                w.bx = w.x >> 5;
                w.by = w.y >> 5;
                w.bz = w.z >> 5;
                p.write(Types.VAR_INT, 1); // block position
                p.write(Types.VAR_INT, w.bx);
                p.write(Types.VAR_INT, w.by);
                p.write(Types.VAR_INT, w.bz);
            }
            p.send(Protocol1_21_5To1_21_6.class);
            w.sent = op != UNTRACK;
        } catch (Exception e) {
            Logger.LOGGER.warn("LCE: could not send a locator bar waypoint: " + e);
            this.supported = false;
        }
    }

}
