package dev.magyul.instantp2p.common.network.packet;

import dev.magyul.instantp2p.common.network.PacketByteBuf;

import java.util.UUID;

public record Moderation(int action, UUID target) {
    public static final String ID = "instant-p2p:moderation";

    public static final int ACTION_EXPEL = 0;
    public static final int ACTION_READMIT = 1;
    public static final int ACTION_KICK = 2;
    public static final int ACTION_REQUEST_STATE = 3;

    public Moderation(PacketByteBuf buf) {
        this(buf.readVarInt(), buf.readUuid());
    }

    public void write(PacketByteBuf buf) {
        buf.writeVarInt(this.action);
        buf.writeUuid(this.target);
    }
}
