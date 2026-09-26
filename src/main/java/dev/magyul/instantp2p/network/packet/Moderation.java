package dev.magyul.instantp2p.network.packet;

import dev.magyul.instantp2p.network.PacketByteBuf;

import java.util.UUID;

public record Moderation(int action, UUID target) {
    public static String ID = "instant-p2p:moderation";

    public Moderation(PacketByteBuf buf) {
        this(buf.readVarInt(), buf.readUuid());
    }

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(this.action);
        buf.writeUuid(this.target);
    }
}
