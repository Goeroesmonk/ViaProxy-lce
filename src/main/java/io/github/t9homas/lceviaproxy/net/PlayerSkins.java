package io.github.t9homas.lceviaproxy.net;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.GameProfile;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_8to1_9.packet.ClientboundPackets1_8;
import io.github.t9homas.lceviaproxy.skin.LceSkins;
import io.netty.channel.Channel;
import net.raphimc.vialegacy.protocol.release.r1_7_6_10tor1_8.Protocolr1_7_6_10Tor1_8;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import net.raphimc.viaproxy.util.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Gives LCE players their skin in the Java client. The client takes a player's skin from its player list entry;
 * ViaLegacy only adds a temporary entry without skin when a player spawns (and removes it 2 ticks later), so the plugin
 * sends its own entry with the signed texture from {@link LceSkins}:
 * <ul>
 *     <li>before the spawn: newer clients keep the first entry of a player and need it to create the player</li>
 *     <li>right after the spawn: older clients (1.8 - 1.19.2) keep the last entry</li>
 *     <li>after ViaLegacy removed its temporary entry, in case the client had not looked the skin up yet</li>
 * </ul>
 * The entry is removed again when the player despawns.
 */
final class PlayerSkins {

    private static final long READD_DELAY_MS = 400; // ViaLegacy removes its entry after 2 ticks

    private final Channel channel;
    private final boolean debug;
    /** server entity id -> name of players with an entry from us */
    private final Map<Integer, String> shown = new HashMap<>();

    PlayerSkins(final Channel channel, final boolean debug) {
        this.channel = channel;
        this.debug = debug;
    }

    void beforeSpawn(final int eid, final String name) {
        if (LceSkins.texture(name) == null) return;
        this.shown.put(eid, name);
        add(name);
    }

    void afterSpawn(final int eid, final String name) {
        if (!name.equals(this.shown.get(eid))) return;
        add(name);
        this.channel.eventLoop().schedule(() -> {
            if (this.channel.isActive() && name.equals(this.shown.get(eid))) add(name);
        }, READD_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    void remove(final int eid) {
        final String name = this.shown.remove(eid);
        if (name != null && !this.shown.containsValue(name)) send(name, false);
    }

    void clear() {
        for (String name : this.shown.values()) send(name, false);
        this.shown.clear();
    }

    private void add(final String name) {
        send(name, true);
    }

    private void send(final String name, final boolean add) {
        final ProxyConnection proxy = ProxyConnection.fromChannel(this.channel);
        final UserConnection user = proxy == null ? null : proxy.getUserConnection();
        if (user == null || !user.getProtocolInfo().getPipeline().contains(Protocolr1_7_6_10Tor1_8.class)) return; // 1.7 clients
        try {
            final PacketWrapper p = PacketWrapper.create(ClientboundPackets1_8.PLAYER_INFO, user);
            p.write(Types.VAR_INT, add ? 0 : 4);
            p.write(Types.VAR_INT, 1);
            p.write(Types.UUID, LceSkins.uuid(name));
            if (add) {
                final GameProfile.Property texture = LceSkins.texture(name);
                p.write(Types.STRING, name);
                p.write(Types.PROFILE_PROPERTY_ARRAY, new GameProfile.Property[]{texture});
                p.write(Types.VAR_INT, 0); // game mode
                p.write(Types.VAR_INT, 0); // ping
                p.write(Types.OPTIONAL_STRING, null); // no display name
            }
            p.send(Protocolr1_7_6_10Tor1_8.class);
            if (this.debug) Logger.LOGGER.info("[LCE] skin entry " + (add ? "for " : "removed for ") + name);
        } catch (Exception e) {
            Logger.LOGGER.warn("LCE: could not send the skin of " + name + ": " + e);
        }
    }

}
