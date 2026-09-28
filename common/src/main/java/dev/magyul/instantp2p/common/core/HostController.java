package dev.magyul.instantp2p.common.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.magyul.instantp2p.common.Utils;
import dev.magyul.instantp2p.common.auth.AuthException;
import dev.magyul.instantp2p.common.auth.HostAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 방 열기·닫기·초대 코드 관리. 설정의 {@code enabled}는 "서버가 켜질 때 자동으로 열기"이고, 꺼져 있어도
 * {@code /p2p open}으로 열 수 있다.
 * <p>
 * 초대 코드는 {@code state.json}에 남겨 재시작해도 그대로 쓴다({@code /p2p newcode}로만 바뀐다).
 * 열기·닫기는 네트워크를 기다리므로 전용 스레드 하나에서 차례로 처리한다 — 서버 스레드를 막지 않는다.
 */
public final class HostController {

    private static final Logger LOG = LoggerFactory.getLogger("Instant-P2P");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String K = "instant-p2p-server.";

    public enum RoomState { CLOSED, OPENING, OPEN }

    private final P2PCore core;
    private final Path stateFile;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "instant-p2p-host");
        t.setDaemon(true);
        return t;
    });

    private volatile RoomState state = RoomState.CLOSED;
    private volatile String inviteCode;

    HostController(P2PCore core) {
        this.core = core;
        this.stateFile = core.platform().dataFolder().resolve("state.json");
        this.inviteCode = loadCode();
    }

    public RoomState state() {
        return state;
    }

    /** 저장된 초대 코드, 한 번도 연 적이 없으면 null */
    public String inviteCode() {
        return inviteCode;
    }

    // ── 서버 수명주기 ─────────────────────────────────────────────────────────

    /** 서버 기동 완료 후 한 번 — enabled면 연다(로그인이 안 되어 있으면 안내만). */
    public void autoStart() {
        if (!core.settings().enabled()) {
            LOG.info("자동 열기가 꺼져 있습니다 (enabled: false) — /p2p open 으로 열 수 있습니다");
            return;
        }
        if (core.account().state() != HostAccount.State.LOGGED_IN) {
            LOG.warn("로그인되어 있지 않아 방을 열지 않았습니다 — /p2p login 으로 Minecraft 계정에 로그인해 주세요");
            return;
        }
        open(core.platform().console());
    }

    /** 서버 종료 — 부른 스레드에서 끝까지 닫는다. */
    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        core.account().cancelLogin();
        if (state != RoomState.CLOSED) {
            core.bridge().stopHost();
            state = RoomState.CLOSED;
        }
    }

    // ── 명령어 ───────────────────────────────────────────────────────────────

    public void open(P2PSender sender) {
        if (core.account().state() != HostAccount.State.LOGGED_IN) {
            sender.send(K + "open.need_login");
            return;
        }
        if (state != RoomState.CLOSED) {
            sender.send(K + (state == RoomState.OPEN ? "open.already" : "open.opening"), new P2PText.Copy(code()));
            return;
        }
        state = RoomState.OPENING;
        sender.send(K + "open.opening");
        executor.execute(() -> doOpen(sender));
    }

    public void close(P2PSender sender) {
        if (state == RoomState.CLOSED) {
            sender.send(K + "close.none");
            return;
        }
        executor.execute(() -> {
            doClose();
            reply(sender, K + "close.done");
        });
    }

    /** 초대 코드를 새로 만든다. 열려 있으면 새 코드로 다시 연다. */
    public void newCode(P2PSender sender) {
        executor.execute(() -> {
            boolean wasOpen = state != RoomState.CLOSED;
            if (wasOpen) doClose();
            String code = Utils.generateCode();
            inviteCode = code;
            saveCode(code);
            LOG.info("초대 코드를 새로 만들었습니다: {}", code);
            reply(sender, K + "newcode.done", new P2PText.Copy(code));
            if (wasOpen) {
                state = RoomState.OPENING;
                doOpen(sender);
            }
        });
    }

    /** 로그아웃 — 열려 있으면 닫는다(방장 재접속·공개 방에 토큰이 필요하므로). */
    public void logout(P2PSender sender) {
        executor.execute(() -> {
            if (state != RoomState.CLOSED) {
                doClose();
                reply(sender, K + "close.done");
            }
            boolean had = core.account().logout();
            reply(sender, K + (had ? "logout.done" : "logout.none"));
        });
    }

    /** 공개 방 정보를 (다시) 올린다 — Velocity가 백엔드 버전을 알아낸 뒤에도 부른다. */
    public void publishPublicRoom() {
        P2PSettings settings = core.settings();
        String code = inviteCode;
        if (state != RoomState.OPEN || !settings.publicRoom() || code == null) return;
        if (core.platform().minecraftVersion() == null) return; // 버전을 알아야 목록에서 호환으로 보인다
        String title = settings.title().isEmpty() ? core.platform().motd() : settings.title();
        core.bridge().publishPublicRoom(code, title, settings.name(), settings.serverUuid().toString(),
                core.onlinePlayers().size(), core.platform().maxPlayers());
    }

    // ── 실제 작업 (executor 스레드) ────────────────────────────────────────────

    private void doOpen(P2PSender sender) {
        String code = code();
        try {
            // 인증을 먼저 확인한다 — 방장 연결·TURN·공개 방이 모두 이 토큰을 쓴다
            core.account().publishToken();
            core.bridge().startHost(code, core.platform().targetHost() + ":" + core.platform().listenPort());
            state = RoomState.OPEN;
            LOG.info("초대 코드: {}", code);
            reply(sender, K + "open.done", new P2PText.Copy(code));
            core.platform().runSync(this::publishPublicRoom);
        } catch (AuthException e) {
            state = RoomState.CLOSED;
            reply(sender, K + "open.failed", e.getMessage());
        } catch (Exception e) {
            LOG.error("[instant-p2p] 방을 열지 못했습니다: {}", e.getMessage(), e);
            core.bridge().stopHost();
            state = RoomState.CLOSED;
            reply(sender, K + "open.failed", String.valueOf(e.getMessage()));
        }
    }

    private void doClose() {
        core.bridge().stopHost();
        state = RoomState.CLOSED;
    }

    private void reply(P2PSender sender, String key, Object... args) {
        core.platform().runSync(() -> sender.send(key, args));
    }

    /** 초대 코드 — 없으면 새로 만들어 저장한다. */
    private synchronized String code() {
        String c = inviteCode;
        if (c == null) {
            c = Utils.generateCode();
            inviteCode = c;
            saveCode(c);
        }
        return c;
    }

    private String loadCode() {
        if (!Files.exists(stateFile)) return null;
        try (Reader r = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
            JsonObject o = GSON.fromJson(r, JsonObject.class);
            JsonElement e = o != null ? o.get("inviteCode") : null;
            String c = e != null && e.isJsonPrimitive() ? e.getAsString() : null;
            return c != null && Utils.isValidCode(c) ? c : null;
        } catch (IOException | RuntimeException e) {
            LOG.warn("state.json을 읽지 못했습니다 — 초대 코드를 새로 만듭니다: {}", e.getMessage());
            return null;
        }
    }

    private void saveCode(String code) {
        JsonObject o = new JsonObject();
        o.addProperty("inviteCode", code);
        try {
            Files.createDirectories(stateFile.getParent());
            try (Writer w = Files.newBufferedWriter(stateFile, StandardCharsets.UTF_8)) {
                GSON.toJson(o, w);
            }
        } catch (IOException e) {
            LOG.warn("state.json을 저장하지 못했습니다 (재시작하면 초대 코드가 바뀝니다): {}", e.getMessage());
        }
    }
}
