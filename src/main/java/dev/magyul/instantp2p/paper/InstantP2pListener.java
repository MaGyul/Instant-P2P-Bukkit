package dev.magyul.instantp2p.paper;

import dev.magyul.instantp2p.core.P2PCore;
import dev.magyul.instantp2p.tunnel.TunnelRegistry;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class InstantP2pListener implements Listener {

    private final P2PCore core;

    InstantP2pListener(P2PCore core) {
        this.core = core;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!core.onPreLogin(event.getUniqueId())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    PaperText.translatable("instant-p2p.msg.still_expelled"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        TunnelRegistry tunnels = core.tunnels();
        tunnels.bySpoofed(p.getAddress()).ifPresent(t -> {
            tunnels.bindPlayer(t, p.getUniqueId());

            Component joinMessage = event.joinMessage();
            if (joinMessage == null) {
                joinMessage = Component.translatable("multiplayer.player.joined", p.name());
            }

            Boolean relay = t.usesRelay();
            String key = Boolean.TRUE.equals(relay)
                    ? "instant-p2p.msg.join_suffix_relay"
                    : "instant-p2p.msg.join_suffix_direct";
            event.joinMessage(joinMessage.appendSpace().append(PaperText.translatable(key)));
        });
        core.onJoin(p.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        core.onQuit(event.getPlayer().getUniqueId());
    }
}
