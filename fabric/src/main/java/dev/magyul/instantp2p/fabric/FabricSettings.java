package dev.magyul.instantp2p.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.magyul.instantp2p.common.core.P2PSettings;

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
 * {@code config/instant-p2p-server/config.json} → {@link P2PSettings}. 키는 Paper config.yml과 같다.
 * 없으면 기본값으로 만들고, 빠진 키는 채워서 다시 저장한다(serverUuid 생성 포함).
 */
public final class FabricSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private FabricSettings() {}

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
        String targetModVersion = string(json, "targetModVersion", "1.2.3");
        boolean relayOnly = bool(json, "relayOnly", false);
        boolean allowBroadcast = bool(json, "allowBroadcast", false);
        List<String> channels = strings(json, "channels", List.of("normal"));
        boolean channelAnd = bool(json, "channelAnd", false);
        String serverUuid = string(json, "serverUuid", "");
        if (serverUuid.isEmpty()) {
            serverUuid = UUID.randomUUID().toString();
            json.addProperty("serverUuid", serverUuid);
        }
        String nativeLogLevel = string(json, "nativeLogLevel", "WARN");

        if (!GSON.toJson(json).equals(before)) {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(json, w);
            }
        }

        return new P2PSettings(enabled, UUID.fromString(serverUuid), targetModVersion, title, name, publicRoom,
                channels, channelAnd, allowBroadcast, relayOnly, nativeLogLevel);
    }

    private static boolean bool(JsonObject json, String key, boolean def) {
        JsonElement e = json.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            json.addProperty(key, def);
            return def;
        }
        return e.getAsBoolean();
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
