package dev.magyul.instantp2p.common.tunnel;

import com.google.common.net.InetAddresses;
import dev.magyul.instantp2p.common.Utils;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TunnelRegistry {

    public static final class Tunnel {
        private final InetSocketAddress localAddress;
        private final String realIp;          // 시그널링이 준 원본 (IP 또는 토큰)
        private final InetAddress peerAddress; // 서버에 보여줄 주소
        private final String sid;
        private final long createdAtMs;
        private volatile Boolean usesRelay;
        private volatile UUID playerId;

        private Tunnel(InetSocketAddress localAddress, String realIp, String sid) {
            this.localAddress = localAddress;
            this.realIp = realIp;
            this.peerAddress = Utils.toPeerAddress(realIp);
            this.sid = sid;
            this.createdAtMs = System.currentTimeMillis();
        }

        public InetSocketAddress localAddress() { return localAddress; }
        public String realIp() { return realIp; }
        public InetAddress peerAddress() { return peerAddress; }
        public String sid() { return sid; }
        public long createdAtMs() { return createdAtMs; }
        /** null = 아직 판별 전 */
        public Boolean usesRelay() { return usesRelay; }
        public UUID playerId() { return playerId; }

        /** 서버 쪽에서 remoteAddress 대신 쓸 주소. 포트는 로컬 포트를 그대로 둔다. */
        public InetSocketAddress spoofedRemote() throws UnknownHostException {
            return new InetSocketAddress(peerAddress, localAddress.getPort());
        }

        @Override
        public String toString() {
            return "Tunnel[sid=" + sid + ", local=" + localAddress + ", real=" + realIp
                    + ", relay=" + usesRelay + ", player=" + playerId + "]";
        }
    }

    private final Map<InetSocketAddress, Tunnel> byLocal = new ConcurrentHashMap<>();
    private final Map<UUID, Tunnel> byPlayer = new ConcurrentHashMap<>();

    // ── 호스트(QuicHost) 쪽 ─────────────────────────────────────────

    /**
     * dialTarget() 직후 호출. localAddress는 다이얼한 소켓의 getLocalAddress().
     * 같은 주소가 이미 있으면(이전 소켓이 정리 전에 포트가 재사용된 경우) 새 것으로 덮어쓴다.
     */
    public Tunnel register(SocketAddress localAddress, String realIp, String sid) {
        if (!(localAddress instanceof InetSocketAddress isa)) {
            throw new IllegalArgumentException("not an InetSocketAddress: " + localAddress);
        }
        Tunnel t = new Tunnel(normalize(isa), realIp, sid);
        Tunnel prev = byLocal.put(t.localAddress, t);
        if (prev != null && prev.playerId != null) {
            byPlayer.remove(prev.playerId, prev);
        }
        return t;
    }

    /** HostSession.close()에서 호출. 이미 다른 터널로 교체된 항목은 건드리지 않는다. */
    public void unregister(Tunnel t) {
        if (t == null) return;
        byLocal.remove(t.localAddress, t);
        UUID pid = t.playerId;
        if (pid != null) {
            byPlayer.remove(pid, t);
        }
    }

    /** notifyConnectionType()의 getStats 콜백에서 호출. 재확인 때 값이 바뀌면 덮어쓴다. */
    public void setRelay(Tunnel t, boolean usesRelay) {
        if (t != null) t.usesRelay = usesRelay;
    }

    // ── 서버(Bukkit) 쪽 ──────────────────────────────────────────────

    /** 서버가 본 remoteAddress로 터널 조회. 터널이 아닌 일반 접속이면 empty. */
    public Optional<Tunnel> byRemote(SocketAddress remote) {
        if (!(remote instanceof InetSocketAddress isa)) return Optional.empty();
        return Optional.ofNullable(byLocal.get(normalize(isa)));
    }

    /** 터널이면 실제 IP, 아니면 원래 IP. 해석 불가면 null. */
    public String resolveRealIp(SocketAddress remote) {
        Optional<Tunnel> t = byRemote(remote);
        if (t.isPresent()) return t.get().realIp;
        if (remote instanceof InetSocketAddress isa && isa.getAddress() != null) {
            return isa.getAddress().getHostAddress();
        }
        return null;
    }

    /** 로그인 성공 시 플레이어와 터널을 묶어 둔다. 이후 UUID로 relay 여부, 실제 IP 조회 가능. */
    public void bindPlayer(Tunnel t, UUID playerId) {
        if (t == null || playerId == null) return;
        t.playerId = playerId;
        Tunnel prev = byPlayer.put(playerId, t);
        if (prev != null && prev != t) {
            prev.playerId = null;
        }
    }

    /** PlayerQuitEvent에서 호출. 터널 자체는 소켓이 닫힐 때 unregister로 정리된다. */
    public void unbindPlayer(UUID playerId) {
        Tunnel t = byPlayer.remove(playerId);
        if (t != null) t.playerId = null;
    }

    public Optional<Tunnel> byPlayer(UUID playerId) {
        return Optional.ofNullable(byPlayer.get(playerId));
    }

    public Optional<Tunnel> bySpoofed(InetSocketAddress addr) {
        if (addr == null || addr.getAddress() == null) return Optional.empty();
        return byLocal.values().stream()
                .filter(t -> t.localAddress.getPort() == addr.getPort()
                        && t.peerAddress.equals(addr.getAddress()))
                .findFirst();
    }

    /** 이 주소(포트 무관)로 보이는 터널이 있는지 — 포트를 모르는 로그인 이벤트용. */
    public boolean hasPeerAddress(InetAddress addr) {
        if (addr == null) return false;
        return byLocal.values().stream().anyMatch(t -> t.peerAddress.equals(addr));
    }

    public int size() {
        return byLocal.size();
    }

    /** onDisable에서 호스트를 닫은 뒤 호출. */
    public void clear() {
        byLocal.clear();
        byPlayer.clear();
    }

    /**
     * 키 비교를 안정적으로 만들기 위해 항상 resolved InetSocketAddress(IP 리터럴 + 포트)로 맞춘다.
     * 호스트명 기반 unresolved 주소는 equals가 IP 기반 주소와 달라서 매칭이 깨진다.
     */
    private static InetSocketAddress normalize(InetSocketAddress isa) {
        if (isa.isUnresolved()) {
            return new InetSocketAddress(isa.getHostString(), isa.getPort());
        }
        return isa;
    }
}
