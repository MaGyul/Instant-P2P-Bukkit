package dev.magyul.instantp2p.common.webrtc;

import dev.magyul.instantp2p.common.core.P2PCore;
import dev.magyul.instantp2p.common.core.P2PPlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tel.schich.libdatachannel.LibDataChannel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

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
        WebRtcHost host = new WebRtcHost(core, roomId, target);
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

    /** 같은 JVM에서 한 번만 로드한다 (클래스로더와 무관하게 .so는 프로세스 단위). */
    private static volatile boolean nativeLoaded = false;

    /**
     * libdatachannel 네이티브를 데이터 폴더에 풀어서 로드한다.
     * <p>
     * 라이브러리 기본 동작은 /tmp에 추출하는 것인데, 호스팅 환경에 따라 /tmp가 noexec로
     * 마운트돼 있으면 매핑에 실패한다. 그래서 직접 &lt;dataFolder&gt;/native/ 에 풀고
     * {@code libdatachannel.native.datachannel-java.path}로 경로를 넘긴다.
     * 네이티브 jar(classifier)는 플랫폼 로더가 플랫폼에 맞는 것만 받아 두며,
     * 그 안에 {@code /native/libdatachannel-java.<ext>}로 들어 있다.
     * <p>
     * 같은 JVM에서 다른 클래스로더가 같은 .so를 다시 로드할 수 없으므로 /reload는 지원하지 않는다.
     */
    public static synchronized void ensureNativeLoaded(P2PPlatform platform) {
        if (nativeLoaded) return;
        // 네이티브 로그는 VERBOSE로 slf4j에 흘러오므로 이 로거만 조정한다
        platform.setLoggerLevel("tel.schich.libdatachannel", platform.settings().nativeLogLevel());
        String file = nativeFileName();
        try {
            Path dir = platform.dataFolder().resolve("native");
            Files.createDirectories(dir);
            Path out = dir.resolve(file);
            try (InputStream in = LibDataChannel.class.getResourceAsStream("/native/" + file)) {
                if (in == null) {
                    throw new IllegalStateException("libdatachannel 네이티브가 클래스패스에 없습니다: /native/" + file
                            + " (이 플랫폼용 classifier jar가 받아졌는지 확인)");
                }
                try {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                } catch (FileSystemException e) {
                    // 윈도우에서 이전 실행이 파일을 잡고 있는 경우 등 — 기존 파일이 있으면 그걸 쓴다
                    if (!Files.exists(out)) throw e;
                    LOG.warn("[WebRTC] could not overwrite {}, using existing file ({})", out, e.getMessage());
                }
            }
            System.setProperty("libdatachannel.native.datachannel-java.path", out.toAbsolutePath().toString());
            LibDataChannel.initialize();
            nativeLoaded = true;
            LOG.info("[WebRTC] libdatachannel loaded from {}", out);
        } catch (IOException | LinkageError e) {
            // LinkageError(UnsatisfiedLinkError)는 Exception이 아니라서 호출부 catch(Exception)에
            // 안 걸린다 — 여기서 원인을 붙여 IllegalStateException으로 바꿔 던진다.
            throw new IllegalStateException("libdatachannel 네이티브 로드 실패: " + e, e);
        }
    }

    private static String nativeFileName() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "libdatachannel-java.dll";
        if (os.contains("mac")) return "libdatachannel-java.dylib";
        return "libdatachannel-java.so";
    }
}
