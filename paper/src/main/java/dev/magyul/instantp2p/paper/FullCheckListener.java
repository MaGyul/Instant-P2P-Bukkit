package dev.magyul.instantp2p.paper;

import dev.magyul.instantp2p.common.core.P2PCore;
import io.papermc.paper.event.player.PlayerServerFullCheckEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.UUID;

/**
 * 정원 초과 입장 — 터널로 들어온 개발자·서포터는 서버가 가득 차도 받는다({@link P2PCore#canBypassPlayerLimit}).
 * <p>
 * Paper는 {@code PlayerServerFullCheckEvent}, Spigot은 {@code PlayerLoginEvent}(KICK_FULL)를 쓴다. Paper에서
 * {@code PlayerLoginEvent}를 들으면 플레이어가 일찍 만들어지고 경고가 나므로 둘을 나눈다. 리스너 클래스를 로드할 때
 * 메서드 인자 타입까지 풀리므로 Paper 이벤트를 참조하는 클래스는 Paper에서만 등록한다.
 */
final class FullCheckListener {

    private FullCheckListener() {}

    static void register(Plugin plugin, P2PCore core) {
        PluginManager pm = plugin.getServer().getPluginManager();
        if (hasClass("io.papermc.paper.event.player.PlayerServerFullCheckEvent")) {
            pm.registerEvents(new Paper(core), plugin);
        } else {
            pm.registerEvents(new Spigot(core), plugin);
        }
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name, false, FullCheckListener.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static boolean bypass(P2PCore core, UUID id, String name) {
        if (!core.canBypassPlayerLimit(id)) return false;
        PaperEntry.LOGGER.info("[host] 정원 초과 입장 허용: {} (개발자·서포터)", name);
        return true;
    }

    static final class Paper implements Listener {
        private final P2PCore core;

        Paper(P2PCore core) {
            this.core = core;
        }

        @EventHandler
        public void onFullCheck(PlayerServerFullCheckEvent event) {
            UUID id = event.getPlayerProfile().getId();
            if (!event.isAllowed() && id != null && bypass(core, id, event.getPlayerProfile().getName())) {
                event.allow(true);
            }
        }
    }

    @SuppressWarnings("deprecation")
    static final class Spigot implements Listener {
        private final P2PCore core;

        Spigot(P2PCore core) {
            this.core = core;
        }

        @EventHandler
        public void onLogin(PlayerLoginEvent event) {
            if (event.getResult() == PlayerLoginEvent.Result.KICK_FULL
                    && bypass(core, event.getPlayer().getUniqueId(), event.getPlayer().getName())) {
                event.allow();
            }
        }
    }
}
