package dev.magyul.instantp2p.fabric.impl;

import dev.magyul.instantp2p.common.core.P2PPlatform;
import dev.magyul.instantp2p.common.core.P2PSender;
import dev.magyul.instantp2p.common.core.P2PSettings;
import dev.magyul.instantp2p.common.i18n.I18n;
import dev.magyul.instantp2p.fabric.ServerListFiles;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;

/**
 * Fabric 플랫폼. 버전마다 시그니처가 바뀐 API(권한, GameProfile, 밴 목록 항목)는 쓰지 않는다 —
 * op·밴 목록은 파일로 읽는다({@link ServerListFiles}). 여기서 쓰는 MC API는 1.21.0~26.x 공통이어야 한다({@link FabricImpl} 참고).
 */
final class FabricPlatform implements P2PPlatform {

    private static final Logger LOGGER = LoggerFactory.getLogger("Instant-P2P");

    private volatile P2PSettings settings;
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
    public P2PSettings loadSettings() throws Exception {
        return dev.magyul.instantp2p.common.core.JsonSettings.load(dataFolder.resolve("config.json"));
    }

    @Override
    public void saveSettings(java.util.Map<String, Object> values) throws Exception {
        dev.magyul.instantp2p.common.core.JsonSettings.save(dataFolder.resolve("config.json"), values);
    }

    @Override
    public void applySettings(P2PSettings settings) {
        this.settings = settings;
    }

    @Override
    public Collection<UUID> bannedPlayers() {
        return bannedPlayers.uuids();
    }

    @Override
    public int maxPlayers() {
        MinecraftServer s = server;
        return s != null ? s.getPlayerList().getMaxPlayers() : 0;
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

    @Override
    public String motd() {
        MinecraftServer s = server;
        return s != null ? s.getMotd() : "";
    }

    /** 콘솔 로그로 — 서식 코드 없이 */
    @Override
    public P2PSender console() {
        return (key, args) -> LOGGER.info(I18n.stripLegacy(I18n.format(key, args))); // Fabric 콘솔은 로거 이름을 안 찍는다 — 머리말을 붙인다
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
        LOGGER.info(I18n.stripLegacy(I18n.formatLog(translationKey, args))); // 콘솔에는 서식 코드 없이
        MinecraftServer s = server;
        if (s == null) return;
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (isAdmin(p)) p.sendSystemMessage(FabricText.translatable(translationKey, args), false); // (Component) 오버로드는 1.21.0에 없다
        }
    }

    boolean isAdmin(ServerPlayer p) {
        return ops.contains(p.getUUID());
    }

    /**
     * 항상 작업 큐에 넣는다. {@code server.execute()}는 서버 스레드에서 부르면 미루지 않고 즉시 실행해서
     * "다음 틱에"(Paper runTask와 같은 의미)가 되지 않는다 — 퇴장 이벤트 등에서 필요하다.
     * <p>
     * 그래서 다른 스레드를 한 번 거쳐 {@code execute}로 넣는다(서버 스레드가 아니면 항상 큐에 들어간다).
     * 큐에 직접 넣는 API는 버전마다 달라 쓸 수 없다 — {@code schedule}은 1.21.0에 없고 {@code wrapRunnable}은 protected였다.
     * {@code execute}는 JDK {@code Executor} 메서드라 이름·접근이 모든 버전에서 같다.
     */
    @Override
    public void runSync(Runnable task) {
        MinecraftServer s = server;
        if (s != null) HOP.execute(() -> s.execute(task));
    }

    private static final java.util.concurrent.Executor HOP = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "instant-p2p-sync");
        t.setDaemon(true);
        return t;
    });

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
}
