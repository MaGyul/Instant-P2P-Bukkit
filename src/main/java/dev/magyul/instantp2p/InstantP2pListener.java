package dev.magyul.instantp2p;

import dev.magyul.instantp2p.network.P2PNet;
import dev.magyul.instantp2p.webrtc.ExpelManager;
import dev.magyul.instantp2p.webrtc.Roles;
import dev.magyul.instantp2p.webrtc.WebRtcBridge;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import static dev.magyul.instantp2p.InstantP2pBukkit.TUNNEL_REGISTRY;

public class InstantP2pListener implements Listener {

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        Roles.refreshBlocking(700L, () -> Bukkit.getScheduler().runTask(InstantP2pBukkit.INSTANCE,
                () -> P2PNet.broadcastRoomState(Bukkit.getServer())));
        if (ExpelManager.isExpelled(event.getUniqueId())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.translatable("instant-p2p.msg.still_expelled"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        TUNNEL_REGISTRY.bySpoofed(p.getAddress()).ifPresent(t -> {
            TUNNEL_REGISTRY.bindPlayer(t, p.getUniqueId());

            Component joinMessage = event.joinMessage();
            if (joinMessage == null) {
                joinMessage = Component.translatable("multiplayer.player.joined", p.name());
            }

            Boolean relay = t.usesRelay();
            String key = Boolean.TRUE.equals(relay)
                    ? "instant-p2p.msg.join_suffix_relay"
                    : "instant-p2p.msg.join_suffix_direct";
            event.joinMessage(joinMessage.appendSpace().append(Component.translatable(key)));
        });
        P2PNet.broadcastRoomState(p.getServer());
        InstantP2pBukkit.INSTANCE.onlinePlayers.add(p.getUniqueId());
        WebRtcBridge.updatePublicRoomPlayerCount(Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        TUNNEL_REGISTRY.unbindPlayer(p.getUniqueId());
        P2PNet.broadcastRoomState(p.getServer());
        ExpelManager.onDisconnect(p.getServer(), p);
        InstantP2pBukkit.INSTANCE.onlinePlayers.remove(p.getUniqueId());
        Bukkit.getScheduler().runTask(InstantP2pBukkit.INSTANCE, () ->
            WebRtcBridge.updatePublicRoomPlayerCount(Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers()));
    }
}
