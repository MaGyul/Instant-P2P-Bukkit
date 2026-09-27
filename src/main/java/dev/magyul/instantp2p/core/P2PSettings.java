package dev.magyul.instantp2p.core;

import dev.magyul.instantp2p.webrtc.P2PConfig;

import java.util.List;
import java.util.UUID;

/**
 * 플랫폼 설정 파일에서 읽은 값. 플랫폼마다 설정 형식이 달라도(yml, json 등) 공통 코드는 이것만 본다.
 * 실행 중에 바뀌지 않는다 — 설정을 다시 읽으려면 새로 만든다.
 *
 * @param channels         설정에 적힌 그대로의 채널 목록 (정규화 전)
 * @param targetModVersion 공개 방 로비 ID에 해시로 들어간다. 클라이언트 모드 버전과 같아야 목록에 보인다.
 * @param nativeLogLevel   libdatachannel 네이티브 로그 단계 (INFO/WARN/ERROR/DEBUG)
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
        boolean relayOnly,
        String nativeLogLevel
) {

    public P2PSettings {
        channels = List.copyOf(channels);
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
