package dev.magyul.instantp2p.common.webrtc;

import dev.magyul.instantp2p.common.core.P2PCore;
import dev.magyul.instantp2p.common.core.P2PPlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 호스트 수명주기와 libdatachannel 네이티브 로드.
 * <p>
 * startHost() → WebRtcHost (libdatachannel-java)
 * <p>
 * 서버 쪽은 DataChannel만 쓰므로 libwebrtc 전체(webrtc-java) 대신 libdatachannel을 쓴다.
 * webrtc-java 리눅스 네이티브는 libpulse/X11/udev/dbus에 링크돼 있어 헤드리스 서버에서
 * 로드 자체가 안 된다. 조인자(모드, libwebrtc)와는 표준 WebRTC라 그대로 붙는다.
 */
public class WebRtcBridge {

    public static final Logger LOG = LoggerFactory.getLogger("webrtc-bridge");

    private final P2PCore core;

    // 네이티브 호스트
    private volatile WebRtcHost webRtcHost;

    // 공개 방 목록 announcer — 공개 방 설정이면 호스트 시작 후 같이 시작,
    // 호스트가 닫힐 때 같이 멈춘다(publishPublicRoom/unpublishPublicRoom).
    private final PublicRoomAnnouncer publicRoomAnnouncer;

    public WebRtcBridge(P2PCore core) {
        this.core = core;
        this.publicRoomAnnouncer = new PublicRoomAnnouncer(core.platform());
    }

    // ── Host (Java 네이티브 — WebRtcHost) ─────────────────────────────────────

    public void startHost(String roomId, String target) {
        stopHost();
        ensureNativeLoaded(core.platform());
        Roles.refreshAsync();

        LOG.info("[WebRTC] Starting native host: room={} target={}", roomId, target);
        // 전송 구현은 여기서만 고른다 (QUIC 전환 시 이 줄만 바뀐다)
        WebRtcHost host = new WebRtcHost(core, new LibDataChannelTransport(core.settings().relayOnly()), roomId, target);
        webRtcHost = host;
        host.start();
    }

    public void stopHost() {
        WebRtcHost host = webRtcHost;
        if (host != null) {
            LOG.info("[WebRTC] Stopping native host");
            host.close();
            webRtcHost = null;
        }
        unpublishPublicRoom();
    }

    /** 방을 공개 목록에 올리거나 이미 올라와 있으면 정보를 갱신한다 — PublicRoomAnnouncer 클래스
     * 주석 참고(방 코드·채널이 그대로면 재접속 없이 메시지만 보낸다). hostUuid는 개인 차단(=밴)
     * 기능용(P2PBanManager 클래스 주석 참고). */
    public void publishPublicRoom(String roomCode, String title, String hostNickname, String hostUuid,
                                  int currentPlayers, int maxPlayers) {
        publicRoomAnnouncer.publish(roomCode, title, hostNickname, hostUuid, currentPlayers, maxPlayers);
    }

    public void unpublishPublicRoom() {
        publicRoomAnnouncer.stop();
    }

    /** 밴 목록이 바뀌었을 때 — 지금 공개된 방이 있으면 즉시 새 밴 목록을 실어 재공지한다
     * (PublicRoomAnnouncer.republishNow 참고). */
    public void republishPublicRoomIfActive() {
        publicRoomAnnouncer.republishNow();
    }

    /** 공개 방 인원(현재/최대)이 바뀔 때마다 호출 — 재접속 없이 메시지만 보낸다(PublicRoomAnnouncer 참고). */
    public void updatePublicRoomPlayerCount(int currentPlayers, int maxPlayers) {
        publicRoomAnnouncer.updatePlayerCount(currentPlayers, maxPlayers);
    }

    // ── 네이티브 로드 ─────────────────────────────────────────────────────────

    /** libdatachannel 네이티브 로드 — {@link NativeLibrary} 참고. */
    public static void ensureNativeLoaded(P2PPlatform platform) {
        // 네이티브 로그는 VERBOSE로 slf4j에 흘러오므로 이 로거만 조정한다
        platform.setLoggerLevel("tel.schich.libdatachannel", platform.settings().nativeLogLevel());
        NativeLibrary.load(platform.dataFolder());
    }
}
