package dev.magyul.instantp2p.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import java.util.UUID;

/**
 * ops.json, banned-players.json을 직접 읽는다. 1.21.x 안에서도 해당 API가 바뀌어서(1.21.9: GameProfile →
 * NameAndId, 권한 레벨 → PermissionSet) 파일 형식(uuid 필드)이 오히려 버전 무관하다.
 * 서버는 목록이 바뀌면 곧바로 파일에 저장하므로 수정 시각으로 캐시한다.
 */
public final class ServerListFiles {

    private final Path file;
    private volatile FileTime loadedAt;
    private volatile Set<UUID> uuids = Set.of();

    public ServerListFiles(Path file) {
        this.file = file;
    }

    public boolean contains(UUID id) {
        return uuids().contains(id);
    }

    /** 스냅샷 (아무 스레드) */
    public synchronized Set<UUID> uuids() {
        try {
            if (!Files.exists(file)) {
                uuids = Set.of();
                loadedAt = null;
                return uuids;
            }
            FileTime modified = Files.getLastModifiedTime(file);
            if (!modified.equals(loadedAt)) {
                uuids = read(file);
                loadedAt = modified;
            }
        } catch (IOException | RuntimeException ignored) {
            // 쓰는 도중에 읽는 등 — 이전 값을 그대로 쓴다
        }
        return uuids;
    }

    private static Set<UUID> read(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonArray()) return Set.of();
            JsonArray arr = root.getAsJsonArray();
            return Set.copyOf(arr.asList().stream()
                    .filter(JsonElement::isJsonObject)
                    .map(JsonElement::getAsJsonObject)
                    .map(o -> o.get("uuid"))
                    .filter(e -> e != null && e.isJsonPrimitive())
                    .map(e -> parse(e.getAsString()))
                    .filter(java.util.Objects::nonNull)
                    .toList());
        }
    }

    private static UUID parse(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
