package io.github.t9homas.lceviaproxy;

import net.raphimc.viaproxy.util.logging.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Properties;

/**
 * plugins/LCERevelations/config.properties
 */
public final class LceConfig {

    private static final String HEADER = """
             LCE Revelations plugin for ViaProxy

             netcode-version: LCE network version sent in the PreLogin packet (Revelations = 560).
             xuid: your player id. "auto" makes a stable one from your user name.
                   To use the same id as your LCE client, copy the value from its uid.dat (for example 0xD8A081207D94B205).
             srv-lookup: follow _minecraft._tcp SRV records when no port is given (LCE clients do this too).
             debug: log unknown / dropped packets.
            """;

    private final int netcodeVersion;
    private final String xuid;
    private final boolean srvLookup;
    private final boolean debug;

    public LceConfig(final File dataFolder) {
        final Properties p = new Properties();
        p.setProperty("netcode-version", "560");
        p.setProperty("xuid", "auto");
        p.setProperty("srv-lookup", "true");
        p.setProperty("debug", "false");

        final File file = new File(dataFolder, "config.properties");
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                p.load(in);
            } catch (Exception e) {
                Logger.LOGGER.warn("Could not read " + file + ": " + e);
            }
        }
        dataFolder.mkdirs();
        try (OutputStream out = new FileOutputStream(file)) {
            p.store(out, HEADER);
        } catch (Exception e) {
            Logger.LOGGER.warn("Could not write " + file + ": " + e);
        }

        this.netcodeVersion = parseInt(p.getProperty("netcode-version"), 560);
        this.xuid = p.getProperty("xuid", "auto").trim();
        this.srvLookup = Boolean.parseBoolean(p.getProperty("srv-lookup", "true").trim());
        this.debug = Boolean.parseBoolean(p.getProperty("debug", "false").trim());
    }

    private static int parseInt(final String s, final int def) {
        try {
            return Integer.decode(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public int netcodeVersion() {
        return this.netcodeVersion;
    }

    public boolean srvLookup() {
        return this.srvLookup;
    }

    public boolean debug() {
        return this.debug;
    }

    /**
     * The XUID (PlayerUID) sent to the server for this user name.
     */
    public long xuidFor(final String userName) {
        if (!this.xuid.isEmpty() && !this.xuid.equalsIgnoreCase("auto")) {
            try {
                String s = this.xuid;
                if (s.startsWith("0x") || s.startsWith("0X")) {
                    s = s.substring(2);
                }
                return Long.parseUnsignedLong(s, 16);
            } catch (Exception e) {
                Logger.LOGGER.warn("Invalid xuid in config: " + this.xuid + ", using auto");
            }
        }
        try {
            final byte[] h = MessageDigest.getInstance("SHA-256").digest(("LCERevelations:" + userName.toLowerCase()).getBytes(StandardCharsets.UTF_8));
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (h[i] & 0xFF);
            }
            return v == 0 ? 1 : v;
        } catch (Exception e) {
            return userName.hashCode() & 0xFFFFFFFFL;
        }
    }

}
