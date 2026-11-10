package dev.magyul.instantp2p.common.signaling;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * [1.3.0] 공개 방 방장 동의 기록(원본 1.5.1 {@code PublicConsent}, 가맹점 F4 {@code ROLE_REV}).
 * <p>
 * 시그널링 서버는 방장의 동의 기록을 서버 쪽에도 두고 있다 — 서버는 {@code POST /api/v1/consent?token=}으로 받고
 * 동의가 없으면 핸드셰이크를 {@code need-consent-host}로 거부할 수 있다(공식). 서버판은 <b>운영자가 직접 동의 문구를 보고
 * 명시적으로 고를 때만</b> 기록한다(자동 동의 없음). 로컬 기록은 데이터 폴더의 {@code consent.json}에
 * {@code "<서버>:<계정 UUID>": 동의한 개정 번호}로 남고, 개정 번호가 필요 번호 이상이면 동의한 것으로 본다.
 */
public final class Consent {
    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-consent");
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /** 동의 문구 쪽수 */
    public static final int PAGES = 4;

    private final Path file;

    public Consent(Path dataFolder) {
        this.file = dataFolder.resolve("consent.json");
    }

    /** 서버가 요구하는 동의 개정 번호 — 공식은 3(모든 멀티플레이), 가맹점은 1(공개 방만). */
    public static int requiredRev(SignalingServer server) {
        return server == SignalingServer.FRANCHISE ? 1 : 3;
    }

    public synchronized boolean hasHost(String accountUuid, SignalingServer server) {
        if (accountUuid == null) return false;
        JsonObject o = read();
        var v = o.get(key(accountUuid, server));
        return v != null && v.isJsonPrimitive() && v.getAsInt() >= requiredRev(server);
    }

    /** 동의를 로컬에 기록한다. */
    public synchronized void recordHost(String accountUuid, SignalingServer server) throws IOException {
        JsonObject o = read();
        o.addProperty(key(accountUuid, server), requiredRev(server));
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("consent.json.tmp");
        Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 동의를 로컬에서 지운다(철회는 서버 운영자에게 따로 요청해야 한다). */
    public synchronized void forgetHost(String accountUuid, SignalingServer server) throws IOException {
        JsonObject o = read();
        if (o.remove(key(accountUuid, server)) != null) {
            Files.writeString(file, GSON.toJson(o), StandardCharsets.UTF_8);
        }
    }

    /**
     * 서버에 동의 기록을 보낸다 — 블로킹, 최대 5번(401/403/429는 바로 중단).
     *
     * @return 서버가 받았으면 true
     */
    public static boolean post(String publishToken, SignalingServer server) {
        if (publishToken == null) return false;
        String url = P2PConfig.signalingHttpUrl() + "/api/v1/consent?token=" + URLEncoder.encode(publishToken, StandardCharsets.UTF_8);
        String body = "{\"rev\":" + requiredRev(server) + ",\"role\":\"host\"}";
        for (int i = 0; i < 5; i++) {
            try {
                HttpResponse<Void> r = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(8))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(), HttpResponse.BodyHandlers.discarding());
                int code = r.statusCode();
                if (code / 100 == 2) return true;
                LOG.warn("[consent] 서버가 동의 기록을 받지 않았다: HTTP {}", code);
                if (code == 401 || code == 403 || code == 429) return false;
            } catch (IOException e) {
                LOG.debug("[consent] 전송 실패: {}", e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            try {
                Thread.sleep(1000L << Math.min(i, 3));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static String key(String uuid, SignalingServer server) {
        return server.id() + ":" + uuid;
    }

    private JsonObject read() {
        try {
            if (Files.isRegularFile(file)) {
                JsonObject o = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
                if (o != null) return o;
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("[consent] consent.json을 읽지 못했다 — 동의 안 한 것으로 본다: {}", e.toString());
        }
        return new JsonObject();
    }
}
