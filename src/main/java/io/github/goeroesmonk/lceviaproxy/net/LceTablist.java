package io.github.goeroesmonk.lceviaproxy.net;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.GameProfile;
import net.raphimc.vialegacy.protocol.release.r1_7_6_10tor1_8.model.TabListEntry;
import net.raphimc.vialegacy.protocol.release.r1_7_6_10tor1_8.storage.TablistStorage;

import java.util.function.Supplier;

/**
 * ViaLegacy's player list storage, with one change: our own entry. ViaLegacy adds it at login / respawn without a skin
 * and removes it again; the client keeps the first entry it sees for its own player, so the skin added later never
 * showed. Here our entry carries our skin from the start and stays.
 */
final class LceTablist extends TablistStorage {

    private final Supplier<GameProfile.Property> selfTexture;

    LceTablist(final UserConnection user, final TablistStorage old, final Supplier<GameProfile.Property> selfTexture) {
        super(user);
        if (old != null) this.getTablist().putAll(old.getTablist());
        this.selfTexture = selfTexture;
    }

    @Override
    public void sendTempEntry(final TabListEntry entry) {
        final GameProfile profile = entry.getGameProfile();
        final java.util.UUID self = this.user().getProtocolInfo().getUuid();
        if (profile == null || self == null || !self.equals(profile.id())) {
            super.sendTempEntry(entry);
            return;
        }
        final GameProfile.Property texture = this.selfTexture.get();
        final TabListEntry withSkin = texture == null ? entry
                : new TabListEntry(new GameProfile(profile.name(), profile.id(), new GameProfile.Property[]{texture}));
        withSkin.setPing(0);
        sendAddEntry(withSkin); // and keep it
    }

}
