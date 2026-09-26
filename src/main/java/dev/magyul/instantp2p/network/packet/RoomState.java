package dev.magyul.instantp2p.network.packet;

import dev.magyul.instantp2p.network.PacketByteBuf;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record RoomState(int maxPlayers, UUID hostUuid, boolean allowBroadcast,
                       Map<UUID, Integer> ranks) {

    public static final String ID = "instant-p2p:room_state";

    public RoomState(PacketByteBuf buf) {
        this(buf.readVarInt(), buf.readUuid(), buf.readBoolean(), readRanks(buf));
    }

    public void write(PacketByteBuf buf) {
        buf.writeVarInt(this.maxPlayers);
        buf.writeUuid(this.hostUuid);
        buf.writeBoolean(this.allowBroadcast);
        buf.writeVarInt(this.ranks.size());
        for (Map.Entry<UUID, Integer> e : this.ranks.entrySet()) {
            buf.writeUuid(e.getKey());
            buf.writeVarInt(e.getValue());
        }
    }

    private static Map<UUID, Integer> readRanks(PacketByteBuf buf) {
        int n = buf.readVarInt();
        Map<UUID, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            UUID id = buf.readUuid();
            out.put(id, buf.readVarInt());
        }
        return Map.copyOf(out);
    }
}
