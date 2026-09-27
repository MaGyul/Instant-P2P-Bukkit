package dev.magyul.instantp2p.paper;

import dev.magyul.instantp2p.common.core.P2PCore;
import dev.magyul.instantp2p.common.tunnel.TunnelRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class InstantP2pListener implements Listener {

    private final P2PCore core;
    private final ServerText text;

    InstantP2pListener(P2PCore core, ServerText text) {
        this.core = core;
        this.text = text;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!core.onPreLogin(event.getUniqueId())) {
            text.disallow(event, AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "instant-p2p.msg.still_expelled");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        TunnelRegistry tunnels = core.tunnels();
        tunnels.bySpoofed(p.getAddress()).ifPresent(t -> {
            tunnels.bindPlayer(t, p.getUniqueId());
            String key = Boolean.TRUE.equals(t.usesRelay())
                    ? "instant-p2p.msg.join_suffix_relay"
                    : "instant-p2p.msg.join_suffix_direct";
            text.appendJoinSuffix(event, key);
        });
        core.onJoin(p.getUniqueId());
        if (core.ipRestoreUnavailable() && p.hasPermission("instantp2p.notify.host")) {
            text.send(p, P2PCore.IP_RESTORE_UNAVAILABLE);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        core.onQuit(event.getPlayer().getUniqueId());
    }
}
