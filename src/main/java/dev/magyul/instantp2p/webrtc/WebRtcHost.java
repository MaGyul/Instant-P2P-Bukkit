package dev.magyul.instantp2p.webrtc;

import dev.magyul.instantp2p.Utils;
import dev.magyul.instantp2p.core.P2PCore;
import dev.magyul.instantp2p.tunnel.TunnelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.DataChannelCallback;
import tel.schich.libdatachannel.IceState;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.PeerConnectionConfiguration;
import tel.schich.libdatachannel.SessionDescriptionType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java 네이티브 WebRTC 호스트 — VILLASframework signaling 프로토콜.
 * <p>
 * 서버(villas-signaling)는 경로 기반 세션 릴레이만 제공하므로
 * 방(roomId) 운영은 다음 2단계 구조로 구현한다:
 * <ol>
 *   <li><b>로비 세션</b> {@code /{roomId}}: 호스트가 peer "h####"로 상주.
 *       조인자는 peer "j{sid}"로 잠깐 접속해 자신을 알린다.
 *       서버가 브로드캐스트하는 control 메시지(peer 목록)로 호스트가 조인을 감지.</li>
 *   <li><b>페어 세션</b> {@code /{roomId}-{sid}}: 조인자별 전용 1:1 세션.
 *       조인자(offer/DataChannel 생성) ↔ 호스트(answer)가 SDP/ICE를 교환.
 *       릴레이 메시지에 발신자 정보가 없으므로 반드시 2인 세션으로 격리.</li>
 * </ol>
 * WebRTC 세션 수립 후 흐름:
 * 첫 데이터 수신 시 target TCP dial(5s) → 양방향 파이프, 16MB 백프레셔,
 * DataChannel open 10초 타임아웃, target 프로브(1s) 후 진행.
 * ICE 서버는 시그널링 서버가 relays(servers) 메시지로 내려주면 그것을,
 * 없으면 P2PConfig 기본값(coturn)을 사용한다.
 */
public class WebRtcHost {

    private static final Logger LOG = LoggerFactory.getLogger("webrtc-host");

    private static final long   INITIAL_BACKOFF_MS   = 1_000;
    private static final long   MAX_BACKOFF_MS       = 30_000;
    private static final int    PROBE_TIMEOUT_MS     = 1_000;
    private static final int    HANDSHAKE_TIMEOUT_MS = 10_000; // DataChannel open 한도
    private static final int    OFFER_TIMEOUT_MS     = 20_000; // 페어 세션에서 OFFER 대기 한도
    private static final int    DIAL_TIMEOUT_MS      = 5_000;
    // 버퍼 한도는 P2PConfig에서 관리 — 지연/처리량 트레이드오프 근거와
    // -Dkfcudp.pipe.* 되돌리기 방법은 그쪽 주석 참고.
    private static final long   DC_BUF_HIGH          = P2PConfig.DC_BUF_HIGH;
    private static final long   DC_BUF_LOW           = P2PConfig.DC_BUF_LOW; // 이하로 빠지면 송신 재개

    // ── 인스턴스 필드 ─────────────────────────────────────────────────────────
    private final P2PCore core;
    /** 로컬 다이얼 소켓 → 접속자 식별자. 플랫폼 쪽 IP 복원(TunnelInjector)이 조회한다. */
    private final TunnelRegistry tunnels;
    private final String roomId;
    private final String targetHost;
    private final int    targetPort;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile WebSocketClient lobbyWs;

    /** sid → 진행 중인 페어 시그널링 */
    private final Map<String, PairSignal> pairs = new ConcurrentHashMap<>();
    /** 처리한 조인 알림 (로비 peer name → 시각) */
    private final Map<String, Long> handledJoins = new ConcurrentHashMap<>();
    /** 서버가 내려준 TURN/STUN relays (없으면 P2PConfig 기본값 사용) */
    private volatile List<String[]> serverRelays = List.of();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "webrtc-host-timer");
                t.setDaemon(true);
                return t;
            });
    private final ExecutorService worker =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "webrtc-host-worker");
                t.setDaemon(true);
                return t;
            });

    /** PeerConnection/DataChannel 네이티브 정리 전용 (releaseNative 참고) */
    private final ExecutorService nativeCloser =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "webrtc-host-closer");
                t.setDaemon(true);
                return t;
            });

    private volatile long backoffMs = INITIAL_BACKOFF_MS;
    private volatile boolean signalingDown = false;
    /** 연속 실패 횟수 — 클래스 아래 scheduleReconnect 주석 참고: 한 번 순단으로는
     * 방장에게 경고를 띄우지 않는다. */
    private volatile int consecutiveFailures = 0;

    public WebRtcHost(P2PCore core, String roomId, String target) {
        this.core = core;
        this.tunnels = core.tunnels();
        this.roomId = roomId;
        int colon = target.lastIndexOf(':');
        if (colon < 0) throw new IllegalArgumentException("invalid target: " + target);
        this.targetHost = target.substring(0, colon);
        this.targetPort = Integer.parseInt(target.substring(colon + 1));
    }

    // ── 라이프사이클 ──────────────────────────────────────────────────────────

    /** 로비 접속 시작. 접속/재접속은 백그라운드에서 진행되며 즉시 반환. */
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        // libdatachannel 네이티브는 WebRtcBridge.ensureNativeLoaded()에서 한 번만 로드한다.
        LOG.info("[host] WebRTC Host: room={} target={}:{}", roomId, targetHost, targetPort);
        worker.execute(this::connectLobby);
    }

    public void close() {
        if (!running.compareAndSet(true, false)) return;
        LOG.info("[host] Closing");
        for (PairSignal p : pairs.values()) p.close();
        pairs.clear();
        handledJoins.clear();
        WebSocketClient ws = lobbyWs;
        if (ws != null) ws.close();
        scheduler.shutdownNow();
        worker.shutdownNow();
        // 세션들이 넘긴 네이티브 정리를 마저 끝낸다 (서버 종료 시 PeerConnection 누수 방지)
        nativeCloser.shutdown();
        try {
            if (!nativeCloser.awaitTermination(3, TimeUnit.SECONDS)) {
                LOG.warn("[host] native cleanup did not finish in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── 로비 세션 (조인 감지) ─────────────────────────────────────────────────

    private void connectLobby() {
        if (!running.get()) return;
        // peer 이름은 접속마다 유니크하게 — 서버는 같은 이름의 재접속을
        // "peer is already connected"로 거부하므로 (연결 유실 직후 재접속 대비)
        String peerName = PeerNames.lobbyHost(
                java.util.concurrent.ThreadLocalRandom.current().nextInt(0x10000, 0x100000));
        WebSocketClient ws = new WebSocketClient(
                P2PConfig.SIGNALING_URL + "/" + roomId + "/" + peerName) {
            @Override public void onConnected() {
                backoffMs = INITIAL_BACKOFF_MS;
                consecutiveFailures = 0;
                send(VillasMsg.hello()); // 서버가 최초 1회 signals 메시지를 요구함
                LOG.info("[host] lobby joined: room={}", roomId);
                if (signalingDown) {
                    signalingDown = false;
                    notifyHost("instant-p2p.msg.signaling_recovered");
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
        lobbyWs = ws;
        try {
            ws.connect();
        } catch (Exception e) {
            LOG.warn("[host] Signaling connect failed: {}", e.toString());
            scheduleReconnect();
            return;
        }
        // 접속하는 사이 방이 닫혔으면 방금 붙은 로비 연결도 닫는다 — 안 닫으면 닫힌 방의
        // 호스트가 로비에 남아 조인자들이 응답 없는 호스트에 붙으려 한다.
        if (!running.get()) ws.close();
    }

    private void scheduleReconnect() {
        if (!running.get()) return;
        // 초대코드를 발급했는데 실제로는 시그널링에 못 붙는 상태로 계속 재시도만
        // 하고 있으면 방장은 그걸 알 방법이 없다 — 한 번만 알려준다(재시도마다 스팸 X).
        // 다만 순간적인 순단 한 번으로는 안 띄운다 — 연속 2번 실패해야(=최소
        // INITIAL_BACKOFF_MS만큼은 계속 안 됐다는 뜻) 진짜 문제로 보고 알린다.
        // 예전엔 첫 끊김부터 곧장 경고를 띄워서, 금방 스스로 재접속되는 순단조차
        // "이유 없이 시그널링 연결 실패"로 보였다.
        consecutiveFailures++;
        if (!signalingDown && consecutiveFailures >= 2) {
            signalingDown = true;
            notifyHost("instant-p2p.msg.signaling_unreachable");
        }
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        LOG.info("[host] Signaling reconnect in {}ms", delay);
        try {
            scheduler.schedule(this::connectLobby, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {}
    }

    private void notifyHost(String translationKey) {
        core.platform().notifyAdmins(translationKey);
    }

    private void handleLobby(String json) {
        if (VillasMsg.has(json, "servers")) {
            updateRelays(json);
        }
        if (!VillasMsg.has(json, "control")) return;

        long now = System.currentTimeMillis();
        handledJoins.values().removeIf(t -> now - t > 600_000);

        for (String[] p : VillasMsg.peers(json)) {
            String name = p[0], remote = p[1];
            // 조인 알림: peer 이름 "j" + 강제 여부 글자('r'/'d') + 16 hex, 현재 연결 중(remote 존재).
            // 강제 글자는 WebRtcClient.announceJoin()이 실어 보낸다 — 조인자가 이미 중계
            // 강제 중이면 호스트가 굳이 1차(직결 전용)부터 시도해서 실패시킬 필요 없이
            // 처음부터 릴레이 허용으로 응답할 수 있다(PairSignal.handlePair 참고).
            if (remote == null) continue;
            PeerNames.Join join = PeerNames.parseJoin(name);
            if (join == null) continue;
            if (handledJoins.putIfAbsent(name, now) != null) continue;

            // "jq" = 입장 전 확인(RoomMembersProbe) — 연결하지 않고 지금 접속자 해시만 알려주고 끝낸다.
            if (join.probe()) {
                String probeSid = join.sid();
                worker.execute(() -> sendMembers(probeSid));
                continue;
            }

            boolean clientRelayForced = join.relayForced();
            String sid = join.sid();
            String clientIp = remote.contains(":") ? remote.substring(0, remote.lastIndexOf(':')) : remote;
            // IP는 로그에 남기지 않는다 — 방장이 버그 리포트로 로그를 그대로
            // 공유하면 조인자의 실제 IP가 텍스트로 박제된다. sid로 세션 추적 충분.
            LOG.info("[host] join detected: sid={} clientRelayForced={}", sid, clientRelayForced);

            worker.execute(() -> {
                // target 프로브 후 진행 (실패 시 조인자는 타임아웃)
                if (!probeTarget()) {
                    LOG.warn("[host] target unreachable; ignoring join sid={}", sid);
                    return;
                }
                PairSignal pair = new PairSignal(sid, clientIp, clientRelayForced);
                PairSignal prev = pairs.put(sid, pair);
                if (prev != null) prev.close();
                pair.open();
            });
        }
    }

    /** 입장 전 확인에 답한다 — 확인하는 쪽이 먼저 열어 둔 페어 세션에 잠깐 붙어 접속자 해시만 보내고 바로 나간다.
     * 서버는 description.type을 그대로 중계하고, 끊는 프레임보다 먼저 온 메시지를 먼저 처리한다. */
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

    private void updateRelays(String json) {
        List<String[]> servers = VillasMsg.servers(json);
        if (!servers.isEmpty()) {
            serverRelays = servers;
            LOG.info("[host] using {} relay(s) from signaling server", servers.size());
        }
    }

    private boolean probeTarget() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(targetHost, targetPort), PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * target으로 {@link SocketChannel}을 연결한다 (타임아웃 {@link #DIAL_TIMEOUT_MS}).
     * <p>
     * {@code SocketChannel}은 블로킹 connect에 타임아웃을 걸 수 없어
     * (예전 {@code Socket.connect(addr, timeout)}에 해당하는 게 없다) 논블로킹으로
     * 연결한 뒤 Selector로 기다린다. 채널이어야 direct 버퍼 read/writev를 쓸 수 있다.
     */
    private SocketChannel dialTarget() throws IOException {
        SocketChannel sc = SocketChannel.open();
        try {
            sc.configureBlocking(false);
            // target이 127.0.0.1이라 대개 여기서 즉시 true가 나와 셀렉터를 안 탄다.
            if (!sc.connect(new InetSocketAddress(targetHost, targetPort))) {
                try (Selector sel = Selector.open()) {
                    SelectionKey key = sc.register(sel, SelectionKey.OP_CONNECT);
                    try {
                        long deadline = System.nanoTime() + DIAL_TIMEOUT_MS * 1_000_000L;
                        while (!sc.finishConnect()) {
                            long remainMs = (deadline - System.nanoTime()) / 1_000_000L;
                            if (remainMs <= 0)
                                throw new SocketTimeoutException(
                                        "dial timeout " + DIAL_TIMEOUT_MS + "ms");
                            sel.select(remainMs);
                            sel.selectedKeys().clear();
                        }
                    } finally {
                        // configureBlocking(true)는 채널에 유효한 키가 남아 있으면
                        // IllegalBlockingModeException을 던진다. 셀렉터를 닫아도
                        // 무효화되지만, 명시적으로 취소해 순서 의존을 없앤다.
                        key.cancel();
                    }
                }
            }
            sc.configureBlocking(true);
            return sc;
        } catch (IOException e) {
            try { sc.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    /**
     * ICE 서버 구성: 시그널링 서버 relays 우선, 없으면 P2PConfig 기본값.
     * @param allowRelay false면 TURN 후보를 아예 안 만든다 — 조인자의 1차(직결 전용)
     *                    OFFER에 맞춰 이쪽도 같은 단계로 맞춰야 릴레이 pair가 안 생긴다.
     *                    {@link PairSignal#handlePair} 참고.
     */
    private PeerConnectionConfiguration buildConfig(boolean allowRelay) {
        // webrtc-java 시절의 -Dkfcudp.ice.anyaddress(portAllocatorConfig)는 libdatachannel에 대응 옵션이 없다.
        return IceConfig.build(serverRelays, "host", allowRelay, core.settings().relayOnly());
    }

    // ── 페어 세션 (조인자별 1:1 시그널링) ────────────────────────────────────

    private class PairSignal {
        final String sid;
        final String clientIp;
        /** 이 조인자가 이미 중계 강제 중이었는지(handleLobby가 "j" peer 이름에서 읽어옴). */
        final boolean clientRelayForced;
        volatile WebSocketClient ws;
        volatile HostSession session;
        volatile boolean closed;
        /** 직전에 처리한 OFFER SDP — 재전달(중복) 판별용. */
        volatile String lastOfferSdp;

        PairSignal(String sid, String clientIp, boolean clientRelayForced) {
            this.sid = sid;
            this.clientIp = clientIp;
            this.clientRelayForced = clientRelayForced;
        }

        void open() {
            if (!running.get()) return;
            // peer 이름의 "h" 다음 글자에 이 방의 중계 강제 여부를 실어 보낸다 — 조인자가
            // 호스트 등장을 감지하는 바로 그 control.peers 메시지에서 같이 읽어가므로
            // 새 메시지 왕복 없이 공짜로 전달된다. 조인자는 이 값을 보고 자기 쪽이
            // 중계 강제가 아니어도 1차(직결 전용) 시도를 건너뛸 수 있다 — 호스트가 이미
            // 릴레이 전용이면 1차는 어차피 실패가 확정이므로(WebRtcClient.acceptAndBridge 참고).
            WebSocketClient w = createClient();
            ws = w;
            try {
                w.connect();
            } catch (Exception e) {
                LOG.warn("[host] pair connect failed sid={}: {}", sid, e.toString());
                close();
                return;
            }
            // OFFER 대기 타임아웃
            try {
                scheduler.schedule(() -> {
                    if (!closed && session == null) {
                        LOG.warn("[host] no OFFER within {}ms; closing pair sid={}", OFFER_TIMEOUT_MS, sid);
                        close();
                    }
                }, OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {}
        }

        private WebSocketClient createClient() {
            boolean relayOnly = core.settings().relayOnly();
            return new WebSocketClient(P2PConfig.SIGNALING_URL + "/"
                    + PeerNames.pairRoom(roomId, sid) + "/" + PeerNames.pairHost(relayOnly, sid)) {
                @Override public void onConnected() {
                    send(VillasMsg.hello());
                    LOG.info("[host] pair session joined: sid={}", sid);
                }
                @Override public void onMessage(String type, String json) {
                    handlePair(json);
                }
                @Override public void onDisconnected() {
                    HostSession s = session;
                    if (s == null || !s.dcOpened) {
                        LOG.warn("[host] pair signaling lost before establishment sid={}", sid);
                        PairSignal.this.close();
                    }
                }
            };
        }

        void handlePair(String json) {
            if (VillasMsg.has(json, "servers")) {
                updateRelays(json);
            }
            if (VillasMsg.has(json, "description")) {
                String desc = VillasMsg.object(json, "description");
                if (desc == null) return;
                String sdpType = VillasMsg.field(desc, "type");
                String sdp     = VillasMsg.field(desc, "spd");
                if (!"offer".equalsIgnoreCase(sdpType) || sdp == null || sdp.isEmpty()) return;
                if (sdp.equals(lastOfferSdp)) return; // 같은 OFFER 재전달 — 무시

                // 조인자는 1차로 직결 전용(TURN 없음)을 시도했다가 실패하면 2차로
                // 릴레이 포함해서 새 OFFER를 다시 보낸다(WebRtcClient.attemptConnection
                // 참고) — 이쪽도 같은 단계에 맞춰 buildConfig를 다시 해야
                // 릴레이 pair가 양쪽 다 안 만들어지거나 양쪽 다 만들어지거나로 맞는다.
                HostSession old = session;
                boolean allowRelay;
                // 조인자가 이미 중계 강제 중이라고 알려온 경우 — 최초 OFFER부터
                // 1차(직결 전용)를 건너뛰고 바로 릴레이 허용으로 응답한다. 조인자도
                // 이 사실을 알고 1차를 안 거치고 온 OFFER이므로(WebRtcClient
                // acceptAndBridge의 skipToRelayOnly 참고) 여기서도 굳이 "최초 OFFER =
                // 1차 직결 전용"으로 응답했다가 실패시키고 재협상을 기다릴 필요가 없다.
                // 최초 OFFER = 1차(직결 전용) 시도
                if (old != null) {
                    if (old.dcOpened) return; // 이미 연결 성사 — 재전달/지연 메시지로 보고 무시
                    LOG.info("[host] renegotiation OFFER received sid={}", sid);
                    old.close(); // 페어 시그널링(this)은 유지, WebRTC 세션만 정리
                    allowRelay = true; // 재협상 = 조인자의 1차 시도 실패 = 2차(릴레이 허용)
                } else allowRelay = clientRelayForced;
                lastOfferSdp = sdp;
                LOG.info("[host] OFFER received sid={} (allowRelay={})", sid, allowRelay);
                final PairSignal self = this;
                final boolean finalAllowRelay = allowRelay;
                worker.execute(() -> startSession(self, sdp, finalAllowRelay));
            } else if (VillasMsg.has(json, "candidate")) {
                String cand = VillasMsg.object(json, "candidate");
                if (cand == null) return;
                String spd = VillasMsg.field(cand, "spd");
                String mid = VillasMsg.field(cand, "mid");
                if (spd == null) return;
                HostSession s = session;
                if (s != null) {
                    s.addRemoteIce(spd, mid != null ? mid : "0");
                }
            }
        }

        void send(String json) {
            if (json.length() > 4000) {
                LOG.warn("[host] outgoing signaling message near server limit ({} bytes)", json.length());
            }
            WebSocketClient w = ws;
            if (w != null) w.send(json);
        }

        void close() {
            if (closed) return;
            closed = true;
            HostSession s = session;
            session = null;
            if (s != null) s.close();
            WebSocketClient w = ws;
            ws = null;
            if (w != null) w.close();
            pairs.remove(sid, this);
        }
    }

    // ── WebRTC 세션 ───────────────────────────────────────────────────────────

    private void startSession(PairSignal pair, String offerSdp, boolean allowRelay) {
        if (!running.get() || pair.closed) return;
        HostSession session = new HostSession(pair);
        pair.session = session;
        try {
            session.begin(offerSdp, allowRelay);
        } catch (Exception e) {
            LOG.warn("[host] New WebRTC session failed: {}", e.toString());
            pair.close();
        }
    }

    private class HostSession {
        private final PairSignal pair;
        private final String sid;
        private final String clientIp;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private volatile PeerConnection    peerConnection;
        private volatile DataChannel       dataChannel;
        private volatile SocketChannel     tcpChannel;
        private volatile BatchPipe.Writer  tcpWriter;
        private volatile int               tunnelLocalPort = -1;
        volatile boolean dcOpened = false;

        private final CountDownLatch dcOpenLatch = new CountDownLatch(1);
        private final Object dialLock = new Object();
        /** 백프레셔 대기/웨이크업 (onBufferedAmountLow 이벤트 기반) */
        private final Object bpLock = new Object();

        /** remote description 적용 전에 도착한 후보 {candidate, mid} */
        private final List<String[]> queuedIce = new ArrayList<>();
        private volatile boolean remoteSet = false;

        /** DataChannel open 처리를 한 번만 하기 위한 플래그 (onOpen/isOpen 양쪽에서 들어올 수 있음) */
        private final AtomicBoolean openHandled = new AtomicBoolean(false);
        /** 이 세션이 TURN 후보를 쓸 수 있었는지 — relay 판별에 쓴다 */
        private volatile boolean allowRelay;
        /** 협상된 원격 max-message-size와 BATCH_MAX 중 작은 값. open 시점에 확정. */
        private volatile int maxSendSize = BatchPipe.BATCH_MAX;
        private final WebRtcStats.RelayTracker relayTracker = new WebRtcStats.RelayTracker();
        /** 터널 등록 전에 relay 판별이 끝났을 수 있어 결과를 들고 있다가 등록 시 반영한다 */
        private volatile Boolean relayResult;

        // 버킷용으로 추가
        private volatile TunnelRegistry.Tunnel tunnel;

        HostSession(PairSignal pair) {
            this.pair = pair;
            this.sid = pair.sid;
            this.clientIp = pair.clientIp;
        }

        /*
         * 콜백 스레드 주의 (libdatachannel-java):
         *  - createPeer(config)의 기본 executor는 Runnable::run이라 모든 콜백이 libdatachannel
         *    네이티브 스레드에서 인라인으로 실행된다. 스레드 풀 executor로 바꾸지 말 것 —
         *    onMessage 순서가 뒤섞여 MC 스트림이 깨지고, 아래 수신 버퍼가 해제된 뒤 읽히게 된다.
         *  - 콜백 안에서 PeerConnection/DataChannel.close()를 직접 부르지 않는다. close()는
         *    진행 중인 콜백이 끝나길 기다리므로 네이티브 정리는 nativeCloser로 넘긴다.
         */
        void begin(String offerSdp, boolean allowRelay) {
            this.allowRelay = allowRelay;
            PeerConnection pc = PeerConnection.createPeer(buildConfig(allowRelay));
            peerConnection = pc;

            // answer는 setRemoteDescription(offer) 후 자동 협상으로 만들어져 이 콜백으로 온다.
            // (webrtc-java의 createAnswer/setLocalDescription 단계가 없다)
            pc.onLocalDescription.register((p, sdp, type) -> {
                if (closed.get() || type != SessionDescriptionType.ANSWER) return;
                pair.send(VillasMsg.description("answer", sdp));
                LOG.info("[host] ANSWER sent sid={}", sid);
            });

            pc.onLocalCandidate.register((p, candidate, mid) -> {
                if (closed.get()) return;
                // libwebrtc(조인자)가 보내는 형식과 맞춘다: "a=" 없이 "candidate:..."
                String c = candidate.startsWith("a=") ? candidate.substring(2) : candidate;
                relayTracker.observe(c);
                pair.send(VillasMsg.candidate(c, mid != null && !mid.isEmpty() ? mid : "0"));
            });

            pc.onIceStateChange.register((p, state) -> {
                if (closed.get()) return;
                // FAILED에서만 종료, DISCONNECTED는 자동 복구 대기
                if (state == IceState.RTC_ICE_FAILED) {
                    LOG.warn("[host] ICE failed sid={} (allowRelay={})", sid, allowRelay);
                    onAttemptFailed(allowRelay);
                } else if (state == IceState.RTC_ICE_DISCONNECTED) {
                    LOG.warn("[host] ICE disconnected sid={}, waiting for reconnect...", sid);
                }
            });

            pc.onDataChannel.register((p, channel) -> {
                if (closed.get()) return;
                LOG.info("[host] DataChannel attached sid={} label={}", sid, channel.label());
                dataChannel = channel;
                setupDataChannel(channel);
            });

            try {
                pc.setRemoteDescription(offerSdp, SessionDescriptionType.OFFER);
            } catch (Exception e) {
                LOG.warn("[host] setRemoteDescription failed sid={}: {}", sid, e.toString());
                pair.close();
                return;
            }
            flushQueuedIce();

            try {
                scheduler.schedule(() -> {
                    if (!closed.get() && dcOpenLatch.getCount() > 0) {
                        LOG.warn("[host] handshake timeout sid={} (allowRelay={})", sid, allowRelay);
                        onAttemptFailed(allowRelay);
                    }
                }, HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {}
        }

        /**
         * 이번 시도(직결 전용/릴레이 포함)가 ICE 실패나 핸드셰이크 타임아웃으로 끝났을 때.
         * allowRelay=false(1차, 직결 전용)면 조인자가 알아서 릴레이 포함 재협상 OFFER를
         * 다시 보낼 것이므로 — 이 세션만 조용히 정리하고 페어 시그널링(pair)은 살려둔다.
         * allowRelay=true(2차, 최종)면 더 이상 재시도가 없으므로 진짜 실패로 취급한다.
         */
        private void onAttemptFailed(boolean allowRelay) {
            // 이미 성사됐던 세션이 끊긴 경우 조인자는 재협상을 보내지 않는다 — 세션만 닫고 페어를
            // 남기면 페어 시그널링 연결과 pairs 항목이 방이 닫힐 때까지 샌다.
            if (allowRelay || dcOpened) {
                if (!dcOpened) notifyHostFailure();
                pair.close();
            } else {
                close();
            }
        }

        /**
         * 접속 시도가 끝내 연결로 안 이어졌을 때 방장 채팅으로만 알림 (조인자는 자기 화면에서 이미 봄).
         * IP는 넣지 않는다 — 방장이 스크린샷을 공유하면 그대로 노출된다.
         */
        private void notifyHostFailure() {
            core.platform().notifyAdmins("instant-p2p.msg.guest_connect_failed");
        }

        /**
         * 직결(Direct)/중계(TURN) 여부를 기록해 둔다. open 직후 한 번, 2초 뒤 한 번 더 읽어서
         * 값이 바뀌면 덮어쓴다 (선택된 경로는 open 이후에도 바뀔 수 있다).
         * open 시점엔 아직 터널이 없을 수 있어(터널은 첫 데이터 수신 때 생긴다) 결과를
         * relayResult에 들고 있다가 등록 시 반영한다 — applyRelay 참고.
         */
        private void notifyConnectionType() {
            Boolean relay = relayTracker.usesRelay(peerConnection, allowRelay);
            if (relay != null) {
                applyRelay(relay);
                LOG.info("[host] connection type (initial) sid={}: relay={}", sid, relay);
            }
            try {
                scheduler.schedule(() -> {
                    if (closed.get()) return;
                    Boolean recheck = relayTracker.usesRelay(peerConnection, allowRelay);
                    if (recheck == null) return;
                    Boolean prev = relayResult;
                    if (prev != null && !recheck.equals(prev)) {
                        LOG.warn("[host] connection type changed on recheck sid={}: {} -> {}", sid, prev, recheck);
                    }
                    applyRelay(recheck);
                }, 2, TimeUnit.SECONDS);
            } catch (RejectedExecutionException ignored) {}
        }

        private void applyRelay(boolean relay) {
            relayResult = relay;
            TunnelRegistry.Tunnel t = tunnel;
            if (t != null) tunnels.setRelay(t, relay);
        }

        void addRemoteIce(String candidate, String mid) {
            relayTracker.observe(candidate);
            synchronized (queuedIce) {
                if (!remoteSet) {
                    queuedIce.add(new String[]{candidate, mid});
                    return;
                }
            }
            applyRemoteIce(candidate, mid);
        }

        private void flushQueuedIce() {
            List<String[]> toApply;
            synchronized (queuedIce) {
                remoteSet = true;
                toApply = new ArrayList<>(queuedIce);
                queuedIce.clear();
            }
            for (String[] c : toApply) applyRemoteIce(c[0], c[1]);
        }

        private void applyRemoteIce(String candidate, String mid) {
            PeerConnection pc = peerConnection;
            if (pc == null || closed.get()) return;
            try {
                pc.addRemoteCandidate(candidate, mid);
            } catch (Exception e) {
                // 해석 못 하는 후보(mDNS .local 등)는 하나 빠져도 나머지로 연결된다
                LOG.debug("[host] remote candidate rejected sid={}: {} ({})", sid, candidate, e.toString());
            }
        }

        // ── 데이터 파이프 ─────────────────────────────────────────────────

        private void setupDataChannel(DataChannel channel) {
            try {
                channel.bufferedAmountLowThreshold((int) DC_BUF_LOW);
            } catch (Exception e) {
                LOG.warn("[host] bufferedAmountLowThreshold failed sid={}: {}", sid, e.toString());
            }
            // 하강 에지(threshold 초과 → 이하)에서만 불린다
            channel.onBufferedAmountLow.register(c -> {
                synchronized (bpLock) { bpLock.notifyAll(); }
            });
            channel.onOpen.register(this::onChannelOpen);
            channel.onClosed.register(c -> {
                LOG.info("[host] DataChannel closed sid={}", sid);
                pair.close();
            });
            channel.onError.register((c, error) -> {
                LOG.warn("[host] DataChannel error sid={}: {}", sid, error);
                pair.close();
            });
            // 수신 버퍼는 네이티브 메모리를 그대로 감싼 것이라 이 콜백이 끝나면 해제된다.
            // onPeerData → BatchPipe.Writer.feed()가 콜백 안에서 동기적으로 청크에 복사하므로 안전하다.
            // (버퍼 참조를 큐에 넣거나 다른 스레드로 넘기는 구조로 바꾸면 안 된다)
            channel.onMessage.register(DataChannelCallback.Message.handleBinary((c, buffer) -> onPeerData(buffer)));

            // 원격이 만든 채널은 콜백 시점에 이미 open일 수 있다
            if (channel.isOpen()) onChannelOpen(channel);
        }

        private void onChannelOpen(DataChannel channel) {
            if (closed.get() || !openHandled.compareAndSet(false, true)) return;
            try {
                maxSendSize = Math.max(1, Math.min(BatchPipe.BATCH_MAX, channel.maxMessageSize()));
            } catch (Exception e) {
                maxSendSize = 64 * 1024; // 협상값을 못 읽으면 SDP 미기재 시 기본값으로 보수적으로
            }
            dcOpened = true;
            dcOpenLatch.countDown();
            notifyConnectionType();
            LOG.info("[host] DataChannel open; waiting for first data sid={} (maxMessageSize={})", sid, maxSendSize);
        }

        private void onPeerData(ByteBuffer data) {
            if (closed.get()) return;
            BatchPipe.Writer w = tcpWriter;
            if (w == null) {
                synchronized (dialLock) {
                    if (closed.get()) return;
                    w = tcpWriter;
                    if (w == null) {
                        LOG.info("[host] first data received; dialing target {}:{} sid={}",
                                targetHost, targetPort, sid);
                        try {
                            SocketChannel sock = dialTarget();
                            sock.setOption(StandardSocketOptions.TCP_NODELAY, true);
                            sock.setOption(StandardSocketOptions.SO_RCVBUF, 512 * 1024);
                            sock.setOption(StandardSocketOptions.SO_SNDBUF, 512 * 1024);
                            tcpChannel = sock;
                            // MC 서버는 이 소켓의 로컬 주소를 조인자의 "IP:포트"로 본다.
                            // 실제 원격 식별자를 레지스트리에 매핑해 둔다 (TunnelInjector가 교체).
                            tunnelLocalPort =
                                    ((InetSocketAddress) sock.getLocalAddress()).getPort();
                            this.tunnel = tunnels.register(sock.getLocalAddress(), this.clientIp, this.sid);
                            Boolean r = relayResult;
                            if (r != null) tunnels.setRelay(this.tunnel, r);
                            // DC→TCP: 전담 writer 스레드가 연속 청크를 writev 1회로 배칭
                            w = new BatchPipe.Writer(sock,
                                    "webrtc-host-tcpw-" + sid,
                                    e -> {
                                        if (!closed.get())
                                            LOG.warn("[host] TCP write failed sid={}: {}", sid, e.getMessage());
                                        pair.close();
                                    });
                            tcpWriter = w;
                            Thread t = new Thread(() -> forwardTcpToWebRtc(sock),
                                    "webrtc-host-tcp-" + sid);
                            t.setDaemon(true);
                            t.setPriority(Thread.NORM_PRIORITY + 2); // 파이프 지연 최소화
                            t.start();
                        } catch (Exception e) {
                            LOG.warn("[host] Failed to dial target {}:{}: {}",
                                    targetHost, targetPort, e.getMessage());
                            notifyHostFailure();
                            pair.close();
                            return;
                        }
                    }
                }
            }
            try {
                w.feed(data); // 동기 복사. 큐 가득 시 블로킹 → SCTP 수신 윈도우로 배압
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pair.close();
            }
        }

        /** MC 서버 TCP → DataChannel. direct 버퍼 직접 read + 백프레셔. */
        private void forwardTcpToWebRtc(SocketChannel sock) {
            ByteBuffer buf = ByteBuffer.allocateDirect(BatchPipe.BATCH_MAX);
            try {
                while (true) {
                    buf.clear();
                    int n = sock.read(buf);
                    if (n < 0) break;   // EOF
                    if (n == 0) continue;

                    DataChannel ch = dataChannel;
                    if (ch == null || closed.get() || !ch.isOpen()) break;

                    // 이벤트 기반 백프레셔: onBufferedAmountLow가 깨움 (50ms 안전 타임아웃)
                    while (ch.bufferedAmount() > DC_BUF_HIGH) {
                        if (closed.get() || !ch.isOpen()) return;
                        synchronized (bpLock) {
                            if (ch.bufferedAmount() > DC_BUF_HIGH) bpLock.wait(50);
                        }
                    }
                    if (closed.get()) break;

                    // sendMessage(ByteBuffer)는 position~limit 구간을 보내고 호출 중에 네이티브로
                    // 복사한다 (webrtc-java처럼 slice()할 필요 없음). 버퍼 위치는 움직이지 않으므로
                    // 직접 넘긴다. 원격 max-message-size를 넘지 않게 잘라서 보낸다.
                    buf.flip();
                    final int end = buf.limit();
                    final int max = maxSendSize;
                    while (buf.position() < end) {
                        int next = Math.min(end, buf.position() + max);
                        buf.limit(next);
                        ch.sendMessage(buf);
                        buf.position(next);
                        buf.limit(end);
                    }
                }
            } catch (Exception e) {
                if (!closed.get()) LOG.warn("[host] TCP read ended sid={}: {}", sid, e.getMessage());
            } finally {
                pair.close();
            }
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            dcOpenLatch.countDown();
            synchronized (bpLock) { bpLock.notifyAll(); } // 백프레셔 대기 해제
            BatchPipe.Writer w = tcpWriter;
            tcpWriter = null;
            if (w != null) w.close();
            if (tunnelLocalPort > 0) {
                tunnels.unregister(tunnel);
                this.tunnel = null;
                tunnelLocalPort = -1;
            }
            try { if (tcpChannel != null) tcpChannel.close(); } catch (Exception ignored) {}

            DataChannel dc = dataChannel;
            dataChannel = null;
            PeerConnection pc = peerConnection;
            peerConnection = null;
            releaseNative(dc, pc);
        }
    }

    /**
     * 네이티브 정리는 전용 스레드에서 한다. close()는 진행 중인 콜백이 끝나길 기다리는데,
     * 우리 close 경로는 그 콜백 안(onClosed/onError/onIceStateChange)에서 시작되는 경우가 많다.
     */
    private void releaseNative(DataChannel dc, PeerConnection pc) {
        if (dc == null && pc == null) return;
        Runnable r = () -> {
            // libdatachannel-java의 close()는 핸들을 먼저 지우고 나서 콜백을 해제하려다
            // "ID does not exist" 에러를 찍는다. 핸들이 살아 있을 때 먼저 비워둔다.
            if (dc != null) {
                quietly(dc.onOpen::deregisterAll, dc.onClosed::deregisterAll, dc.onError::deregisterAll,
                        dc.onMessage::deregisterAll, dc.onBufferedAmountLow::deregisterAll);
                try { dc.close(); } catch (Exception ignored) {}
            }
            if (pc != null) {
                quietly(pc.onLocalDescription::deregisterAll, pc.onLocalCandidate::deregisterAll,
                        pc.onIceStateChange::deregisterAll, pc.onDataChannel::deregisterAll);
                try { pc.close(); } catch (Exception ignored) {}
            }
        };
        try {
            nativeCloser.execute(r);
        } catch (RejectedExecutionException e) {
            r.run();
        }
    }

    private static void quietly(Runnable... actions) {
        for (Runnable a : actions) {
            try { a.run(); } catch (Exception ignored) {}
        }
    }
}
