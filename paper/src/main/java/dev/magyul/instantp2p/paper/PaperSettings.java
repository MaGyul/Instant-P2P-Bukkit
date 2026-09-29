package dev.magyul.instantp2p.paper;

import dev.magyul.instantp2p.common.core.P2PSettings;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * config.yml → {@link P2PSettings}.
 * <p>
 * Bukkit의 {@code getConfig()/reloadConfig()}는 쓰지 않는다 — 문법 오류가 있으면 예외 대신 오류 로그만 남기고 <b>빈 설정</b>을
 * 돌려줘서, 그걸 정상으로 읽고 serverUuid를 새로 만들어 저장하면 사용자 파일이 기본값으로 덮인다. 여기서는 직접 파싱해
 * 오류면 예외를 던지고 파일에 손대지 않는다. 저장은 파싱에 성공했고 serverUuid가 비어 있을 때 한 번뿐이다.
 */
final class PaperSettings {

    private PaperSettings() {}

    /** @throws InvalidConfigurationException 문법 오류 (메시지에 줄·칸 위치가 들어 있다) */
    static P2PSettings load(JavaPlugin plugin) throws IOException, InvalidConfigurationException {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        return new P2PSettings(
                config.getBoolean("enabled", false),
                serverUuid(file, config),
                config.getString("targetModVersion", "auto"),
                config.getString("title", ""),
                config.getString("name", "Server"),
                config.getBoolean("publicRoom", true),
                config.getStringList("channels"),
                config.getBoolean("channelAnd", false),
                config.getBoolean("allowBroadcast", false),
                config.getInt("udpPort", 0),
                config.getBoolean("maxPlayersEnabled", false),
                config.getInt("maxPlayers", 0));
    }

    /**
     * 주어진 키만 바꿔 저장한다({@code /p2p max-players}). 파일을 다시 파싱해서 바꾸므로 다른 값과 주석은 그대로 남는다
     * (1.18.1+ YamlConfiguration은 주석을 보존). 문법 오류면 예외, 파일 무변경.
     */
    static void save(JavaPlugin plugin, Map<String, Object> values) throws IOException, InvalidConfigurationException {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        boolean addComment = !config.contains("maxPlayersEnabled") && values.containsKey("maxPlayersEnabled");
        values.forEach(config::set);
        if (addComment) {
            // 1.1.0 이전 config.yml에는 이 키가 없다 — 끝에 붙으므로 설명을 달아 둔다
            config.setComments("maxPlayersEnabled", List.of(
                    "P2P 최대 인원 (/p2p max-players on|off|set <인원>) — 켜면 P2P 접속은 서버 정원 대신 maxPlayers 기준으로 막습니다",
                    "maxPlayers는 서버 정원(max-players)보다 클 수 없습니다"));
        }
        config.save(file);
    }

    /** 서버 고유 UUID (없으면 랜덤으로 생성 후 저장) */
    private static UUID serverUuid(File file, YamlConfiguration config) throws IOException, InvalidConfigurationException {
        String serverUuid = config.getString("serverUuid", null);
        if (serverUuid == null || serverUuid.isEmpty()) {
            UUID created = UUID.randomUUID();
            config.set("serverUuid", created.toString());
            config.save(file);
            return created;
        }
        try {
            return UUID.fromString(serverUuid.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidConfigurationException("serverUuid 형식이 잘못되었습니다: " + serverUuid);
        }
    }
}
