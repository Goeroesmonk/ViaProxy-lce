package io.github.t9homas.lceviaproxy;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionType;
import net.raphimc.vialegacy.api.LegacyProtocolVersion;

import java.util.Comparator;

/**
 * Adds "LCE Revelations" to ViaProxy's server version list.
 * <p>
 * Minecraft Legacy Console Edition (TU19 based) speaks a protocol that is derived from Java Edition 1.6.4.
 * The plugin translates it to real 1.6.4 packets, so ViaLegacy / ViaVersion can take it from there.
 */
public final class LceVersions {

    public static final ProtocolVersion LCE_REVELATIONS = new LceProtocolVersion(-1900, "LCE Revelations");

    private LceVersions() {
    }

    public static void register() {
        if (!ProtocolVersion.isRegistered(LCE_REVELATIONS.getVersionType(), LCE_REVELATIONS.getVersion())) {
            ProtocolVersion.register(LCE_REVELATIONS);
        }
    }

    public static boolean isLce(final ProtocolVersion version) {
        return version instanceof LceProtocolVersion;
    }

    /**
     * The Java Edition version the LCE stream is translated to.
     */
    public static ProtocolVersion baseVersion() {
        return LegacyProtocolVersion.r1_6_4;
    }

    private static final class LceProtocolVersion extends ProtocolVersion {

        private LceProtocolVersion(final int id, final String name) {
            super(VersionType.SPECIAL, id, -1, name, null);
        }

        @Override
        protected Comparator<ProtocolVersion> customComparator() {
            // Sort right below 1.7.1, i.e. just above 1.6.4. (ViaLegacy's versions aren't loaded yet when plugins enable.)
            return (o1, o2) -> {
                if (o1 == o2) {
                    return 0;
                }
                if (o1 instanceof LceProtocolVersion) {
                    return o2.newerThanOrEqualTo(ProtocolVersion.v1_7_1) ? -1 : 1;
                }
                return o1.newerThanOrEqualTo(ProtocolVersion.v1_7_1) ? 1 : -1;
            };
        }

    }

}
