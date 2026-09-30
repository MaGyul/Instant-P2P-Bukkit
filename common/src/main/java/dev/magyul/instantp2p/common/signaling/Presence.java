package dev.magyul.instantp2p.common.signaling;

import dev.magyul.instantp2p.common.core.HostController;
import dev.magyul.instantp2p.common.core.P2PCore;
import dev.magyul.instantp2p.common.core.P2PPlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 접속 상태 보고 — 가맹점 서버 전용. 가맹점 수정판(F3c 기준)의 {@code Presence}와 같은 요청을 1분마다 보낸다.
 * <p>
 * 가맹점 운영자 설명: 공개 방 정보 위조를 막고 운영에 쓰는 값이다(약관 3. 접속 상태). <b>가맹점 서버를 쓸 때만</b>(약관 동의 후) 보낸다.
 * <pre>
 *   GET /api/v1/presence?state=host|idle[&amp;room=코드&amp;members=이름:UUID,…]&amp;token=…&amp;uuid=…&amp;name=…&amp;v=server-버전&amp;mc=…&amp;hwid=…
 * </pre>
 * 방이 열려 있으면 {@code host}(방 코드는 {@code F-} 없는 원래 값, 접속자 최대 100명), 아니면 {@code idle}.
 * {@code v}는 가맹점 수정판({@code 1.4.3+F3c})과 달리 서버판 표기({@code server-1.2.0}) — 운영자가 서버판 방을 알아보게.
 * 응답은 보지 않는다(실패해도 조용히 넘어간다).
 */
public final class Presence {

    private static final Logger LOG = LoggerFactory.getLogger("Instant-P2P");
    private static final long INTERVAL_SEC = 60;
    private static final int MAX_MEMBERS = 100;
    private static final String VERSION = loadVersion();

    private final P2PCore core;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "instant-p2p-presence");
        t.setDaemon(true);
        return t;
    });

    public Presence(P2PCore core) {
        this.core = core;
    }

    public void start() {
        timer.scheduleAtFixedRate(this::tick, 5, INTERVAL_SEC, TimeUnit.SECONDS);
    }

    public void stop() {
        timer.shutdownNow();
    }

    private void tick() {
        try {
            if (P2PConfig.server() != SignalingServer.FRANCHISE) return;
            HostController host = core.host();
            String room = host.inviteCode();
            if (host.state() != HostController.RoomState.OPEN || room == null) {
                send("idle", null, null);
                return;
            }
            // 접속자 이름은 서버 스레드에서 읽고, 보내기(해시 계산·토큰)는 이 스레드로 돌아와서
            core.platform().runSync(() -> {
                String members = members();
                try {
                    timer.execute(() -> send("host", room, members));
                } catch (RejectedExecutionException ignored) {
                    // 서버 종료 중
                }
            });
        } catch (RuntimeException e) {
            LOG.debug("[presence] 상태 보고 실패(무시): {}", e.toString());
        }
    }

    /** 서버 스레드. {@code 이름:UUID}를 쉼표로 (가맹점 수정판과 같은 형식) */
    private String members() {
        P2PPlatform platform = core.platform();
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (UUID id : core.onlinePlayers()) {
            if (n++ >= MAX_MEMBERS) break;
            String name = platform.playerName(id);
            if (b.length() > 0) b.append(',');
            b.append(name != null ? name : "").append(':').append(id);
        }
        return b.toString();
    }

    private void send(String state, String room, String members) {
        if (P2PConfig.server() != SignalingServer.FRANCHISE) return; // 그사이 공식 서버로 바꿨다
        StringBuilder url = new StringBuilder(P2PConfig.signalingHttpUrl()).append("/api/v1/presence?state=").append(state);
        if (room != null) url.append("&room=").append(enc(room));
        if (members != null && !members.isEmpty()) url.append("&members=").append(enc(members));
        String token = core.account().publishTokenOrNull();
        if (token != null) url.append("&token=").append(enc(token));
        String uuid = core.account().uuid();
        if (uuid != null) url.append("&uuid=").append(enc(uuid));
        String name = core.account().name();
        if (name != null) url.append("&name=").append(enc(name));
        url.append("&v=").append(enc("server-" + VERSION));
        String mc = core.platform().minecraftVersion();
        url.append("&mc=").append(enc(mc != null ? mc : ""));
        url.append(HardwareId.query());
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString())).timeout(Duration.ofSeconds(8)).GET().build();
            // URL에 토큰·방 코드가 들어가므로 로그에 남기지 않는다
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).exceptionally(e -> null);
        } catch (RuntimeException e) {
            LOG.debug("[presence] 요청을 만들지 못했습니다: {}", e.toString());
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** 빌드 때 채운 서버판 버전 ({@code instant-p2p-server.properties}) */
    private static String loadVersion() {
        try (InputStream in = Presence.class.getResourceAsStream("/instant-p2p-server.properties")) {
            if (in == null) return "unknown";
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version", "unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }
}
