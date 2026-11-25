package io.github.t9homas.lceviaproxy.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * Translates Java 1.6.4 client packets (as produced by ViaLegacy) to LCE packets.
 */
public final class JavaToLce {

    private final ByteBufAllocator alloc;
    private final LceToJava ids;

    public JavaToLce(final ByteBufAllocator alloc, final LceToJava ids) {
        this.alloc = alloc;
        this.ids = ids;
    }

    private ByteBuf pkt(final int id) {
        final ByteBuf b = this.alloc.buffer();
        b.writeByte(id);
        return b;
    }

    public ByteBuf chat(final String message) {
        final ByteBuf b = pkt(0x03);
        b.writeShort(0);        // e_ChatCustom
        b.writeShort(1 << 4);   // 1 string, 0 ints
        Types.writeString(b, message);
        return b;
    }

    /**
     * @param id  1.6.4 packet id (already read)
     * @param in  rest of the packet
     * @return the LCE packet, or null to drop it
     */
    public ByteBuf translate(final int id, final ByteBuf in) {
        switch (id) {
            case 0x00 -> {
                final ByteBuf b = pkt(0x00);
                b.writeInt(in.readInt());
                return b;
            }
            case 0x03 -> {
                return chat(Types.readString(in));
            }
            case 0x07 -> { // use entity -> interact
                final ByteBuf b = pkt(0x07);
                b.writeInt(this.ids.serverEid(in.readInt()));
                b.writeInt(this.ids.serverEid(in.readInt()));
                b.writeByte(in.readByte());
                return b;
            }
            case 0x12 -> { // animation
                final ByteBuf b = pkt(0x12);
                b.writeInt(this.ids.serverEid(in.readInt()));
                b.writeByte(in.readByte());
                return b;
            }
            case 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x10, 0x1B, 0x65, 0x6A, 0x6C, 0xCA -> {
                // same layout
                final ByteBuf b = pkt(id);
                b.writeBytes(in);
                return b;
            }
            case 0x0F -> { // place block
                final ByteBuf b = pkt(0x0F);
                b.writeInt(in.readInt());
                b.writeByte(in.readByte());
                b.writeInt(in.readInt());
                b.writeByte(in.readByte());
                Types.itemJavaToLce(in, b);
                b.writeByte(in.readByte());
                b.writeByte(in.readByte());
                b.writeByte(in.readByte());
                return b;
            }
            case 0x13 -> { // entity action
                final ByteBuf b = pkt(0x13);
                b.writeInt(this.ids.serverEid(in.readInt()));
                int action = in.readByte();
                if (action == 6) action = 8;      // horse jump -> RIDING_JUMP
                else if (action == 7) action = 9; // open horse inventory -> OPEN_INVENTORY
                b.writeByte(action);
                b.writeInt(in.readInt());
                return b;
            }
            case 0x66 -> { // window click
                final ByteBuf b = pkt(0x66);
                b.writeByte(in.readByte());
                b.writeShort(in.readShort());
                b.writeByte(in.readByte());
                b.writeShort(in.readShort());
                b.writeByte(in.readByte());
                Types.itemJavaToLce(in, b);
                return b;
            }
            case 0x6B -> { // creative slot
                final ByteBuf b = pkt(0x6B);
                b.writeShort(in.readShort());
                Types.itemJavaToLce(in, b);
                return b;
            }
            case 0x82 -> { // sign (+ verified / censored flags)
                final ByteBuf b = pkt(0x82);
                b.writeInt(in.readInt());
                b.writeShort(in.readShort());
                b.writeInt(in.readInt());
                b.writeBoolean(false);
                b.writeBoolean(false);
                for (int i = 0; i < 4; i++) Types.writeString(b, Types.readString(in));
                return b;
            }
            case 0xCD -> { // client status: 0 = initial spawn (LCE doesn't need it), 1 = respawn
                final int status = in.readByte();
                if (status == 1) {
                    final ByteBuf b = pkt(0xCD);
                    b.writeByte(1); // PERFORM_RESPAWN
                    return b;
                }
                return null;
            }
            case 0xFA -> { // plugin message
                final String channel = Types.readString(in);
                final int len = in.readShort();
                if (channel.startsWith("MC|") && !channel.equals("MC|Brand") && !channel.equals("MC|PingHost")) {
                    final ByteBuf b = pkt(0xFA);
                    Types.writeString(b, channel);
                    b.writeShort(len);
                    if (len > 0) b.writeBytes(in, len);
                    return b;
                }
                return null;
            }
            case 0xFF -> {
                final ByteBuf b = pkt(0xFF);
                b.writeInt(1); // eDisconnect_Quitting
                return b;
            }
            default -> {
                // tab complete (0xCB), client settings (0xCC), ... have no LCE equivalent
                return null;
            }
        }
    }

}
