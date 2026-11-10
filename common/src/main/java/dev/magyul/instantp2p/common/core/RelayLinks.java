package dev.magyul.instantp2p.common.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 중계 강제 접속자의 "계정 ↔ 지난 합성 주소" 기록({@code relay-links.json}).
 * <p>
 * 중계 강제 접속자의 서버 쪽 주소는 접속할 때마다 바뀌는 합성 주소라 {@code /ban-ip}가 재접속을 막지 못한다.
 * 그래서 로그인할 때 이 계정이 전에 쓴 합성 주소가 IP 밴 목록에 있는지 본다({@link P2PCore#isLinkedIpBanned}).
 * 실제 IP는 담지 않는다 — 합성 주소만(해시라 원래 IP를 알 수 없다). 계정마다 최근 {@link #MAX_PER_ACCOUNT}개.
 */
final class RelayLinks {
    private static final Logger LOG = LoggerFactory.getLogger("Instant-P2P");
    static final int MAX_PER_ACCOUNT = 16;
    private static final int MAX_ACCOUNTS = 2000;
    private static final Gson GSON = new Gson();

    private final Path file;
    private final LinkedHashMap<String, LinkedHashSet<String>> byAccount = new LinkedHashMap<>();

    RelayLinks(Path file) {
        this.file = file;
        load();
    }

    /** 이 계정이 이 합성 주소로 들어왔다고 기록한다. 새로 보는 주소일 때만 파일에 쓴다. */
    synchronized void remember(UUID account, String address) {
        LinkedHashSet<String> set = byAccount.remove(account.toString());
        if (set == null) set = new LinkedHashSet<>();
        boolean isNew = set.add(address);
        while (set.size() > MAX_PER_ACCOUNT) set.remove(set.iterator().next());
        byAccount.put(account.toString(), set); // 가장 최근 계정이 뒤로
        while (byAccount.size() > MAX_ACCOUNTS) byAccount.remove(byAccount.keySet().iterator().next());
        if (isNew) save();
    }

    synchronized Set<String> addresses(UUID account) {
        LinkedHashSet<String> set = byAccount.get(account.toString());
        return set == null ? Set.of() : new LinkedHashSet<>(set);
    }

    private void load() {
        try {
            if (!Files.isRegularFile(file)) return;
            JsonObject o = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (o == null) return;
            o.entrySet().forEach(e -> {
                LinkedHashSet<String> set = new LinkedHashSet<>();
                if (e.getValue().isJsonArray()) e.getValue().getAsJsonArray().forEach(a -> set.add(a.getAsString()));
                byAccount.put(e.getKey(), set);
            });
        } catch (IOException | RuntimeException e) {
            LOG.warn("[host] relay-links.json을 읽지 못했다 — 비어 있는 것으로 시작: {}", e.toString());
        }
    }

    private void save() {
        try {
            JsonObject o = new JsonObject();
            for (var e : byAccount.entrySet()) {
                JsonArray arr = new JsonArray();
                e.getValue().forEach(arr::add);
                o.add(e.getKey(), arr);
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("relay-links.json.tmp");
            Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warn("[host] relay-links.json을 쓰지 못했다: {}", e.toString());
        }
    }
}
