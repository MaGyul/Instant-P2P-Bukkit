package dev.magyul.instantp2p.common.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 서버의 IP 밴 목록({@code banned-ips.json}, 바닐라·Paper 공통 형식 {@code [{"ip":"…"}]}) 스냅샷.
 * 서버 폴더(작업 디렉터리)의 파일을 읽고 수정 시각이 바뀔 때만 다시 읽는다. 파일이 없으면(Velocity 등) 비어 있다.
 */
final class BannedIps {
    private final Path file;
    private FileTime loadedAt;
    private Set<String> ips = Set.of();

    BannedIps(Path file) {
        this.file = file;
    }

    synchronized Set<String> ips() {
        try {
            if (!Files.exists(file)) {
                loadedAt = null;
                ips = Set.of();
                return ips;
            }
            FileTime modified = Files.getLastModifiedTime(file);
            if (!modified.equals(loadedAt)) {
                ips = read(file);
                loadedAt = modified;
            }
        } catch (IOException | RuntimeException ignored) {
            // 쓰는 도중에 읽는 등 — 이전 값을 그대로 쓴다
        }
        return ips;
    }

    static Set<String> read(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(r));
        }
    }

    static Set<String> parse(JsonElement root) {
        Set<String> out = new HashSet<>();
        if (root == null || !root.isJsonArray()) return out;
        JsonArray arr = root.getAsJsonArray();
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonElement ip = e.getAsJsonObject().get("ip");
            if (ip != null && ip.isJsonPrimitive()) out.add(ip.getAsString());
        }
        return out;
    }
}
