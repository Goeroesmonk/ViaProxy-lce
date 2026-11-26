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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Player list entries for the LCE players the server spawned for us: they carry the skin, and they keep the players in
 * the tab list (ViaLegacy only adds a temporary entry when a player spawns and removes it 2 ticks later; the LCE player
 * list packet has no names).
 * <ul>
 *     <li>skins from {@link LceSkins}: sent before the spawn (newer clients keep the first entry of a player and need it
 *     to create the player), right after it (1.8 - 1.19.2 keep the last entry) and after ViaLegacy removed its entry</li>
 *     <li>other players: sent after ViaLegacy removed its entry, with the profile ViaLegacy used</li>
 * </ul>
 * The entry is removed again when the player despawns.
 */
final class PlayerSkins {

    private static final long READD_DELAY_MS = 400; // ViaLegacy removes its entry after 2 ticks

    private record Entry(String name, UUID uuid) {
    }

    private final Channel channel;
    private final boolean debug;
    /** server entity id -> player with an entry from us */
    private final Map<Integer, Entry> shown = new HashMap<>();

    PlayerSkins(final Channel channel, final boolean debug) {
        this.channel = channel;
        this.debug = debug;
    }

    void beforeSpawn(final int eid, final String name) {
        final Entry e = new Entry(name, LceSkins.javaUuid(name));
        this.shown.put(eid, e);
        if (LceSkins.texture(name) != null) send(e, true);
    }

    void afterSpawn(final int eid, final String name) {
        final Entry e = this.shown.get(eid);
        if (e == null || !e.name.equals(name)) return;
        if (LceSkins.texture(name) != null) send(e, true);
        this.channel.eventLoop().schedule(() -> {
            if (this.channel.isActive() && e.equals(this.shown.get(eid))) send(e, true);
        }, READD_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /** Our own Java skin (signed Mojang textures), or null. */
    private GameProfile.Property selfTexture;
    private Entry selfEntry;

    /** Our own entry (ViaLegacy removes it too), after login and respawn. */
    void self(final String name) {
        this.channel.eventLoop().schedule(() -> {
            final ProxyConnection proxy = ProxyConnection.fromChannel(this.channel);
            final UserConnection user = proxy == null ? null : proxy.getUserConnection();
            if (!this.channel.isActive() || user == null || user.getProtocolInfo().getUuid() == null) return;
            this.selfEntry = new Entry(name, user.getProtocolInfo().getUuid());
            send(this.selfEntry, true);
        }, READD_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /** Our skin arrived (it is looked up while logging in): show it on our own player too. */
    void selfTexture(final String value, final String signature) {
        this.selfTexture = new GameProfile.Property("textures", value, signature);
        if (this.selfEntry != null) {
            send(this.selfEntry, false); // newer clients keep the first entry: replace it
            send(this.selfEntry, true);
        }
    }

    /** Names in the colors the server shows them in chat (lower case name -> name with color codes). */
    private final Map<String, String> colors = new HashMap<>();

    /** The server showed a player's name in colors in chat: show it like that in the tab list too. */
    void coloredName(final String name, final String colored) {
        if (colored.equals(this.colors.put(name.toLowerCase(), colored))) return;
        for (Entry e : this.shown.values()) {
            if (e.name.equalsIgnoreCase(name)) sendDisplayName(e);
        }
        if (this.selfEntry != null && this.selfEntry.name.equalsIgnoreCase(name)) sendDisplayName(this.selfEntry);
    }

    private String displayName(final Entry e) {
        final String colored = this.colors.get(e.name.toLowerCase());
        if (colored == null) return null;
        final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("text", colored);
        return o.toString();
    }

    private void sendDisplayName(final Entry e) {
        final ProxyConnection proxy = ProxyConnection.fromChannel(this.channel);
        final UserConnection user = proxy == null ? null : proxy.getUserConnection();
        if (user == null || !user.getProtocolInfo().getPipeline().contains(Protocolr1_7_6_10Tor1_8.class)) return;
        try {
            final PacketWrapper p = PacketWrapper.create(ClientboundPackets1_8.PLAYER_INFO, user);
            p.write(Types.VAR_INT, 3); // update display name
            p.write(Types.VAR_INT, 1);
            p.write(Types.UUID, e.uuid);
            p.write(Types.OPTIONAL_STRING, displayName(e));
            p.send(Protocolr1_7_6_10Tor1_8.class);
        } catch (Exception ex) {
            Logger.LOGGER.warn("LCE: could not color the name of " + e.name + ": " + ex);
        }
    }

    void remove(final int eid) {
        final Entry e = this.shown.remove(eid);
        if (e != null && !this.shown.containsValue(e)) send(e, false);
    }

    void clear() {
        for (Entry e : this.shown.values()) send(e, false);
        this.shown.clear();
    }

    private void send(final Entry e, final boolean add) {
        final ProxyConnection proxy = ProxyConnection.fromChannel(this.channel);
        final UserConnection user = proxy == null ? null : proxy.getUserConnection();
        if (user == null || !user.getProtocolInfo().getPipeline().contains(Protocolr1_7_6_10Tor1_8.class)) return; // 1.7 clients
        try {
            final PacketWrapper p = PacketWrapper.create(ClientboundPackets1_8.PLAYER_INFO, user);
            p.write(Types.VAR_INT, add ? 0 : 4);
            p.write(Types.VAR_INT, 1);
            p.write(Types.UUID, e.uuid);
            if (add) {
                final GameProfile.Property[] properties = e == this.selfEntry && this.selfTexture != null
                        ? new GameProfile.Property[]{this.selfTexture} : LceSkins.properties(e.name, e.uuid);
                p.write(Types.STRING, e.name);
                p.write(Types.PROFILE_PROPERTY_ARRAY, properties);
                p.write(Types.VAR_INT, 0); // game mode
                p.write(Types.VAR_INT, 0); // ping
                p.write(Types.OPTIONAL_STRING, displayName(e)); // the name as colored in chat, or none
            }
            p.send(Protocolr1_7_6_10Tor1_8.class);
            if (this.debug) Logger.LOGGER.info("[LCE] player list entry " + (add ? "for " : "removed for ") + e.name);
        } catch (Exception ex) {
            Logger.LOGGER.warn("LCE: could not send the player list entry of " + e.name + ": " + ex);
        }
    }

}
