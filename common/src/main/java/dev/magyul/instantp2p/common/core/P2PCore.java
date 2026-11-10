package dev.magyul.instantp2p.common.core;

import dev.magyul.instantp2p.common.DevBadge;
import dev.magyul.instantp2p.common.auth.HostAccount;
import dev.magyul.instantp2p.common.network.PacketByteBuf;
import dev.magyul.instantp2p.common.network.packet.Moderation;
import dev.magyul.instantp2p.common.network.packet.RoomState;
import dev.magyul.instantp2p.common.i18n.I18n;
import dev.magyul.instantp2p.common.tunnel.TunnelRegistry;
import dev.magyul.instantp2p.common.core.ExpelManager;
import dev.magyul.instantp2p.common.signaling.Roles;
import dev.magyul.instantp2p.common.core.P2PBridge;
import dev.magyul.instantp2p.common.signaling.ModVersion;
import dev.magyul.instantp2p.common.signaling.P2PConfig;
import dev.magyul.instantp2p.common.signaling.Presence;
import dev.magyul.instantp2p.common.signaling.SignalingServer;
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
    private final RelayLinks relayLinks;
    private final P2PBanList banList;
    private final BannedIps bannedIps = new BannedIps(java.nio.file.Path.of("banned-ips.json"));
    /** 방장 동의 기록(공개 방 / 공식 서버) */
    private final dev.magyul.instantp2p.common.signaling.Consent consent;
    private final HostController host;
    /** 가맹점 서버 상태 보고 — 가맹점 서버일 때만 보낸다 */
    private final Presence presence;
    private volatile boolean ipRestoreUnavailable;
    /** 터널로 로그인 중인 UUID → 기록 시각. 정원 검사 이벤트에 주소가 없는 플랫폼(Paper)이 쓴다. */
    private final Map<UUID, Long> tunnelLogins = new ConcurrentHashMap<>();

    public P2PCore(P2PPlatform platform) {
        // 다른 TLS/HTTP 클라이언트가 만들어지기 전에 — 패치 안 된 Java면 교체형 TrustManager를 먼저 깔아 둔다
        JreGuard.init(platform.dataFolder());
        this.platform = platform;
        this.expel = new ExpelManager(platform, this::broadcastRoomState);
        this.bridge = new P2PBridge(this);
        this.relayLinks = new RelayLinks(platform.dataFolder().resolve("relay-links.json"));
        this.banList = new P2PBanList(platform.dataFolder().resolve("p2p-bans.json"));
        this.account = new HostAccount(platform.dataFolder(), platform.settings().serverUuid().toString());
        this.consent = new dev.magyul.instantp2p.common.signaling.Consent(platform.dataFolder());
        this.host = new HostController(this);
        P2PSettings s = platform.settings();
        P2PConfig.useServer(s.effectiveServer());
        warnIfTermsMissing(s);
        this.presence = new Presence(this);
        presence.start();
    }

    public Presence presence() { return presence; }

    /**
     * 서버 스레드. 설정을 적용한다 — 플랫폼 값과 방을 올릴 서버. 설정은 이 메서드로만 바꾼다.
     *
     * @return 방을 올릴 서버가 바뀌었는지 (열려 있으면 다시 열어야 한다 — {@link HostController#reopenIfOpen})
     */
    public boolean applySettings(P2PSettings s) {
        SignalingServer before = P2PConfig.server();
        platform.applySettings(s);
        SignalingServer now = s.effectiveServer();
        P2PConfig.useServer(now);
        warnIfTermsMissing(s);
        if (before == now) return false;
        // 게시 토큰·TURN 계정·버전 조회 결과는 서버마다 따로다
        account.forgetServerState();
        ModVersion.reset();
        LOG.info("[host] 방을 올릴 서버를 바꿨습니다: {} ({})", now.id(), now.host());
        return true;
    }

    private static void warnIfTermsMissing(P2PSettings s) {
        if (s.server() == SignalingServer.FRANCHISE && !s.franchiseTermsAccepted()) {
            LOG.warn("[host] signalingServer가 franchise지만 가맹점 약관(버전 {})에 동의하지 않아 공식 서버를 씁니다 — /p2p server franchise",
                    SignalingServer.FRANCHISE_TERMS_VERSION);
        }
    }

    public P2PPlatform platform() { return platform; }
    public P2PSettings settings() { return platform.settings(); }
    public TunnelRegistry tunnels() { return tunnels; }
    public ExpelManager expel() { return expel; }
    public P2PBridge bridge() { return bridge; }
    public HostAccount account() { return account; }
    public dev.magyul.instantp2p.common.signaling.Consent consent() { return consent; }
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

    /**
     * 중계 강제 접속자의 계정이 전에 쓴 합성 주소 중 IP 밴된 것이 있으면 true — 플랫폼이 IP 밴 거부
     * ({@code multiplayer.disconnect.ip_banned})로 입장을 막는다. 중계 강제 접속자의 서버 쪽 주소는 접속할 때마다 바뀌어
     * {@code /ban-ip}만으로는 재접속이 안 막히기 때문이다(원본 1.5.1은 중계 접속자의 실제 IP를 몰라 {@code /ban-ip}가 거부되고 계정 밴만 된다).
     * 직결 접속자는 건드리지 않는다(바닐라 IP 밴이 그대로 동작).
     */
    public boolean isLinkedIpBanned(UUID player, InetAddress address) {
        if (player == null || address == null || !tunnels.isRelayAddress(address)) return false;
        String current = address.getHostAddress();
        relayLinks.remember(player, current);
        java.util.Set<String> banned = bannedIps.ips();
        if (banned.isEmpty()) return false;
        for (String old : relayLinks.addresses(player)) {
            if (!old.equals(current) && banned.contains(old)) {
                LOG.info("[host] 중계 접속자 {}: 전에 쓴 합성 주소가 IP 밴 목록에 있어 입장 거부", player);
                return true;
            }
        }
        return false;
    }

    /** 밴 사유를 안 줬을 때 — 바닐라와 같은 문구. */
    public static final String DEFAULT_BAN_REASON = "Banned by an operator.";

    /**
     * 로그인 IP 밴 검사 — 막혀 있으면 사유, 아니면 null. 플러그인 자체 목록({@code /p2p ban-ip})과, 중계 강제 접속자가 전에 쓴
     * 합성 주소가 서버 IP 밴 목록에 있는 경우({@link #isLinkedIpBanned})를 본다. 플랫폼이 사유와 함께 입장을 거부한다.
     */
    public String ipBanDenial(UUID player, InetAddress address) {
        String addr = address != null ? address.getHostAddress() : null;
        var hit = banList.find(player, addr);
        if (hit.isPresent()) return hit.get().reason() == null || hit.get().reason().isBlank() ? DEFAULT_BAN_REASON : hit.get().reason();
        if (isLinkedIpBanned(player, address)) return DEFAULT_BAN_REASON;
        return null;
    }

    private static final java.util.regex.Pattern IPV4 = java.util.regex.Pattern.compile("(\\d{1,3}\\.){3}\\d{1,3}");

    /**
     * 서버 스레드. {@code /p2p ban-ip <플레이어|IP> [사유]} — 접속 중인 P2P 접속자를 이름으로, 또는 IP로 막는다.
     * 중계 강제 접속자는 계정(UUID)으로 막는다(서버는 원래 IP를 모른다 — 어디에도 남지 않는다). 방송에 비치지 않게 IP는 출력하지 않는다.
     * 일반 서버(Paper·Fabric)에서는 직결 접속자를 바닐라 {@code ban-ip}로도 올려 바닐라 목록과 같이 동작한다.
     */
    public void banIp(P2PSender sender, String target, String reason) {
        String why = reason == null || reason.isBlank() ? DEFAULT_BAN_REASON : reason.strip();
        UUID id = null;
        String name = null, addr;
        boolean relay = false;
        if (IPV4.matcher(target).matches()) {
            addr = target;
        } else {
            TunnelRegistry.Tunnel tunnel = null;
            for (UUID p : tunnels.players().keySet()) {
                String n = platform.playerName(p);
                if (n != null && n.equalsIgnoreCase(target)) {
                    id = p;
                    name = n;
                    tunnel = tunnels.byPlayer(p).orElse(null);
                    break;
                }
            }
            if (tunnel == null) {
                sender.send(K_BAN + "not_guest", target);
                return;
            }
            relay = tunnel.realIp() != null && tunnel.realIp().startsWith("relay-");
            addr = tunnel.peerAddress().getHostAddress();
        }
        P2PBanList.Entry e = banList.add(relay ? id : null, name, addr, relay, why);
        // 지금 접속 중인 해당 접속자 퇴장 — 중계는 계정, 직결은 같은 주소의 접속자 모두
        for (UUID p : new java.util.ArrayList<>(tunnels.players().keySet())) {
            var t = tunnels.byPlayer(p);
            boolean hit = relay ? p.equals(id) : t.isPresent() && t.get().peerAddress().getHostAddress().equals(addr);
            if (hit) platform.kick(p, K_BAN + "denied", why);
        }
        if (!relay) platform.runConsoleCommand("ban-ip " + addr + " " + why); // 바닐라 목록에도 (Velocity는 해당 없음)
        sender.send(K_BAN + "done", "#" + e.id(), name != null ? name : "-", kindLabel(relay), shownAddr(e));
    }

    /** 서버 스레드. {@code /p2p pardon-ip <#번호|플레이어|UUID|IP>} */
    public void pardonIp(P2PSender sender, String target) {
        String t = target.startsWith("#") ? target.substring(1) : target;
        java.util.List<P2PBanList.Entry> removed;
        if (t.chars().allMatch(Character::isDigit) && !t.isEmpty() && target.startsWith("#")) {
            removed = banList.removeById(Integer.parseInt(t));
        } else {
            removed = banList.remove(t);
        }
        for (P2PBanList.Entry e : removed) {
            if (!e.relay() && e.addr() != null) platform.runConsoleCommand("pardon-ip " + e.addr());
        }
        sender.send(K_BAN + (removed.isEmpty() ? "none_found" : "pardoned"), removed.size());
    }

    /** 서버 스레드. {@code /p2p ban-list} — 번호·이름·종류·사유(IP는 보이지 않는다) */
    public void banListTo(P2PSender sender) {
        var all = banList.all();
        if (all.isEmpty()) {
            sender.send(K_BAN + "list_empty");
            return;
        }
        sender.send(K_BAN + "list_header", all.size());
        for (P2PBanList.Entry e : all) {
            sender.send(K_BAN + "list_entry", "#" + e.id(), e.name() != null ? e.name() : "-", kindLabel(e.relay()), shownAddr(e),
                    e.reason() == null || e.reason().isBlank() ? DEFAULT_BAN_REASON : e.reason());
        }
    }

    /** 화면에 보일 주소 — 중계 접속자는 합성 주소(원래 IP는 서버도 모른다), 직결 접속자는 실제 IP(직결은 IP가 보일 것을 감안하고 접속한다). */
    private static String shownAddr(P2PBanList.Entry e) {
        return e.addr() != null ? e.addr() : "-";
    }

    private static String kindLabel(boolean relay) {
        return I18n.stripLegacy(I18n.fallback(K_BAN + (relay ? "kind_relay" : "kind_direct")));
    }

    private static final String K_BAN = "instant-p2p-server.ban.";

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
        bridge.pushRoomInfo();
        broadcastRoomState();
        bridge.updatePublicRoomPlayerCount(onlinePlayers.size(), maxPlayers());
    }

    /** 서버 스레드. 입장 완료. */
    public void onJoin(UUID player) {
        tunnelLogins.remove(player);
        onlinePlayers.add(player);
        broadcastRoomState();
        bridge.updatePublicRoomPlayerCount(onlinePlayers.size(), maxPlayers());
        bridge.hostEvent(roomLogEvent("join", player));
    }

    /** 서버 스레드. 퇴장 — 이 시점엔 플랫폼 온라인 목록에 아직 남아 있으므로 방 상태는 다음 틱에 보낸다. */
    public void onQuit(UUID player) {
        bridge.hostEvent(roomLogEvent("leave", player)); // 아직 온라인 목록에 있어 이름을 알 수 있다
        tunnels.unbindPlayer(player);
        expel.onDisconnect(player);
        onlinePlayers.remove(player);
        platform.runSync(() -> {
            broadcastRoomState();
            bridge.updatePublicRoomPlayerCount(onlinePlayers.size(), maxPlayers());
        });
    }

    /** [1.3.0] 방 기록 이벤트 {@code {"k":"join|leave","by":uuid,"nick":이름}} (닉네임은 32자까지). */
    private String roomLogEvent(String kind, UUID player) {
        String name = platform.playerName(player);
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("k", kind);
        o.addProperty("by", player.toString());
        o.addProperty("nick", name == null ? "" : name.length() > 32 ? name.substring(0, 32) : name);
        return o.toString();
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

    /** room_state 등급 목록 상한 (원본 1.5.1 P2PNet.readRanks). */
    static final int MAX_RANKS = 64;

    private static Map<UUID, Integer> rankMap(Collection<UUID> online) {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        for (UUID id : online) {
            int rank = ExpelManager.priority(id); // 방장이므로 자기 Roles 사본으로 계산된다
            if (rank > 0) out.put(id, rank);
        }
        // [1.3.0] 원본 1.5.1 클라이언트는 등급 목록이 64개를 넘으면 패킷을 거부(예외)한다 — 높은 등급부터 64명만 보낸다.
        if (out.size() > MAX_RANKS) {
            Map<UUID, Integer> capped = new LinkedHashMap<>();
            out.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                    .limit(MAX_RANKS)
                    .forEach(e -> capped.put(e.getKey(), e.getValue()));
            return capped;
        }
        return out;
    }
}
