package dev.magyul.instantp2p.common.i18n;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 모드 lang 파일(ko.json) 기반 fallback 문자열.
 * <p>
 * 모드 클라이언트는 번역 키를 자기 언어로 보여주고, 모드가 없는 클라이언트와 콘솔은 이 fallback을 본다.
 * 텍스트 컴포넌트 생성은 플랫폼마다 달라서(Adventure, 바닐라 Component) 여기서는 문자열만 다룬다.
 */
public final class I18n {

    private static final Gson GSON = new GsonBuilder().create();
    /** 레거시 서식 코드(§ + 코드 한 글자) */
    private static final Pattern LEGACY_CODE = Pattern.compile("§[0-9a-fk-orA-FK-OR]");

    /** 서버판 메시지 머리말 — 이 플러그인·모드가 보낸 메시지라는 표시 */
    public static final String PREFIX = "§b[P2P]§r ";
    private static final String SERVER_KEYS = "instant-p2p-server.";
    /**
     * 머리말을 붙이지 않는 서버판 키 — 다른 메시지 안에 끼워 쓰는 조각(버튼 문구, 서버 이름, 직결/중계)과
     * 약관 화면의 본문 줄(제목 줄에만 붙인다 — 약관 화면이 채팅창에 딱 맞아 줄바꿈이 늘면 안 된다).
     */
    private static final Set<String> NO_PREFIX = Set.of(
            "copy", "copy.hover", "status.direct", "status.relay", "server.official", "server.franchise",
            "terms.body", "terms.server_note", "terms.disclaimer", "terms.prompt", "terms.accept", "terms.deny");
    /**
     * 머리말을 붙이는 모드 키 — 서버가 관리자에게 보내는 알림. 문구는 클라이언트가 번역하므로 플랫폼이 번역 컴포넌트
     * <b>바깥 앞에</b> 머리말 조각을 붙인다({@link #hasPrefix}, {@link #plainFallback}).
     */
    private static final Set<String> PREFIXED_MOD_KEYS = Set.of(
            "instant-p2p.msg.signaling_unreachable", "instant-p2p.msg.signaling_recovered",
            "instant-p2p.msg.guest_connect_failed", "instant-p2p.msg.ip_restore_unavailable");
    private static JsonObject ko;

    static {
        try (var stream = I18n.class.getResourceAsStream("/i18n/ko.json")) {
            if (stream != null) {
                ko = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private I18n() {}

    /**
     * 키의 fallback 원문 (레거시 서식 코드 포함). 없으면 키 그대로.
     * 서버판 메시지 키({@code instant-p2p-server.*}, 조각 제외)에는 {@link #PREFIX}를 붙인다 — 모드 키는 클라이언트가 번역하므로 그대로.
     */
    public static String fallback(String key) {
        String raw = raw(key);
        return hasPrefix(key) ? PREFIX + raw : raw;
    }

    /** fallback에 인자를 채운 문자열 (머리말 포함) */
    public static String format(String key, Object... values) {
        if (ko != null) {
            return String.format(fallback(key), values);
        }
        return key;
    }

    /** 콘솔 로그용 — 로거 태그가 이미 붙으므로 머리말 없이 인자를 채운다 */
    public static String formatLog(String key, Object... values) {
        if (ko != null) {
            return String.format(raw(key), values);
        }
        return key;
    }

    /** 머리말 없는 fallback — 번역 컴포넌트의 fallback으로 쓰고 머리말은 컴포넌트 바깥에 따로 붙일 때 */
    public static String plainFallback(String key) {
        return raw(key);
    }

    private static String raw(String key) {
        JsonElement e = ko == null ? null : ko.get(key);
        return e != null ? e.getAsString() : key;
    }

    /** 이 키의 메시지에 {@link #PREFIX}를 붙이는지 — 서버판 메시지(조각 제외)와 관리자 알림 모드 키 */
    public static boolean hasPrefix(String key) {
        if (PREFIXED_MOD_KEYS.contains(key)) return true;
        return key.startsWith(SERVER_KEYS) && !NO_PREFIX.contains(key.substring(SERVER_KEYS.length()));
    }

    /** 레거시 서식 코드를 뗀 문자열 */
    public static String stripLegacy(String s) {
        return LEGACY_CODE.matcher(s).replaceAll("");
    }

    /** 맨 앞의 레거시 색 코드 글자 (예: "§c..." → 'c'). 없으면 0. */
    public static char leadingColorCode(String s) {
        if (s.length() >= 2 && s.charAt(0) == '§') {
            char c = Character.toLowerCase(s.charAt(1));
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) return c;
        }
        return 0;
    }
}
