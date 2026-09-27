package dev.magyul.instantp2p.i18n;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

    /** 키의 fallback 원문 (레거시 서식 코드 포함). 없으면 키 그대로. */
    public static String fallback(String key) {
        JsonElement e = ko == null ? null : ko.get(key);
        return e != null ? e.getAsString() : key;
    }

    /** fallback에 인자를 채운 문자열 — 콘솔 로그용. */
    public static String format(String key, Object... values) {
        if (ko != null) {
            return String.format(fallback(key), values);
        }
        return key;
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
