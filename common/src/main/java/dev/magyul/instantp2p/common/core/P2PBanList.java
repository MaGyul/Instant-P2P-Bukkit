package dev.magyul.instantp2p.common.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 플러그인 자체 IP 밴 목록({@code p2p-bans.json}) — {@code /p2p ban-ip}·{@code pardon-ip}·{@code ban-list}.
 * Velocity 같은 프록시에는 IP 밴 목록이 없고, 중계 강제 접속자는 서버 쪽 주소가 접속할 때마다 바뀌어 바닐라 IP 밴으로 못 막는다.
 * <p>
 * 항목: 직결 접속자는 <b>주소</b>(실제 IPv4, 바닐라 {@code banned-ips.json}과 같은 정보)로 막고, 중계 강제 접속자는
 * <b>계정 UUID</b>로 막는다 — 중계 접속자의 원래 IP는 서버도 모르고 이 파일에도 남지 않는다(합성 주소와 UUID뿐).
 * 화면에는 번호({@code #n})와 주소를 보인다 — 중계 접속자는 합성 주소(원래 IP는 어디에도 없다), 직결 접속자는 IP가 보일 것을 감안하고 접속한다.
 */
final class P2PBanList {
    private static final Logger LOG = LoggerFactory.getLogger("Instant-P2P");
    private static final Gson GSON = new Gson();

    /** 밴 항목 하나. {@code addr}는 직결이면 실제 주소, 중계면 합성 주소(참고용). */
    record Entry(int id, UUID uuid, String name, String addr, boolean relay, String reason, long createdAt) {}

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();
    private int nextId = 1;

    P2PBanList(Path file) {
        this.file = file;
        load();
    }

    /** 밴을 추가한다. 같은 대상(중계는 UUID, 직결은 주소)이 이미 있으면 사유만 바꾼다. */
    synchronized Entry add(UUID uuid, String name, String addr, boolean relay, String reason) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            boolean same = relay ? (e.relay && uuid != null && uuid.equals(e.uuid)) : (!e.relay && addr != null && addr.equals(e.addr));
            if (same) {
                Entry upd = new Entry(e.id, uuid != null ? uuid : e.uuid, name != null ? name : e.name, addr, relay, reason, e.createdAt);
                entries.set(i, upd);
                save();
                return upd;
            }
        }
        Entry e = new Entry(nextId++, uuid, name, addr, relay, reason, System.currentTimeMillis());
        entries.add(e);
        save();
        return e;
    }

    /** 번호로 푼다. 푼 항목. */
    synchronized List<Entry> removeById(int id) {
        return removeWhere(e -> e.id == id);
    }

    /** 이름(마지막으로 본 닉네임)·UUID·주소 중 맞는 것을 모두 푼다. 푼 항목. */
    synchronized List<Entry> remove(String target) {
        return removeWhere(e -> (e.name != null && e.name.equalsIgnoreCase(target))
                || (e.uuid != null && e.uuid.toString().equalsIgnoreCase(target))
                || (e.addr != null && e.addr.equals(target)));
    }

    private List<Entry> removeWhere(java.util.function.Predicate<Entry> match) {
        List<Entry> removed = new ArrayList<>();
        entries.removeIf(e -> {
            if (!match.test(e)) return false;
            removed.add(e);
            return true;
        });
        if (!removed.isEmpty()) save();
        return removed;
    }

    /** 로그인 검사: 이 계정·주소가 막혀 있으면 그 항목. 중계 항목은 계정(UUID)으로, 직결 항목은 주소로 맞춘다. */
    synchronized Optional<Entry> find(UUID uuid, String addr) {
        for (Entry e : entries) {
            if (e.relay) {
                if (uuid != null && uuid.equals(e.uuid)) return Optional.of(e);
            } else if (addr != null && addr.equals(e.addr)) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    synchronized List<Entry> all() {
        return List.copyOf(entries);
    }

    // ── 저장 ────────────────────────────────────────────────────────────────

    private void load() {
        try {
            if (!Files.isRegularFile(file)) return;
            JsonElement root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonElement.class);
            if (root == null || !root.isJsonArray()) return;
            for (JsonElement el : root.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                int id = o.has("id") ? o.get("id").getAsInt() : nextId;
                UUID uuid = o.has("uuid") && !o.get("uuid").isJsonNull() ? UUID.fromString(o.get("uuid").getAsString()) : null;
                entries.add(new Entry(id, uuid, str(o, "name"), str(o, "addr"),
                        o.has("relay") && o.get("relay").getAsBoolean(),
                        o.has("reason") ? o.get("reason").getAsString() : "",
                        o.has("created") ? o.get("created").getAsLong() : 0L));
                nextId = Math.max(nextId, id + 1);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("[host] p2p-bans.json을 읽지 못했다 — 비어 있는 것으로 시작합니다. 파일을 확인하세요: {}", e.toString());
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private void save() {
        try {
            JsonArray arr = new JsonArray();
            for (Entry e : entries) {
                JsonObject o = new JsonObject();
                o.addProperty("id", e.id);
                if (e.uuid != null) o.addProperty("uuid", e.uuid.toString());
                if (e.name != null) o.addProperty("name", e.name);
                if (e.addr != null) o.addProperty("addr", e.addr);
                o.addProperty("relay", e.relay);
                o.addProperty("reason", e.reason == null ? "" : e.reason);
                o.addProperty("created", e.createdAt);
                arr.add(o);
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("p2p-bans.json.tmp");
            Files.writeString(tmp, GSON.toJson(arr), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warn("[host] p2p-bans.json을 쓰지 못했다: {}", e.toString());
        }
    }
}
