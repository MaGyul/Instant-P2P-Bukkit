package dev.magyul.instantp2p.webrtc;

import dev.magyul.instantp2p.InstantP2pBukkit;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class P2PConfig {

    private static final String BROADCAST_TAG = "broadcast";
    private static final char AND_SEPARATOR = '\u001F';
    private static final String DEFAULT_CHANNEL = "normal";
    private static final String PUBLIC_ROOMS_LOBBY_PREFIX = "__instant_p2p_public_rooms__";

    public static final String MC_VERSION = Bukkit.getMinecraftVersion();
    public static String MOD_VERSION = "1.2.3"; // 당사 개발자에게 어떻게 갈지 의논 (방 표시 여부 때문에)

    public static final int MAX_CHANNELS = 5;
    public static final int MAX_CHANNEL_LENGTH = 24;
    public static final int PUBLIC_ROOM_SHARD_COUNT = 4;

    public static final String SIGNALING_URL =
            System.getProperty("kfcudp.signaling", "ws://kite-private-cloud.kro.kr:8090");
    public static final String SIGNALING_HTTP_URL =
            SIGNALING_URL.startsWith("wss://") ? "https://" + SIGNALING_URL.substring(6)
                    : SIGNALING_URL.startsWith("ws://") ? "http://" + SIGNALING_URL.substring(5)
                      : SIGNALING_URL;

    /** coturn STUN (무인증) */
    public static final String STUN_URL =
            System.getProperty("kfcudp.stun", "stun:kite-private-cloud.kro.kr:3478");
    /** coturn TURN (정적 계정 인증) */
    public static final String TURN_URL =
            System.getProperty("kfcudp.turn", "turn:kite-private-cloud.kro.kr:3478");


    public static final String TURN_USERNAME =
            System.getProperty("kfcudp.turn.user", "minecraft");
    public static final String TURN_CREDENTIAL =
            System.getProperty("kfcudp.turn.pass", "minecraft");

    public static final long DC_BUF_HIGH =
            Long.getLong("kfcudp.pipe.dchigh", 1024 * 1024L);
    public static final long DC_BUF_LOW =
            Long.getLong("kfcudp.pipe.dclow", 256 * 1024L);
    public static final int PIPE_QUEUE_CHUNKS =
            Integer.getInteger("kfcudp.pipe.queuechunks", 64);

    private final FileConfiguration config;

    public P2PConfig(FileConfiguration config) {
        this.config = config;
        MOD_VERSION = config.getString("targetModVersion");
    }

    public boolean isEnabled() {
        return config.getBoolean("enabled", true);
    }

    public String getName() {
        return config.getString("name", "Server");
    }

    public String getTitle() {
        return config.getString("title", "");
    }

    public boolean isPublicRoom() {
        return config.getBoolean("publicRoom", true);
    }

    public boolean isRelayOnly() {
        return config.getBoolean("relayOnly", false);
    }

    public void setRelayOnly(boolean value) {
        if (isEquals("relayOnly", value)) return;
        config.set("relayOnly", value);
        save();
    }

    public boolean isAllowBroadcast() {
        return config.getBoolean("allowBroadcast", false);
    }

    public void setAllowBroadcast(boolean value) {
        if (isEquals("allowBroadcast", value)) return;
        config.set("allowBroadcast", value);
        save();
    }

    public String announcedChannel() {
        String base = getChannel();
        return isAllowBroadcast() ? base + "," + BROADCAST_TAG : base;
    }

    public static boolean isBroadcastTagged(String channelStr) {
        if (channelStr == null) return false;
        for (String part : channelStr.split(",")) {
            if (part.trim().equalsIgnoreCase(BROADCAST_TAG)) return true;
        }
        return false;
    }

    public static String stripBroadcastTag(String channelStr) {
        if (channelStr == null) return null;
        StringBuilder out = new StringBuilder();
        for (String part : channelStr.split(",", -1)) {
            if (part.trim().equalsIgnoreCase(BROADCAST_TAG)) continue;
            if (!out.isEmpty()) out.append(',');
            out.append(part);
        }
        return out.toString();
    }

    private List<String> getChannels() {
        return config.getStringList("channels");
    }

    public String getChannel() {
        return String.join(",", getChannels());
    }

    public String getChannelKey() {
        return getChannel() + (isChannelAnd() ? "&" : "|");
    }

    public boolean isChannelAnd() {
        return config.getBoolean("channelAnd", false);
    }

    public void setChannelAnd(boolean value) {
        if (isEquals("channelAnd", value)) return;
        config.set("channelAnd", value);
        save();
    }

    public static List<String> parseChannels(String text) {
        List<String> out = new ArrayList<>();
        String[] parts = (text == null ? "" : text).split(",", -1);
        int n = parts.length;
        if (n > 1 && parts[n - 1].isBlank()) n--;
        for (int i = 0; i < n; i++) {
            String t = parts[i].trim();
            if (t.isEmpty()) t = DEFAULT_CHANNEL;
            if (t.length() > MAX_CHANNEL_LENGTH) t = t.substring(0, MAX_CHANNEL_LENGTH);
            if (out.stream().anyMatch(t::equalsIgnoreCase)) continue;
            if (out.size() >= MAX_CHANNELS) break;
            out.add(t);
        }
        return List.copyOf(out);
    }

    public void setChannels(String text) {
        List<String> parsed = parseChannels(text);
        if (parsed.equals(getChannels())) return;
        config.set("channels", parsed);
        save();
    }

    public static List<String> effectiveChannels(List<String> channels, boolean and) {
        if (!and || channels.size() <= 1) return channels;
        return List.of(channels.stream().map(c -> c.toLowerCase(java.util.Locale.ROOT)).sorted()
                .collect(java.util.stream.Collectors.joining(String.valueOf(AND_SEPARATOR))));
    }

    public List<String> getEffectiveChannels() {
        return effectiveChannels(getChannels(), isChannelAnd());
    }

    public static boolean roomVisible(String hostChannels, boolean hostAnd, List<String> mine, boolean mineAnd) {
        List<String> host = effectiveChannels(parseChannels(hostChannels), hostAnd);
        List<String> me = effectiveChannels(mine, mineAnd);
        return host.stream().anyMatch(h -> me.stream().anyMatch(h::equalsIgnoreCase));
    }

    public static int publicRoomShardFor(String roomCode) {
        return Math.floorMod(roomCode.hashCode(), PUBLIC_ROOM_SHARD_COUNT);
    }

    public static String publicRoomsLobbyId(String channel, int shard) {
        // 채널 이름·모드 버전 둘 다 해시로만 나간다(서버 로그·URL에 원문이 안 남게).
        return PUBLIC_ROOMS_LOBBY_PREFIX + "_" + channelTag(channel) + "_" + versionTag() + "_" + shard;
    }

    private static String channelTag(String channel) {
        return sha256Prefix8(channel.toLowerCase(java.util.Locale.ROOT));
    }

    private static String versionTag() {
        return sha256Prefix8(MOD_VERSION);
    }

    private static String sha256Prefix8(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // 서버 고유 UUID 생성 (없으면 랜덤으로 생성 후 저장)
    public UUID getServerUUID() {
        String serverUuid = config.getString("serverUuid", null);
        if (serverUuid == null || serverUuid.isEmpty()) {
            UUID createdUUID = UUID.randomUUID();
            config.set("serverUuid", createdUUID.toString());
            save();
            return createdUUID;
        }

        return UUID.fromString(serverUuid);
    }

    private boolean isEquals(String key, Object value) {
        Object readValue = config.get(key);
        return Objects.equals(readValue, value);
    }

    private void save() {
        InstantP2pBukkit.INSTANCE.saveConfig();
    }
}
