package io.github.t9homas.lceviaproxy.skin;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.minecraft.GameProfile;
import net.raphimc.vialegacy.protocol.release.r1_7_6_10tor1_8.provider.GameProfileFetcher;
import net.raphimc.viaproxy.util.logging.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows the skins of LCE players in the Java client.
 * <p>
 * Every skin an LCE player can wear is built into the console client (8 default skins + the DLC skin packs), so the plugin
 * ships a list of them with signed Mojang textures ({@code lce_skins.tsv}, made once with MineSkin; the plugin itself
 * never contacts MineSkin). When the server spawns an LCE player, its skin id is looked up in the list and ViaLegacy's
 * game profile lookup is answered with that texture.
 */
public final class LceSkins {

    private record Texture(String value, String signature) {
    }

    private record Player(UUID uuid, GameProfile profile) {
    }

    private static final Map<String, Texture> TEXTURES = load();
    /** lower case name -> player with a known skin */
    private static final Map<String, Player> BY_NAME = new ConcurrentHashMap<>();
    private static final Map<UUID, Player> BY_UUID = new ConcurrentHashMap<>();
    private static boolean installed;

    private LceSkins() {
    }

    private static Map<String, Texture> load() {
        final Map<String, Texture> map = new HashMap<>();
        try (InputStream in = LceSkins.class.getResourceAsStream("/lce_skins.tsv")) {
            if (in == null) return map;
            final BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                final String[] p = line.split("\t");
                if (p.length == 3) map.put(p[0], new Texture(p[1], p[2]));
            }
        } catch (Exception e) {
            Logger.LOGGER.warn("LCE: could not read the skin list: " + e);
        }
        return map;
    }

    /**
     * The key of a skin id in the list (the skin id is the one in the LCE player spawn / login packets):
     * {@code dlcskinNNNNNNNN.png} for DLC skins, {@code charN} for the default skins.
     *
     * @param playerIndex the player's index on the server, picks the default skin for "automatically selected"
     */
    static String keyFor(final int skinId, final int playerIndex) {
        if ((skinId & 0x80000000) != 0) {
            return String.format("dlcskin%08d.png", skinId & 0x7FFFFFFF);
        }
        if ((skinId & 0x7FFFFFE0) != 0) return null; // a custom (ugc) skin: not in the list
        int index = skinId & 0x1F;
        if (index == 0) index = (playerIndex & 0x7) + 1; // "automatically selected": one of the 8 default skins
        return index <= 8 ? "char" + (index - 1) : null;
    }

    /**
     * Remembers the skin of an LCE player; called before the spawn packet goes to ViaLegacy.
     */
    public static void register(final String name, final int skinId, final int playerIndex) {
        final String key = keyFor(skinId, playerIndex);
        // custom skin (other Java players through this plugin use one): ViaLegacy looks the name up at Mojang as usual
        if (key == null) {
            // forget a skin the name had before (it switched to a custom skin)
            final Player old = BY_NAME.remove(name.toLowerCase());
            if (old != null) BY_UUID.remove(old.uuid);
            return;
        }
        final Texture t = TEXTURES.get(key);
        final UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        // a skin that is not in the list (a pack only the server has): no skin rather than a random Java player's
        final GameProfile.Property[] properties = t == null ? new GameProfile.Property[0]
                : new GameProfile.Property[]{new GameProfile.Property("textures", t.value, t.signature)};
        final Player p = new Player(uuid, new GameProfile(name, uuid, properties));
        BY_NAME.put(name.toLowerCase(), p);
        BY_UUID.put(uuid, p);
    }

    /** The UUID the Java client knows the player by (ViaLegacy's offline UUID). */
    public static UUID uuid(final String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** The signed texture of a registered LCE player, or null. */
    public static GameProfile.Property texture(final String name) {
        final Player p = BY_NAME.get(name.toLowerCase());
        return p == null || p.profile.properties().length == 0 ? null : p.profile.properties()[0];
    }

    public static int size() {
        return TEXTURES.size();
    }

    /**
     * Puts our profile lookup in front of ViaProxy's, so that with ViaLegacy's "legacy skin loading" on, LCE players keep
     * the UUID the plugin sends their skin for.
     */
    public static synchronized void install() {
        if (installed || TEXTURES.isEmpty()) return;
        installed = true;
        try {
            final GameProfileFetcher original = Via.getManager().getProviders().get(GameProfileFetcher.class);
            Via.getManager().getProviders().use(GameProfileFetcher.class, new Fetcher(original));
            Logger.LOGGER.info("LCE: " + TEXTURES.size() + " LCE skins available");
        } catch (Throwable t) {
            Logger.LOGGER.warn("LCE: could not set up LCE skins: " + t);
        }
    }

    private static final class Fetcher extends GameProfileFetcher {

        private final GameProfileFetcher original;

        Fetcher(final GameProfileFetcher original) {
            this.original = original;
        }

        @Override
        public boolean isUuidLoaded(final String name) {
            return BY_NAME.containsKey(name.toLowerCase()) || super.isUuidLoaded(name);
        }

        @Override
        public UUID getMojangUuid(final String name) {
            final Player p = BY_NAME.get(name.toLowerCase());
            return p != null ? p.uuid : super.getMojangUuid(name);
        }

        @Override
        public boolean isGameProfileLoaded(final UUID uuid) {
            return BY_UUID.containsKey(uuid) || super.isGameProfileLoaded(uuid);
        }

        @Override
        public GameProfile getGameProfile(final UUID uuid) {
            final Player p = BY_UUID.get(uuid);
            return p != null ? p.profile : super.getGameProfile(uuid);
        }

        @Override
        public UUID loadMojangUuid(final String name) throws Exception {
            if (this.original == null) throw new IllegalStateException("no profile lookup");
            return this.original.loadMojangUuid(name);
        }

        @Override
        public GameProfile loadGameProfile(final UUID uuid) throws Exception {
            if (this.original == null) throw new IllegalStateException("no profile lookup");
            return this.original.loadGameProfile(uuid);
        }
    }

}
