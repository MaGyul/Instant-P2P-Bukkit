package dev.magyul.instantp2p.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.magyul.instantp2p.common.core.JsonSettings;
import dev.magyul.instantp2p.common.core.P2PCore;
import dev.magyul.instantp2p.common.core.P2PCommand;
import dev.magyul.instantp2p.common.core.P2PSettings;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Velocity 진입점 (velocity-plugin.json은 이 어노테이션으로 생성된다). 3.x·4.x 공통.
 * <p>
 * 프록시에서 못 하는 것: 입장 메시지 suffix(백엔드가 보낸다), 밴 목록(백엔드 몫). 백엔드에는 이 플러그인이 필요 없다.
 * expel/kick은 프록시에서 끊는다.
 */
@Plugin(
        id = "instant-p2p-proxy",
        name = "instant-p2p proxy",
        version = BuildInfo.VERSION,
        description = "instant-p2p 모드 클라이언트가 초대 코드/공개 방 목록으로 이 프록시에 접속하게 한다",
        authors = {"MaGyul"})
public final class VelocityEntry {

    private static final String CONFIG_FILE = "config.json";
    private static final String MOD_VERSION_KEY = "minecraftVersion";
    private static final long VERSION_RETRY_SECONDS = 30;

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private P2PCore core;
    private VelocityPlatform platform;
    private final VelocityTunnelInjector injector = new VelocityTunnelInjector();

    @Inject
    public VelocityEntry(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        P2PSettings settings;
        String configuredVersion;
        try {
            logger.info("컨피그를 불러오는 중...");
            Path file = dataDirectory.resolve(CONFIG_FILE);
            settings = JsonSettings.load(file);
            // 비워 두면 백엔드 ping으로 정한다 — 모드 클라이언트 버전과 같아야 공개 방 목록에서 호환으로 보인다
            configuredVersion = JsonSettings.extraString(file, MOD_VERSION_KEY, "");
        } catch (Exception e) {
            logger.error("컨피그를 불러오는데 실패 했습니다! {} 파일에 문제가 없나요?", dataDirectory.resolve(CONFIG_FILE), e);
            return;
        }
        platform = new VelocityPlatform(this, server, settings, dataDirectory);
        core = new P2PCore(platform);

        // bind 전에 걸어야 새 리스너 채널에 적용된다. 실패해도 접속은 되므로 끄지 않는다 (관리자에게만 알림)
        if (!injector.inject(server, core.tunnels())) core.markIpRestoreUnavailable();
        server.getChannelRegistrar().register(VelocityPlatform.ROOM_STATE, VelocityPlatform.MODERATION);
        server.getCommandManager().register(
                server.getCommandManager().metaBuilder(P2PCommand.NAME).plugin(this).build(), new VelocityCommand(core));

        if (!configuredVersion.isBlank()) {
            publishWithVersion(configuredVersion.trim());
        } else {
            resolveBackendVersion();
        }
        // 리스너 bind 뒤에 열어야 터널 다이얼이 붙는다 — 스케줄러로 한 박자 미룬다
        server.getScheduler().buildTask(this, () -> core.host().autoStart()).schedule();
    }

    /** 백엔드가 아직 안 켜졌을 수 있어 성공할 때까지 재시도한다. 버전을 알아야 공개 방을 올린다. */
    private void resolveBackendVersion() {
        BackendVersion.ping(server).whenComplete((version, error) -> {
            if (error != null) {
                logger.warn("[public-room] 백엔드 서버 버전을 알 수 없어 {}초 뒤 다시 시도합니다 ({}) — config.json의 {}로 직접 지정할 수 있습니다",
                        VERSION_RETRY_SECONDS, error.getMessage(), MOD_VERSION_KEY);
                server.getScheduler().buildTask(this, this::resolveBackendVersion)
                        .delay(VERSION_RETRY_SECONDS, TimeUnit.SECONDS).schedule();
                return;
            }
            logger.info("[public-room] 백엔드 서버 버전: {}", version);
            publishWithVersion(version);
        });
    }

    /** 버전을 알면 공개 방을 올릴 수 있다 — 방이 이미 열려 있으면 지금 올린다 */
    private void publishWithVersion(String version) {
        platform.setMinecraftVersion(version);
        core.host().publishPublicRoom();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (core == null) return;
        injector.uninject();
        core.tunnels().clear();
        core.host().shutdown();
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        if (core == null) return;
        if (!core.onPreLogin(event.getPlayer().getUniqueId())) {
            event.setResult(LoginEvent.ComponentResult.denied(
                    VelocityText.translatable("instant-p2p.msg.still_expelled")));
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        if (core == null) return;
        Player player = event.getPlayer();
        core.tunnels().bySpoofed(player.getRemoteAddress())
                .ifPresent(t -> core.tunnels().bindPlayer(t, player.getUniqueId()));
        core.onJoin(player.getUniqueId());
        if (core.ipRestoreUnavailable() && platform.isAdmin(player)) {
            player.sendMessage(VelocityText.translatable(P2PCore.IP_RESTORE_UNAVAILABLE));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (core == null) return;
        // 로그인이 끝난 적 없는 접속(중복 로그인 등)은 onJoin도 없었다
        if (event.getLoginStatus() != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) return;
        core.onQuit(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (core == null || !VelocityPlatform.MODERATION.equals(event.getIdentifier())) return;
        // 백엔드로 넘기지 않는다 — 프록시가 처리한다
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (event.getSource() instanceof Player player) {
            byte[] data = event.getData();
            platform.runSync(() -> core.onModeration(player.getUniqueId(), data));
        }
    }
}
