package dev.magyul.instantp2p.paper;

import dev.magyul.instantp2p.Utils;
import dev.magyul.instantp2p.core.P2PCore;
import dev.magyul.instantp2p.core.P2PSettings;
import dev.magyul.instantp2p.tunnel.TunnelInjector;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InstantP2pBukkit extends JavaPlugin {
    public static final Logger LOGGER = LoggerFactory.getLogger("Instant-P2P");

    private P2PSettings settings;
    private P2PCore core;

    @Override
    public void onLoad() {
        try {
            saveDefaultConfig();
            LOGGER.info("컨피그를 불러오는 중...");
            settings = PaperSettings.load(this);
            if (!settings.enabled()) {
                LOGGER.info("P2P 기능이 비활성화 되어있으므로 플러그인이 비활성화됩니다.");
                Bukkit.getPluginManager().disablePlugin(this);
            }
        } catch (Exception e) {
            LOGGER.error("컨피그를 불러오는데 실패 했습니다! 컨피그 파일이 존재 하는지, 파일에 문제가 없나요?", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onEnable() {
        if (settings == null || !settings.enabled()) return;

        PaperPlatform platform = new PaperPlatform(this, settings);
        core = new P2PCore(platform);

        try {
            TunnelInjector.inject(core.tunnels());
            P2PNet.register(this, core);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("네트워크 셋팅에 실패했습니다! 플러그인이 비활성화됩니다.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(new InstantP2pListener(core), this);

        // 버킷이 완전히 켜진 후 스캐줄이 돌아가므로 버킷이 켜지고 안정화가 시작될때 P2P 서비스를 시작한다.
        Bukkit.getScheduler().runTask(this, () -> {
            LOGGER.info("초대 코드 생성중...");
            String inviteCode = Utils.generateCode();

            try {
                core.bridge().startHost(inviteCode, "127.0.0.1:" + platform.listenPort());
            } catch (Exception e) {
                LOGGER.error("[instant-p2p] Failed to start host: {}", e.getMessage(), e);
            }

            LOGGER.info("초대 코드: {}", inviteCode);

            String title = settings.title();
            if (title.isEmpty()) {
                title = LegacyComponentSerializer.legacySection().serialize(Bukkit.getServer().motd());
            }

            if (settings.publicRoom()) {
                core.bridge().publishPublicRoom(inviteCode, title, settings.name(), settings.serverUuid().toString(),
                        getServer().getOnlinePlayers().size(), getServer().getMaxPlayers());
            }
        });
    }

    @Override
    public void onDisable() {
        if (core == null) return;

        TunnelInjector.uninject();
        core.tunnels().clear();
        core.bridge().stopHost();
    }
}
