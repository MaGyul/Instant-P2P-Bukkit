package dev.magyul.instantp2p.common.core;

import dev.magyul.instantp2p.common.core.P2PCore;
import dev.magyul.instantp2p.common.quic.QuicHost;
import dev.magyul.instantp2p.common.signaling.PublicRoomAnnouncer;
import dev.magyul.instantp2p.common.signaling.Roles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 호스트 수명주기와 공개 방 announce.
 * <p>
 * 전송은 QUIC(kwik, 순수 Java) — 원본 모드 1.3에서 WebRTC를 대체했다. 네이티브 라이브러리가 필요 없다.
 */
public class P2PBridge {

    public static final Logger LOG = LoggerFactory.getLogger("p2p-bridge");

    private final P2PCore core;

    private volatile QuicHost host;

    // 공개 방 목록 announcer — 공개 방 설정이면 호스트 시작 후 같이 시작,
    // 호스트가 닫힐 때 같이 멈춘다(publishPublicRoom/unpublishPublicRoom).
    private final PublicRoomAnnouncer publicRoomAnnouncer;

    public P2PBridge(P2PCore core) {
        this.core = core;
        this.publicRoomAnnouncer = new PublicRoomAnnouncer(core.platform());
    }

    public void startHost(String roomId, String target) throws Exception {
        stopHost();
        Roles.refreshAsync();

        LOG.info("[QUIC] Starting host: room={} target={}", roomId, target);
        QuicHost h = new QuicHost(core, roomId, target);
        host = h;
        h.start();
    }

    public void stopHost() {
        QuicHost h = host;
        if (h != null) {
            LOG.info("[QUIC] Stopping host");
            h.close();
            host = null;
        }
        unpublishPublicRoom();
    }

    /** 방을 공개 목록에 올리거나 이미 올라와 있으면 정보를 갱신한다 — PublicRoomAnnouncer 클래스
     * 주석 참고(방 코드·채널이 그대로면 재접속 없이 메시지만 보낸다). */
    public void publishPublicRoom(String roomCode, String title, String hostNickname, String hostUuid,
                                  int currentPlayers, int maxPlayers) {
        publicRoomAnnouncer.publish(roomCode, title, hostNickname, hostUuid, currentPlayers, maxPlayers);
    }

    public void unpublishPublicRoom() {
        publicRoomAnnouncer.stop();
    }

    /** 밴 목록이 바뀌었을 때 — 지금 공개된 방이 있으면 즉시 새 밴 목록을 실어 재공지한다. */
    public void republishPublicRoomIfActive() {
        publicRoomAnnouncer.republishNow();
    }

    /** 공개 방 인원(현재/최대)이 바뀔 때마다 호출 — 재접속 없이 메시지만 보낸다. */
    public void updatePublicRoomPlayerCount(int currentPlayers, int maxPlayers) {
        publicRoomAnnouncer.updatePlayerCount(currentPlayers, maxPlayers);
    }
}
