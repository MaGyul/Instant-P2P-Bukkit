package dev.magyul.instantp2p.common.signaling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 개발자·서포터·방송인 UUID 목록 — 시그널링 서버의 {@code GET /api/v1/roles}에서 받아온다(운영자가 roles.json을 고치면 다음 갱신 때 반영).
 * <p>
 * 응답은 시그널링 서버의 Ed25519 서명({@code X-Roles-Signature})이 맞아야 쓴다 — 평문 http라 중간자가 자기 UUID를 끼워 넣을 수 있다.
 * <p>
 * <b>폴링하지 않는다</b>(원본 개발자 요청). 호스트를 시작할 때 한 번({@link #refreshAsync}), 로그인 때 쿨다운 60초로
 * ({@link #refreshOnLogin}) 비동기로만 갱신한다. 실패하면 마지막 성공값을 그대로 쓴다. 로컬 파일 캐시는 두지 않는다.
 */
public final class Roles {

    private static final Logger LOGGER = LoggerFactory.getLogger("Instant-P2P");

    private static final Gson GSON = new GsonBuilder().create();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    /**
     * mc-signaling의 개인키(-roles-sign-key)로 서명한 응답만 믿는다 — SIGNALING_URL 기본값이
     * ws://(평문)라 이 REST 호출도 평문 http라서, 같은 네트워크의 중간자가 응답을 가로채 자기
     * UUID를 dev/streamer로 끼워 넣을 수 있다. 대칭키(HMAC)로는 못 막는다 — 클라이언트에 박아 넣는
     * 값은 jar를 풀면 누구나 꺼낼 수 있어 "비밀"이 아니기 때문. 그래서 개인키는 서버에만 두고
     * 공개키만 여기 박아, 서명 없는(혹은 검증 실패한) 응답은 통째로 버린다(이전 캐시 값 유지).
     * 서버가 아직 서명 안 하면(개인키 미설정) 이 검증에 걸려 새 값이 전혀 반영되지 않으니 —
     * roles.json을 실제로 쓰려면 서버에도 -roles-sign-key를 설정해야 한다.
     */
    private static final String PUBLIC_KEY_B64 = "bBrZee5a/YT/UyoXRY5DiHAhvRGfaeKtOekGVBhMEeo=";
    private static final byte[] ED25519_SPKI_PREFIX =
            {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
    private static final PublicKey SIGNING_KEY = loadPublicKey();

    private static volatile Set<UUID> dev = Set.of();
    private static volatile Set<UUID> supporter = Set.of();
    private static volatile Set<UUID> streamer = Set.of();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "instant-p2p-roles");
        t.setDaemon(true);
        return t;
    });

    private static final long LOGIN_REFRESH_COOLDOWN_MS = 60_000;
    private static final AtomicLong lastLoginRefresh = new AtomicLong();

    private Roles() {}

    public static boolean isDev(UUID id) {
        return id != null && dev.contains(id);
    }

    public static boolean isSupporter(UUID id) {
        return id != null && supporter.contains(id);
    }

    public static boolean isStreamer(UUID id) {
        return id != null && streamer.contains(id);
    }

    /**
     * 로그인 시점 갱신. 쿨다운 안이면 아무것도 안 한다 (전용 서버는 로그인이 잦아서).
     * 로그인을 막지 않고 비동기로 가져오며, 바뀌었으면 onChanged로 room_state를 다시 보낸다.
     */
    public static void refreshOnLogin(Runnable onChanged) {
        long now = System.currentTimeMillis();
        long last = lastLoginRefresh.get();
        if (now - last < LOGIN_REFRESH_COOLDOWN_MS || !lastLoginRefresh.compareAndSet(last, now)) return;
        CompletableFuture.supplyAsync(Roles::refreshNow, EXECUTOR)
                .thenAccept(changed -> { if (changed) onChanged.run(); });
    }

    /** 호스트를 시작할 때(P2PBridge.startHost) 부른다. 백그라운드 스레드에서 돌고 즉시 리턴한다. */
    public static void refreshAsync() {
        EXECUTOR.execute(Roles::refreshNow);
    }

    /** @return 목록이 실제로 바뀌었으면 true(실패·무변화는 false). */
    private static boolean refreshNow() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(P2PConfig.officialHttpUrl() + "/api/v1/roles"))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOGGER.warn("[roles] fetch failed: HTTP {}", resp.statusCode());
                return false;
            }
            String sigB64 = resp.headers().firstValue("X-Roles-Signature").orElse(null);
            if (!verifySignature(resp.body(), sigB64)) {
                LOGGER.warn("[roles] response signature missing or invalid, ignoring (possible tampering)");
                return false;
            }
            return apply(resp.body());
        } catch (Exception e) {
            LOGGER.warn("[roles] fetch failed: {}", e.getMessage());
            return false;
        }
    }

    /** @return 세 목록 중 하나라도 실제로 달라졌으면 true. 서명 검증은 호출부에서 끝낸 상태여야 한다(테스트용으로 패키지 공개). */
    static boolean apply(String json) {
        JsonObject o;
        try {
            o = GSON.fromJson(json, JsonObject.class);
        } catch (Exception e) {
            LOGGER.warn("[roles] malformed response, keeping previous values: {}", e.getMessage());
            return false;
        }
        if (o == null) return false;
        Set<UUID> newDev = parseUuids(o, "dev");
        Set<UUID> newSupporter = parseUuids(o, "supporter");
        Set<UUID> newStreamer = parseUuids(o, "streamer");
        boolean changed = !newDev.equals(dev) || !newSupporter.equals(supporter) || !newStreamer.equals(streamer);
        dev = newDev;
        supporter = newSupporter;
        streamer = newStreamer;
        if (changed) {
            LOGGER.info("[roles] updated: dev={} supporter={} streamer={}", dev.size(), supporter.size(), streamer.size());
        }
        return changed;
    }

    private static PublicKey loadPublicKey() {
        try {
            byte[] raw = Base64.getDecoder().decode(PUBLIC_KEY_B64);
            byte[] spki = new byte[ED25519_SPKI_PREFIX.length + raw.length];
            System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
            System.arraycopy(raw, 0, spki, ED25519_SPKI_PREFIX.length, raw.length);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        } catch (Exception e) {
            LOGGER.error("[roles] failed to load embedded signing public key: {}", e.getMessage());
            return null;
        }
    }

    private static boolean verifySignature(String body, String sigB64) {
        if (sigB64 == null || SIGNING_KEY == null) return false;
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(SIGNING_KEY);
            verifier.update(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(sigB64));
        } catch (Exception e) {
            LOGGER.warn("[roles] signature verification error: {}", e.getMessage());
            return false;
        }
    }

    private static Set<UUID> parseUuids(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return Set.of();
        Set<UUID> out = new HashSet<>();
        for (var el : o.getAsJsonArray(key)) {
            try {
                out.add(UUID.fromString(el.getAsString()));
            } catch (Exception ignored) {
                // 잘못 적힌 UUID 한 줄 때문에 나머지 목록까지 버리지 않는다.
            }
        }
        return Set.copyOf(out);
    }
}
