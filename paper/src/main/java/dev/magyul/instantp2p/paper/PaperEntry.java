package dev.magyul.instantp2p.paper;

import dev.magyul.instantp2p.common.MinecraftVersions;
import dev.magyul.instantp2p.common.Utils;
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
        if (!MinecraftVersions.atLeast(minecraftVersion, MIN_VERSION)) {
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (settings == null || !settings.enabled()) return;

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
                title = motd();
            }

            if (settings.publicRoom()) {
                core.bridge().publishPublicRoom(inviteCode, title, settings.name(), settings.serverUuid().toString(),
                        getServer().getOnlinePlayers().size(), getServer().getMaxPlayers());
            }
        });
    }

    /** room_update.version — 클라이언트가 문자열 비교한다. getBukkitVersion()은 "1.21.11-R0.1-SNAPSHOT" 형식(Paper/Spigot 공통). */
    private static String minecraftVersion() {
        String bukkit = Bukkit.getBukkitVersion();
        int dash = bukkit.indexOf('-');
        return dash > 0 ? bukkit.substring(0, dash) : bukkit;
    }

    /** 서버 MOTD(레거시 서식 문자열). Paper에서 deprecated지만 Spigot과 공통으로 쓸 수 있는 건 이것뿐이다. */
    @SuppressWarnings("deprecation")
    private static String motd() {
        return Bukkit.getMotd();
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
        core.bridge().stopHost();
    }
}
