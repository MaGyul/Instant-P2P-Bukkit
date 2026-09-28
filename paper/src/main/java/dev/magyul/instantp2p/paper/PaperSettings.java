package dev.magyul.instantp2p.paper;

import dev.magyul.instantp2p.common.core.P2PSettings;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/** config.yml → {@link P2PSettings} */
final class PaperSettings {

    private PaperSettings() {}

    static P2PSettings load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        return new P2PSettings(
                config.getBoolean("enabled", true),
                serverUuid(plugin, config),
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
    private static UUID serverUuid(JavaPlugin plugin, FileConfiguration config) {
        String serverUuid = config.getString("serverUuid", null);
        if (serverUuid == null || serverUuid.isEmpty()) {
            UUID created = UUID.randomUUID();
            config.set("serverUuid", created.toString());
            plugin.saveConfig();
            return created;
        }
        return UUID.fromString(serverUuid);
    }
}
