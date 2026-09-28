package dev.magyul.instantp2p.common.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JSON 설정 파일 → {@link P2PSettings} (Fabric, Velocity). 키는 Paper config.yml과 같다.
 * 없으면 기본값으로 만들고, 빠진 키는 채워서 다시 저장한다(serverUuid 생성 포함). 모르는 키는 그대로 둔다.
 */
public final class JsonSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private JsonSettings() {}

    public static P2PSettings load(Path file) throws IOException {
        JsonObject json = new JsonObject();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject read = GSON.fromJson(r, JsonObject.class);
                if (read != null) json = read;
            }
        }
        String before = GSON.toJson(json);

        boolean enabled = bool(json, "enabled", true);
        String name = string(json, "name", "Server");
        String title = string(json, "title", "");
        boolean publicRoom = bool(json, "publicRoom", true);
        String targetModVersion = string(json, "targetModVersion", "auto");
        boolean relayOnly = bool(json, "relayOnly", false);
        int udpPort = integer(json, "udpPort", 0);
        boolean allowBroadcast = bool(json, "allowBroadcast", false);
        List<String> channels = strings(json, "channels", List.of("normal"));
        boolean channelAnd = bool(json, "channelAnd", false);
        String serverUuid = string(json, "serverUuid", "");
        if (serverUuid.isEmpty()) {
            serverUuid = UUID.randomUUID().toString();
            json.addProperty("serverUuid", serverUuid);
        }

        if (!GSON.toJson(json).equals(before)) {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(json, w);
            }
        }

        return new P2PSettings(enabled, UUID.fromString(serverUuid), targetModVersion, title, name, publicRoom,
                channels, channelAnd, allowBroadcast, relayOnly, udpPort);
    }

    /** 플랫폼 전용 문자열 키 (예: Velocity의 minecraftVersion). 없으면 기본값을 채워 저장한다. */
    public static String extraString(Path file, String key, String def) throws IOException {
        JsonObject json = new JsonObject();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject read = GSON.fromJson(r, JsonObject.class);
                if (read != null) json = read;
            }
        }
        if (json.has(key) && json.get(key).isJsonPrimitive()) return json.get(key).getAsString();
        json.addProperty(key, def);
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(json, w);
        }
        return def;
    }

    private static boolean bool(JsonObject json, String key, boolean def) {
        JsonElement e = json.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            json.addProperty(key, def);
            return def;
        }
        return e.getAsBoolean();
    }

    private static int integer(JsonObject json, String key, int def) {
        JsonElement e = json.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            json.addProperty(key, def);
            return def;
        }
        try {
            return e.getAsInt();
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static String string(JsonObject json, String key, String def) {
        JsonElement e = json.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            json.addProperty(key, def);
            return def;
        }
        return e.getAsString();
    }

    private static List<String> strings(JsonObject json, String key, List<String> def) {
        JsonElement e = json.get(key);
        if (e == null || !e.isJsonArray()) {
            JsonArray arr = new JsonArray();
            def.forEach(arr::add);
            json.add(key, arr);
            return def;
        }
        List<String> out = new ArrayList<>();
        for (JsonElement item : e.getAsJsonArray()) out.add(item.getAsString());
        return out;
    }
}
