package dev.magyul.instantp2p.paper;

import dev.magyul.instantp2p.common.core.P2PCore;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * [1.3.1] 신고 증거용 채팅 기록 — 방 안 채팅을 받는 쪽에 보이는 문장으로 메모리에 남긴다({@link P2PCore#recordChat}).
 * Paper는 {@code AsyncChatEvent}(렌더러로 최종 문장을 만든다), Spigot은 {@code AsyncPlayerChatEvent}. 취소된 채팅은 남기지 않는다.
 * 이벤트 클래스를 참조하는 리스너는 {@link FullCheckListener}처럼 해당 서버에서만 등록한다.
 */
final class ChatEvidence {

    private ChatEvidence() {}

    static void register(Plugin plugin, P2PCore core) {
        PluginManager pm = plugin.getServer().getPluginManager();
        if (hasClass("io.papermc.paper.event.player.AsyncChatEvent")) {
            pm.registerEvents(new Paper(core), plugin);
        } else {
            pm.registerEvents(new Spigot(core), plugin);
        }
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name, false, ChatEvidence.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static final class Paper implements Listener {
        private final P2PCore core;

        Paper(P2PCore core) {
            this.core = core;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) {
            try {
                var player = event.getPlayer();
                // 받는 쪽에 보이는 최종 문장(채팅 플러그인의 렌더러까지 반영) — 시청자는 비워서 렌더한다
                var rendered = event.renderer().render(player, player.displayName(), event.message(), net.kyori.adventure.audience.Audience.empty());
                String line = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(rendered);
                core.recordChat(player.getName(), line);
            } catch (Throwable t) {
                // 부가 기능 — 채팅 처리에는 영향을 주지 않는다
            }
        }
    }

    static final class Spigot implements Listener {
        private final P2PCore core;

        Spigot(P2PCore core) {
            this.core = core;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onChat(AsyncPlayerChatEvent event) {
            try {
                String line = WorldEvidence.plain(String.format(event.getFormat(), event.getPlayer().getDisplayName(), event.getMessage()));
                core.recordChat(event.getPlayer().getName(), line);
            } catch (Throwable t) {
                // 부가 기능
            }
        }
    }
}
