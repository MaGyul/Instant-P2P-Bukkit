package dev.magyul.instantp2p.paper;

import dev.magyul.instantp2p.common.core.P2PSettings;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
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
                config.getBoolean("relayOnly", false),
                config.getInt("udpPort", 0));
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
