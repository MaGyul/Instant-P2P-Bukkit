package dev.magyul.instantp2p.common.signaling;

import java.util.ArrayList;
import java.util.List;

/**
 * 플랫폼과 무관한 상수와 채널·공개 방 로비 계산. 설정 값은 {@link dev.magyul.instantp2p.common.core.P2PSettings}.
 */
public final class P2PConfig {

    private static final String BROADCAST_TAG = "broadcast";
    private static final char AND_SEPARATOR = '\u001F';
    private static final String DEFAULT_CHANNEL = "normal";
    private static final String PUBLIC_ROOMS_LOBBY_PREFIX = "__instant_p2p_public_rooms__";

    public static final int MAX_CHANNELS = 5;
    public static final int MAX_CHANNEL_LENGTH = 24;
    public static final int PUBLIC_ROOM_SHARD_COUNT = 4;

    /** 지금 방을 올리는 서버 — 설정(signalingServer)이 바뀌면 {@code P2PCore.applySettings}가 고친다. */
    private static volatile SignalingServer server = SignalingServer.OFFICIAL;

    public static SignalingServer server() {
        return server;
    }

    public static void useServer(SignalingServer s) {
        server = s != null ? s : SignalingServer.OFFICIAL;
    }

    /**
     * mc-signaling — TLS(wss). 원본 1.4부터 평문 8090은 닫혔다(지문·후보·토큰이 그대로 보이므로).
     * {@link WebSocketClient}는 wss일 때 인증서를 확인한다. {@code -Dkfcudp.signaling}으로 바꿀 수 있다(테스트용, 서버 선택보다 우선).
     */
    public static String signalingUrl() {
        return System.getProperty("kfcudp.signaling", server.signalingUrl());
    }

    public static String signalingHttpUrl() {
        return toHttp(signalingUrl());
    }

    /**
     * 역할(roles) 조회는 서버 선택과 관계없이 <b>공식 서버</b>에서 — 응답은 공식 서버 개인키로 서명돼 있고
     * ({@link Roles}), 가맹점 서버는 서명 없는 다른 목록을 준다(2026-09-30 확인).
     */
    public static String officialHttpUrl() {
        return toHttp(System.getProperty("kfcudp.signaling", SignalingServer.OFFICIAL.signalingUrl()));
    }

    private static String toHttp(String ws) {
        return ws.startsWith("wss://") ? "https://" + ws.substring(6)
                : ws.startsWith("ws://") ? "http://" + ws.substring(5)
                  : ws;
    }

    /** 1.4 전용 coturn(3490, 임시 계정 방식) */
    public static String stunUrl() {
        return System.getProperty("kfcudp.stun", server.stunUrl());
    }

    public static String turnUrl() {
        return System.getProperty("kfcudp.turn", server.turnUrl());
    }

    /**
     * TURN 계정 — <b>기본값이 없다</b>. 방장 계정 인증을 거쳐 시그널링에서 받는다({@code HostAccount.turnCredentials}).
     * 이 두 값은 테스트용 덮어쓰기일 뿐이다.
     */
    public static final String TURN_USERNAME = System.getProperty("kfcudp.turn.user");
    public static final String TURN_CREDENTIAL = System.getProperty("kfcudp.turn.pass");

    private P2PConfig() {}

    /** room_update에 싣는 채널 — 방송 허용이면 broadcast 태그를 붙인다. */
    public static String announcedChannel(String base, boolean allowBroadcast) {
        return allowBroadcast ? base + "," + BROADCAST_TAG : base;
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

    public static List<String> effectiveChannels(List<String> channels, boolean and) {
        if (!and || channels.size() <= 1) return channels;
        return List.of(channels.stream().map(c -> c.toLowerCase(java.util.Locale.ROOT)).sorted()
                .collect(java.util.stream.Collectors.joining(String.valueOf(AND_SEPARATOR))));
    }

    public static int publicRoomShardFor(String roomCode) {
        return Math.floorMod(roomCode.hashCode(), PUBLIC_ROOM_SHARD_COUNT);
    }

    /** @param modVersion 대상 모드 버전 — 해시로 들어가므로 클라이언트 모드 버전과 정확히 같아야 한다. */
    public static String publicRoomsLobbyId(String channel, int shard, String modVersion) {
        // 채널 이름·모드 버전 둘 다 해시로만 나간다(서버 로그·URL에 원문이 안 남게).
        return PUBLIC_ROOMS_LOBBY_PREFIX + "_" + channelTag(channel) + "_" + versionTag(modVersion) + "_" + shard;
    }

    private static String channelTag(String channel) {
        return sha256Prefix8(channel.toLowerCase(java.util.Locale.ROOT));
    }

    private static String versionTag(String modVersion) {
        return sha256Prefix8(modVersion);
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
}
