package io.github.t9homas.lceviaproxy.data;

import java.util.List;

/**
 * LCE sends most chat lines as a message type + arguments (the console client looks up the localised text).
 * This turns them into plain English text for Java clients.
 * <p>
 * Arguments: string 0 = player, string 1 = killer / source name, string 2 = item name, int 0 = source entity type.
 */
public final class LceText {

    // Index = EChatPacketMessage value. %1 = player, %2 = source, %3 = item.
    private static final String[] CHAT = {
            null,                                         // custom text
            "This bed is occupied",
            "You can only sleep at night",
            "Your home bed was missing or obstructed",
            "You may not rest now, there are monsters nearby",
            "%1 is sleeping in a bed",
            "You are sleeping in a bed",
            "%1 left the game",
            "%1 joined the game",
            "%1 was kicked from the game",
            null,                                         // cannot place lava (UI popup only)
            "%1 went up in flames",
            "%1 burned to death",
            "%1 tried to swim in lava",
            "%1 suffocated in a wall",
            "%1 drowned",
            "%1 starved to death",
            "%1 was pricked to death",
            "%1 hit the ground too hard",
            "%1 fell out of the world",
            "%1 died",
            "%1 blew up",
            "%1 was killed by magic",
            "%1 was slain by %2",
            "%1 was slain by %2",
            "%1 was shot by %2",
            "%1 was fireballed by %2",
            "%1 was pummeled by %2",
            "%1 was killed by %2 using magic",
            "%1 was roasted in dragon breath",
            "%1 was squashed by a falling anvil",
            "%1 was squashed by a falling block",
            "%1 was killed while trying to hurt %2",
            "%1 fell off a ladder",
            "%1 fell off some vines",
            "%1 fell out of the water",
            "%1 fell from a high place",
            "%1 was doomed to fall by %2",
            "%1 was doomed to fall by %2",
            "%1 was doomed to fall by %2 using %3",
            "%1 fell too far and was finished by %2",
            "%1 fell too far and was finished by %2 using %3",
            "%1 walked into fire whilst fighting %2",
            "%1 was burnt to a crisp whilst fighting %2",
            "%1 tried to swim in lava to escape %2",
            "%1 drowned whilst trying to escape %2",
            "%1 walked into a cactus whilst trying to escape %2",
            "%1 was blown up by %2",
            "%1 withered away",
            "%1 was slain by %2 using %3",
            "%1 was shot by %2 using %3",
            "%1 was fireballed by %2 using %3",
            "%1 was pummeled by %2 using %3",
            "%1 was killed by %2 using %3",
            "%1 entered The End",
            "%1 left The End",
            "Can't use Spawn Egg: the maximum number of Pigs, Sheep and Cows has been reached",
            "Can't use Spawn Egg: the maximum number of Chickens has been reached",
            "Can't use Spawn Egg: the maximum number of Squid has been reached",
            "Can't use Spawn Egg: the maximum number of Mooshrooms has been reached",
            "Can't use Spawn Egg: the maximum number of Wolves has been reached",
            "Can't use Spawn Egg: the maximum number of animals has been reached",
            "Can't use Spawn Egg: the maximum number of enemies has been reached",
            "Can't use Spawn Egg: the maximum number of Villagers has been reached",
            "The maximum number of Paintings / Item Frames has been reached",
            "You can't spawn enemies in Peaceful mode",
            "This animal can't enter Love Mode: the maximum number of bred animals has been reached",
            "This animal can't enter Love Mode: the maximum number of bred Pigs, Sheep and Cows has been reached",
            "This animal can't enter Love Mode: the maximum number of bred Chickens has been reached",
            "This animal can't enter Love Mode: the maximum number of bred Mooshrooms has been reached",
            "This animal can't enter Love Mode: the maximum number of bred Wolves has been reached",
            "Can't shear this Mooshroom: the maximum number of Pigs, Sheep and Cows has been reached",
            "The maximum number of Boats has been reached",
            "The maximum number of Bats has been reached",
            "Teleported %1 to %2",
            "Teleported to %2",
            "%1 teleported to you",
    };

    // eDisconnectReason (Windows build, no PS3/Orbis/Xbox One only entries)
    private static final String[] DISCONNECT = {
            "Disconnected",
            "Quitting",
            "Connection closed",
            "Took too long to log in",
            "Illegal stance",
            "Illegal position",
            "Moved too quickly",
            "Flying is not enabled on this server",
            "Kicked from the game",
            "Timed out",
            "Overflow",
            "End of stream",
            "The server is full",
            "Outdated server",
            "Outdated client (check netcode-version in the plugin config)",
            "Unexpected packet",
            "Connection creation failed",
            "The host has no multiplayer privileges",
            "No multiplayer privileges",
            "User generated content is disabled",
            "User generated content is disabled",
            "Content restricted",
            "Content restricted",
            "The host has user generated content disabled",
            "No friends in the game",
            "You are banned from this server",
            "You are not friends with the host",
            "NAT type mismatch",
    };

    private LceText() {
    }

    /**
     * @return the chat line, or null to drop it
     */
    public static String chat(final int type, final List<String> strings, final List<Integer> ints) {
        if (type == 0) {
            return strings.isEmpty() ? null : String.join(" ", strings);
        }
        if (type < 0 || type >= CHAT.length) {
            return strings.isEmpty() ? null : String.join(" ", strings);
        }
        final String template = CHAT[type];
        if (template == null) {
            return null;
        }
        final String player = strings.size() > 0 ? strings.get(0) : "";
        String source = strings.size() > 1 ? strings.get(1) : "";
        if (source.isEmpty()) {
            source = "a mob";
        }
        final String item = strings.size() > 2 ? strings.get(2) : "";
        return template.replace("%1", player).replace("%2", source).replace("%3", item);
    }

    public static String disconnect(final int reason) {
        if (reason >= 0 && reason < DISCONNECT.length) {
            return DISCONNECT[reason];
        }
        return "Disconnected (reason " + reason + ")";
    }

}
