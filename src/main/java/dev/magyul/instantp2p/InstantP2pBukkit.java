package dev.magyul.instantp2p;

import dev.magyul.instantp2p.network.P2PNet;
import dev.magyul.instantp2p.tunnel.TunnelInjector;
import dev.magyul.instantp2p.tunnel.TunnelRegistry;
import dev.magyul.instantp2p.webrtc.P2PConfig;
import dev.magyul.instantp2p.webrtc.WebRtcBridge;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tel.schich.libdatachannel.LibDataChannelArchDetect;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InstantP2pBukkit extends JavaPlugin {
    public static InstantP2pBukkit INSTANCE;
    public static final Logger LOGGER = LoggerFactory.getLogger("Instant-P2P");
    public static final TunnelRegistry TUNNEL_REGISTRY = new TunnelRegistry();

    private static String inviteCode = null;

    public P2PConfig config;
    public Collection<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();

    @Override
    public void onLoad() {

        try {
            INSTANCE = this;

            saveDefaultConfig();
            LOGGER.info("컨피그를 불러오는 중...");
            config = new P2PConfig(getConfig());
            if (!config.isEnabled()) {
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
        if (!config.isEnabled()) return;

        try {
            TunnelInjector.inject();
            P2PNet.register();
        } catch (ReflectiveOperationException e) {
            LOGGER.error("네트워크 셋팅에 실패했습니다! 플러그인이 비활성화됩니다.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        LibDataChannelArchDetect.initialize();

        Bukkit.getPluginManager().registerEvents(new InstantP2pListener(), this);

        // 버킷이 완전히 켜진 후 스캐줄이 돌아가므로 버킷이 켜지고 안정화가 시작될때 P2P 서비스를 시작한다.
        Bukkit.getScheduler().runTask(this, () -> {
            LOGGER.info("초대 코드 생성중...");
            inviteCode = Utils.generateCode();

            try {
                WebRtcBridge.startHost(inviteCode, "127.0.0.1:" + Bukkit.getServer().getPort());
            } catch (Exception e) {
                LOGGER.error("[instant-p2p] Failed to start host: {}", e.getMessage(), e);
            }

            LOGGER.info("초대 코드: {}", inviteCode);

            String title = config.getTitle();
            if (title.isEmpty()) {
                title = LegacyComponentSerializer.legacySection().serialize(Bukkit.getServer().motd());
            }

            if (config.isPublicRoom()) {
                WebRtcBridge.publishPublicRoom(inviteCode, title, config.getName(), config.getServerUUID().toString(),
                        getServer().getOnlinePlayers().size(), getServer().getMaxPlayers());
            }
        });
    }

    @Override
    public void onDisable() {
        if (!config.isEnabled()) return;

        TunnelInjector.uninject();
        TUNNEL_REGISTRY.clear();
        WebRtcBridge.stopHost();
    }
}
