package dev.magyul.instantp2p.paper;

import dev.magyul.instantp2p.core.P2PCore;
import dev.magyul.instantp2p.network.packet.Moderation;
import dev.magyul.instantp2p.network.packet.RoomState;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** 플러그인 채널 등록 — 송신은 {@link PaperPlatform#broadcastRoomState}. */
final class P2PNet {

    private P2PNet() {}

    static void register(JavaPlugin plugin, P2PCore core) {
        var msger = Bukkit.getMessenger();
        msger.registerOutgoingPluginChannel(plugin, RoomState.ID);
        msger.registerIncomingPluginChannel(plugin, Moderation.ID,
                (channel, player, message) -> core.onModeration(player.getUniqueId(), message));
    }
}
