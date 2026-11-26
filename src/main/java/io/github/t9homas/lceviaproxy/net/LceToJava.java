package io.github.t9homas.lceviaproxy.net;

import io.github.t9homas.lceviaproxy.data.LceSounds;
import io.github.t9homas.lceviaproxy.data.LceText;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Translates one LCE server packet into zero or more Java 1.6.4 server packets.
 * <p>
 * All reads of a packet happen before any state is changed, so a packet that is not complete yet
 * (IndexOutOfBoundsException) can be retried later without side effects.
 */
public final class LceToJava {

    private static final String[] ATTRIBUTES = {
            "generic.maxHealth", "generic.followRange", "generic.knockbackResistance", "generic.movementSpeed",
            "generic.attackDamage", "horse.jumpStrength", "zombie.spawnReinforcements"
    };

    private static final String[] CONTAINER_TITLES = {
            "container.chest", "container.crafting", "container.furnace", "container.dispenser", "container.enchant",
            "container.brewing", "entity.Villager.name", "container.beacon", "container.repair", "container.hopper",
            "container.dropper", "", "container.crafting", "container.chest", "container.chestDouble", "container.enderchest",
            "container.minecart", "container.hopper"
    };

    private final ByteBufAllocator alloc;
    private final boolean debug;
    private final Map<Integer, byte[]> rotations = new HashMap<>();
    /** Server entity ids that were spawned and not removed (to repair 11 bit ids). */
    private final java.util.Set<Integer> spawned = new java.util.HashSet<>();
    private final java.util.Set<Integer> bats = new java.util.HashSet<>();
    /** Locator bar for 1.21.6+ clients, null when off. */
    Locator locator;
    /** Skins of LCE players (see LceSkins), null when off. */
    PlayerSkins skins;
    /** Join / leave messages, null when off. */
    Presence presence;
    /** Id of the open crafting table window, -1 if none. */
    int craftingWindow = -1;
    /** World border and the sea outside the world, null when off. */
    WorldEdge edge;
    /** Our user name (for our own player list entry). */
    String selfName;
    /** Run before / after the translated packets went to ViaLegacy. */
    private final java.util.ArrayDeque<Runnable> before = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Runnable> after = new java.util.ArrayDeque<>();

    private static final int BAT = 65;
    /** "<name>" in chat, where the server may color the name. */
    private static final java.util.regex.Pattern CHAT_NAME = java.util.regex.Pattern.compile("<([^<>]{1,96})>");

    /** Entity id the Java client has for itself (from Login, never changes). */
    int playerEntityId = -1;
    /** Entity id the LCE server currently uses for us (changes on respawn). */
    int serverEntityId = -1;
    int dimension = 0;

    private static final int ALIAS = 0x40000000;

    /**
     * LCE gives the player a new entity id on respawn, Java can't change its own id. Map server ids to client ids.
     */
    int eid(final int serverId) {
        if (serverId == this.serverEntityId) return this.playerEntityId;
        if (serverId == this.playerEntityId) return serverId | ALIAS; // another entity got our old id
        return serverId;
    }

    /**
     * Client id -> server id.
     */
    int serverEid(final int clientId) {
        if (clientId == this.playerEntityId) return this.serverEntityId;
        if ((clientId & ALIAS) != 0) return clientId & ~ALIAS;
        return clientId;
    }

    public LceToJava(final ByteBufAllocator alloc, final boolean debug) {
        this.alloc = alloc;
        this.debug = debug;
    }

    private static int levelIdxToDimension(final int idx) {
        return idx == 1 ? -1 : (idx == 2 ? 1 : 0);
    }

    private boolean sameLevel(final int levelIdx) {
        return levelIdxToDimension(levelIdx) == this.dimension;
    }

    private ByteBuf pkt(final int id) {
        final ByteBuf b = this.alloc.buffer();
        b.writeByte(id);
        return b;
    }

    /**
     * Pre-built Java Login (0x01) from the LCE Login response.
     */
    public ByteBuf javaLogin(final int entityId, final String levelType, final int gameMode, final int dimension, final int difficulty, final int maxPlayers) {
        this.playerEntityId = entityId;
        this.serverEntityId = entityId;
        this.dimension = dimension;
        final ByteBuf b = pkt(0x01);
        b.writeInt(entityId);
        Types.writeString(b, levelType.isEmpty() ? "default" : levelType);
        b.writeByte(gameMode);
        b.writeByte(dimension);
        b.writeByte(difficulty);
        b.writeByte(0);
        b.writeByte(Math.min(maxPlayers & 0xFF, 127));
        return b;
    }

    public static ByteBuf kick(final ByteBufAllocator alloc, final String reason) {
        final ByteBuf b = alloc.buffer();
        b.writeByte(0xFF);
        Types.writeString(b, reason);
        return b;
    }

    public ByteBuf chat(final String text) {
        // 1.6.4 chat is a JSON object; § formatting inside "text" is converted by ViaLegacy
        final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("text", text);
        final ByteBuf b = pkt(0x03);
        Types.writeString(b, o.toString());
        return b;
    }

    /**
     * @return false if the packet id is unknown (stream can't continue)
     */
    /** Frees the buffers kept between packets (the connection closed). */
    void release() {
        if (this.spawnPosition != null) {
            this.spawnPosition.release();
            this.spawnPosition = null;
        }
        if (this.dismountPosition != null) {
            this.dismountPosition.release();
            this.dismountPosition = null;
        }
    }

    public boolean translate(final int id, final ByteBuf in, final Consumer<ByteBuf> out) throws Exception {
        final List<ByteBuf> list = new ArrayList<>(2);
        try {
            if (!this.translate0(id, in, list)) {
                for (ByteBuf b : list) b.release();
                return false;
            }
        } catch (Exception | Error e) {
            for (ByteBuf b : list) b.release();
            this.before.clear();
            this.after.clear();
            throw e;
        }
        if (this.spawnPosition != null && System.currentTimeMillis() - this.spawnAt > SPAWN_CHECK_MS) checkSpawn(list, true);
        if (this.presence != null && this.playerEntityId != -1) this.presence.tick(line -> list.add(chat(line)));
        Runnable r;
        while ((r = this.before.poll()) != null) r.run();
        for (ByteBuf b : list) out.accept(b);
        while ((r = this.after.poll()) != null) r.run();
        return true;
    }

    private boolean translate0(final int id, final ByteBuf in, final List<ByteBuf> out) throws Exception {
        switch (id) {
            case 0x00 -> { // keep alive
                final ByteBuf b = pkt(0x00);
                b.writeInt(in.readInt());
                out.add(b);
            }
            case 0x01, 0x02 -> { // login / pre-login after the handshake: ignore
                if (id == 0x01) skipLogin(in);
                else skipPreLogin(in);
            }
            case 0x03 -> { // chat
                final int type = in.readShort();
                final int counts = in.readShort();
                final List<String> strings = new ArrayList<>();
                final List<Integer> ints = new ArrayList<>();
                for (int i = 0; i < ((counts >> 4) & 0xF); i++) strings.add(Types.readString(in));
                for (int i = 0; i < (counts & 0xF); i++) ints.add(in.readInt());
                final String text = LceText.chat(type, strings, ints);
                if (text != null && !text.isEmpty()) {
                    out.add(chat(text));
                    if (this.skins != null) {
                        final java.util.regex.Matcher m = CHAT_NAME.matcher(text);
                        while (m.find()) {
                            final String colored = m.group(1);
                            final String plain = colored.replaceAll("\u00A7.", "");
                            if (colored.indexOf('\u00A7') >= 0 && plain.matches("[A-Za-z0-9_ ]{1,16}")) {
                                this.after.add(() -> this.skins.coloredName(plain, colored));
                            }
                        }
                    }
                }
            }
            case 0x04 -> copy(in, out, id, "ll");
            case 0x05 -> { // equipment
                final ByteBuf b = pkt(0x05);
                b.writeInt(eid(in.readInt()));
                b.writeShort(in.readShort());
                Types.itemLceToJava(in, b);
                out.add(b);
            }
            case 0x06 -> copy(in, out, id, "iii");
            case 0x07 -> copy(in, out, id, "iib");
            case 0x08 -> { // health (+ LCE damage source byte)
                final ByteBuf b = pkt(0x08);
                b.writeFloat(in.readFloat());
                b.writeShort(in.readShort());
                b.writeFloat(in.readFloat());
                in.readByte();
                out.add(b);
            }
            case 0x09 -> { // respawn
                final int dim = in.readByte();
                final int gm = in.readByte();
                final int height = in.readShort();
                final String lt = Types.readString(in);
                in.readLong(); // seed
                final int diff = in.readByte();
                in.readBoolean(); // new sea level
                final int newEid = in.readUnsignedShort();
                in.readShort(); // xz size
                in.readUnsignedByte(); // hell scale
                this.dimension = dim;
                this.expectSpawn = true;
                this.loadedChunks.clear();
                if (this.edge != null) this.edge.respawn();
                this.rotations.clear();
                this.spawned.clear();
                this.bats.clear();
                this.vehiclePos.clear();
                this.vehicleTop.clear();
                this.riding = -1;
                if (this.dismountPosition != null) this.dismountPosition.release();
                this.dismountPosition = null;
                if (this.locator != null) this.locator.clear();
                if (this.skins != null) {
                    this.skins.clear();
                    if (this.selfName != null) this.skins.self(this.selfName);
                }
                if (newEid > 0) {
                    if (this.debug && newEid != this.serverEntityId) {
                        debugLog("Respawn: server entity id " + this.serverEntityId + " -> " + newEid + " (client keeps " + this.playerEntityId + ")");
                    }
                    this.serverEntityId = newEid & 0xFFFF;
                }
                final ByteBuf b = pkt(0x09);
                b.writeInt(dim);
                b.writeByte(diff);
                b.writeByte(gm);
                b.writeShort(height <= 0 ? 256 : height);
                Types.writeString(b, lt.isEmpty() ? "default" : lt);
                out.add(b);
            }
            case 0x0A -> copy(in, out, id, "b");
            case 0x0B -> copy(in, out, id, "ddddb");
            case 0x0C -> copy(in, out, id, "ffb");
            case 0x0D -> {
                final List<ByteBuf> one = new ArrayList<>(1);
                copy(in, one, id, "ddddffb");
                final ByteBuf b = one.get(0);
                if (this.expectSpawn) {
                    // the spawn position: comes before the chunks around it, the client may sink in a bit
                    this.expectSpawn = false;
                    if (this.spawnPosition != null) this.spawnPosition.release();
                    this.spawnPosition = b.copy();
                    this.spawnAt = System.currentTimeMillis();
                    this.clientMoved = false;
                }
                if (this.riding >= 0) {
                    // getting out: the server puts us next to the vehicle (findStandUpPosition) before it tells us we
                    // stopped riding, while the client still sits in the vehicle; repeat it after the dismount
                    if (this.dismountPosition != null) this.dismountPosition.release();
                    this.dismountPosition = b.copy();
                }
                out.add(b);
            }
            case 0x10 -> copy(in, out, id, "s");
            case 0x11 -> copy(in, out, id, "ebibi");
            case 0x12 -> copy(in, out, id, "eb");
            case 0x14 -> { // named entity spawn
                final int eid = in.readInt();
                final String name = Types.readString(in);
                final int x = in.readInt(), y = in.readInt(), z = in.readInt();
                final byte yaw = in.readByte(), pitch = in.readByte(), head = in.readByte();
                final short item = in.readShort();
                in.skipBytes(8 + 8); // xuids
                final int playerIndex = in.readUnsignedByte();
                final int skinId = in.readInt();
                in.skipBytes(4 + 4); // cape, privileges
                final ByteBuf meta = this.alloc.buffer();
                try {
                    Types.metadataLceToJava(in, meta);
                } catch (Exception | Error e) {
                    meta.release();
                    throw e;
                }
                if (eid == this.serverEntityId) {
                    meta.release();
                    break; // the Java client never gets a spawn packet for itself
                }
                this.rotations.put(eid, new byte[]{yaw, pitch});
                this.spawned.add(eid);
                final String javaName = name.length() > 16 ? name.substring(0, 16) : name;
                if (this.skins != null) {
                    io.github.t9homas.lceviaproxy.skin.LceSkins.register(javaName, skinId, playerIndex);
                    this.before.add(() -> this.skins.beforeSpawn(eid, javaName));
                    this.after.add(() -> this.skins.afterSpawn(eid, javaName));
                }
                if (this.locator != null) this.locator.add(eid, javaName, x, y, z); // after register: same UUID as the list entry
                if (this.presence != null) this.presence.spawned(eid, javaName);
                final ByteBuf b = pkt(0x14);
                b.writeInt(eid(eid));
                Types.writeString(b, javaName);
                b.writeInt(x);
                b.writeInt(y);
                b.writeInt(z);
                b.writeByte(yaw);
                b.writeByte(pitch);
                b.writeShort(item);
                b.writeBytes(meta);
                meta.release();
                out.add(b);
                final ByteBuf h = pkt(0x23);
                h.writeInt(eid(eid));
                h.writeByte(head);
                out.add(h);
            }
            case 0x16 -> copy(in, out, id, "ee");
            case 0x17 -> { // object / vehicle spawn
                final int eid = in.readShort() & 0xFFFF;
                final byte type = in.readByte();
                final int x = in.readInt(), y = in.readInt(), z = in.readInt();
                final byte yaw = in.readByte(), pitch = in.readByte();
                final int data = in.readInt();
                short vx = 0, vy = 0, vz = 0;
                if (data > -1) {
                    vx = in.readShort();
                    vy = in.readShort();
                    vz = in.readShort();
                }
                this.rotations.put(eid, new byte[]{yaw, pitch});
                this.spawned.add(eid);
                trackVehicle(eid, type == 10 ? 0.35 : type == 1 ? 0.3 : -1, x, y, z); // minecart / boat
                final ByteBuf b = pkt(0x17);
                b.writeInt(eid(eid));
                b.writeByte(type);
                b.writeInt(x);
                b.writeInt(y);
                b.writeInt(z);
                b.writeByte(pitch);
                b.writeByte(yaw);
                // arrows, fishing hooks and fireballs carry their owner's entity id
                final boolean owner = type == 60 || type == 90 || type == 63 || type == 64 || type == 66;
                b.writeInt(owner && data > 0 ? eid(data) : data);
                if (data > 0) {
                    b.writeShort(vx);
                    b.writeShort(vy);
                    b.writeShort(vz);
                }
                out.add(b);
            }
            case 0x18 -> { // mob spawn
                final int eid = in.readShort() & 0xFFFF;
                final int type = in.readUnsignedByte();
                final int x = in.readInt(), y = in.readInt(), z = in.readInt();
                final byte yaw = in.readByte(), pitch = in.readByte(), head = in.readByte();
                final short vx = in.readShort(), vy = in.readShort(), vz = in.readShort();
                final ByteBuf meta = this.alloc.buffer();
                try {
                    Types.metadataLceToJava(in, meta, type == BAT);
                } catch (Exception | Error e) {
                    meta.release();
                    throw e;
                }
                this.rotations.put(eid, new byte[]{yaw, pitch});
                this.spawned.add(eid);
                if (type == BAT) this.bats.add(eid);
                else this.bats.remove(eid);
                trackVehicle(eid, type == 90 ? 0.9 : type == 100 ? 1.6 : -1, x, y, z); // pig / horse
                final ByteBuf b = pkt(0x18);
                b.writeInt(eid(eid));
                b.writeByte(type);
                b.writeInt(x);
                b.writeInt(y);
                b.writeInt(z);
                b.writeByte(yaw);
                b.writeByte(pitch);
                b.writeByte(head);
                b.writeShort(vx);
                b.writeShort(vy);
                b.writeShort(vz);
                b.writeBytes(meta);
                meta.release();
                out.add(b);
            }
            case 0x19 -> { // spawn (painting / xp orb / lightning): remember the entity
                final int spawnedId = in.getInt(in.readerIndex());
                copy(in, out, id, "eSiiii");
                this.spawned.add(spawnedId);
            }
            case 0x1A -> { // spawn (painting / xp orb / lightning): remember the entity
                final int spawnedId = in.getInt(in.readerIndex());
                copy(in, out, id, "eiiis");
                this.spawned.add(spawnedId);
            }
            case 0x1B -> copy(in, out, id, "ffbb");
            case 0x1C -> { // velocity
                final int idAndFlag = in.readShort() & 0xFFFF;
                int eid = smallId(idAndFlag);
                final int vx, vy, vz;
                if ((idAndFlag & 0x800) != 0 && isBrokenVelocity(idAndFlag, in)) {
                    // LCE server bug: an entity id >= 2048 sets the "small values" flag by itself, the values are shorts.
                    // (LCE clients crash on this packet.)
                    eid = idAndFlag;
                    vx = in.readShort();
                    vy = in.readShort();
                    vz = in.readShort();
                } else if ((idAndFlag & 0x800) != 0) {
                    vx = in.readByte() * 16;
                    vy = in.readByte() * 16;
                    vz = in.readByte() * 16;
                } else {
                    vx = in.readShort();
                    vy = in.readShort();
                    vz = in.readShort();
                }
                final ByteBuf b = pkt(0x1C);
                b.writeInt(eid(eid));
                b.writeShort(vx);
                b.writeShort(vy);
                b.writeShort(vz);
                out.add(b);
            }
            case 0x1D -> { // destroy
                final int count = in.readByte();
                final int[] ids = new int[Math.max(count, 0)];
                for (int i = 0; i < ids.length; i++) ids[i] = in.readInt();
                final ByteBuf b = pkt(0x1D);
                b.writeByte(ids.length);
                for (int e : ids) {
                    b.writeInt(eid(e));
                    this.rotations.remove(e);
                    this.spawned.remove(e);
                    if (this.locator != null) this.locator.remove(e);
                    if (this.skins != null) this.skins.remove(e);
                    this.bats.remove(e);
                    trackVehicle(e, -1, 0, 0, 0);
                }
                out.add(b);
            }
            case 0x1E -> entityOnly(in.readShort() & 0xFFFF, out);
            case 0x1F -> relMove(in.readShort() & 0xFFFF, in.readByte(), in.readByte(), in.readByte(), out);
            case 0x20 -> {
                final int eid = in.readShort() & 0xFFFF;
                final byte dy = in.readByte(), dp = in.readByte();
                look(eid, dy, dp, out);
            }
            case 0x21 -> {
                final int eid = in.readShort() & 0xFFFF;
                final byte dx = in.readByte(), dyy = in.readByte(), dz = in.readByte(), dy = in.readByte(), dp = in.readByte();
                lookMove(eid, dx, dyy, dz, dy, dp, out);
            }
            case 0x22 -> { // teleport
                final int eid = in.readShort() & 0xFFFF;
                final int x = in.readInt(), y = in.readInt(), z = in.readInt();
                final byte yaw = in.readByte(), pitch = in.readByte();
                this.rotations.put(eid, new byte[]{yaw, pitch});
                this.spawned.add(eid);
                if (this.locator != null) this.locator.teleport(eid, x, y, z);
                moveVehicle(eid, x, y, z, true);
                final ByteBuf b = pkt(0x22);
                b.writeInt(eid(eid));
                b.writeInt(x);
                b.writeInt(y);
                b.writeInt(z);
                b.writeByte(yaw);
                b.writeByte(pitch);
                out.add(b);
            }
            case 0x23 -> copy(in, out, id, "eb");
            case 0x26 -> copy(in, out, id, "eb");
            case 0x27 -> { // attach entity (riding / leash)
                final int rider = in.readInt(), vehicle = in.readInt();
                final byte type = in.readByte();
                final ByteBuf b = pkt(0x27);
                b.writeInt(eid(rider));
                b.writeInt(vehicle < 0 ? vehicle : eid(vehicle));
                b.writeByte(type);
                out.add(b);
                if (rider == this.serverEntityId && type == 0) {
                    if (vehicle >= 0) {
                        this.riding = vehicle;
                        if (this.dismountPosition != null) this.dismountPosition.release();
                        this.dismountPosition = null;
                    } else {
                        dismount(out);
                    }
                }
            }
            case 0x28 -> { // metadata
                final ByteBuf b = pkt(0x28);
                try {
                    final int eid = in.readInt();
                    b.writeInt(eid(eid));
                    Types.metadataLceToJava(in, b, this.bats.contains(eid));
                } catch (Exception | Error e) {
                    b.release();
                    throw e;
                }
                out.add(b);
            }
            case 0x29 -> copy(in, out, id, "ebbs");
            case 0x2A -> copy(in, out, id, "eb");
            case 0x2B -> copy(in, out, id, "fss");
            case 0x2C -> { // attributes: LCE uses ids instead of names and modifier ids instead of UUIDs
                final int eid = in.readInt();
                final int count = in.readInt();
                final ByteBuf body = this.alloc.buffer();
                int written = 0;
                try {
                    for (int i = 0; i < count; i++) {
                        final int attr = in.readShort();
                        final double base = in.readDouble();
                        final int mods = in.readShort();
                        final ByteBuf modBuf = this.alloc.buffer();
                        try {
                            for (int j = 0; j < mods; j++) {
                                final int modId = in.readInt();
                                final double amount = in.readDouble();
                                final byte op = in.readByte();
                                final UUID uuid = UUID.nameUUIDFromBytes(("LCEModifier" + modId).getBytes());
                                modBuf.writeLong(uuid.getMostSignificantBits());
                                modBuf.writeLong(uuid.getLeastSignificantBits());
                                modBuf.writeDouble(amount);
                                modBuf.writeByte(op);
                            }
                            if (attr >= 0 && attr < ATTRIBUTES.length) {
                                Types.writeString(body, ATTRIBUTES[attr]);
                                body.writeDouble(base);
                                body.writeShort(mods);
                                body.writeBytes(modBuf);
                                written++;
                            }
                        } finally {
                            modBuf.release(); // also when the packet is not complete yet
                        }
                    }
                } catch (Exception | Error e) {
                    body.release();
                    throw e;
                }
                if (written > 0) {
                    final ByteBuf b = pkt(0x2C);
                    b.writeInt(eid(eid));
                    b.writeInt(written);
                    b.writeBytes(body);
                    out.add(b);
                }
                body.release();
            }
            case 0x32 -> { // chunk visibility
                final int cx = in.readInt(), cz = in.readInt();
                final boolean visible = in.readUnsignedByte() != 0;
                if (!visible && (this.edge == null || !this.edge.drawsSea(this.dimension, cx, cz))) {
                    out.add(ChunkConverter.unloadChunk(this.alloc, cx, cz));
                    this.loadedChunks.remove(chunkKey(cx, cz));
                }
            }
            case 0x33 -> blockRegion(in, out);
            case 0x34 -> { // chunk tiles update
                final int xc = in.readShort();
                final int zc = in.readShort();
                final int countAndFlags = in.readUnsignedByte();
                final boolean dataAllZero = (countAndFlags & 0x80) != 0;
                final int levelIdx = (countAndFlags >> 5) & 3;
                final int count = countAndFlags & 0x1F;
                final int[] records = new int[count];
                int block = -1;
                for (int i = 0; i < count; i++) {
                    final int xzAndFlag = in.readShort() & 0xFFFF;
                    final int y = in.readUnsignedByte();
                    if ((xzAndFlag & 0x80) != 0) block = in.readUnsignedByte();
                    final int data = dataAllZero ? 0 : in.readUnsignedByte();
                    final int x = (xzAndFlag >> 12) & 15, z = (xzAndFlag >> 8) & 15;
                    records[i] = (x << 28) | (z << 24) | (y << 16) | ((block & 0xFFF) << 4) | (data & 0xF);
                }
                if (count > 0 && sameLevel(levelIdx)) {
                    final ByteBuf b = pkt(0x34);
                    b.writeInt(xc);
                    b.writeInt(zc);
                    b.writeShort(count);
                    b.writeInt(count * 4);
                    for (int r : records) b.writeInt(r);
                    out.add(b);
                }
            }
            case 0x35 -> { // block change
                final int x = in.readInt();
                final int y = in.readUnsignedByte();
                final int z = in.readInt();
                final int block = in.readShort() & 0xFFFF;
                final int dataLevel = in.readUnsignedByte();
                if (sameLevel((dataLevel >> 4) & 0xF)) {
                    final ByteBuf b = pkt(0x35);
                    b.writeInt(x);
                    b.writeByte(y);
                    b.writeInt(z);
                    b.writeShort(block);
                    b.writeByte(dataLevel & 0xF);
                    out.add(b);
                }
            }
            case 0x36 -> copy(in, out, id, "isibbs");
            case 0x37 -> copy(in, out, id, "eiiib");
            case 0x3C -> { // explosion (+ LCE knockback only flag)
                final boolean knockbackOnly = in.readBoolean();
                double x = 0, y = -1000, z = 0;
                float r = 0;
                byte[] records = new byte[0];
                if (!knockbackOnly) {
                    x = in.readDouble();
                    y = in.readDouble();
                    z = in.readDouble();
                    r = in.readFloat();
                    final int count = in.readInt();
                    records = new byte[count * 3];
                    in.readBytes(records);
                }
                final float kx = in.readFloat(), ky = in.readFloat(), kz = in.readFloat();
                final ByteBuf b = pkt(0x3C);
                b.writeDouble(x);
                b.writeDouble(y);
                b.writeDouble(z);
                b.writeFloat(r);
                b.writeInt(records.length / 3);
                b.writeBytes(records);
                b.writeFloat(kx);
                b.writeFloat(ky);
                b.writeFloat(kz);
                out.add(b);
            }
            case 0x3D -> copy(in, out, id, "iibiib");
            case 0x3E -> { // sound: LCE sends an id and a float pitch
                final int sound = in.readInt();
                final int x = in.readInt(), y = in.readInt(), z = in.readInt();
                final float volume = in.readFloat();
                final float pitch = in.readFloat();
                final String name = LceSounds.name(sound);
                if (name != null) {
                    final ByteBuf b = pkt(0x3E);
                    Types.writeString(b, name);
                    b.writeInt(x);
                    b.writeInt(y);
                    b.writeInt(z);
                    b.writeFloat(volume);
                    b.writeByte(Math.max(0, Math.min(255, (int) (pitch * 63.0F))));
                    out.add(b);
                } else if (this.debug) {
                    debugLog("Unknown sound id " + sound);
                }
            }
            case 0x3F -> copy(in, out, id, "Sfffffffi");
            case 0x46 -> copy(in, out, id, "bb");
            case 0x47 -> { // spawn (painting / xp orb / lightning): remember the entity
                final int spawnedId = in.getInt(in.readerIndex());
                copy(in, out, id, "ebiii");
                this.spawned.add(spawnedId);
            }
            case 0x64 -> { // open window
                final int windowId = in.readUnsignedByte();
                final int type = in.readUnsignedByte();
                final int size = in.readUnsignedByte();
                final boolean customName = in.readBoolean();
                int entityId = 0;
                if (type == 11) entityId = in.readInt();
                final String title = customName ? Types.readString(in) : null;
                int javaType = type;
                if (type == 12) javaType = 1;
                else if (type >= 13 && type <= 16) javaType = 0;
                else if (type == 17) javaType = 9;
                this.craftingWindow = javaType == 1 ? windowId : (this.craftingWindow == windowId ? -1 : this.craftingWindow);
                final ByteBuf b = pkt(0x64);
                b.writeByte(windowId);
                b.writeByte(javaType);
                Types.writeString(b, title != null ? title : (type < CONTAINER_TITLES.length ? CONTAINER_TITLES[type] : ""));
                b.writeByte(size);
                b.writeBoolean(title != null);
                if (javaType == 11) b.writeInt(eid(entityId));
                out.add(b);
            }
            case 0x65 -> {
                if (in.getUnsignedByte(in.readerIndex()) == this.craftingWindow) this.craftingWindow = -1;
                copy(in, out, id, "b");
            }
            case 0x67 -> {
                final ByteBuf b = pkt(0x67);
                try {
                    b.writeByte(in.readByte());
                    b.writeShort(in.readShort());
                    Types.itemLceToJava(in, b);
                } catch (Exception | Error e) {
                    b.release();
                    throw e;
                }
                out.add(b);
            }
            case 0x68 -> {
                final ByteBuf b = pkt(0x68);
                try {
                    b.writeByte(in.readByte());
                    final int count = in.readShort();
                    b.writeShort(count);
                    for (int i = 0; i < count; i++) Types.itemLceToJava(in, b);
                } catch (Exception | Error e) {
                    b.release();
                    throw e;
                }
                out.add(b);
            }
            case 0x69 -> copy(in, out, id, "bss");
            case 0x6A -> copy(in, out, id, "bsb");
            case 0x6B -> {
                final ByteBuf b = pkt(0x6B);
                try {
                    b.writeShort(in.readShort());
                    Types.itemLceToJava(in, b);
                } catch (Exception | Error e) {
                    b.release();
                    throw e;
                }
                out.add(b);
            }
            case 0x6C -> copy(in, out, id, "bb");
            case 0x82 -> { // sign (+ LCE verified / censored flags)
                final int x = in.readInt();
                final short y = in.readShort();
                final int z = in.readInt();
                in.readBoolean();
                in.readBoolean();
                final String[] lines = new String[4];
                for (int i = 0; i < 4; i++) lines[i] = Types.readString(in);
                final ByteBuf b = pkt(0x82);
                b.writeInt(x);
                b.writeShort(y);
                b.writeInt(z);
                for (String l : lines) Types.writeString(b, l.length() > 15 ? l.substring(0, 15) : l);
                out.add(b);
            }
            case 0x83 -> { // map data
                final short type = in.readShort(), itemId = in.readShort();
                final int len = in.readUnsignedShort();
                final byte[] data = new byte[len];
                in.readBytes(data);
                final ByteBuf b = pkt(0x83);
                b.writeShort(type);
                b.writeShort(itemId);
                b.writeShort(len);
                b.writeBytes(data);
                out.add(b);
            }
            case 0x84 -> { // tile entity data
                final ByteBuf b = pkt(0x84);
                try {
                    b.writeInt(in.readInt());
                    b.writeShort(in.readShort());
                    b.writeInt(in.readInt());
                    b.writeByte(in.readByte());
                    Types.nbtLceToJava(in, b);
                } catch (Exception | Error e) {
                    b.release();
                    throw e;
                }
                out.add(b);
            }
            case 0x85 -> copy(in, out, id, "biii");
            case 0xC8 -> { // award stat (LCE: id + param blob) - not shown
                in.readInt();
                final int len = in.readInt();
                if (len > 0) in.skipBytes(len);
            }
            case 0xC9 -> { // LCE player info (small id / colour / privileges / entity): used for join / leave messages
                final int smallId = in.readUnsignedByte();
                in.skipBytes(2 + 4);
                final int entity = in.readInt();
                if (this.presence != null && entity != this.serverEntityId) this.presence.info(smallId, entity);
            }
            case 0xCA -> copy(in, out, id, "bff");
            case 0xCE -> copy(in, out, id, "SSb");
            case 0xCF -> {
                final String owner = Types.readString(in);
                final byte method = in.readByte();
                String objective = null;
                int score = 0;
                if (method != 1) {
                    objective = Types.readString(in);
                    score = in.readInt();
                }
                final ByteBuf b = pkt(0xCF);
                Types.writeString(b, owner);
                b.writeByte(method);
                if (objective != null) {
                    Types.writeString(b, objective);
                    b.writeInt(score);
                }
                out.add(b);
            }
            case 0xD0 -> copy(in, out, id, "bS");
            case 0xD1 -> { // teams
                final String name = Types.readString(in);
                final byte method = in.readByte();
                final ByteBuf b = pkt(0xD1);
                try {
                    Types.writeString(b, name);
                    b.writeByte(method);
                    if (method == 0 || method == 2) {
                        Types.writeString(b, Types.readString(in));
                        Types.writeString(b, Types.readString(in));
                        Types.writeString(b, Types.readString(in));
                        b.writeByte(in.readByte());
                    }
                    if (method == 0 || method == 3 || method == 4) {
                        final int count = in.readShort();
                        b.writeShort(count);
                        for (int i = 0; i < count; i++) Types.writeString(b, Types.readString(in));
                    }
                } catch (Exception | Error e) {
                    b.release();
                    throw e;
                }
                out.add(b);
            }
            case 0xFA -> { // custom payload
                final String channel = Types.readString(in);
                final int len = in.readShort();
                final byte[] data = len > 0 && len < Short.MAX_VALUE ? new byte[len] : new byte[0];
                in.readBytes(data);
                if (!channel.startsWith("MC|Fork") && !channel.startsWith("MC|C")) {
                    final ByteBuf b = pkt(0xFA);
                    Types.writeString(b, channel);
                    b.writeShort(data.length);
                    b.writeBytes(data);
                    out.add(b);
                } else if (this.debug) {
                    debugLog("Dropped server payload " + channel + " (" + data.length + " bytes)");
                }
            }
            case 0xFF -> { // disconnect: LCE sends a reason id
                out.add(kick(this.alloc, LceText.disconnect(in.readInt())));
            }

            // ---------------------------------------------------------- LCE only, dropped
            case 0x96 -> in.skipBytes(2 + 4);   // craft item
            case 0x97 -> in.skipBytes(4 + 4);   // trade item
            case 0x98 -> in.skipBytes(4);       // debug options
            case 0x99 -> in.skipBytes(1 + 4);   // server settings changed
            case 0x9A -> { // texture
                Types.skipJavaUtf(in);
                final int n = in.readShort();
                if (n > 0) in.skipBytes(n);
            }
            case 0x9B -> in.skipBytes(16);      // chunk visibility area
            case 0x9C -> in.skipBytes(1);       // update progress
            case 0x9D -> { // texture change
                in.skipBytes(4 + 1);
                Types.skipJavaUtf(in);
            }
            case 0x9E -> { // game rule progress
                in.readInt();
                Types.readString(in);
                in.skipBytes(4 + 1 + 4);
                final int n = in.readInt();
                if (n > 0) in.skipBytes(n);
            }
            case 0x9F -> in.skipBytes(1);       // kick player (small id)
            case 0xA0 -> { // texture and geometry
                Types.skipJavaUtf(in);
                in.readInt();
                final int n = in.readShort();
                if (n > 0) in.skipBytes(n);
                in.readInt();
                final int boxes = in.readShort();
                if (boxes > 0) in.skipBytes(boxes * (2 + 8 * 4));
            }
            case 0xA1 -> { // texture and geometry change
                in.skipBytes(4 + 4);
                Types.skipJavaUtf(in);
            }

            // ---------------------------------------------------------- small entity moves
            case 0xA2 -> entityOnly(smallId(in.readShort()), out);
            case 0xA3 -> { // small pos
                final int idAndY = in.readShort();
                final int xz = in.readByte();
                relMove(smallId(idAndY), (byte) (xz >> 4), (byte) (idAndY >> 11), (byte) ((xz << 28) >> 28), out);
            }
            case 0xA4 -> { // small rot (yaw delta only)
                final int idAndRot = in.readShort();
                look(smallId(idAndRot), (byte) (idAndRot >> 11), (byte) 0, out);
            }
            case 0xA5 -> { // small pos + rot
                final int idAndRot = in.readShort();
                final int xyz = in.readShort();
                final byte dx = (byte) (xyz >> 11);
                final byte dy = (byte) ((xyz << 21) >> 26);
                final byte dz = (byte) ((xyz << 27) >> 27);
                lookMove(smallId(idAndRot), dx, dy, dz, (byte) (idAndRot >> 11), (byte) 0, out);
            }
            case 0xA6 -> in.skipBytes(1 + 4 + 4); // XZ packet
            case 0xA7 -> { // game command
                in.readInt();
                final int n = in.readShort();
                if (n > 0 && n < Short.MAX_VALUE) in.skipBytes(n);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Packet ids an LCE server sends (Packet.cpp, receiveOnClient). */
    private static final java.util.BitSet SERVER_PACKETS = new java.util.BitSet(256);

    static {
        for (int id : new int[]{0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 16, 17, 18, 20, 22, 23, 24, 25, 26, 28, 29, 30, 31, 32, 33, 34, 35,
                38, 39, 40, 41, 42, 43, 44, 50, 51, 52, 53, 54, 55, 60, 61, 62, 63, 70, 71, 100, 101, 103, 104, 105, 106, 107, 130, 131, 132,
                133, 153, 154, 155, 156, 157, 158, 160, 161, 162, 163, 164, 165, 166, 200, 201, 202, 206, 207, 208, 209, 250, 255}) {
            SERVER_PACKETS.set(id);
        }
    }

    /**
     * Small move / velocity packets carry only 11 bits of the entity id. Entities with ids >= 2048 arrive cut down:
     * if no entity has the short id but one with the same low 11 bits exists, it is that one.
     */
    /** An entity the client has: one the server spawned, or ourselves (our id changes and grows with each respawn). */
    /** Entities spawned in the packets a velocity check looks ahead at. */
    private final java.util.Set<Integer> chainKnown = new java.util.HashSet<>();

    private boolean known(final int id) {
        return id == this.serverEntityId || this.spawned.contains(id) || this.chainKnown.contains(id);
    }

    private int smallId(final int packed) {
        final int id = packed & 0x7FF;
        if (known(id)) return id;
        for (int high = 1; high < 32; high++) {
            final int full = id | (high << 11);
            if (known(full)) return full;
        }
        return id;
    }

    /**
     * A velocity packet with the 0x800 flag is either a small one (3 bytes follow) or one for an entity id >= 2048, where
     * the id overlaps the flag and 3 shorts follow. Decide by which entity exists, else by which reading lands on a
     * valid next packet.
     */
    private boolean isBrokenVelocity(final int idAndFlag, final ByteBuf in) {
        if (in.readableBytes() < 7) throw new IndexOutOfBoundsException("velocity: waiting for the next packet");
        final int r = in.readerIndex();
        final boolean smallFits = SERVER_PACKETS.get(in.getUnsignedByte(r + 3));
        final boolean shortsFit = SERVER_PACKETS.get(in.getUnsignedByte(r + 6));
        if (smallFits != shortsFit) return shortsFit;
        // the server only uses shorts when a value does not fit in a byte * 16 (SetEntityMotionPacket::_init)
        boolean needsShorts = false;
        for (int i = 0; i < 3; i++) {
            final int v = in.getShort(r + i * 2);
            if (v < -128 * 16 || v >= 128 * 16) needsShorts = true;
        }
        if (!needsShorts) return false;
        // both readings land on a valid packet id: does the next packet name an entity we know?
        // (the server sends metadata / moves / velocity for the same entities in a row)
        final int limit = r + 6 + 64; // both readings are scored over the same bytes
        // decide only with the whole window there (at the end of the received data the chains are too short)
        if (in.writerIndex() < limit) throw new IndexOutOfBoundsException("velocity: waiting for more packets");
        this.chainKnown.clear();
        final int small = chainScore(in, r + 3, limit, 0x1C, true), shorts = chainScore(in, r + 6, limit, 0x1C, true);
        this.chainKnown.clear();
        if (this.debug && shorts > small) {
            debugLog("velocity read as shorts 0x" + Integer.toHexString(idAndFlag) + ": bytes score " + small + ", shorts score " + shorts + " "
                    + io.netty.buffer.ByteBufUtil.hexDump(in, r, Math.min(in.readableBytes(), 24)));
        }
        // a tie: the small form is by far the most common
        return shorts > small;
    }

    /**
     * How believable the packets starting at {@code at} are: follows the packets of a known size up to {@code limit} (moves, head
     * rotations, ...) and adds up {@link #nextPacketScore}; an invalid packet id on the way costs 3.
     */
    private int chainScore(final ByteBuf in, int at, final int limit, int previous, boolean first) {
        int total = 0;
        while (at < limit && at < in.writerIndex()) {
            final int id = in.getUnsignedByte(at);
            if (!SERVER_PACKETS.get(id)) return total - 3;
            if (id == 0x00 && previous == 0x00) total -= 2; // misread zeros look like keep alives (one a second)
            previous = id;
            if (first) {
                total += nextPacketScore(in, at); // may wait for more data
                first = false;
            } else {
                try {
                    total += nextPacketScore(in, at);
                } catch (IndexOutOfBoundsException end) {
                    return total;
                }
            }
            if (id == 0x1C && at + 3 <= in.writerIndex() && (in.getUnsignedShort(at + 1) & 0x800) != 0) {
                // another ambiguous velocity: follow both readings
                return total + Math.max(chainScore(in, at + 6, limit, id, false), chainScore(in, at + 9, limit, id, false));
            }
            int len = fixedLength(id);
            if (id == 0x1C) len = 9; // velocity without the flag
            if (len == 0) len = variableLength(in, at, id);
            if (len <= 0) return total;
            if (id == 0x17 || id == 0x18) this.chainKnown.add(in.getUnsignedShort(at + 1)); // spawned on the way
            at += len;
        }
        return total;
    }

    /** Length of some variable size packets (with the id byte), -1 if unknown / not all there / not plausible. */
    private static int variableLength(final ByteBuf in, final int at, final int id) {
        try {
            int p = at + 1;
            switch (id) {
                case 0x28 -> { // metadata
                    p += 4;
                    p = skipMetadata(in, p);
                }
                case 0x05 -> { // equipment
                    p += 4 + 2;
                    p = skipItem(in, p);
                }
                case 0x2C -> { // attributes
                    p += 4;
                    final int count = in.getInt(p);
                    if (count < 0 || count > 32) return -1;
                    p += 4;
                    for (int i = 0; i < count; i++) {
                        p += 2 + 8;
                        final int mods = in.getShort(p);
                        if (mods < 0 || mods > 64) return -1;
                        p += 2 + mods * (4 + 8 + 1);
                    }
                }
                case 0x17 -> { // object spawn
                    p += 2 + 1 + 12 + 2;
                    final int data = in.getInt(p);
                    p += 4 + (data > -1 ? 6 : 0);
                }
                case 0x18 -> { // mob spawn
                    p += 2 + 1 + 12 + 3 + 6;
                    p = skipMetadata(in, p);
                }
                case 0x1D -> { // destroy
                    final int count = in.getByte(p);
                    if (count < 0 || count > 64) return -1;
                    p += 1 + count * 4;
                }
                case 0x33 -> { // chunk
                    if (in.getUnsignedByte(p) > 3) return -1;
                    p += 1 + 4 + 2 + 4 + 3;
                    final int size = in.getInt(p) & 0x3FFFFFFF;
                    if (size > 4 * 1024 * 1024) return -1;
                    p += 4 + size;
                }
                default -> {
                    return -1;
                }
            }
            return p <= in.writerIndex() ? p - at : -1;
        } catch (IndexOutOfBoundsException e) {
            return -1;
        }
    }

    private static int skipMetadata(final ByteBuf in, int p) {
        for (int n = 0; n < 32; n++) {
            final int header = in.getUnsignedByte(p++);
            if (header == 0x7F) return p;
            switch (header >> 5) {
                case 0 -> p += 1;
                case 1 -> p += 2;
                case 2, 3 -> p += 4;
                case 4 -> {
                    final int len = in.getShort(p);
                    if (len < 0 || len > 1024) throw new IndexOutOfBoundsException();
                    p += 2 + len * 2;
                }
                case 5 -> p = skipItem(in, p);
                case 6 -> p += 12;
                default -> throw new IndexOutOfBoundsException();
            }
        }
        throw new IndexOutOfBoundsException(); // no end marker: not metadata
    }

    private static int skipItem(final ByteBuf in, int p) {
        final int item = in.getShort(p);
        p += 2;
        if (item < 0) return p;
        p += 1 + 2;
        final int nbt = in.getShort(p);
        p += 2;
        if (nbt > 0) p += nbt;
        return p;
    }

    /** Length of packets with a fixed size (with the id byte), 0 for others. */
    private static int fixedLength(final int id) {
        return switch (id) {
            case 0x00 -> 5;          // keep alive
            case 0x10 -> 3;          // held item
            case 0x04 -> 17;         // time
            case 0x06 -> 13;         // spawn position
            case 0x08 -> 12;         // health
            case 0x16 -> 9;          // collect item
            case 0x29 -> 9;          // mob effect
            case 0x35 -> 13;         // block change
            case 0x3D -> 19;         // level event
            case 0x3E -> 25;         // sound
            case 0x46 -> 3;          // game event
            case 0x47 -> 18;         // lightning
            case 0x12, 0x23, 0x26, 0x2A -> 6; // int entity id + byte
            case 0x1E, 0xA2, 0xA4 -> 3;
            case 0xA3 -> 4;
            case 0xA5, 0x20 -> 5;
            case 0x1F -> 6;
            case 0x21 -> 8;
            case 0x22 -> 17;
            case 0x27 -> 10;
            case 0xC9 -> 12;         // player info
            default -> 0;
        };
    }

    /**
     * How believable a packet starting at {@code at} is: +2 if it is for an entity that exists, -1 if it names an unknown
     * entity, 0 if it has no entity id (or not enough bytes are there yet).
     */
    private int nextPacketScore(final ByteBuf in, final int at) {
        final int id = in.getUnsignedByte(at);
        final int end = in.writerIndex();
        switch (id) {
            case 0x05 -> { // equipment: entity, slot 0..4, item id (or -1)
                if (at + 9 > end) throw new IndexOutOfBoundsException("velocity: waiting for the next packet");
                final int slot = in.getShort(at + 5), item = in.getShort(at + 7);
                return known(in.getInt(at + 1)) && slot >= 0 && slot <= 4 && item >= -1 && item < 4096 ? 2 : -3;
            }
            case 0x12, 0x16, 0x23, 0x26, 0x28, 0x29, 0x2A, 0x2C -> { // int entity id
                if (at + 5 > end) throw new IndexOutOfBoundsException("velocity: waiting for the next packet");
                final int e = in.getInt(at + 1);
                return known(e) ? 2 : -2;
            }
            case 0x1E, 0x1F, 0x20, 0x21, 0x22 -> { // short entity id
                if (at + 3 > end) throw new IndexOutOfBoundsException("velocity: waiting for the next packet");
                final int e = in.getUnsignedShort(at + 1);
                return known(e) ? 2 : -2;
            }
            case 0x33 -> { // chunk: flags, int x, short y, int z, 3 sizes, int length, then zlib data
                if (at + 20 > end) throw new IndexOutOfBoundsException("velocity: waiting for the next packet");
                if (in.getUnsignedByte(at + 1) > 3) return -2;
                final int len = in.getInt(at + 15) & 0x3FFFFFFF;
                if (len == 0) return 1;
                return in.getUnsignedByte(at + 19) == 0x78 ? 2 : -2;
            }
            case 0x27 -> { // attach: rider and vehicle (-1 = none) must exist, type 0 / 1
                if (at + 10 > end) throw new IndexOutOfBoundsException("velocity: waiting for the next packet");
                final int rider = in.getInt(at + 1), vehicle = in.getInt(at + 5), type = in.getUnsignedByte(at + 9);
                return known(rider) && (vehicle == -1 || known(vehicle)) && type <= 1 ? 2 : -2;
            }
            case 0x04 -> { // time: world age and day time, both small positive numbers
                if (at + 17 > end) throw new IndexOutOfBoundsException("velocity: waiting for the next packet");
                final long age = in.getLong(at + 1), time = in.getLong(at + 9);
                return age >= 0 && age < (1L << 40) && Math.abs(time) < (1L << 40) ? 1 : -2;
            }
            case 0x18 -> { // mob spawn: a real mob type
                if (at + 4 > end) throw new IndexOutOfBoundsException("velocity: waiting for the next packet");
                final int type = in.getUnsignedByte(at + 3);
                return type >= 48 && type <= 120 ? 1 : -2;
            }
            case 0xFF -> { // a disconnect in the middle of the stream is unlikely
                return -1;
            }
            case 0x1C, 0xA2, 0xA3, 0xA4, 0xA5 -> { // 11 bit entity id
                if (at + 3 > end) throw new IndexOutOfBoundsException("velocity: waiting for the next packet");
                final int e = in.getUnsignedShort(at + 1) & 0x7FF;
                return known(smallId(e)) ? 1 : -1;
            }
            default -> {
                return 0;
            }
        }
    }

    // ------------------------------------------------------------------ entity movement (LCE rotations are relative)

    private void entityOnly(final int eid, final List<ByteBuf> out) {
        final ByteBuf b = pkt(0x1E);
        b.writeInt(eid(eid));
        out.add(b);
    }

    private void relMove(final int eid, final byte dx, final byte dy, final byte dz, final List<ByteBuf> out) {
        if (this.locator != null) this.locator.move(eid, dx, dy, dz);
        moveVehicle(eid, dx, dy, dz, false);
        final ByteBuf b = pkt(0x1F);
        b.writeInt(eid(eid));
        b.writeByte(dx);
        b.writeByte(dy);
        b.writeByte(dz);
        out.add(b);
    }

    private byte[] rotate(final int eid, final byte dYaw, final byte dPitch) {
        final byte[] r = this.rotations.computeIfAbsent(eid, k -> new byte[2]);
        r[0] = (byte) (r[0] + dYaw);
        r[1] = (byte) (r[1] + dPitch);
        return r;
    }

    private void look(final int eid, final byte dYaw, final byte dPitch, final List<ByteBuf> out) {
        final byte[] r = rotate(eid, dYaw, dPitch);
        final ByteBuf b = pkt(0x20);
        b.writeInt(eid(eid));
        b.writeByte(r[0]);
        b.writeByte(r[1]);
        out.add(b);
    }

    private void lookMove(final int eid, final byte dx, final byte dy, final byte dz, final byte dYaw, final byte dPitch, final List<ByteBuf> out) {
        if (this.locator != null) this.locator.move(eid, dx, dy, dz);
        moveVehicle(eid, dx, dy, dz, false);
        final byte[] r = rotate(eid, dYaw, dPitch);
        final ByteBuf b = pkt(0x21);
        b.writeInt(eid(eid));
        b.writeByte(dx);
        b.writeByte(dy);
        b.writeByte(dz);
        b.writeByte(r[0]);
        b.writeByte(r[1]);
        out.add(b);
    }

    // ------------------------------------------------------------------ vehicles

    /** Vehicle entity id -> {x, y, z} in 1/32 blocks. */
    private final Map<Integer, int[]> vehiclePos = new HashMap<>();
    /** Vehicle entity id -> top of the vehicle above its position, in blocks. */
    private final Map<Integer, Double> vehicleTop = new HashMap<>();
    private int riding = -1;
    /** The last position the server sent while we were riding (where it puts us when we get out). */
    private ByteBuf dismountPosition;
    /** The Java client's last rotation (from its move packets). */
    float clientYaw, clientPitch;

    private void trackVehicle(final int eid, final double top, final int x, final int y, final int z) {
        if (top < 0) {
            this.vehicleTop.remove(eid);
            this.vehiclePos.remove(eid);
            return;
        }
        this.vehicleTop.put(eid, top);
        this.vehiclePos.put(eid, new int[]{x, y, z});
    }

    private void moveVehicle(final int eid, final int dx, final int dy, final int dz, final boolean absolute) {
        final int[] p = this.vehiclePos.get(eid);
        if (p == null) return;
        if (absolute) {
            p[0] = dx;
            p[1] = dy;
            p[2] = dz;
        } else {
            p[0] += dx;
            p[1] += dy;
            p[2] += dz;
        }
    }

    /**
     * Getting out of a vehicle: the LCE server does not send a new position (the LCE client puts itself on top of the
     * vehicle, Entity::ride). Java clients wait for the server, so they stayed inside the vehicle. Do what the LCE client does.
     */
    private void dismount(final List<ByteBuf> out) {
        if (this.dismountPosition != null) {
            // the exact spot the server teleported us to (the client must confirm it exactly, see PlayerConnection::handleMovePlayer)
            out.add(this.dismountPosition);
            this.dismountPosition = null;
            this.riding = -1;
            if (this.debug) debugLog("Dismounted: placed where the server put us");
            return;
        }
        final int[] p = this.vehiclePos.get(this.riding);
        final Double top = this.vehicleTop.get(this.riding);
        this.riding = -1;
        if (p == null || top == null) return;
        final double feet = p[1] / 32.0 + top;
        final ByteBuf b = pkt(0x0D);
        b.writeDouble(p[0] / 32.0);
        b.writeDouble(feet + 1.62);
        b.writeDouble(feet);
        b.writeDouble(p[2] / 32.0);
        b.writeFloat(this.clientYaw);
        b.writeFloat(this.clientPitch);
        b.writeBoolean(false);
        out.add(b);
        if (this.debug) debugLog("Dismounted: placed on top of the vehicle at y " + feet);
    }

    // ------------------------------------------------------------------ chunks

    /**
     * LCE sends the spawn position about 2 seconds before the chunks around it. The client gets it at once (holding it
     * left newer clients at 0,0) and starts falling meanwhile: on a slab it sank into the slab. Once the chunks around
     * the spawn are there (or after a few seconds), a client that sank below the spawn without walking away gets the
     * spawn position again.
     */
    private static final long SPAWN_CHECK_MS = 5000;
    private final java.util.Set<Long> loadedChunks = new java.util.HashSet<>();
    private boolean expectSpawn = true;
    private ByteBuf spawnPosition;
    private long spawnAt;
    /** The client's last position (from its move packets). */
    double clientX, clientY, clientZ;
    boolean clientMoved;

    private static long chunkKey(final int cx, final int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private void checkSpawn(final List<ByteBuf> out, final boolean timeout) {
        final ByteBuf spawn = this.spawnPosition;
        final double x = spawn.getDouble(1), feet = spawn.getDouble(1 + 16), z = spawn.getDouble(1 + 24);
        if (!timeout) {
            if (System.currentTimeMillis() - this.spawnAt < 1000) return; // give the client time to fall first
            final int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!this.loadedChunks.contains(chunkKey(cx + dx, cz + dz))
                            && (this.edge == null || !this.edge.drawsSea(this.dimension, cx + dx, cz + dz))) return; // not yet
                }
            }
        }
        this.spawnPosition = null;
        final double horizontal = (this.clientX - x) * (this.clientX - x) + (this.clientZ - z) * (this.clientZ - z);
        if (this.clientMoved && this.clientY < feet - 0.05 && horizontal < 0.25) {
            spawn.setFloat(1 + 32, this.clientYaw);   // keep where the player looks
            spawn.setFloat(1 + 36, this.clientPitch);
            out.add(spawn);
            if (this.debug) debugLog("Spawn: client sank to y " + this.clientY + ", sent the spawn position (y " + feet + ") again");
        } else {
            spawn.release();
        }
    }

    private void blockRegion(final ByteBuf in, final List<ByteBuf> out) throws Exception {
        final int flags = in.readUnsignedByte();
        final int x = in.readInt();
        final int y = in.readShort();
        final int z = in.readInt();
        final int xs = in.readUnsignedByte() + 1;
        int ys = in.readUnsignedByte() + 1;
        final int zs = in.readUnsignedByte() + 1;
        final boolean full = (flags & 0x01) != 0;
        if ((flags & 0x02) != 0) ys = 0;
        int size = in.readInt();
        final int levelIdx = (size >> 30) & 3;
        size &= 0x3FFFFFFF;
        if (size > 16 * 1024 * 1024) throw new IllegalStateException("chunk data too big (" + size + " bytes), stream out of sync");
        if (in.readableBytes() < size) throw new IndexOutOfBoundsException("chunk not complete yet");
        final byte[] compressed = new byte[size];
        in.readBytes(compressed);

        if (!sameLevel(levelIdx)) {
            return;
        }
        if (this.edge != null && this.edge.drawsSea(this.dimension, x >> 4, z >> 4)) {
            return; // outside the world: the plugin shows the sea there
        }
        // all bytes are read: from here an IndexOutOfBounds is broken data, not a packet that is not complete yet
        try {
            convertRegion(x, y, z, xs, ys, zs, full, size, compressed, out);
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalStateException("broken chunk data: " + e, e);
        }
    }

    private void convertRegion(final int x, final int y, final int z, final int xs, final int ys, final int zs, final boolean full,
                               final int size, final byte[] compressed, final List<ByteBuf> out) throws Exception {
        final byte[] data = size == 0 ? new byte[0] : ChunkConverter.decompress(compressed);
        if (full) {
            out.add(ChunkConverter.fullChunk(this.alloc, x >> 4, z >> 4, ys, data, this.dimension == 0));
            this.loadedChunks.add(chunkKey(x >> 4, z >> 4));
            if (this.spawnPosition != null) checkSpawn(out, false);
        } else {
            ChunkConverter.partialRegion(this.alloc, x, y, z, xs, ys, zs, data, out::add);
        }
    }

    // ------------------------------------------------------------------ passthrough

    /**
     * Copies a packet whose layout is the same in LCE and 1.6.4.
     * b=byte s=short i=int l=long f=float d=double S=string
     */
    private void copy(final ByteBuf in, final List<ByteBuf> out, final int id, final String spec) {
        final ByteBuf b = pkt(id);
        try {
            for (int i = 0; i < spec.length(); i++) {
                switch (spec.charAt(i)) {
                    case 'b' -> b.writeByte(in.readByte());
                    case 's' -> b.writeShort(in.readShort());
                    case 'i', 'f' -> b.writeInt(in.readInt());
                    case 'e' -> b.writeInt(eid(in.readInt()));
                    case 'l', 'd' -> b.writeLong(in.readLong());
                    case 'S' -> Types.writeString(b, Types.readString(in));
                    default -> throw new IllegalStateException("bad spec " + spec);
                }
            }
        } catch (Exception | Error e) {
            b.release();
            throw e;
        }
        out.add(b);
    }

    static void skipLogin(final ByteBuf in) {
        in.readInt();
        Types.readString(in);
        Types.readString(in);
        in.skipBytes(8 + 4 + 1 + 1 + 1 + 8 + 8 + 1 + 4 + 1 + 4 + 1 + 4 + 4 + 1 + 1 + 4 + 2 + 1);
    }

    static void skipPreLogin(final ByteBuf in) {
        in.readShort();
        Types.readString(in);
        in.skipBytes(1 + 4);
        final int players = in.readUnsignedByte();
        in.skipBytes(players * 8 + 14 + 4 + 1 + 4);
    }

    private static void debugLog(final String s) {
        net.raphimc.viaproxy.util.logging.Logger.LOGGER.info("[LCE] " + s);
    }

}
