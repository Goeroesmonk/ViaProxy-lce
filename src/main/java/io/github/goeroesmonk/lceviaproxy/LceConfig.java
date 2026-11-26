package io.github.goeroesmonk.lceviaproxy;

import net.raphimc.viaproxy.util.logging.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * plugins/LCERevelations/config.yml (an old config.properties is taken over and renamed to config.properties.old).
 */
public final class LceConfig {

    /** key, default, comment */
    private static final String[][] OPTIONS = {
            {"skin", "auto", "The skin LCE players see on you: auto = the Java skin of your user name (from Mojang),\n"
                    + "another player's name, a path to a 64x64 / 64x32 PNG file, or none (the server picks a default skin;\n"
                    + "you still see your Java skin)."},
            {"lce-skins", "true", "Show the skins of LCE players (default and DLC skins, from a list built into the plugin)."},
            {"locator-bar", "true", "Show the other players on the locator bar of 1.21.6+ clients, colored by name."},
            {"world-border", "true", "Show the edge of the LCE world as a world border, with the endless sea behind it like on LCE."},
            {"join-leave-messages", "true", "Show \"... joined / left the game\" (the plugin makes them; texts in messages.yml)."},
            {"goodbye-message", "false", "Send a chat message (messages.yml: goodbye) to the server when you leave."},
            {"xuid", "auto", "Your player id. auto makes a stable one from your user name. To use the same id as your LCE\n"
                    + "client, copy the value from its uid.dat (for example 0xD8A081207D94B205)."},
            {"netcode-version", "560", "LCE network version sent in the PreLogin packet (Revelations = 560)."},
            {"srv-lookup", "true", "Follow _minecraft._tcp SRV records when no port is given (LCE clients do this too)."},
            {"debug", "false", "Log every packet and the packets before a protocol error."},
    };

    private final int netcodeVersion;
    private final String xuid;
    private final boolean srvLookup;
    private final boolean debug;
    private final String skin;
    private final boolean locatorBar;
    private final boolean lceSkins;
    private final boolean worldBorder;
    private final boolean joinLeaveMessages;
    private final boolean goodbyeMessage;

    public LceConfig(final File dataFolder) {
        final Map<String, String> v = new LinkedHashMap<>();
        for (String[] o : OPTIONS) v.put(o[0], o[1]);

        final File yml = new File(dataFolder, "config.yml");
        final File old = new File(dataFolder, "config.properties");
        if (!yml.isFile() && old.isFile()) { // take over the old config
            final Properties p = new Properties();
            try (InputStream in = new FileInputStream(old)) {
                p.load(in);
                for (String k : p.stringPropertyNames()) if (v.containsKey(k)) v.put(k, p.getProperty(k).trim());
            } catch (Exception e) {
                Logger.LOGGER.warn("Could not read " + old + ": " + e);
            }
            if (!old.renameTo(new File(dataFolder, "config.properties.old"))) old.delete();
        } else if (yml.isFile()) {
            try {
                for (String line : Files.readAllLines(yml.toPath(), StandardCharsets.UTF_8)) {
                    line = line.strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    final int colon = line.indexOf(':');
                    if (colon <= 0) continue;
                    final String key = line.substring(0, colon).strip();
                    if (v.containsKey(key)) v.put(key, unquote(line.substring(colon + 1).strip()));
                }
            } catch (Exception e) {
                Logger.LOGGER.warn("Could not read " + yml + ": " + e);
            }
        }

        // write it back (adds new options with their default and comment)
        final StringBuilder out = new StringBuilder("# LCE Revelations plugin for ViaProxy. Restart ViaProxy after changing this file.\n");
        for (String[] o : OPTIONS) {
            out.append('\n');
            for (String c : o[2].split("\n")) out.append("# ").append(c).append('\n');
            out.append(o[0]).append(": ").append(quote(v.get(o[0]))).append('\n');
        }
        dataFolder.mkdirs();
        try {
            Files.writeString(yml.toPath(), out.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Logger.LOGGER.warn("Could not write " + yml + ": " + e);
        }

        this.netcodeVersion = parseInt(v.get("netcode-version"), 560);
        this.xuid = v.get("xuid");
        this.srvLookup = Boolean.parseBoolean(v.get("srv-lookup"));
        this.debug = Boolean.parseBoolean(v.get("debug"));
        this.skin = v.get("skin");
        this.locatorBar = Boolean.parseBoolean(v.get("locator-bar"));
        this.lceSkins = Boolean.parseBoolean(v.get("lce-skins"));
        this.worldBorder = Boolean.parseBoolean(v.get("world-border"));
        this.joinLeaveMessages = Boolean.parseBoolean(v.get("join-leave-messages"));
        this.goodbyeMessage = Boolean.parseBoolean(v.get("goodbye-message"));
    }

    /** YAML single quoted for text (paths keep their backslashes), plain for true / false / numbers. */
    private static String quote(final String value) {
        if (value.matches("true|false|-?[0-9]+|auto|none")) return value;
        return "'" + value.replace("'", "''") + "'";
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) return value.substring(1, value.length() - 1);
        final int comment = value.indexOf(" #");
        return (comment >= 0 ? value.substring(0, comment) : value).strip();
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

    public boolean lceSkins() {
        return this.lceSkins;
    }

    public boolean joinLeaveMessages() {
        return this.joinLeaveMessages;
    }

    public boolean goodbyeMessage() {
        return this.goodbyeMessage;
    }

    public boolean worldBorder() {
        return this.worldBorder;
    }

    public boolean locatorBar() {
        return this.locatorBar;
    }

    public String skin() {
        return this.skin;
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
