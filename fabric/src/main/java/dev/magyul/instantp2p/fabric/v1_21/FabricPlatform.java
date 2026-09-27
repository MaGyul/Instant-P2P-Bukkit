package dev.magyul.instantp2p.fabric.v1_21;

import dev.magyul.instantp2p.common.core.P2PPlatform;
import dev.magyul.instantp2p.common.core.P2PSettings;
import dev.magyul.instantp2p.common.i18n.I18n;
import dev.magyul.instantp2p.fabric.ServerListFiles;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;

/**
 * Fabric 1.21.x 플랫폼. 1.21.x 안에서 시그니처가 바뀐 API(권한, GameProfile, 밴 목록 항목)는 쓰지 않는다 —
 * op·밴 목록은 파일로 읽는다({@link ServerListFiles}). 여기서 쓰는 MC API는 1.21.0~1.21.11 공통이어야 한다.
 */
final class FabricPlatform implements P2PPlatform {

    private static final Logger LOGGER = LoggerFactory.getLogger("Instant-P2P");

    private final P2PSettings settings;
    private final Path dataFolder;
    private final String minecraftVersion;
    private final ServerListFiles ops;
    private final ServerListFiles bannedPlayers;
    private volatile MinecraftServer server;

    FabricPlatform(P2PSettings settings, Path dataFolder, Path serverDir, String minecraftVersion) {
        this.settings = settings;
        this.dataFolder = dataFolder;
        this.minecraftVersion = minecraftVersion;
        this.ops = new ServerListFiles(serverDir.resolve("ops.json"));
        this.bannedPlayers = new ServerListFiles(serverDir.resolve("banned-players.json"));
    }

    void attach(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public P2PSettings settings() {
        return settings;
    }

    @Override
    public Collection<UUID> bannedPlayers() {
        return bannedPlayers.uuids();
    }

    @Override
    public int maxPlayers() {
        MinecraftServer s = server;
        return s != null ? s.getMaxPlayers() : 0;
    }

    @Override
    public String minecraftVersion() {
        return minecraftVersion;
    }

    @Override
    public int listenPort() {
        return server.getPort();
    }

    @Override
    public Path dataFolder() {
        return dataFolder;
    }

    private ServerPlayer player(UUID id) {
        MinecraftServer s = server;
        return s != null ? s.getPlayerList().getPlayer(id) : null;
    }

    @Override
    public boolean isOnline(UUID player) {
        return player(player) != null;
    }

    @Override
    public String playerName(UUID player) {
        ServerPlayer p = player(player);
        return p != null ? p.getScoreboardName() : null;
    }

    /** serverUuid이거나 op. Fabric에는 퍼미션 시스템이 기본으로 없어 op로 판정한다. */
    @Override
    public boolean isHost(UUID player) {
        return player.equals(settings.serverUuid()) || ops.contains(player);
    }

    @Override
    public void kick(UUID player, String translationKey, Object... args) {
        ServerPlayer p = player(player);
        if (p != null) p.connection.disconnect(FabricText.translatable(translationKey, args));
    }

    @Override
    public void notifyAdmins(String translationKey, Object... args) {
        LOGGER.info(I18n.format(translationKey, args));
        MinecraftServer s = server;
        if (s == null) return;
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (isAdmin(p)) p.sendSystemMessage(FabricText.translatable(translationKey, args));
        }
    }

    boolean isAdmin(ServerPlayer p) {
        return ops.contains(p.getUUID());
    }

    /**
     * 항상 작업 큐에 넣는다. {@code server.execute()}는 서버 스레드에서 부르면 미루지 않고 즉시 실행해서
     * "다음 틱에"(Paper runTask와 같은 의미)가 되지 않는다 — 퇴장 이벤트 등에서 필요하다.
     */
    @Override
    public void runSync(Runnable task) {
        MinecraftServer s = server;
        if (s != null) s.schedule(s.wrapRunnable(task));
    }

    @Override
    public void broadcastRoomState(byte[] payload) {
        MinecraftServer s = server;
        if (s == null) return;
        Payloads.RoomStatePayload packet = new Payloads.RoomStatePayload(payload);
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            // 모드가 없는 클라이언트는 채널을 등록하지 않는다 (Paper sendPluginMessage와 같은 동작)
            if (ServerPlayNetworking.canSend(p, Payloads.RoomStatePayload.TYPE)) {
                ServerPlayNetworking.send(p, packet);
            }
        }
    }

    @Override
    public void setLoggerLevel(String loggerName, String level) {
        try {
            Configurator.setLevel(loggerName, Level.toLevel(level, Level.WARN));
        } catch (NoClassDefFoundError e) {
            LOGGER.debug("log4j-core not available; {} level unchanged", loggerName);
        }
    }
}
