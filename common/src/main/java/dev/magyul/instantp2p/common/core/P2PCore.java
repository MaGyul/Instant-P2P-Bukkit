package dev.magyul.instantp2p.common.core;

import dev.magyul.instantp2p.common.network.PacketByteBuf;
import dev.magyul.instantp2p.common.network.packet.Moderation;
import dev.magyul.instantp2p.common.network.packet.RoomState;
import dev.magyul.instantp2p.common.tunnel.TunnelRegistry;
import dev.magyul.instantp2p.common.core.ExpelManager;
import dev.magyul.instantp2p.common.signaling.Roles;
import dev.magyul.instantp2p.common.core.P2PBridge;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 플랫폼과 무관한 호스트 상태 묶음. 플랫폼 진입점이 하나 만들어 이벤트를 넘겨준다.
 * <p>
 * 접속자 목록은 플랫폼 API 대신 여기서 이벤트로 관리한다 — members probe 응답은 worker 스레드에서
 * 나가는데, 플랫폼의 온라인 목록은 서버 스레드 밖에서 안전하게 읽을 수 있다는 보장이 없다.
 */
public final class P2PCore {

    /** IP 복원(TunnelInjector)을 못 하게 됐을 때 관리자에게 보내는 번역 키 */
    public static final String IP_RESTORE_UNAVAILABLE = "instant-p2p.msg.ip_restore_unavailable";

    private final P2PPlatform platform;
    private final TunnelRegistry tunnels = new TunnelRegistry();
    private final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();
    private final ExpelManager expel;
    private final P2PBridge bridge;
    private volatile boolean ipRestoreUnavailable;

    public P2PCore(P2PPlatform platform) {
        this.platform = platform;
        this.expel = new ExpelManager(platform, this::broadcastRoomState);
        this.bridge = new P2PBridge(this);
    }

    public P2PPlatform platform() { return platform; }
    public P2PSettings settings() { return platform.settings(); }
    public TunnelRegistry tunnels() { return tunnels; }
    public ExpelManager expel() { return expel; }
    public P2PBridge bridge() { return bridge; }

    /** IP 복원이 꺼진 상태인지 — 관리자가 입장할 때마다 다시 알린다. */
    public boolean ipRestoreUnavailable() {
        return ipRestoreUnavailable;
    }

    /** 아무 스레드. IP 복원을 못 하게 됐다 — 접속은 되지만 밴/IP밴/throttle이 127.0.0.1 기준이 된다. */
    public void markIpRestoreUnavailable() {
        ipRestoreUnavailable = true;
        platform.runSync(() -> platform.notifyAdmins(IP_RESTORE_UNAVAILABLE));
    }

    /** 스레드 안전한 스냅샷 */
    public Collection<UUID> onlinePlayers() {
        return List.copyOf(onlinePlayers);
    }

    // ── 플랫폼 이벤트 ────────────────────────────────────────────────────────

    /** 로그인 전(비동기 가능). 역할 목록을 갱신하고, 추방 상태면 false — 플랫폼이 입장을 거부한다. */
    public boolean onPreLogin(UUID player) {
        Roles.refreshOnLogin(() -> platform.runSync(this::broadcastRoomState));
        return !expel.isExpelled(player);
    }

    /** 서버 스레드. 입장 완료. */
    public void onJoin(UUID player) {
        onlinePlayers.add(player);
        broadcastRoomState();
        bridge.updatePublicRoomPlayerCount(onlinePlayers.size(), platform.maxPlayers());
    }

    /** 서버 스레드. 퇴장 — 이 시점엔 플랫폼 온라인 목록에 아직 남아 있으므로 방 상태는 다음 틱에 보낸다. */
    public void onQuit(UUID player) {
        tunnels.unbindPlayer(player);
        expel.onDisconnect(player);
        onlinePlayers.remove(player);
        platform.runSync(() -> {
            broadcastRoomState();
            bridge.updatePublicRoomPlayerCount(onlinePlayers.size(), platform.maxPlayers());
        });
    }

    /** 서버 스레드. instant-p2p:moderation 수신. */
    public void onModeration(UUID sender, byte[] payload) {
        Moderation packet = new Moderation(PacketByteBuf.wrap(payload));
        expel.handleRequest(sender, packet.action(), packet.target());
    }

    // ── room_state ───────────────────────────────────────────────────────────

    /** 서버 스레드. 방 상태(정원, 호스트, 방송 허용, 등급)를 접속자 전원에게 보낸다. */
    public void broadcastRoomState() {
        P2PSettings settings = platform.settings();
        RoomState packet = new RoomState(platform.maxPlayers(), settings.serverUuid(),
                settings.allowBroadcast(), rankMap(onlinePlayers));
        PacketByteBuf buf = PacketByteBuf.allocate();
        packet.write(buf);
        platform.broadcastRoomState(buf.toByteArray());
    }

    private static Map<UUID, Integer> rankMap(Collection<UUID> online) {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        for (UUID id : online) {
            int rank = ExpelManager.priority(id); // 방장이므로 자기 Roles 사본으로 계산된다
            if (rank > 0) out.put(id, rank);
        }
        return out;
    }
}
