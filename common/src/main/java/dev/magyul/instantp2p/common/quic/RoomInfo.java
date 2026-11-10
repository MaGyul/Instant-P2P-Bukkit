package dev.magyul.instantp2p.common.quic;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.magyul.instantp2p.common.core.P2PCore;
import dev.magyul.instantp2p.common.core.P2PSettings;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * [1.3.0] 방장 → 시그널링 서버로 보내는 방 정보({@code room})와 접근 목록({@code acl}) JSON(원본 1.5.1 형식).
 * <p>
 * {@code opts.mode}·{@code opts.signed}는 서버 폴더의 {@code server.properties}에서 읽는다(바닐라와 같은 기본값 —
 * gamemode=survival, enforce-secure-profile=true). 파일이 없는 환경(Velocity 등)은 기본값을 쓰므로 실제 백엔드 설정과
 * 다를 수 있다. 시그널링 서버는 {@code signed=false}인 공개 방을 목록에서 뺀다(알림 {@code unsigned}).
 */
final class RoomInfo {
    private RoomInfo() {}

    static String json(P2PCore core) {
        P2PSettings s = core.settings();
        boolean pub = core.host().publicRoomActive();
        String title = s.title().isEmpty() ? core.platform().motd() : s.title();
        Properties props = serverProperties();
        JsonObject o = new JsonObject();
        o.addProperty("public", pub);
        o.addProperty("title", pub && title != null ? title : "");
        o.addProperty("max", core.maxPlayers());
        JsonObject opts = new JsonObject();
        opts.addProperty("mode", gameMode(props));
        opts.addProperty("cheats", false); // 전용 서버에는 "치트 허용" 개념이 없다 — 명령은 op 권한으로 따로 막힌다
        opts.addProperty("broadcast", s.allowBroadcast());
        opts.addProperty("relay_forced", false); // 서버판은 방장 쪽 중계 강제가 없다
        opts.addProperty("signed", signedChat(props));
        o.add("opts", opts);
        return o.toString();
    }

    /** 밴 목록(대시 없는 UUID)과 허용 목록(서버판은 화이트리스트를 따로 보내지 않으므로 null). */
    static String aclJson(P2PCore core) {
        JsonObject o = new JsonObject();
        JsonArray banned = new JsonArray();
        int n = 0;
        for (UUID id : core.platform().bannedPlayers()) {
            if (n++ >= 500) break;
            banned.add(id.toString().replace("-", ""));
        }
        o.add("banned", banned);
        o.add("allow", JsonNull.INSTANCE);
        return o.toString();
    }

    static String gameMode(Properties props) {
        String m = props.getProperty("gamemode", "survival").trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "0", "survival" -> "survival";
            case "1", "creative" -> "creative";
            case "2", "adventure" -> "adventure";
            case "3", "spectator" -> "spectator";
            default -> "survival";
        };
    }

    static boolean signedChat(Properties props) {
        return !"false".equalsIgnoreCase(props.getProperty("enforce-secure-profile", "true").trim());
    }

    private static Properties serverProperties() {
        Properties p = new Properties();
        Path f = Path.of("server.properties");
        if (Files.isRegularFile(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException | IllegalArgumentException ignored) {
            }
        }
        return p;
    }
}
