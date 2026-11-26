package io.github.t9homas.lceviaproxy.net;

import io.github.t9homas.lceviaproxy.LceMessages;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Join / leave messages made by the plugin. LCE servers can turn the join / leave chat off, but they send every player's
 * PlayerInfo (small id + entity id, no name) once every 10 seconds (PlayerList::tick): a new small id is a join, one
 * that stops coming is a leave. Names come from player spawns.
 */
final class Presence {

    private static final long FIRST_CYCLE_MS = 12_000; // small ids in the first cycle are players who were already there
    private static final long LEAVE_MS = 15_000;       // not seen for this long: left
    private static final long NAME_WAIT_MS = 10_000;   // wait this long for the name of a joining player

    private static final class Player {
        int entity;
        long lastSeen;
        String name;
        boolean joinPending;
        long joinAt;
    }

    private final LceMessages messages;
    private final long start = System.currentTimeMillis();
    private final Map<Integer, String> names = new HashMap<>(); // entity id -> name (also after despawn)
    private final Map<Integer, Player> players = new HashMap<>(); // small id -> player
    int selfSmallId = -1;

    Presence(final LceMessages messages) {
        this.messages = messages;
    }

    void spawned(final int entity, final String name) {
        this.names.put(entity, name);
        for (Player p : this.players.values()) {
            if (p.entity == entity && p.name == null) p.name = name;
        }
    }

    /** PlayerInfo (0xC9). */
    void info(final int smallId, final int entity) {
        if (smallId == this.selfSmallId) return;
        final long now = System.currentTimeMillis();
        Player p = this.players.get(smallId);
        if (p == null) {
            p = new Player();
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
            if (name != null) p.name = name;
        }
    }

    /** Called with every server packet: the messages to show now. */
    void tick(final List<String> out) {
        if (this.players.isEmpty()) return;
        final long now = System.currentTimeMillis();
        for (Iterator<Player> it = this.players.values().iterator(); it.hasNext(); ) {
            final Player p = it.next();
            if (p.joinPending && (p.name != null || now - p.joinAt > NAME_WAIT_MS)) {
                p.joinPending = false;
                add(out, p.name == null ? this.messages.get("join-unknown", null) : this.messages.get("join", p.name));
            }
            if (now - p.lastSeen > LEAVE_MS) {
                it.remove();
                if (!p.joinPending) {
                    add(out, p.name == null ? this.messages.get("leave-unknown", null) : this.messages.get("leave", p.name));
                }
            }
        }
    }

    private static void add(final List<String> out, final String message) {
        if (!message.isEmpty()) out.add(message);
    }

}
