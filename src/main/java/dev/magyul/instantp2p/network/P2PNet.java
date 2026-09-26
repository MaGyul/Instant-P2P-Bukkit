package dev.magyul.instantp2p.network;

import dev.magyul.instantp2p.InstantP2pBukkit;
import dev.magyul.instantp2p.network.packet.Moderation;
import dev.magyul.instantp2p.network.packet.RoomState;
import dev.magyul.instantp2p.webrtc.ExpelManager;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.craftbukkit.util.UnsafeList;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class P2PNet {

    public static final int ACTION_EXPEL = 0;
    public static final int ACTION_READMIT = 1;
    public static final int ACTION_KICK = 2;
    public static final int ACTION_REQUEST_STATE = 3;

    public static void register() {
        var plugin = InstantP2pBukkit.INSTANCE;
        var msger = Bukkit.getMessenger();
        msger.registerOutgoingPluginChannel(plugin, RoomState.ID);
        msger.registerIncomingPluginChannel(plugin, Moderation.ID, (channel, player, message) -> {
            PacketByteBuf buf = PacketByteBuf.wrap(message);
            Moderation packet = new Moderation(buf);
            ExpelManager.handleRequest(player.getServer(), player, packet.action(), packet.target());
        });
    }

    private static Map<UUID, Integer> rankMap(java.util.List<UUID> online) {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        for (UUID id : online) {
            int rank = ExpelManager.priority(id); // 방장이므로 자기 Roles 사본으로 계산된다
            if (rank > 0) out.put(id, rank);
        }
        return out;
    }

    private static void sendRoomState(List<UUID> online) {
        RoomState packet = new RoomState(
                Bukkit.getMaxPlayers(),
                InstantP2pBukkit.INSTANCE.config.getServerUUID(),
                InstantP2pBukkit.INSTANCE.config.isAllowBroadcast(),
                rankMap(online)
        );

        PacketByteBuf buf = PacketByteBuf.allocate();
        packet.write(buf);

        Bukkit.getServer().sendPluginMessage(InstantP2pBukkit.INSTANCE, RoomState.ID, buf.toByteArray());
    }

    public static void broadcastRoomState(Server server) {
        List<UUID> online = new UnsafeList<>();
        for (Player player : server.getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }

        sendRoomState(online);
    }
}
