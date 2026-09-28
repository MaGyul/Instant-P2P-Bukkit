package dev.magyul.instantp2p.common.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Microsoft 계정 → Minecraft 액세스 토큰. 전용 서버에는 로그인한 플레이어 세션이 없으므로 운영자가 기기 코드로 한 번 로그인하고,
 * 이후에는 갱신 토큰으로 다시 받는다.
 * <p>
 * 흐름: 기기 코드(또는 갱신 토큰) → MS 액세스 토큰 → Xbox Live → XSTS → {@code login_with_xbox} → 프로필.
 * 앱 ID는 Minecraft API 사용 승인을 받은 공용 클라이언트여야 한다({@link #CLIENT_ID}).
 * <p>
 * 토큰 값은 절대 로그에 남기지 않는다.
 */
public final class MicrosoftAuth {

    /** 기본값은 마인월드 런처 앱 ID(Minecraft API 승인됨). {@code -Dinstantp2p.auth.clientId}로 바꿀 수 있다. */
    public static final String CLIENT_ID =
            System.getProperty("instantp2p.auth.clientId", "f0393746-2f60-4c4b-bdf7-abd4f68a060d");

    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final String MS_BASE = "https://login.microsoftonline.com/consumers/oauth2/v2.0";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private MicrosoftAuth() {}

    public record DeviceCode(String deviceCode, String userCode, String verificationUri, int intervalSec, long expiresAtMs) {}

    /** refreshToken은 응답에 없으면 null(그 경우 이전 값을 계속 쓴다). */
    public record MsTokens(String accessToken, String refreshToken) {}

    /** Minecraft 세션. uuid는 대시 없는 32자리. */
    public record McSession(String accessToken, String uuid, String name, long expiresAtMs) {}

    public static DeviceCode startDeviceCode() throws AuthException {
        JsonObject o = postForm(MS_BASE + "/devicecode", "client_id=" + enc(CLIENT_ID) + "&scope=" + enc(SCOPE), "기기 코드 발급");
        return new DeviceCode(str(o, "device_code"), str(o, "user_code"), str(o, "verification_uri"),
                Math.max(1, num(o, "interval", 5)), System.currentTimeMillis() + num(o, "expires_in", 900) * 1000L);
    }

    /** 사용자가 코드를 입력할 때까지 기다린다(블로킹). 취소되거나 만료되면 예외. */
    public static MsTokens pollDeviceCode(DeviceCode code, BooleanSupplier cancelled) throws AuthException, InterruptedException {
        long interval = code.intervalSec() * 1000L;
        while (System.currentTimeMillis() < code.expiresAtMs()) {
            Thread.sleep(interval);
            if (cancelled.getAsBoolean()) throw new AuthException("로그인이 취소되었습니다");
            HttpResponse<String> r = send(post(MS_BASE + "/token", "grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code")
                    + "&client_id=" + enc(CLIENT_ID) + "&device_code=" + enc(code.deviceCode()), "application/x-www-form-urlencoded"), "로그인 확인");
            JsonObject o = json(r.body());
            if (r.statusCode() == 200) return new MsTokens(str(o, "access_token"), str(o, "refresh_token"));
            String error = o != null ? str(o, "error") : null;
            if ("authorization_pending".equals(error)) continue;
            if ("slow_down".equals(error)) {
                interval += 5000;
                continue;
            }
            if ("authorization_declined".equals(error)) throw new AuthException("로그인이 거절되었습니다");
            if ("expired_token".equals(error)) break;
            throw new AuthException("로그인 확인 실패 (" + (error != null ? error : "HTTP " + r.statusCode()) + ")");
        }
        throw new AuthException("로그인 코드가 만료되었습니다 — 다시 시도해 주세요");
    }

    public static MsTokens refresh(String refreshToken) throws AuthException {
        HttpResponse<String> r = send(post(MS_BASE + "/token", "grant_type=refresh_token&client_id=" + enc(CLIENT_ID)
                + "&scope=" + enc(SCOPE) + "&refresh_token=" + enc(refreshToken), "application/x-www-form-urlencoded"), "토큰 갱신");
        JsonObject o = json(r.body());
        if (r.statusCode() != 200) {
            String error = o != null ? str(o, "error") : null;
            if ("invalid_grant".equals(error)) {
                throw new AuthException("저장된 로그인이 만료되었거나 해제되었습니다 — /p2p login 으로 다시 로그인해 주세요", true);
            }
            throw new AuthException("토큰 갱신 실패 (" + (error != null ? error : "HTTP " + r.statusCode()) + ")");
        }
        return new MsTokens(str(o, "access_token"), str(o, "refresh_token"));
    }

    /** MS 액세스 토큰 → Minecraft 세션(프로필 포함). */
    public static McSession minecraft(String msAccessToken) throws AuthException {
        JsonObject xbl = postJson("https://user.auth.xboxlive.com/user/authenticate",
                "{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\",\"RpsTicket\":\"d=" + msAccessToken + "\"},"
                        + "\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}", "Xbox Live 인증");
        String uhs = userHash(xbl);

        HttpResponse<String> xr = send(post("https://xsts.auth.xboxlive.com/xsts/authorize",
                "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\"" + str(xbl, "Token") + "\"]},"
                        + "\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}", "application/json"), "XSTS 인증");
        JsonObject xsts = json(xr.body());
        if (xr.statusCode() != 200) throw new AuthException(xstsError(xsts, xr.statusCode()));

        JsonObject mc = postJson("https://api.minecraftservices.com/authentication/login_with_xbox",
                "{\"identityToken\":\"XBL3.0 x=" + uhs + ";" + str(xsts, "Token") + "\"}", "Minecraft 로그인");
        String mcToken = str(mc, "access_token");
        long expiresAt = System.currentTimeMillis() + num(mc, "expires_in", 86400) * 1000L;

        HttpResponse<String> pr = send(HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile"))
                .timeout(TIMEOUT).header("Authorization", "Bearer " + mcToken).GET().build(), "프로필 조회");
        if (pr.statusCode() == 404) throw new AuthException("이 계정은 Minecraft: Java Edition을 가지고 있지 않습니다");
        if (pr.statusCode() != 200) throw new AuthException("프로필 조회 실패 (HTTP " + pr.statusCode() + ")");
        JsonObject profile = json(pr.body());
        return new McSession(mcToken, str(profile, "id"), str(profile, "name"), expiresAt);
    }

    private static String userHash(JsonObject xbl) throws AuthException {
        try {
            JsonArray xui = xbl.getAsJsonObject("DisplayClaims").getAsJsonArray("xui");
            return xui.get(0).getAsJsonObject().get("uhs").getAsString();
        } catch (RuntimeException e) {
            throw new AuthException("Xbox Live 응답 형식 오류");
        }
    }

    private static String xstsError(JsonObject o, int status) {
        long xerr = o != null && o.has("XErr") ? o.get("XErr").getAsLong() : 0;
        if (xerr == 2148916233L) return "이 Microsoft 계정에 Xbox 프로필이 없습니다 — minecraft.net에서 한 번 로그인해 주세요";
        if (xerr == 2148916235L) return "Xbox Live를 쓸 수 없는 지역의 계정입니다";
        if (xerr == 2148916236L || xerr == 2148916237L) return "성인 인증이 필요한 계정입니다";
        if (xerr == 2148916238L) return "미성년 계정은 가족 그룹에 추가되어야 합니다";
        return "XSTS 인증 실패 (HTTP " + status + (xerr != 0 ? ", XErr " + xerr : "") + ")";
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    static HttpRequest post(String url, String body, String contentType) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Content-Type", contentType).header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    static HttpResponse<String> send(HttpRequest req, String what) throws AuthException {
        try {
            return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException(what + " 중단됨");
        } catch (Exception e) {
            throw new AuthException(what + " 실패 — 네트워크 오류: " + e.getClass().getSimpleName());
        }
    }

    private static JsonObject postForm(String url, String body, String what) throws AuthException {
        HttpResponse<String> r = send(post(url, body, "application/x-www-form-urlencoded"), what);
        JsonObject o = json(r.body());
        if (r.statusCode() != 200 || o == null) {
            String error = o != null ? str(o, "error") : null;
            throw new AuthException(what + " 실패 (" + (error != null ? error : "HTTP " + r.statusCode()) + ")");
        }
        return o;
    }

    private static JsonObject postJson(String url, String body, String what) throws AuthException {
        HttpResponse<String> r = send(post(url, body, "application/json"), what);
        JsonObject o = json(r.body());
        if (r.statusCode() != 200 || o == null) throw new AuthException(what + " 실패 (HTTP " + r.statusCode() + ")");
        return o;
    }

    static JsonObject json(String body) {
        try {
            JsonElement e = JsonParser.parseString(body);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    static long num(JsonObject o, String key, long def) {
        JsonElement e = o == null ? null : o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsLong() : def;
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static int num(JsonObject o, String key, int def) {
        return (int) num(o, key, (long) def);
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
