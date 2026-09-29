package dev.magyul.instantp2p.common.core;

import dev.magyul.instantp2p.common.signaling.P2PConfig;

import java.util.List;
import java.util.UUID;

/**
 * 플랫폼 설정 파일에서 읽은 값. 플랫폼마다 설정 형식이 달라도(yml, json 등) 공통 코드는 이것만 본다.
 * 실행 중에 바뀌지 않는다 — 설정을 다시 읽으려면 새로 만든다.
 *
 * @param channels         채널 목록 (원본 규칙으로 정규화된다)
 * @param targetModVersion 공개 방 로비 ID에 해시로 들어간다. 클라이언트 모드 버전과 같아야 목록에 보인다. {@code auto}면 시그널링 서버 값
 *                         ({@link dev.magyul.instantp2p.common.signaling.ModVersion}).
 * @param udpPort          QUIC이 쓸 UDP 포트. 0이면 실행할 때마다 임의 포트(방화벽에서 열어 두면 직결이 잘 된다).
 * @param maxPlayersEnabled P2P 최대 인원을 쓸지 ({@code /p2p max-players on|off})
 * @param maxPlayers       P2P 최대 인원 — 켜져 있으면 터널 접속은 서버 정원 대신 이 값(서버 정원 이하)으로 막는다. 0이면 아직 안 정함.
 */
public record P2PSettings(
        boolean enabled,
        UUID serverUuid,
        String targetModVersion,
        String title,
        String name,
        boolean publicRoom,
        List<String> channels,
        boolean channelAnd,
        boolean allowBroadcast,
        int udpPort,
        boolean maxPlayersEnabled,
        int maxPlayers
) {

    public P2PSettings {
        // 원본이 채널 입력을 다듬는 규칙과 같게 (공백 제거, 대소문자 무시 중복 제거, 최대 5개·24자, 비면 normal)
        channels = P2PConfig.parseChannels(String.join(",", channels));
        if (udpPort < 0 || udpPort > 65535) udpPort = 0;
        if (maxPlayers < 0) maxPlayers = 0;
    }

    /** P2P 최대 인원 없이 (테스트용) */
    public P2PSettings(boolean enabled, UUID serverUuid, String targetModVersion, String title, String name,
                       boolean publicRoom, List<String> channels, boolean channelAnd, boolean allowBroadcast, int udpPort) {
        this(enabled, serverUuid, targetModVersion, title, name, publicRoom, channels, channelAnd, allowBroadcast, udpPort,
                false, 0);
    }

    /** serverUuid만 바꾼 사본 — 리로드 때 이전 값을 유지하려고 쓴다 */
    public P2PSettings withServerUuid(UUID uuid) {
        return new P2PSettings(enabled, uuid, targetModVersion, title, name, publicRoom, channels, channelAnd,
                allowBroadcast, udpPort, maxPlayersEnabled, maxPlayers);
    }

    /** P2P 최대 인원만 바꾼 사본 — {@code /p2p max-players} */
    public P2PSettings withMaxPlayers(boolean enabled, int max) {
        return new P2PSettings(this.enabled, serverUuid, targetModVersion, title, name, publicRoom, channels, channelAnd,
                allowBroadcast, udpPort, enabled, max);
    }

    /** P2P 최대 인원이 실제로 적용되는지 (켜져 있고 값이 정해져 있음) */
    public boolean limitsMaxPlayers() {
        return maxPlayersEnabled && maxPlayers > 0;
    }

    /** 공개 방 announce에 들어가는 값이 달라졌는지 */
    public boolean publicRoomDiffers(P2PSettings o) {
        return publicRoom != o.publicRoom || channelAnd != o.channelAnd || allowBroadcast != o.allowBroadcast
                || !title.equals(o.title) || !name.equals(o.name) || !channels.equals(o.channels)
                || !targetModVersion.equals(o.targetModVersion);
    }

    /** 쉼표로 이은 채널 문자열 */
    public String channel() {
        return String.join(",", channels);
    }

    /** room_update에 싣는 채널 — 방송 허용이면 broadcast 태그를 붙인다. */
    public String announcedChannel() {
        return P2PConfig.announcedChannel(channel(), allowBroadcast);
    }

    /** 공개 방 로비를 고르는 데 쓰는 채널 목록 (AND 모드면 하나로 합쳐진다) */
    public List<String> effectiveChannels() {
        return P2PConfig.effectiveChannels(channels, channelAnd);
    }
}
