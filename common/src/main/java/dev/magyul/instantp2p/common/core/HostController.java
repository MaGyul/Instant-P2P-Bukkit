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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 방 열기·닫기·초대 코드 관리. 설정의 {@code enabled}는 "서버가 켜질 때 자동으로 열기"이고, 꺼져 있어도
 * {@code /p2p open}으로 열 수 있다.
 * <p>
 * 초대 코드는 {@code state.json}에 남겨 재시작해도 그대로 쓴다({@code /p2p newcode}로만 바뀐다).
 * <p>
 * 방장 key도 코드와 함께 남긴다. 랑데부 서버는 방장이 끊긴 뒤에도 잠시 그 코드를 이전 key로 잡아 두므로
 * (재접속한 방장이 방을 되찾게), 같은 코드로 다시 열 때 key가 바뀌면 409 Conflict로 거부된다.
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
    /** 초대 코드 + 랑데부 방장 key. 한 번도 연 적이 없으면 null */
    private volatile Room room;

    private record Room(String code, String hostKey) {}

    HostController(P2PCore core) {
        this.core = core;
        this.stateFile = core.platform().dataFolder().resolve("state.json");
        this.room = loadRoom();
    }

    public RoomState state() {
        return state;
    }

    /** 저장된 초대 코드, 한 번도 연 적이 없으면 null */
    public String inviteCode() {
        Room r = room;
        return r != null ? r.code() : null;
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
            sender.send(K + (state == RoomState.OPEN ? "open.already" : "open.opening"), new P2PText.Copy(room().code()));
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
            Room r = newRoom();
            String code = r.code();
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

    /**
     * 서버 스레드. 설정 파일을 다시 읽어 적용한다({@code /p2p reload}). 대부분은 바로 반영된다 —
     * 공개 방 정보는 다시 올리고, 방송 허용은 room_state를 다시 보낸다.
     * UDP 포트는 방을 다시 열어야 하고(자동으로 다시 열면 접속자가 끊긴다), serverUuid는 로그인 정보 암호화에
     * 묶여 있어 재시작 전까지 이전 값을 쓴다.
     */
    public void reload(P2PSender sender) {
        P2PSettings old = core.settings();
        P2PSettings fresh;
        try {
            fresh = core.platform().loadSettings();
        } catch (Exception e) {
            String why = String.valueOf(e.getMessage());
            LOG.warn("설정을 다시 불러오지 못했습니다 (파일은 그대로, 이전 설정 유지): {}", why);
            // 파서 메시지는 여러 줄(위치·원문)이라 채팅에는 첫 줄만
            int nl = why.indexOf('\n');
            sender.send(K + "reload.failed", nl > 0 ? why.substring(0, nl).trim() : why);
            return;
        }
        if (!fresh.serverUuid().equals(old.serverUuid())) {
            sender.send(K + "reload.uuid_kept");
            fresh = fresh.withServerUuid(old.serverUuid());
        }
        core.platform().applySettings(fresh);
        LOG.info("설정을 다시 불러왔습니다");
        sender.send(K + "reload.done");

        if (state == RoomState.CLOSED) return;
        if (fresh.udpPort() != old.udpPort()) sender.send(K + "reload.udp_port");
        if (fresh.allowBroadcast() != old.allowBroadcast()) core.broadcastRoomState();
        if (fresh.publicRoomDiffers(old)) {
            // 채널·모드 버전이 바뀌면 로비 자체가 달라진다 — 내렸다가 다시 올린다
            executor.execute(() -> {
                core.bridge().unpublishPublicRoom();
                core.platform().runSync(this::publishPublicRoom);
            });
        }
    }

    /** 공개 방 정보를 (다시) 올린다 — Velocity가 백엔드 버전을 알아낸 뒤에도 부른다. */
    public void publishPublicRoom() {
        P2PSettings settings = core.settings();
        String code = inviteCode();
        if (state != RoomState.OPEN || !settings.publicRoom() || code == null) return;
        if (core.platform().minecraftVersion() == null) return; // 버전을 알아야 목록에서 호환으로 보인다
        String title = settings.title().isEmpty() ? core.platform().motd() : settings.title();
        core.bridge().publishPublicRoom(code, title, settings.name(), settings.serverUuid().toString(),
                core.onlinePlayers().size(), core.platform().maxPlayers());
    }

    // ── 실제 작업 (executor 스레드) ────────────────────────────────────────────

    private void doOpen(P2PSender sender) {
        Room r = room();
        String code = r.code();
        try {
            // 인증을 먼저 확인한다 — 방장 연결·TURN·공개 방이 모두 이 토큰을 쓴다
            core.account().publishToken();
            core.bridge().startHost(code, r.hostKey(), core.platform().targetHost() + ":" + core.platform().listenPort());
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

    /** 초대 코드 + 방장 key — 없으면 새로 만들어 저장한다. */
    private synchronized Room room() {
        Room r = room;
        return r != null ? r : newRoom();
    }

    private synchronized Room newRoom() {
        Room r = new Room(Utils.generateCode(), newHostKey());
        room = r;
        saveRoom(r);
        return r;
    }

    private static String newHostKey() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private Room loadRoom() {
        if (!Files.exists(stateFile)) return null;
        try (Reader reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
            JsonObject o = GSON.fromJson(reader, JsonObject.class);
            String code = string(o, "inviteCode");
            if (code == null || !Utils.isValidCode(code)) return null;
            String key = string(o, "hostKey");
            if (key == null || !key.matches("[0-9a-f]{32}")) {
                // 예전 state.json(코드만 있음) — key를 만들어 채운다
                Room r = new Room(code, newHostKey());
                saveRoom(r);
                return r;
            }
            return new Room(code, key);
        } catch (IOException | RuntimeException e) {
            LOG.warn("state.json을 읽지 못했습니다 — 초대 코드를 새로 만듭니다: {}", e.getMessage());
            return null;
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o != null ? o.get(key) : null;
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private void saveRoom(Room r) {
        JsonObject o = new JsonObject();
        o.addProperty("inviteCode", r.code());
        o.addProperty("hostKey", r.hostKey());
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
