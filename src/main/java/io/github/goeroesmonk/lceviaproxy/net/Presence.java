package io.github.goeroesmonk.lceviaproxy.net;

import io.github.goeroesmonk.lceviaproxy.LceMessages;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Join / leave messages made by the plugin. LCE servers can turn the join / leave chat off, but they send every player's
 * PlayerInfo (small id + entity id, no name) once every 10 seconds (PlayerList::tick): a new small id is a join, one
 * that stops coming is a leave. Names come from player spawns.
 * <p>
 * It also keeps the tab list: every player the server reports stays in it (named "Player N" until the player was near
 * once, like on LCE), not only the players near us.
 */
final class Presence {

    private static final long FIRST_CYCLE_MS = 12_000; // small ids in the first cycle are players who were already there
    private static final long LEAVE_MS = 15_000;       // not seen for this long: left
    private static final long NAME_WAIT_MS = 10_000;   // wait this long for the name of a joining player

    private static final class Player {
        int smallId;
        int entity = -1;
        long lastSeen;
        String name;
        boolean joinPending;
        long joinAt;
    }

    private final LceMessages messages; // null: no join / leave messages
    private final PlayerSkins tab;      // null: no tab list entries
    private final long start = System.currentTimeMillis();
    private final Map<Integer, String> names = new HashMap<>(); // entity id -> name (also after despawn)
    private final Map<Integer, Player> players = new HashMap<>(); // small id -> player
    int selfSmallId = -1;

    Presence(final LceMessages messages, final PlayerSkins tab) {
        this.messages = messages;
        this.tab = tab;
    }

    void spawned(final int entity, final String name) {
        this.names.put(entity, name);
        for (Player p : this.players.values()) {
            if (p.entity == entity && !name.equals(p.name)) {
                p.name = name;
                list(p);
            }
        }
    }

    /** PlayerInfo (0xC9). */
    void info(final int smallId, final int entity) {
        if (smallId == this.selfSmallId) return;
        final long now = System.currentTimeMillis();
        Player p = this.players.get(smallId);
        final boolean added = p == null;
        if (added) {
            p = new Player();
            p.smallId = smallId;
            this.players.put(smallId, p);
            if (now - this.start > FIRST_CYCLE_MS) {
                p.joinPending = true;
                p.joinAt = now;
            }
        }
        p.lastSeen = now;
        if (entity >= 0 && entity != p.entity) {
            p.entity = entity;
            final String name = this.names.get(entity);
            if (name != null && !name.equals(p.name)) {
                p.name = name;
                if (!added) list(p);
            }
        }
        if (added) list(p);
    }

    private void list(final Player p) {
        if (this.tab != null) this.tab.listed(p.smallId, p.name);
    }


    /** Called with every server packet: the messages to show now. */
    void tick(final java.util.function.Consumer<String> out) {
        if (this.players.isEmpty()) return;
        final long now = System.currentTimeMillis();
        for (Iterator<Player> it = this.players.values().iterator(); it.hasNext(); ) {
            final Player p = it.next();
            if (p.joinPending && (p.name != null || now - p.joinAt > NAME_WAIT_MS)) {
                p.joinPending = false;
                add(out, p.name == null ? msg("join-unknown", null) : msg("join", p.name));
            }
            if (now - p.lastSeen > LEAVE_MS) {
                it.remove();
                if (this.tab != null) this.tab.unlisted(p.smallId);
                if (!p.joinPending) {
                    add(out, p.name == null ? msg("leave-unknown", null) : msg("leave", p.name));
                }
            }
        }
    }

    private String msg(final String key, final String name) {
        return this.messages == null ? null : this.messages.get(key, name);
    }

    private void add(final java.util.function.Consumer<String> out, final String message) {
        if (this.messages != null && message != null && !message.isEmpty()) out.accept(message);
    }

}
