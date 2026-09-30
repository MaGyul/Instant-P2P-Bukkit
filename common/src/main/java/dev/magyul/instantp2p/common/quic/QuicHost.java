package dev.magyul.instantp2p.common.quic;

import dev.magyul.instantp2p.common.Utils;
import dev.magyul.instantp2p.common.core.P2PCore;
import dev.magyul.instantp2p.common.signaling.HardwareId;
import dev.magyul.instantp2p.common.signaling.P2PConfig;
import dev.magyul.instantp2p.common.signaling.VillasMsg;
import dev.magyul.instantp2p.common.signaling.WebSocketClient;
import dev.magyul.instantp2p.common.tunnel.TunnelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.Statistics;
import tech.kwik.core.send.SendStatistics;
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
import java.net.SocketException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * QUIC 호스트 (원본 모드 1.4의 QuicHost를 서버용으로 옮김).
 * <ol>
 *   <li>UDP 소켓 하나({@link QuicIce})로 host/srflx/relay 후보를 모으고, 그 소켓 위에 kwik QUIC 서버를 띄운다.
 *       인증서는 실행마다 새로 만드는 자체 서명 — 조인자는 시그널링으로 받은 지문으로 확인한다.</li>
 *   <li>랑데부 서버에 방장으로 붙는다({@code /rv/{roomId}/host?key&token}). 조인자가 오면 서버가 sid를 정해 {@code join}으로
 *       알려 주고, 그 조인자와의 메시지는 sid를 붙여 오간다(다른 조인자는 보지도 끼어들지도 못한다).
 *       지문({@code quic-answer})과 후보를 보내고, 받은 후보로 펀칭한다.</li>
 *   <li>조인자는 QUIC 연결 하나를 맺고 MC 접속마다 스트림을 연다. 스트림 하나 = 로컬 MC 서버로의 TCP 하나.</li>
 * </ol>
 * 방장 연결에는 시그널링 게시 토큰이 필요하다({@code HostAccount}). 접속자 주소는 QUIC 연결의 실제 UDP 출발 주소다({@link #peerKey}).
 */
public final class QuicHost {

    private static final Logger LOG = LoggerFactory.getLogger("quic-host");

    static final String ALPN = "instant-p2p";
    static final String MSG_ANSWER = "quic-answer";
    /** 중계 강제인데 중계 계정이 없다 — 알릴 후보가 없으니 조인자가 30초 기다리지 않게 바로 알린다. */
    static final String MSG_NO_RELAY = "quic-no-relay";
    private static final int DIAL_TIMEOUT_MS = 5_000;
    /** 조인자의 2단계 시도(직결 2초 + 중계 30초)보다 길어야 방장이 먼저 포기하지 않는다. */
    private static final long PUNCH_MS = 35_000L;
    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;
    private static final int PIPE_BUF = 65_536;
    /** 동시에 진행하는 접속 협상 수 상한 — 가짜 접속으로 체크 패킷을 증폭시키지 못하게(원본과 같음). */
    private static final int MAX_NEGOTIATIONS = 4;

    private final P2PCore core;
    private final TunnelRegistry tunnels;
    private final String roomId;
    private final String targetHost;
    private final int targetPort;
    /**
     * 랑데부 서버에서 이 방 코드를 잡아 두는 비밀. 잠깐 끊겼다 다시 붙어도 같은 key여야 방을 되찾는다
     * — 코드만 아는 남이 그 사이 방장 자리를 가로채지 못하게. 원본은 방마다 새로 만들지만 서버판은 코드를 유지하므로
     * key도 코드와 함께 저장해 둔 값을 받는다(바뀌면 서버가 이전 key로 잡아 둔 동안 409).
     */
    private final String hostKey;

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
    /** 협상 중인 조인자(sid) → 그 조인자가 보낸 후보 */
    private final Map<String, List<QuicIce.Candidate>> joiners = new ConcurrentHashMap<>();
    /** sid → 진행 중인 협상. 조인자가 떠나면(leave) 끊어 협상 자리를 돌려준다. */
    private final Map<String, Future<?>> negotiations = new ConcurrentHashMap<>();
    private final Semaphore negotiating = new Semaphore(MAX_NEGOTIATIONS);

    /**
     * 접속 표(원본 1.4.3) — 랑데부로 협상한 조인자에게만 주는 일회용 무작위 값(값 = 만료 시각). 조인자는 <b>모든 스트림의
     * 맨 앞 {@link #TICKET_BYTES} 바이트</b>에 이걸 싣고, 방장은 표가 맞는 연결만 받는다. 1.4.3 클라이언트는 {@code quic-answer}에
     * 표가 없으면 접속을 포기한다("방장이 옛 버전일 수 있습니다").
     * <p>
     * 방장 UDP 주소는 후보로 조인자 전원(공개 방이면 누구나)에게 나가는데 QUIC 서버는 그 포트로 오는 연결을 누구에게서나 받는다 —
     * 표가 없으면 랑데부의 접속 제한을 건너뛰고 직접 들어와 연결마다 스트림 × (스레드 + MC 서버 TCP)를 만들 수 있었다.
     * 표는 스트림 데이터 안에 실려 암호화된 뒤 나간다. 표 없는 연결은 첫 스트림에서 {@link #TICKET_WAIT_MS} 안에 연결째 끊긴다.
     */
    private final Map<String, Long> tickets = new ConcurrentHashMap<>();
    static final int TICKET_BYTES = 16;
    /** 표 유효 시간 — 조인자가 핸드셰이크를 몇 번 시도해도 넉넉하되 오래 두지는 않는다. */
    private static final long TICKET_TTL_MS = 120_000;
    /** 첫 스트림에서 표를 읽을 때까지 기다리는 한도. kwik 스트림 읽기엔 시간 제한이 없어 연결을 닫아 깨운다. */
    private static final long TICKET_WAIT_MS = 3_000;
    /** 표를 아직 못 낸(확인 중인) 연결의 동시 상한 — 표 없는 연결이 몰려도 스레드가 무한히 늘지 않게. */
    private final Semaphore unauth = new Semaphore(32);
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "quic-host-watchdog");
        t.setDaemon(true);
        return t;
    });
    private static final SecureRandom RNG = new SecureRandom();
    private volatile long lastRogueLog;
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "quic-host-worker");
        t.setDaemon(true);
        return t;
    });

    public QuicHost(P2PCore core, String roomId, String hostKey, String target) {
        this.core = core;
        this.tunnels = core.tunnels();
        this.roomId = roomId;
        this.hostKey = hostKey;
        int colon = target.lastIndexOf(':');
        if (colon < 0) throw new IllegalArgumentException("invalid target: " + target);
        this.targetHost = target.substring(0, colon);
        this.targetPort = Integer.parseInt(target.substring(colon + 1));
    }

    // ── 라이프사이클 ──────────────────────────────────────────────────────────

    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) return;
        ServerConnectorImpl.DEFAULT_CLOSE_TIMEOUT_IN_SECONDS = 2;
        // kwik은 연결이 끝난 뒤 sender 스레드에서 통계 클래스를 처음 로드한다. 종료 때는 그 시점이 플러그인
        // 비활성화 뒤라 Spigot이 jar를 이미 닫아 "zip file closed"가 난다 → 미리 로드해 둔다.
        preload(Statistics.class, SendStatistics.class);
        QuicCert.Identity id = QuicCert.generate();
        fingerprint = id.fingerprint();

        // 서버판은 방장 쪽 중계 강제가 없다(원본 개발자 요청 — 중계 서버 부담). 중계는 직결이 안 될 때와 조인자가 강제할 때만 쓴다.
        QuicIce agent = openIce(core.settings().udpPort());
        ice = agent;
        // TURN 계정은 방장 계정 인증으로 받는다. 못 받으면 중계 없이(직결만) 간다.
        String[] turn = core.account().turnCredentials();
        if (turn != null) {
            agent.enableTurn(P2PConfig.turnUrl(), turn[0], turn[1]);
        } else {
            LOG.warn("[turn] 중계 계정이 없어 중계 없이 엽니다 (직결만 가능)");
        }
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
        worker.execute(this::openLobby);
    }

    public void close() {
        if (!running.compareAndSet(true, false)) return;
        long t0 = System.currentTimeMillis();
        WebSocketClient l = lobby;
        if (l != null) l.close();
        for (Future<?> f : negotiations.values()) f.cancel(true);
        // 연결을 먼저 우리가 닫는다 — server.close()의 종료 대기가 기다릴 대상이 없게
        for (QuicConnection c : live) {
            try { c.close(); } catch (Exception ignored) {}
        }
        live.clear();
        QuicIce agent = ice;
        // TURN 반납은 소켓이 열려 있을 때 — ServerConnector.close()가 공유 소켓까지 닫는다
        if (agent != null) agent.releaseTurn();
        ServerConnector s = server;
        if (s != null) s.close();
        if (agent != null) agent.close();
        worker.shutdownNow();
        watchdog.shutdownNow();
        LOG.info("[quic-host] stopped room={} ({}ms)", roomId, System.currentTimeMillis() - t0);
    }

    /** 설정한 UDP 포트가 이미 쓰이고 있으면 임의 포트로 연다 (접속은 되게, 관리자는 로그로 안다). */
    private QuicIce openIce(int port) throws SocketException {
        if (port != 0) {
            try {
                return new QuicIce(P2PConfig.stunUrl(), false, port);
            } catch (SocketException e) {
                LOG.warn("[quic-host] UDP {} 포트를 열 수 없다({}) — 임의 포트로 연다", port, e.getMessage());
            }
        }
        return new QuicIce(P2PConfig.stunUrl(), false, 0);
    }

    private static void preload(Class<?>... classes) {
        for (Class<?> c : classes) {
            try {
                Class.forName(c.getName(), true, c.getClassLoader());
            } catch (ClassNotFoundException ignored) {
            }
        }
    }

    // ── 랑데부: 서버가 맺어 주는 조인자 ────────────────────────────────────────

    private void openLobby() {
        if (!running.get()) return;
        // 토큰은 붙을 때마다 다시 받는다 — 방이 토큰 수명(12시간)보다 오래 열려 있어도 재접속이 막히지 않게
        // (남은 시간이 넉넉하면 네트워크 없이 그대로 준다)
        String token = core.account().publishTokenOrNull();
        String url = P2PConfig.signalingUrl() + "/rv/" + roomId + "/host?key=" + hostKey
                + (token != null ? "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8) : "")
                + HardwareId.query(); // 가맹점 서버일 때만 붙는다(가맹점 수정판과 같게)
        WebSocketClient ws = new WebSocketClient(url) {
            @Override public void onConnected() {
                backoffMs = INITIAL_BACKOFF_MS;
                consecutiveFailures = 0;
                LOG.info("[host] rendezvous joined: room={}", roomId);
                if (signalingDown) {
                    signalingDown = false;
                    core.platform().notifyAdmins("instant-p2p.msg.signaling_recovered");
                }
            }
            @Override public void onMessage(String type, String json) {
                handleRendezvous(json);
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
            // 401 = 토큰 문제(만료·로그아웃·차단). URL은 남기지 않는다(토큰이 들어 있다).
            LOG.warn("[host] Signaling connect failed: {}", e.getMessage());
            scheduleReconnect();
            return;
        }
        if (!running.get()) ws.close();
    }

    /**
     * 랑데부가 끊기면 다시 붙는다(같은 key라 방 코드를 되찾는다). 이게 없으면 시그널링이 한 번 끊긴 뒤로
     * 조인 감지가 영구히 멈춰 방은 열려 있는데 아무도 못 들어오는 상태가 된다.
     */
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
                openLobby();
            });
        } catch (RejectedExecutionException ignored) {}
    }

    /** 서버가 보낸 것: join(새 조인자) / leave(조인자가 떠남) / sid가 붙은 후보. */
    private void handleRendezvous(String json) {
        String join = VillasMsg.object(json, "join");
        if (join != null) {
            onJoin(join);
            return;
        }
        String leave = VillasMsg.object(json, "leave");
        if (leave != null) {
            String sid = VillasMsg.field(leave, "sid");
            if (sid != null) {
                joiners.remove(sid);
                Future<?> f = negotiations.remove(sid);
                if (f != null) f.cancel(true); // punch는 인터럽트되면 멈춘다
            }
            return;
        }
        String cand = VillasMsg.object(json, "candidate");
        String sid = VillasMsg.field(json, "sid");
        if (cand == null || sid == null) return;
        List<QuicIce.Candidate> theirs = joiners.get(sid);
        QuicIce agent = ice;
        if (theirs == null || agent == null) return;
        QuicIce.Candidate c = QuicIce.Candidate.parse(VillasMsg.field(cand, "spd"));
        // 조인자 한 명당 상한 — 넘치는 건 버린다(반사 공격 방지)
        if (c != null && !theirs.contains(c) && theirs.size() < QuicIce.Candidate.MAX_PER_PEER) {
            agent.addRemote(c);
            theirs.add(c);
        }
    }

    private void onJoin(String join) {
        String sid = VillasMsg.field(join, "sid");
        // sid는 서버가 만들지만 우리 맵 키로 쓰므로 형식은 확인한다(16 hex)
        if (sid == null || !sid.matches("[0-9a-f]{16}")) return;
        // 입장 전 확인 — 연결하지 않고 지금 접속자 해시만 알려주고 끝
        if ("true".equals(VillasMsg.field(join, "probe"))) {
            send(sid, VillasMsg.description("members", Utils.encodePlayerHashes(core.onlinePlayers(), roomId)));
            return;
        }
        boolean clientRelayForced = "true".equals(VillasMsg.field(join, "relay"));
        // IP는 로그에 남기지 않는다 — 방장이 로그를 공유하면 조인자 IP가 박제된다
        LOG.info("[host] join detected: sid={} clientRelayForced={}", sid, clientRelayForced);
        if (!negotiating.tryAcquire()) {
            LOG.warn("[host] 동시 협상이 너무 많다 — sid={} 는 건너뛴다 (조인자가 다시 시도하면 된다)", sid);
            return;
        }
        // 후보 목록은 지금 만든다 — 이 조인자의 후보가 바로 뒤이어 들어온다
        List<QuicIce.Candidate> theirs = new CopyOnWriteArrayList<>();
        joiners.put(sid, theirs);
        try {
            negotiations.put(sid, worker.submit(() -> {
                try {
                    negotiate(sid, clientRelayForced, theirs);
                } finally {
                    joiners.remove(sid);
                    negotiations.remove(sid);
                    negotiating.release();
                }
            }));
        } catch (RejectedExecutionException e) {
            joiners.remove(sid);
            negotiating.release();
        }
    }

    /** 이 조인자에게만 간다 — 서버가 sid로 라우팅한다. */
    private void send(String sid, String msg) {
        WebSocketClient ws = lobby;
        if (ws != null) ws.send("{\"sid\":\"" + sid + "\"," + msg.substring(1));
    }

    /**
     * 지문·후보를 보내고 홀을 뚫는다. {@code theirs}는 이 조인자의 후보만 모인 목록이다 — 소켓과 ICE는
     * 조인자 전원이 공유하므로 뚫을 때는 반드시 자기 후보로만 판정해야 한다.
     * <p>
     * 조인자가 중계 강제면 내 host 후보만 알리지 않는다(사설 IP는 상대 allocation에서 닿지 않는다). 방장 쪽 중계 강제는
     * 서버판에 없다(원본 개발자 요청) — 원본의 relayNow 자리는 항상 false.
     */
    private void negotiate(String sid, boolean clientRelayForced, List<QuicIce.Candidate> theirs) {
        QuicIce agent = ice;
        if (agent == null || !running.get()) return;
        // 중계 서버가 재시작돼 allocation을 다시 잡았으면 지금 relay 후보로 바꿔 알린다(원본 1.4.2)
        List<QuicIce.Candidate> mine = QuicIce.advertised(agent.withCurrentRelay(candidates), false, clientRelayForced);
        if (mine.isEmpty()) {
            LOG.warn("[host] 조인자에게 알릴 후보가 없다(STUN·중계 모두 실패) — sid={} 를 받을 수 없다", sid);
            send(sid, VillasMsg.description(MSG_NO_RELAY, ""));
            return;
        }
        try {
            // 지문 뒤에 접속 표를 붙인다 — 이 조인자에게만 간다(서버가 sid로 라우팅)
            send(sid, VillasMsg.description(MSG_ANSWER, fingerprint + " " + newTicket()));
            for (QuicIce.Candidate c : mine) {
                send(sid, VillasMsg.candidate(c.line(), "0"));
            }
            // ownLoop=false — ServerConnector가 이미 수신 루프를 돌리고 있다
            QuicIce.Candidate picked = agent.punch(theirs, PUNCH_MS, false, false);
            LOG.info("[host] punch done sid={} result={}", sid,
                    picked != null ? picked.type() : "none (조인자 쪽 경로로 붙을 수 있다)");
        } catch (Exception e) {
            LOG.warn("[host] negotiation failed sid={}: {}", sid, e.getMessage());
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
        InetSocketAddress turn = TurnAllocation.parseUrl(P2PConfig.turnUrl());
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

    private String newTicket() {
        long now = System.currentTimeMillis();
        tickets.values().removeIf(exp -> exp < now); // 만료된 표를 치운다
        byte[] b = new byte[TICKET_BYTES];
        RNG.nextBytes(b);
        String hex = HexFormat.of().formatHex(b);
        tickets.put(hex, now + TICKET_TTL_MS);
        return hex;
    }

    /** 표를 <b>한 번만</b> 쓰게 꺼낸다 — 있고 만료 전이면 true. */
    private boolean takeTicket(String hex) {
        Long exp = tickets.remove(hex);
        return exp != null && exp >= System.currentTimeMillis();
    }

    /**
     * 스트림 맨 앞 접속 표를 읽어 확인한다({@link #tickets}). 이 연결의 <b>첫</b> 스트림이면 표를 소진해 연결을 확인 상태로 만들고,
     * 이후 스트림은 같은 표로 시작하는지만 본다. 틀리거나 시간 안에 안 오면 연결째 닫는다. (원본 1.4.3 그대로)
     *
     * @return 통과하면 true — 이때 스트림 입력은 표 16바이트를 소비한 상태다
     */
    private boolean admit(QuicStream stream, QuicConnection conn, AtomicReference<String> authed) {
        final boolean first = authed.get() == null;
        if (first && !unauth.tryAcquire()) {
            conn.close(); // 표를 못 낸 연결이 이미 너무 많다
            return false;
        }
        ScheduledFuture<?> kill = first ? watchdog.schedule(() -> conn.close(), TICKET_WAIT_MS, TimeUnit.MILLISECONDS) : null;
        try {
            byte[] t = stream.getInputStream().readNBytes(TICKET_BYTES);
            boolean ok = false;
            if (t.length == TICKET_BYTES) {
                String hex = HexFormat.of().formatHex(t);
                synchronized (authed) { // 첫 스트림 둘이 동시에 와도 한 번만 소진한다
                    String cur = authed.get();
                    ok = cur != null ? cur.equals(hex) : takeTicket(hex) && authed.compareAndSet(null, hex);
                }
            }
            if (!ok) {
                // 불청객이 로그를 도배하지 못하게 10초에 한 번만. 상대 주소는 남기지 않는다.
                long now = System.currentTimeMillis();
                if (now - lastRogueLog > 10_000) {
                    lastRogueLog = now;
                    LOG.warn("[quic-host] 접속 표가 없거나 틀린 연결을 끊는다");
                }
                conn.close();
            }
            return ok;
        } catch (IOException e) {
            return false; // 시간 안에 표가 안 와서 연결이 닫혔다
        } finally {
            if (kill != null) kill.cancel(false);
            if (first) unauth.release();
        }
    }

    private void bridge(QuicStream stream, Peer peer, QuicConnection conn, AtomicReference<String> authed) {
        // 표 확인이 MC 서버로 다이얼·터널 등록보다 먼저 — 표 없는 연결은 서버에 닿지 않는다
        if (!admit(stream, conn, authed)) return;
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

        /** MC는 접속자당 TCP 하나 = 스트림 하나지만 재접속 중 겹칠 수 있어 여유를 둔다. 한 명이 스레드·MC 연결을 무한정 못 만들게 16(원본 1.4.3). */
        @Override public int maxConcurrentPeerInitiatedBidirectionalStreams() { return 16; }
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
            // 양쪽 중 하나라도 relay면 중계 — 출처가 TURN 서버이거나, 우리가 그 상대에게 allocation으로 보내고 있거나
            boolean relayed = ip != null && (isTurnServer(ip) || (agent != null && agent.sendsViaRelayTo(remote)));
            Peer peer = new Peer(key, relayed);
            if (key == null) {
                LOG.warn("[host] 상대 주소를 못 잡았다 — 이 연결엔 IP 복원이 적용되지 않는다");
            } else if (!relayed) {
                // 우리 쪽 채널 바인딩이 punch와 나란히 돌아 연결이 먼저 성립할 수 있다 — 2초 뒤 한 번 더 본다
                worker.execute(() -> {
                    try {
                        TimeUnit.SECONDS.sleep(2);
                    } catch (InterruptedException e) {
                        return;
                    }
                    QuicIce a2 = ice;
                    if (a2 != null && a2.sendsViaRelayTo(remote)) {
                        LOG.info("[host] connection type changed: direct -> relay");
                        peer.markRelayed();
                    }
                });
            }
            LOG.info("[host] QUIC connection accepted (relay={})", relayed);

            // 이 연결이 확인받은 표(hex) — 첫 스트림이 정한다. 이후 스트림은 같은 표로 시작해야 한다.
            AtomicReference<String> authed = new AtomicReference<>();
            return new ApplicationProtocolConnection() {
                @Override
                public void acceptPeerInitiatedStream(QuicStream stream) {
                    worker.execute(() -> bridge(stream, peer, conn, authed));
                }
            };
        }
    }
}
