package dev.magyul.instantp2p.paper;

import dev.magyul.instantp2p.common.core.P2PPlatform;
import dev.magyul.instantp2p.common.core.P2PSender;
import dev.magyul.instantp2p.common.core.P2PText;
import dev.magyul.instantp2p.common.core.P2PSettings;
import dev.magyul.instantp2p.common.i18n.I18n;
import dev.magyul.instantp2p.common.network.packet.RoomState;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;

import static dev.magyul.instantp2p.paper.PaperEntry.LOGGER;

final class PaperPlatform implements P2PPlatform {

    private final JavaPlugin plugin;
    private volatile P2PSettings settings;
    private final ServerText text;
    private final String minecraftVersion;

    PaperPlatform(JavaPlugin plugin, P2PSettings settings, ServerText text, String minecraftVersion) {
        this.plugin = plugin;
        this.settings = settings;
        this.text = text;
        this.minecraftVersion = minecraftVersion;
    }

    @Override
    public P2PSettings settings() {
        return settings;
    }

    @Override
    public P2PSettings loadSettings() throws Exception {
        return PaperSettings.load(plugin);
    }

    @Override
    public void saveSettings(java.util.Map<String, Object> values) throws Exception {
        PaperSettings.save(plugin, values);
    }

    @Override
    public void applySettings(P2PSettings settings) {
        this.settings = settings;
    }

    @Override
    public Collection<UUID> bannedPlayers() {
        // TODO announcer 스레드에서 불린다 — 서버 스레드에서 갱신하는 스냅샷으로 바꿀 것
        return Bukkit.getBannedPlayers().stream().map(OfflinePlayer::getUniqueId).toList();
    }

    @Override
    public int maxPlayers() {
        return Bukkit.getMaxPlayers();
    }

    @Override
    public String minecraftVersion() {
        return minecraftVersion;
    }

    @Override
    public int listenPort() {
        return Bukkit.getServer().getPort();
    }

    @Override
    public Path dataFolder() {
        return plugin.getDataFolder().toPath();
    }

    /** 서버 MOTD(레거시 서식 문자열). Paper에서 deprecated지만 Spigot과 공통으로 쓸 수 있는 건 이것뿐이다. */
    @Override
    @SuppressWarnings("deprecation")
    public String motd() {
        return Bukkit.getMotd();
    }

    @Override
    public P2PSender console() {
        return (key, args) -> text.send(Bukkit.getConsoleSender(), key, P2PText.plain(args));
    }

    @Override
    public boolean isOnline(UUID player) {
        return Bukkit.getPlayer(player) != null;
    }

    @Override
    public String playerName(UUID player) {
        Player p = Bukkit.getPlayer(player);
        return p != null ? p.getName() : null;
    }

    @Override
    public boolean isHost(UUID player) {
        if (player.equals(settings.serverUuid())) return true;
        Player p = Bukkit.getPlayer(player);
        return p != null && p.hasPermission("instantp2p.host");
    }

    @Override
    public void kick(UUID player, String translationKey, Object... args) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) text.kick(p, translationKey, args);
    }

    @Override
    public void notifyAdmins(String translationKey, Object... args) {
        LOGGER.info(I18n.stripLegacy(I18n.formatLog(translationKey, args))); // 콘솔에는 서식 코드 없이
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("instantp2p.notify.host")) {
                text.send(player, translationKey, args);
            }
        }
    }

    @Override
    public void runSync(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void broadcastRoomState(byte[] payload) {
        Bukkit.getServer().sendPluginMessage(plugin, RoomState.ID, payload);
    }
}
