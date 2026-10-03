package dev.magyul.instantp2p.common.auth;

import com.google.gson.JsonObject;
import dev.magyul.instantp2p.common.signaling.P2PConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * 방장 계정 — 운영자가 {@code /p2p login}으로 로그인한 Minecraft 계정으로 시그널링 게시 토큰을 받는다.
 * <p>
 * 원본 1.4의 {@code MojangAuth}와 같은 흐름이다: 시그널링 challenge → Mojang {@code session/minecraft/join} 등록 →
 * 시그널링 {@code /api/v1/auth/verify}가 {@code hasJoined}로 확인하고 게시 토큰(12시간)을 준다. 이 토큰으로 방장 랑데부 연결,
 * TURN 임시 계정 발급, 공개 방 등록을 한다. 클라이언트는 게임 세션의 액세스 토큰을 쓰지만, 서버는 저장해 둔 갱신 토큰으로
 * Minecraft 토큰을 다시 받는다({@link MicrosoftAuth}).
 * <p>
 * 네트워크를 기다리는 메서드는 블로킹이다 — 서버 스레드에서 부르지 않는다.
 */
public final class HostAccount {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-auth");

    /** 토큰 수명이 이만큼 남으면 미리 새로 받는다 (재접속 중에 만료되지 않게). */
    private static final long RENEW_BEFORE_MS = 30 * 60_000L;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    public enum State { LOGGED_OUT, PENDING, LOGGED_IN }

    /** 저장하는 값 — 갱신 토큰과 표시용 프로필 */
    private record Stored(String refreshToken, String uuid, String name) {}

    private final TokenStore store;

    private volatile Stored account;
    private volatile MicrosoftAuth.McSession session;
    private volatile String publishToken;
    private volatile long publishExpiresAtMs;
    private volatile String[] turn;
    private volatile long turnReuseUntilMs;

    private volatile MicrosoftAuth.DeviceCode pendingCode;
    private volatile Thread pendingThread;

    public HostAccount(Path dataFolder, String serverId) {
        this.store = new TokenStore(dataFolder, serverId);
        store.load().ifPresent(this::restore);
    }

    private void restore(String json) {
        JsonObject o = MicrosoftAuth.json(json);
        String refresh = MicrosoftAuth.str(o, "refresh_token");
        if (refresh == null) {
            LOG.warn("[auth] 저장된 로그인 정보가 비어 있습니다 — 다시 로그인해 주세요");
            return;
        }
        account = new Stored(refresh, MicrosoftAuth.str(o, "uuid"), MicrosoftAuth.str(o, "name"));
        LOG.info("[auth] 저장된 계정 {} 으로 로그인되어 있습니다", account.name());
    }

    public State state() {
        if (pendingCode != null) return State.PENDING;
        return account != null ? State.LOGGED_IN : State.LOGGED_OUT;
    }

    /** 로그인한 계정 닉네임, 없으면 null */
    public String name() {
        Stored a = account;
        return a != null ? a.name() : null;
    }

    /** 로그인한 계정 UUID(대시 포함), 없으면 null */
    public String uuid() {
        Stored a = account;
        if (a == null || a.uuid() == null) return null;
        String u = a.uuid();
        if (u.length() == 32) {
            u = u.substring(0, 8) + "-" + u.substring(8, 12) + "-" + u.substring(12, 16) + "-"
                    + u.substring(16, 20) + "-" + u.substring(20);
        }
        return u;
    }

    /** 로그인 대기 중인 코드, 없으면 null */
    public MicrosoftAuth.DeviceCode pendingCode() {
        return pendingCode;
    }

    /**
     * 기기 코드 로그인을 시작한다(비동기). 코드가 나오면 {@code onCode}, 끝나면 {@code onDone}(성공 시 null, 실패 시 이유).
     * 이미 대기 중이면 새로 시작하지 않고 false.
     */
    public synchronized boolean login(Consumer<MicrosoftAuth.DeviceCode> onCode, Consumer<String> onDone) {
        if (pendingThread != null) return false;
        Thread t = new Thread(() -> {
            String error = null;
            try {
                MicrosoftAuth.DeviceCode code = MicrosoftAuth.startDeviceCode();
                pendingCode = code;
                onCode.accept(code);
                MicrosoftAuth.MsTokens ms = MicrosoftAuth.pollDeviceCode(code, () -> pendingThread != Thread.currentThread());
                MicrosoftAuth.McSession mc = MicrosoftAuth.minecraft(ms.accessToken());
                if (ms.refreshToken() == null) throw new AuthException("갱신 토큰을 받지 못했습니다(앱 설정 확인 필요)");
                synchronized (this) {
                    if (pendingThread != Thread.currentThread()) throw new AuthException("로그인이 취소되었습니다");
                    clearTokens();
                    session = mc;
                    account = new Stored(ms.refreshToken(), mc.uuid(), mc.name());
                    persist();
                }
                LOG.info("[auth] {} 계정으로 로그인했습니다", mc.name());
            } catch (AuthException e) {
                error = e.getMessage();
            } catch (InterruptedException e) {
                error = "로그인이 취소되었습니다";
            } catch (IOException e) {
                error = "로그인 정보를 저장하지 못했습니다: " + e.getMessage();
            } catch (RuntimeException e) {
                error = "알 수 없는 오류: " + e;
                LOG.warn("[auth] 로그인 중 예외", e);
            } finally {
                synchronized (this) {
                    if (pendingThread == Thread.currentThread()) {
                        pendingThread = null;
                        pendingCode = null;
                    }
                }
            }
            if (error != null) LOG.warn("[auth] 로그인 실패: {}", error);
            onDone.accept(error);
        }, "instant-p2p-login");
        t.setDaemon(true);
        pendingThread = t;
        t.start();
        return true;
    }

    /** 진행 중인 로그인만 취소한다 (서버 종료 시). 저장된 로그인은 그대로 둔다. */
    public synchronized void cancelLogin() {
        Thread t = pendingThread;
        pendingThread = null;
        pendingCode = null;
        if (t != null) t.interrupt();
    }

    /** 로그아웃 — 진행 중인 로그인도 취소하고, 저장된 로그인 정보(암호문)를 지운다. 키는 남긴다(TokenStore.delete). */
    public synchronized boolean logout() {
        boolean had = account != null || pendingThread != null || store.exists();
        Thread t = pendingThread;
        pendingThread = null;
        pendingCode = null;
        if (t != null) t.interrupt();
        account = null;
        clearTokens();
        store.delete();
        if (had) LOG.info("[auth] 로그아웃했습니다 — 저장된 로그인 정보를 지웠습니다");
        return had;
    }

    /**
     * 시그널링 서버를 바꿨다 — 게시 토큰·TURN 계정은 그 서버가 발급한 것이라 버린다(다음에 새 서버에서 다시 받는다).
     * Microsoft/Minecraft 로그인은 서버와 무관하므로 그대로 둔다.
     */
    public synchronized void forgetServerState() {
        publishToken = null;
        publishExpiresAtMs = 0;
        turn = null;
        turnReuseUntilMs = 0;
    }

    private void clearTokens() {
        session = null;
        publishToken = null;
        publishExpiresAtMs = 0;
        turn = null;
        turnReuseUntilMs = 0;
    }

    private void persist() throws IOException {
        Stored a = account;
        JsonObject o = new JsonObject();
        o.addProperty("refresh_token", a.refreshToken());
        o.addProperty("uuid", a.uuid());
        o.addProperty("name", a.name());
        store.save(o.toString());
    }

    // ── 토큰 ─────────────────────────────────────────────────────────────────

    /**
     * 시그널링 게시 토큰(블로킹). 남은 시간이 넉넉하면 네트워크 없이 그대로 준다.
     * 서버가 인증을 쓰지 않으면(challenge 503) null.
     *
     * @throws AuthException 로그인이 안 되어 있거나 발급 실패
     */
    public synchronized String publishToken() throws AuthException {
        String t = publishToken;
        if (t != null && System.currentTimeMillis() < publishExpiresAtMs - RENEW_BEFORE_MS) return t;
        if (account == null) throw new AuthException("로그인되어 있지 않습니다 — /p2p login");

        HttpResponse<String> ch = get(P2PConfig.signalingHttpUrl() + "/api/v1/auth/challenge", "challenge 발급");
        if (ch.statusCode() == 503) {
            LOG.debug("[auth] 시그널링 서버가 인증을 쓰지 않는다 — 토큰 없이 진행");
            return null;
        }
        if (ch.statusCode() == 429) {
            // 인증 무차별 대입 차단(가맹점 F3.1d~) — 너무 자주 물었다
            throw new AuthException("시그널링 인증이 잠시 막혔습니다(HTTP 429) — 잠시 후 다시 시도해 주세요");
        }
        String challenge = MicrosoftAuth.str(MicrosoftAuth.json(ch.body()), "challenge");
        if (ch.statusCode() != 200 || challenge == null) throw new AuthException("challenge 발급 실패 (HTTP " + ch.statusCode() + ")");

        MicrosoftAuth.McSession mc = minecraftSession();
        HttpResponse<String> join = MicrosoftAuth.send(MicrosoftAuth.post("https://sessionserver.mojang.com/session/minecraft/join",
                "{\"accessToken\":\"" + mc.accessToken() + "\",\"selectedProfile\":\"" + mc.uuid() + "\",\"serverId\":\"" + challenge + "\"}",
                "application/json"), "Mojang 세션 등록");
        if (join.statusCode() / 100 != 2) {
            session = null; // 토큰이 막혔을 수 있다 — 다음엔 새로 받는다
            throw new AuthException("Mojang 세션 등록 실패 (HTTP " + join.statusCode() + ")");
        }

        String verifyUrl = P2PConfig.signalingHttpUrl() + "/api/v1/auth/verify?username=" + MicrosoftAuth.enc(mc.name())
                + "&challenge=" + MicrosoftAuth.enc(challenge);
        HttpResponse<String> v = get(verifyUrl, "계정 확인");
        if (v.statusCode() == 503) {
            // 시그널링 서버가 Mojang hasJoined 응답을 못 받았다 — 한 번만 다시 묻는다 (가맹점 F3.3과 같게)
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuthException("계정 확인이 중단되었습니다");
            }
            v = get(verifyUrl, "계정 확인");
            if (v.statusCode() == 503) throw new AuthException("Mojang 서버 응답이 늦어 계정 확인을 못 했습니다 — 잠시 후 다시 시도해 주세요");
        }
        if (v.statusCode() == 403) {
            // 403은 차단(banned)일 때만 차단으로 안내 — 그 밖엔 Mojang 확인 실패다
            String err = MicrosoftAuth.str(MicrosoftAuth.json(v.body()), "error");
            if (err != null && err.contains("banned")) throw new AuthException("이 계정은 시그널링 서버에서 방 열기가 차단되었습니다");
            session = null; // Minecraft 토큰이 만료됐을 수 있다 — 다음엔 새로 받는다
            throw new AuthException("Mojang 계정 확인에 실패했습니다 — 잠시 후 다시 열어 보고, 계속되면 /p2p logout 후 다시 로그인해 주세요");
        }
        JsonObject vo = MicrosoftAuth.json(v.body());
        String token = MicrosoftAuth.str(vo, "token");
        if (v.statusCode() != 200 || token == null) throw new AuthException("계정 확인 실패 (HTTP " + v.statusCode() + ")");
        long ttl = MicrosoftAuth.num(vo, "expires_in", 3600);
        publishToken = token;
        publishExpiresAtMs = System.currentTimeMillis() + ttl * 1000L;
        LOG.info("[auth] 시그널링 인증 완료 ({}분)", ttl / 60);
        return token;
    }

    /** 게시 토큰, 실패하면 null (이유는 경고로 남긴다). */
    public String publishTokenOrNull() {
        try {
            return publishToken();
        } catch (AuthException e) {
            LOG.warn("[auth] {}", e.getMessage());
            return null;
        }
    }

    /** Minecraft 세션 — 만료가 가까우면 저장된 갱신 토큰으로 다시 받는다(갱신 토큰도 새 값으로 바꿔 저장). */
    private MicrosoftAuth.McSession minecraftSession() throws AuthException {
        MicrosoftAuth.McSession mc = session;
        if (mc != null && System.currentTimeMillis() < mc.expiresAtMs() - RENEW_BEFORE_MS) return mc;
        Stored a = account;
        MicrosoftAuth.MsTokens ms;
        try {
            ms = MicrosoftAuth.refresh(a.refreshToken());
        } catch (AuthException e) {
            if (e.loginInvalid()) {
                account = null;
                clearTokens();
                store.delete();
            }
            throw e;
        }
        mc = MicrosoftAuth.minecraft(ms.accessToken());
        session = mc;
        if (ms.refreshToken() != null && !ms.refreshToken().equals(a.refreshToken()) || !mc.name().equals(a.name())) {
            account = new Stored(ms.refreshToken() != null ? ms.refreshToken() : a.refreshToken(), mc.uuid(), mc.name());
            try {
                persist();
            } catch (IOException e) {
                LOG.warn("[auth] 갱신된 로그인 정보를 저장하지 못했습니다: {}", e.getMessage());
            }
        }
        return mc;
    }

    /**
     * TURN 임시 계정 {@code {user, pass}} (블로킹). 받아 둔 계정이 충분히 남아 있으면 그대로 쓴다.
     * 못 받으면 null — 그러면 중계 없이(직결만) 간다. {@code -Dkfcudp.turn.user/pass}가 있으면 그걸 쓴다(테스트용).
     */
    public synchronized String[] turnCredentials() {
        if (P2PConfig.TURN_USERNAME != null && P2PConfig.TURN_CREDENTIAL != null) {
            return new String[]{P2PConfig.TURN_USERNAME, P2PConfig.TURN_CREDENTIAL};
        }
        String[] c = turn;
        if (c != null && System.currentTimeMillis() < turnReuseUntilMs) return c;
        try {
            String token = publishToken();
            String url = P2PConfig.signalingHttpUrl() + "/api/v1/turn/credentials"
                    + (token != null ? "?token=" + MicrosoftAuth.enc(token) : "");
            HttpResponse<String> r = get(url, "중계 계정 발급");
            if (r.statusCode() != 200) {
                LOG.warn("[auth] 중계 계정을 못 받았다(HTTP {}) — 중계 없이 진행한다", r.statusCode());
                return null;
            }
            JsonObject o = MicrosoftAuth.json(r.body());
            String u = MicrosoftAuth.str(o, "username");
            String p = MicrosoftAuth.str(o, "password");
            if (u == null || u.isBlank() || p == null || p.isBlank()) return null;
            long exp = MicrosoftAuth.num(o, "expires", 0);
            long now = System.currentTimeMillis();
            // 원본과 같게: 만료 6시간 전까지 재사용, 만료가 없으면 2시간마다 다시 묻는다
            turnReuseUntilMs = exp > 0 ? exp * 1000L - 6 * 60 * 60_000L : now + 2 * 60 * 60_000L;
            turn = new String[]{u, p};
            return turn;
        } catch (AuthException e) {
            LOG.warn("[auth] 중계 계정 요청 실패 — 중계 없이 진행한다: {}", e.getMessage());
            return null;
        }
    }

    private static HttpResponse<String> get(String url, String what) throws AuthException {
        return MicrosoftAuth.send(HttpRequest.newBuilder(URI.create(url)).timeout(HTTP_TIMEOUT).GET().build(), what);
    }
}
