package dev.magyul.instantp2p.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.magyul.instantp2p.common.core.P2PPlatform;
import dev.magyul.instantp2p.common.core.P2PSender;
import dev.magyul.instantp2p.common.core.P2PText;
import dev.magyul.instantp2p.common.core.P2PSettings;
import dev.magyul.instantp2p.common.i18n.I18n;
import dev.magyul.instantp2p.common.network.packet.Moderation;
import dev.magyul.instantp2p.common.network.packet.RoomState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Velocity 플랫폼. 프록시는 월드가 없어 밴 목록·입장 메시지가 없다(백엔드 몫).
 * 호스트/관리자 판정은 퍼미션(instantp2p.host, instantp2p.notify.host) — 퍼미션 플러그인이 없으면 serverUuid만 호스트.
 */
final class VelocityPlatform implements P2PPlatform {

    private static final Logger LOGGER = LoggerFactory.getLogger("Instant-P2P");

    static final MinecraftChannelIdentifier ROOM_STATE = MinecraftChannelIdentifier.from(RoomState.ID);
    static final MinecraftChannelIdentifier MODERATION = MinecraftChannelIdentifier.from(Moderation.ID);

    private final Object plugin;
    private final ProxyServer server;
    private volatile P2PSettings settings;
    private final Path dataFolder;
    /** room_update.version — 설정값이나 백엔드 ping으로 정해진다 (BackendVersion 참고) */
    private volatile String minecraftVersion;

    VelocityPlatform(Object plugin, ProxyServer server, P2PSettings settings, Path dataFolder) {
        this.plugin = plugin;
        this.server = server;
        this.settings = settings;
        this.dataFolder = dataFolder;
    }

    void setMinecraftVersion(String version) {
        this.minecraftVersion = version;
    }

    @Override
    public P2PSettings settings() {
        return settings;
    }

    @Override
    public P2PSettings loadSettings() throws Exception {
        Path file = dataFolder.resolve(VelocityEntry.CONFIG_FILE);
        P2PSettings loaded = dev.magyul.instantp2p.common.core.JsonSettings.load(file);
        // minecraftVersion을 직접 적었으면 그 값으로 (비웠으면 백엔드 ping으로 알아낸 값을 그대로 둔다)
        String version = dev.magyul.instantp2p.common.core.JsonSettings.extraString(file, VelocityEntry.MOD_VERSION_KEY, "");
        if (!version.isBlank()) pendingVersion = version.trim();
        return loaded;
    }

    /** loadSettings에서 읽은 minecraftVersion — applySettings 때 반영한다 */
    private volatile String pendingVersion;

    @Override
    public void saveSettings(java.util.Map<String, Object> values) throws Exception {
        dev.magyul.instantp2p.common.core.JsonSettings.save(dataFolder.resolve(VelocityEntry.CONFIG_FILE), values);
    }

    @Override
    public void applySettings(P2PSettings settings) {
        this.settings = settings;
        String v = pendingVersion;
        pendingVersion = null;
        if (v != null) minecraftVersion = v;
    }

    @Override
    public Collection<UUID> bannedPlayers() {
        return List.of(); // 프록시에는 밴 목록이 없다
    }

    @Override
    public int maxPlayers() {
        return server.getConfiguration().getShowMaxPlayers();
    }

    @Override
    public String minecraftVersion() {
        return minecraftVersion;
    }

    @Override
    public int listenPort() {
        return server.getBoundAddress().getPort();
    }

    @Override
    public Path dataFolder() {
        return dataFolder;
    }

    /** 와일드카드 bind면 루프백, 특정 주소에 bind했으면 그 주소 */
    @Override
    public String targetHost() {
        java.net.InetSocketAddress bind = server.getBoundAddress();
        if (bind.getAddress() == null || bind.getAddress().isAnyLocalAddress()) return "127.0.0.1";
        return bind.getAddress().getHostAddress();
    }

    @Override
    public String motd() {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(server.getConfiguration().getMotd());
    }

    @Override
    public P2PSender console() {
        return (key, args) -> server.getConsoleCommandSource().sendMessage(VelocityText.translatable(key, P2PText.plain(args)));
    }

    @Override
    public boolean isOnline(UUID player) {
        return server.getPlayer(player).isPresent();
    }

    @Override
    public String playerName(UUID player) {
        return server.getPlayer(player).map(Player::getUsername).orElse(null);
    }

    @Override
    public boolean isHost(UUID player) {
        if (player.equals(settings.serverUuid())) return true;
        return server.getPlayer(player).map(p -> p.hasPermission("instantp2p.host")).orElse(false);
    }

    @Override
    public void kick(UUID player, String translationKey, Object... args) {
        server.getPlayer(player).ifPresent(p -> p.disconnect(VelocityText.translatable(translationKey, args)));
    }

    @Override
    public void notifyAdmins(String translationKey, Object... args) {
        LOGGER.info(I18n.stripLegacy(I18n.formatLog(translationKey, args))); // 콘솔에는 서식 코드 없이
        for (Player p : server.getAllPlayers()) {
            if (isAdmin(p)) p.sendMessage(VelocityText.translatable(translationKey, args));
        }
    }

    boolean isAdmin(Player p) {
        return p.hasPermission("instantp2p.notify.host");
    }

    /** 프록시에는 메인 스레드가 없다 — 스케줄러로 넘긴다(호출한 이벤트가 끝난 뒤 실행). */
    @Override
    public void runSync(Runnable task) {
        server.getScheduler().buildTask(plugin, task).schedule();
    }

    @Override
    public void broadcastRoomState(byte[] payload) {
        for (Player p : server.getAllPlayers()) {
            p.sendPluginMessage(ROOM_STATE, payload);
        }
    }
}
