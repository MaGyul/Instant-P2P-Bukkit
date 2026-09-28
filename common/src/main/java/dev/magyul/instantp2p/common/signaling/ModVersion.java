package dev.magyul.instantp2p.common.signaling;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 공개 방 로비 ID에 넣을 모드 버전 — 설정 {@code targetModVersion}이 {@code "auto"}(또는 빈 값)면 시그널링 서버의
 * {@code GET /api/v1/version}({@code {"current":"1.3",...}}, 원본 ModVersionCheck가 구버전 안내에 쓰는 것)에서 받는다.
 * <p>
 * 로비 ID에 모드 버전 해시가 들어가서, 클라이언트 모드 버전과 다르면 목록에 안 보인다. 배포본 버전을 따라가면
 * 최신 클라이언트에게 보인다(구버전 클라이언트에게는 안 보인다 — 원본 호스트도 자기 버전 로비에만 올라간다).
 * <p>
 * 폴링하지 않는다. {@link PublicRoomAnnouncer}가 공개 방 로비에 접속할 때만 부르고, 성공한 값은 announce를 멈출 때까지
 * 재사용한다({@link #reset}). 실패하면 {@link #FALLBACK}을 쓰고 다음 재접속 때 다시 묻는다.
 */
public final class ModVersion {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-public");

    public static final String AUTO = "auto";
    /** 조회 실패 시 — 이 빌드가 맞춘 원본 모드 버전 */
    public static final String FALLBACK = "1.3";

    private static final Pattern VALID = Pattern.compile("[0-9A-Za-z.+_-]{1,32}");
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

    private static volatile String fetched;

    private ModVersion() {}

    public static boolean isAuto(String configured) {
        return configured == null || configured.isBlank() || AUTO.equalsIgnoreCase(configured.trim());
    }

    /** 설정 값이 버전이면 그대로, auto면 시그널링 서버 값(블로킹 HTTP — announce 스레드에서만 부른다). */
    static String resolve(String configured) {
        if (!isAuto(configured)) return configured.trim();
        String v = fetched;
        if (v != null) return v;
        v = fetch();
        if (v == null) {
            LOG.warn("[public-room] 모드 버전 조회 실패 — {}로 올린다", FALLBACK);
            return FALLBACK;
        }
        fetched = v;
        LOG.info("[public-room] 모드 버전 {} (시그널링 서버 기준)", v);
        return v;
    }

    /** announce를 멈출 때 — 다음 호스트 시작에서 다시 묻는다. */
    static void reset() {
        fetched = null;
    }

    private static String fetch() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(P2PConfig.SIGNALING_HTTP_URL + "/api/v1/version"))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.debug("[public-room] version HTTP {}", resp.statusCode());
                return null;
            }
            return parse(resp.body());
        } catch (Exception e) {
            LOG.debug("[public-room] version 조회 실패: {}", e.toString());
            return null;
        }
    }

    /** 응답 본문에서 {@code current} — 서명이 없는 평문 응답이라 버전처럼 생긴 값만 받는다. */
    static String parse(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            JsonElement e = o.get("current");
            if (e == null || !e.isJsonPrimitive()) return null;
            String v = e.getAsString().trim();
            return VALID.matcher(v).matches() ? v : null;
        } catch (Exception e) {
            return null;
        }
    }
}
