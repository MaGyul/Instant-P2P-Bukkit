package dev.magyul.instantp2p.common.core;

import dev.magyul.instantp2p.common.DevBadge;
import dev.magyul.instantp2p.common.auth.HostAccount;
import dev.magyul.instantp2p.common.network.PacketByteBuf;
import dev.magyul.instantp2p.common.network.packet.Moderation;
import dev.magyul.instantp2p.common.network.packet.RoomState;
import dev.magyul.instantp2p.common.tunnel.TunnelRegistry;
import dev.magyul.instantp2p.common.core.ExpelManager;
import dev.magyul.instantp2p.common.signaling.Roles;
import dev.magyul.instantp2p.common.core.P2PBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
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

    private static final Logger LOG = LoggerFactory.getLogger("Instant-P2P");

    /** P2P 최대 인원이 찼을 때 입장 거부 사유 — 바닐라 키라 클라이언트가 자기 언어로 보여 준다. */
    public static final String SERVER_FULL = "multiplayer.disconnect.server_full";

    /** IP 복원(TunnelInjector)을 못 하게 됐을 때 관리자에게 보내는 번역 키 */
    public static final String IP_RESTORE_UNAVAILABLE = "instant-p2p.msg.ip_restore_unavailable";

    /** 로그인 전 검사와 정원 검사 사이 간격의 상한 — 넘으면 터널 접속 기록을 버린다. */
    private static final long TUNNEL_LOGIN_TTL_MS = 60_000;

    private final P2PPlatform platform;
    private final TunnelRegistry tunnels = new TunnelRegistry();
    private final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();
    private final ExpelManager expel;
    private final P2PBridge bridge;
    private final HostAccount account;
    private final HostController host;
    private volatile boolean ipRestoreUnavailable;
    /** 터널로 로그인 중인 UUID → 기록 시각. 정원 검사 이벤트에 주소가 없는 플랫폼(Paper)이 쓴다. */
    private final Map<UUID, Long> tunnelLogins = new ConcurrentHashMap<>();

    public P2PCore(P2PPlatform platform) {
        this.platform = platform;
        this.expel = new ExpelManager(platform, this::broadcastRoomState);
        this.bridge = new P2PBridge(this);
        this.account = new HostAccount(platform.dataFolder(), platform.settings().serverUuid().toString());
        this.host = new HostController(this);
    }

    public P2PPlatform platform() { return platform; }
    public P2PSettings settings() { return platform.settings(); }
    public TunnelRegistry tunnels() { return tunnels; }
    public ExpelManager expel() { return expel; }
    public P2PBridge bridge() { return bridge; }
    public HostAccount account() { return account; }
    public HostController host() { return host; }

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

    /** {@link #onPreLogin(UUID)} + 접속 주소가 터널이면 기록해 둔다({@link #canBypassPlayerLimit}용). */
    public boolean onPreLogin(UUID player, InetAddress address) {
        noteTunnelLogin(player, address);
        return onPreLogin(player);
    }

    /** 테스트용으로 분리 (역할 갱신 네트워크 호출 없이) */
    void noteTunnelLogin(UUID player, InetAddress address) {
        long now = System.currentTimeMillis();
        tunnelLogins.values().removeIf(at -> now - at > TUNNEL_LOGIN_TTL_MS);
        if (address != null && tunnels.hasPeerAddress(address)) {
            tunnelLogins.put(player, now);
        } else {
            tunnelLogins.remove(player);
        }
    }

    /**
     * 서버가 가득 찼을 때 들여보낼지 — 터널로 들어온 개발자·서포터(원본 {@code canBypassPlayerLimit}, {@link DevBadge#hasPerk}).
     * 들어온 뒤엔 한 자리를 그대로 차지한다(인원 수에서 빼지 않음). 서버 주소로 직접 접속한 경우는 해당하지 않는다.
     * 먼저 {@link #onPreLogin(UUID, InetAddress)}가 불려 있어야 한다.
     */
    public boolean canBypassPlayerLimit(UUID player, InetAddress address) {
        return address != null && tunnels.hasPeerAddress(address) && DevBadge.hasPerk(player);
    }

    /** 정원 검사에 주소가 없는 플랫폼(Paper)용 — {@link #onPreLogin(UUID, InetAddress)}에서 기록한 값을 쓴다. */
    public boolean canBypassPlayerLimit(UUID player) {
        Long at = tunnelLogins.get(player);
        return at != null && System.currentTimeMillis() - at <= TUNNEL_LOGIN_TTL_MS && DevBadge.hasPerk(player);
    }

    /**
     * 모드에 보이는 정원(room_state, 공개 방) — P2P 최대 인원이 켜져 있으면 그 값(서버 정원을 넘지 않게), 아니면 서버 정원.
     */
    public int maxPlayers() {
        int server = platform.maxPlayers();
        P2PSettings s = platform.settings();
        if (!s.limitsMaxPlayers()) return server;
        return Math.min(s.maxPlayers(), server);
    }

    /** P2P 최대 인원 초과 거부 로그 — 정원 검사가 한 입장에 두 번 불리는 플랫폼(Fabric)이 있어 한 번만 */
    private final Map<UUID, Long> fullLogged = new ConcurrentHashMap<>();

    /**
     * P2P 최대 인원이 켜져 있고 찼으면 true — 플랫폼이 서버 정원 초과({@link #SERVER_FULL})로 입장을 거부한다.
     * 터널로 들어오는 접속만 막고(서버 주소로 직접 접속하면 서버 정원을 따름), 개발자·서포터는 정원 초과 입장처럼 예외.
     * 인원은 서버 전체 접속자 수(모드 목록에 보이는 인원과 같은 기준).
     */
    public boolean isP2PFull(UUID player, String name, InetAddress address) {
        if (!platform.settings().limitsMaxPlayers()) return false;
        if (address == null || !tunnels.hasPeerAddress(address)) return false;
        if (onlinePlayers.contains(player)) return false; // 중복 접속은 서버가 기존 접속을 끊고 받는다
        int max = maxPlayers();
        if (onlinePlayers.size() < max) return false;
        if (DevBadge.hasPerk(player)) return false;
        long now = System.currentTimeMillis();
        fullLogged.values().removeIf(t -> now - t > 30_000);
        if (fullLogged.putIfAbsent(player, now) == null) {
            LOG.info("[host] P2P 최대 인원({}명)이 차서 입장 거부: {}", max, name != null ? name : player);
        }
        return true;
    }

    /** 서버 스레드. P2P 최대 인원이 바뀌었다 — 모드에 보이는 정원을 다시 알린다. */
    public void onMaxPlayersChanged() {
        broadcastRoomState();
        bridge.updatePublicRoomPlayerCount(onlinePlayers.size(), maxPlayers());
    }

    /** 서버 스레드. 입장 완료. */
    public void onJoin(UUID player) {
        tunnelLogins.remove(player);
        onlinePlayers.add(player);
        broadcastRoomState();
        bridge.updatePublicRoomPlayerCount(onlinePlayers.size(), maxPlayers());
    }

    /** 서버 스레드. 퇴장 — 이 시점엔 플랫폼 온라인 목록에 아직 남아 있으므로 방 상태는 다음 틱에 보낸다. */
    public void onQuit(UUID player) {
        tunnels.unbindPlayer(player);
        expel.onDisconnect(player);
        onlinePlayers.remove(player);
        platform.runSync(() -> {
            broadcastRoomState();
            bridge.updatePublicRoomPlayerCount(onlinePlayers.size(), maxPlayers());
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
        RoomState packet = new RoomState(maxPlayers(), settings.serverUuid(),
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
