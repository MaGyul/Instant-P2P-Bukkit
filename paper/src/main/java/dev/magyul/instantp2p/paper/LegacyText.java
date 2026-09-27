package dev.magyul.instantp2p.paper;

import dev.magyul.instantp2p.common.i18n.I18n;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Spigot: Adventure가 없어 문자열 API만 쓴다. 모드 클라이언트도 서버 쪽 fallback(ko.json)으로 보인다.
 * 여기서 쓰는 문자열 메서드는 Paper에서 deprecated지만 Spigot에서는 이것뿐이다.
 */
@SuppressWarnings("deprecation")
final class LegacyText implements ServerText {

    @Override
    public void kick(Player player, String key, Object... args) {
        player.kickPlayer(I18n.format(key, args));
    }

    @Override
    public void send(Player player, String key, Object... args) {
        player.sendMessage(I18n.format(key, args));
    }

    @Override
    public void disallow(AsyncPlayerPreLoginEvent event, AsyncPlayerPreLoginEvent.Result result, String key, Object... args) {
        event.disallow(result, I18n.format(key, args));
    }

    @Override
    public void appendJoinSuffix(PlayerJoinEvent event, String key) {
        String joinMessage = event.getJoinMessage();
        if (joinMessage == null) {
            joinMessage = "§e" + event.getPlayer().getName() + " joined the game";
        }
        event.setJoinMessage(joinMessage + " " + I18n.format(key));
    }
}
