package dev.magyul.instantp2p.paper;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 번역 키 기반 메시지 출력. Paper는 Adventure(모드 클라이언트는 자기 언어), Spigot은 Adventure가 없어
 * 레거시 문자열(서버 쪽 fallback)로 보낸다. Adventure를 참조하는 코드는 {@link AdventureText}에만 둔다 —
 * Spigot에서 그 클래스가 로드되지 않게.
 */
interface ServerText {

    void kick(Player player, String key, Object... args);

    /** 플레이어·콘솔 공용 */
    void send(CommandSender sender, String key, Object... args);

    void disallow(AsyncPlayerPreLoginEvent event, AsyncPlayerPreLoginEvent.Result result, String key, Object... args);

    /** 입장 메시지 뒤에 번역 키 문구를 한 칸 띄워 붙인다. */
    void appendJoinSuffix(PlayerJoinEvent event, String key);

    static ServerText detect() {
        try {
            Class<?> component = Class.forName("net.kyori.adventure.text.Component");
            Player.class.getMethod("kick", component);
            return new AdventureText();
        } catch (ReflectiveOperationException | LinkageError e) {
            return new LegacyText();
        }
    }
}
