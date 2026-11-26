package io.github.goeroesmonk.lceviaproxy;

import net.raphimc.viaproxy.util.logging.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * plugins/LCERevelations/messages.yml: texts of the messages the plugin makes. Simple "key: value" lines (the value may
 * be quoted), "&" or "§" color codes, %player% = the player's name.
 */
public final class LceMessages {

    private static final String DEFAULT = """
            # Messages of the LCE Revelations plugin. Color codes: &e, &c, ... (or §e). %player% = the player's name.

            # Shown to you when a player joins / leaves the server (config: join-leave-messages).
            # LCE servers can turn these messages off; the plugin then makes them itself.
            join: "&e%player% joined the game"
            leave: "&e%player% left the game"
            # When the name of the player is not known (they never came near you)
            join-unknown: "&eA player joined the game"
            leave-unknown: "&eA player left the game"

            # Sent to the server as a chat message when you leave (config: goodbye-message).
            goodbye: "Bye!"
            """;

    private final Map<String, String> values = new HashMap<>();

    public LceMessages(final File dataFolder) {
        final File file = new File(dataFolder, "messages.yml");
        try {
            if (!file.isFile()) {
                dataFolder.mkdirs();
                Files.writeString(file.toPath(), DEFAULT, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Logger.LOGGER.warn("Could not write " + file + ": " + e);
        }
        parse(DEFAULT);
        try {
            if (file.isFile()) parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Logger.LOGGER.warn("Could not read " + file + ": " + e);
        }
    }

    private void parse(final String text) {
        for (String line : text.split("\\R")) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            final int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String value = line.substring(colon + 1).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            this.values.put(line.substring(0, colon).strip(), value);
        }
    }

    /** The message with color codes, or "" when it is empty / missing. */
    public String get(final String key, final String player) {
        final String v = this.values.getOrDefault(key, "");
        return v.replace("%player%", player == null ? "" : player).replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
    }

}
