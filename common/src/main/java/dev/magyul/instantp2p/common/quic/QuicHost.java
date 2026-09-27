package dev.magyul.instantp2p.common.quic;

import dev.magyul.instantp2p.common.Utils;
import dev.magyul.instantp2p.common.core.P2PCore;
import dev.magyul.instantp2p.common.tunnel.TunnelRegistry;
import dev.magyul.instantp2p.common.signaling.P2PConfig;
import dev.magyul.instantp2p.common.signaling.PeerNames;
import dev.magyul.instantp2p.common.signaling.VillasMsg;
import dev.magyul.instantp2p.common.signaling.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.server.ApplicationProtocolConnection;
import tech.kwik.core.server.ApplicationProtocolConnectionFactory;
import tech.kwik.core.server.ServerConnection;
import tech.kwik.core.server.ServerConnector;
import tech.kwik.core.server.impl.ServerConnectorImpl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * QUIC 호스트 (원본 모드 1.3의 QuicHost를 서버용으로 옮김). 시그널링은 VILLAS relay를 그대로 쓴다.
 * <ol>
 *   <li>UDP 소켓 하나({@link QuicIce})로 host/srflx/relay 후보를 모으고, 그 소켓 위에 kwik QUIC 서버를 띄운다.
 *       인증서는 실행마다 새로 만드는 자체 서명 — 조인자는 시그널링으로 받은 지문으로 확인한다.</li>
 *   <li>로비 {@code /{roomId}/h{4자리 숫자}}에 상주하다 조인 알림({@code j{d|r}{sid}})을 보면
 *       페어 방 {@code /{roomId}-{sid}/h{sid}}에 붙어 지문({@code quic-answer})과 후보를 보내고, 받은 후보로 펀칭한다.</li>
 *   <li>조인자는 QUIC 연결 하나를 맺고 MC 접속마다 스트림을 연다. 스트림 하나 = 로컬 MC 서버로의 TCP 하나.</li>
 * </ol>
 * 접속자 주소는 QUIC 연결의 실제 UDP 출발 주소다({@link #peerKey}).
 */
public final class QuicHost {

    private static final Logger LOG = LoggerFactory.getLogger("quic-host");

    static final String ALPN = "instant-p2p";
    static final String MSG_ANSWER = "quic-answer";
    private static final int DIAL_TIMEOUT_MS = 5_000;
    private static final long PUNCH_MS = 35_000L;
    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;
    private static final int PIPE_BUF = 65_536;

    private final P2PCore core;
    private final TunnelRegistry tunnels;
    private final String roomId;
    private final String targetHost;
    private final int targetPort;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerConnector server;
    private volatile QuicIce ice;
    private volatile WebSocketClient lobby;
    private volatile String fingerprint;
    private volatile List<QuicIce.Candidate> candidates = List.of();
    private volatile long backoffMs = INITIAL_BACKOFF_MS;
    private volatile boolean signalingDown = false;
    /** 한 번 순단으로는 경고하지 않는다 — 연속 2번 실패해야 알린다 */
    private volatile int consecutiveFailures = 0;

    private final Set<QuicConnection> live = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> handled = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "quic-host-worker");
        t.setDaemon(true);
        return t;
    });

    public QuicHost(P2PCore core, String roomId, String target) {
        this.core = core;
        this.tunnels = core.tunnels();
        this.roomId = roomId;
        int colon = target.lastIndexOf(':');
        if (colon < 0) throw new IllegalArgumentException("invalid target: " + target);
        this.targetHost = target.substring(0, colon);
        this.targetPort = Integer.parseInt(target.substring(colon + 1));
    }

    private boolean relayOnly() {
        return core.settings().relayOnly();
    }

    // ── 라이프사이클 ──────────────────────────────────────────────────────────

    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) return;
        ServerConnectorImpl.DEFAULT_CLOSE_TIMEOUT_IN_SECONDS = 2;
        QuicCert.Identity id = QuicCert.generate();
        fingerprint = id.fingerprint();

        QuicIce agent = new QuicIce(P2PConfig.STUN_URL, relayOnly());
        ice = agent;
        agent.enableTurn(P2PConfig.TURN_URL, P2PConfig.TURN_USERNAME, P2PConfig.TURN_CREDENTIAL);
        candidates = agent.gather();

        server = ServerConnector.builder()
                .withSocket(agent.socket())
                .withKeyStore(id.keyStore(), QuicCert.ALIAS, QuicCert.PASSWORD)
                .withSupportedVersion(QuicConnection.QuicVersion.V1)
                .withLogger(KwikLog.quiet())
                .build();
        server.registerApplicationProtocol(ALPN, new TunnelFactory());
        server.start();
        LOG.info("[quic-host] listening room={} udpPort={} target={}:{}", roomId, agent.localPort(), targetHost, targetPort);
        worker.execute(this::connectLobby);
    }

    public void close() {
        if (!running.compareAndSet(true, false)) return;
        long t0 = System.currentTimeMillis();
        WebSocketClient l = lobby;
        if (l != null) l.close();
        for (QuicConnection c : live) {
            try { c.close(); } catch (Exception ignored) {}
        }
        live.clear();
        ServerConnector s = server;
        if (s != null) s.close();
        QuicIce agent = ice;
        if (agent != null) agent.close();
        worker.shutdownNow();
        LOG.info("[quic-host] stopped room={} ({}ms)", roomId, System.currentTimeMillis() - t0);
    }

    // ── 로비 (조인 감지) ──────────────────────────────────────────────────────

    private void connectLobby() {
        if (!running.get()) return;
        // 로비 이름은 접속마다 새로 — 서버가 같은 이름의 재접속을 거부한다
        String peerName = PeerNames.lobbyHost(ThreadLocalRandom.current().nextInt(1000, 10000));
        WebSocketClient ws = new WebSocketClient(P2PConfig.SIGNALING_URL + "/" + roomId + "/" + peerName) {
            @Override public void onConnected() {
                send(VillasMsg.hello());
                backoffMs = INITIAL_BACKOFF_MS;
                consecutiveFailures = 0;
                LOG.info("[host] lobby joined: room={}", roomId);
                if (signalingDown) {
                    signalingDown = false;
                    core.platform().notifyAdmins("instant-p2p.msg.signaling_recovered");
                }
            }
            @Override public void onMessage(String type, String json) {
                handleLobby(json);
            }
            @Override protected int readIdleTimeoutMs() {
                return LIVENESS_TIMEOUT_MS;
            }
            @Override public void onDisconnected() {
                scheduleReconnect();
            }
        };
        lobby = ws;
        try {
            ws.connect();
        } catch (Exception e) {
            LOG.warn("[host] Signaling connect failed: {}", e.toString());
            scheduleReconnect();
            return;
        }
        if (!running.get()) ws.close();
    }

    private void scheduleReconnect() {
        if (!running.get()) return;
        consecutiveFailures++;
        if (!signalingDown && consecutiveFailures >= 2) {
            signalingDown = true;
            core.platform().notifyAdmins("instant-p2p.msg.signaling_unreachable");
        }
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        LOG.info("[host] Signaling reconnect in {}ms", delay);
        try {
            worker.execute(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(delay);
                } catch (InterruptedException e) {
                    return;
                }
                connectLobby();
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private void handleLobby(String json) {
        if (!VillasMsg.has(json, "control")) return;
        long now = System.currentTimeMillis();
        handled.values().removeIf(t -> now - t > 600_000L);

        for (String[] p : VillasMsg.peers(json)) {
            String name = p[0], remote = p[1];
            if (remote == null) continue;
            PeerNames.Join join = PeerNames.parseJoin(name);
            if (join == null || handled.putIfAbsent(name, now) != null) continue;

            // "jq" = 입장 전 확인 — 연결하지 않고 접속자 해시만 알려주고 끝낸다
            if (join.probe()) {
                worker.execute(() -> sendMembers(join.sid()));
                continue;
            }
            // IP는 로그에 남기지 않는다 — 방장이 로그를 공유하면 조인자 IP가 박제된다
            LOG.info("[host] join detected: sid={} clientRelayForced={}", join.sid(), join.relayForced());
            worker.execute(() -> negotiate(join.sid(), join.relayForced()));
        }
    }

    /** 입장 전 확인 응답 — 확인하는 쪽이 열어 둔 페어 방에 잠깐 붙어 접속자 해시만 보내고 나간다. */
    private void sendMembers(String sid) {
        if (!running.get()) return;
        WebSocketClient w = new WebSocketClient(P2PConfig.SIGNALING_URL + "/"
                + PeerNames.pairRoom(roomId, sid) + "/" + PeerNames.probeHost(sid)) {
            @Override public void onConnected() {
                send(VillasMsg.hello());
                send(VillasMsg.description("members", Utils.encodePlayerHashes(core.onlinePlayers(), roomId)));
            }
            @Override public void onMessage(String type, String json) {}
        };
        try {
            w.connect();
        } catch (Exception e) {
            LOG.warn("[host] members reply failed sid={}: {}", sid, e.toString());
        }
        w.close();
    }

    // ── 조인자별 협상 (지문 + 후보 교환, 펀칭) ─────────────────────────────────

    private void negotiate(String sid, boolean clientRelayForced) {
        QuicIce agent = ice;
        if (agent == null || !running.get()) return;
        List<QuicIce.Candidate> theirs = new CopyOnWriteArrayList<>();
        boolean relayNow = relayOnly();
        WebSocketClient pair = null;
        try {
            WebSocketClient ws = new WebSocketClient(P2PConfig.SIGNALING_URL + "/"
                    + PeerNames.pairRoom(roomId, sid) + "/" + PeerNames.pairHost(sid)) {
                @Override public void onConnected() {
                    send(VillasMsg.hello());
                    send(VillasMsg.description(MSG_ANSWER, fingerprint));
                    for (QuicIce.Candidate c : QuicIce.advertised(candidates, relayNow, clientRelayForced)) {
                        send(VillasMsg.candidate(c.line(), "0"));
                    }
                }
                @Override public void onMessage(String type, String json) {
                    if (!VillasMsg.has(json, "candidate")) return;
                    String cand = VillasMsg.object(json, "candidate");
                    if (cand == null) return;
                    QuicIce.Candidate c = QuicIce.Candidate.parse(VillasMsg.field(cand, "spd"));
                    if (c == null) return;
                    agent.addRemote(c);
                    if (!theirs.contains(c)) theirs.add(c);
                }
            };
            pair = ws;
            ws.connect();
            QuicIce.Candidate picked = agent.punch(theirs, PUNCH_MS, false, relayNow);
            LOG.info("[host] punch done sid={} result={}", sid,
                    picked != null ? picked.type() : "none (조인자 쪽 경로로 붙을 수 있다)");
        } catch (Exception e) {
            LOG.warn("[host] negotiation failed sid={}: {}", sid, e.getMessage());
        } finally {
            if (pair != null) pair.close();
        }
    }

    // ── QUIC 연결 → 로컬 TCP ─────────────────────────────────────────────────

    /**
     * 서버에 보여줄 접속자 식별자 ({@link Utils#toPeerAddress}가 주소로 바꾼다).
     * <ul>
     *   <li>IPv4 실제 주소 — 그대로 (IP 밴이 그대로 먹는다)</li>
     *   <li>조인자가 중계 강제라 TURN 서버 주소로 들어온 경우 — 전원이 같은 IP가 되어 연속 접속 제한에 서로 걸리므로
     *       TURN 할당(ip:port) 기준 합성 주소. 중계 강제는 IP를 숨기려는 설정이기도 하다.</li>
     *   <li>IPv6 — 바닐라 IP 밴이 IPv6를 못 읽으므로 합성 IPv4</li>
     * </ul>
     */
    private String peerKey(InetSocketAddress remote) {
        if (remote == null || remote.getAddress() == null) return null;
        String ip = remote.getAddress().getHostAddress();
        if (isTurnServer(ip)) return "relay-" + ip + ":" + remote.getPort();
        if (!(remote.getAddress() instanceof Inet4Address)) return "ip6-" + ip;
        return ip;
    }

    private boolean isTurnServer(String ip) {
        InetSocketAddress turn = TurnAllocation.parseUrl(P2PConfig.TURN_URL);
        return turn != null && turn.getAddress() != null && turn.getAddress().getHostAddress().equals(ip);
    }

    /** 연결 하나의 정보 — 스트림(=MC 접속)마다 터널을 등록할 때 쓴다 */
    private final class Peer {
        final String key;
        volatile boolean relayed;
        /** 이 연결의 스트림들이 등록한 터널 — 경로가 중계로 바뀌면 같이 고친다 */
        final Set<TunnelRegistry.Tunnel> tunnels = ConcurrentHashMap.newKeySet();

        Peer(String key, boolean relayed) {
            this.key = key;
            this.relayed = relayed;
        }

        void markRelayed() {
            relayed = true;
            for (TunnelRegistry.Tunnel t : tunnels) QuicHost.this.tunnels.setRelay(t, true);
        }
    }

    private void bridge(QuicStream stream, Peer peer) {
        Socket tcp = new Socket();
        TunnelRegistry.Tunnel tunnel = null;
        try {
            tcp.setTcpNoDelay(true);
            tcp.connect(new InetSocketAddress(targetHost, targetPort), DIAL_TIMEOUT_MS);
            // MC 서버는 이 소켓의 로컬 주소를 접속자 주소로 본다 — 데이터를 흘리기 전에 등록한다 (TunnelInjector가 교체)
            if (peer.key != null) {
                tunnel = tunnels.register(tcp.getLocalSocketAddress(), peer.key, "quic");
                tunnels.setRelay(tunnel, peer.relayed);
                peer.tunnels.add(tunnel);
            }
        } catch (IOException e) {
            LOG.warn("[host] Failed to dial target {}:{}: {}", targetHost, targetPort, e.getMessage());
            core.platform().notifyAdmins("instant-p2p.msg.guest_connect_failed");
            try { tcp.close(); } catch (IOException ignored) {}
            try { stream.getOutputStream().close(); } catch (IOException ignored) {}
            return;
        }

        TunnelRegistry.Tunnel registered = tunnel;
        AtomicBoolean closed = new AtomicBoolean(false);
        Runnable closeBoth = () -> {
            if (!closed.compareAndSet(false, true)) return;
            try { tcp.close(); } catch (IOException ignored) {}
            try { stream.getOutputStream().close(); } catch (IOException ignored) {}
            try { stream.getInputStream().close(); } catch (IOException ignored) {}
            tunnels.unregister(registered);
            if (registered != null) peer.tunnels.remove(registered);
        };
        try {
            pump("quic-host-up", tcp.getInputStream(), stream.getOutputStream(), closeBoth);
            pump("quic-host-down", stream.getInputStream(), tcp.getOutputStream(), closeBoth);
        } catch (IOException e) {
            closeBoth.run();
        }
    }

    private static void pump(String name, InputStream in, OutputStream out, Runnable onEnd) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[PIPE_BUF];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException e) {
                LOG.debug("[host] {} ended: {}", name, e.getMessage());
            } finally {
                onEnd.run();
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    private final class TunnelFactory implements ApplicationProtocolConnectionFactory {

        @Override public int maxConcurrentPeerInitiatedBidirectionalStreams() { return 100; }
        @Override public int maxConcurrentPeerInitiatedUnidirectionalStreams() { return 0; }
        @Override public int minBidirectionalStreamReceiverBufferSize() { return 1_000_000; }

        @Override
        public ApplicationProtocolConnection createConnection(String protocol, QuicConnection conn) {
            live.add(conn);
            conn.setConnectionListener(event -> live.remove(conn));

            InetSocketAddress remote = conn instanceof ServerConnection sc ? sc.getInitialRemoteAddress() : null;
            String key = peerKey(remote);
            QuicIce agent = ice;
            String ip = remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : null;
            boolean relayed = ip != null && (isTurnServer(ip) || (agent != null && agent.sendsViaRelayTo(ip)));
            Peer peer = new Peer(key, relayed);
            if (key == null) {
                LOG.warn("[host] 상대 주소를 못 잡았다 — 이 연결엔 IP 복원이 적용되지 않는다");
            } else if (!relayed) {
                // 경로가 연결 직후 중계로 바뀌는 경우가 있어 한 번 더 확인한다
                worker.execute(() -> {
                    try {
                        TimeUnit.SECONDS.sleep(2);
                    } catch (InterruptedException e) {
                        return;
                    }
                    QuicIce a2 = ice;
                    if (a2 != null && a2.sendsViaRelayTo(ip)) {
                        LOG.info("[host] connection type changed: direct -> relay");
                        peer.markRelayed();
                    }
                });
            }
            LOG.info("[host] QUIC connection accepted (relay={})", relayed);

            return new ApplicationProtocolConnection() {
                @Override
                public void acceptPeerInitiatedStream(QuicStream stream) {
                    worker.execute(() -> bridge(stream, peer));
                }
            };
        }
    }
}
