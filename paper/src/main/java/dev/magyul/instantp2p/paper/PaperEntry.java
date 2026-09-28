package dev.magyul.instantp2p.paper;

import dev.magyul.instantp2p.common.MinecraftVersions;
import dev.magyul.instantp2p.common.core.P2PCore;
import dev.magyul.instantp2p.common.core.P2PSettings;
import dev.magyul.instantp2p.common.tunnel.TunnelInjector;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bukkit/Spigot/Paper 진입점 (plugin.yml). Paper 전용 API는 {@link ServerText}로 감싸서 Spigot에서도 로드된다.
 */
public final class PaperEntry extends JavaPlugin {
    public static final Logger LOGGER = LoggerFactory.getLogger("Instant-P2P");

    /** 지원 하한 — 원본 모드의 최소 버전 */
    private static final int[] MIN_VERSION = {1, 21};

    private P2PSettings settings;
    private P2PCore core;
    private String minecraftVersion;

    @Override
    public void onLoad() {
        minecraftVersion = minecraftVersion();
        if (!MinecraftVersions.atLeast(minecraftVersion, MIN_VERSION)) {
            LOGGER.warn("MC {}는 지원하지 않습니다 (1.21 이상 필요). 플러그인이 비활성화됩니다.", minecraftVersion);
            return;
        }
        try {
            saveDefaultConfig();
            LOGGER.info("컨피그를 불러오는 중...");
            settings = PaperSettings.load(this);
        } catch (Exception e) {
            LOGGER.error("컨피그를 불러오는데 실패 했습니다! 컨피그 파일이 존재 하는지, 파일에 문제가 없나요?", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onEnable() {
        if (!MinecraftVersions.atLeast(minecraftVersion, MIN_VERSION)) {
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (settings == null) return;

        ServerText text = ServerText.detect();
        PaperPlatform platform = new PaperPlatform(this, settings, text, minecraftVersion);
        core = new P2PCore(platform);

        // IP 복원은 실패해도 접속은 되므로 플러그인을 끄지 않는다 (관리자에게만 알림)
        Object minecraftServer = minecraftServer();
        if (minecraftServer == null) {
            core.markIpRestoreUnavailable();
        } else {
            TunnelInjector.inject(minecraftServer, core.tunnels(), core::markIpRestoreUnavailable);
        }
        P2PNet.register(this, core);

        Bukkit.getPluginManager().registerEvents(new InstantP2pListener(core, text), this);
        FullCheckListener.register(this, core);

        var command = getCommand("p2p");
        if (command != null) {
            P2PCommandExecutor executor = new P2PCommandExecutor(core, text);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        // 스케줄러는 서버가 완전히 켜진 뒤에 돈다 — 그때 자동 열기(enabled)를 확인한다
        Bukkit.getScheduler().runTask(this, () -> core.host().autoStart());
    }

    /** room_update.version — 클라이언트가 문자열 비교한다. getBukkitVersion()은 "1.21.11-R0.1-SNAPSHOT" 형식(Paper/Spigot 공통). */
    private static String minecraftVersion() {
        String bukkit = Bukkit.getBukkitVersion();
        int dash = bukkit.indexOf('-');
        return dash > 0 ? bukkit.substring(0, dash) : bukkit;
    }

    /** CraftServer.getServer() → MinecraftServer(DedicatedServer). Paper/Spigot 공통, 이름은 Bukkit 구현 쪽이라 매핑과 무관. */
    private Object minecraftServer() {
        try {
            Object craftServer = Bukkit.getServer();
            return craftServer.getClass().getMethod("getServer").invoke(craftServer);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("[tunnel] MinecraftServer 인스턴스를 얻지 못했습니다 — IP 복원을 끕니다 (접속은 됩니다)", e);
            return null;
        }
    }

    @Override
    public void onDisable() {
        if (core == null) return;

        TunnelInjector.uninject();
        core.tunnels().clear();
        core.host().shutdown();
    }
}
