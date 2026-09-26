package dev.magyul.instantp2p.webrtc;

import dev.magyul.instantp2p.InstantP2PLoader;
import dev.magyul.instantp2p.InstantP2pBukkit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tel.schich.libdatachannel.LibDataChannel;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * WebRTC/KCP 브리지 관리. (외부 바이너리 의존 없음 — 전부 Java 네이티브)
 * <p>
 * startHost() → WebRtcHost (libdatachannel-java)
 * <p>
 * 서버 쪽은 DataChannel만 쓰므로 libwebrtc 전체(webrtc-java) 대신 libdatachannel을 쓴다.
 * webrtc-java 리눅스 네이티브는 libpulse/X11/udev/dbus에 링크돼 있어 헤드리스 서버에서
 * 로드 자체가 안 된다. 조인자(모드, libwebrtc)와는 표준 WebRTC라 그대로 붙는다.
 */
public class WebRtcBridge {

    public static final Logger LOG = LoggerFactory.getLogger("webrtc-bridge");

    private static final int LOCAL_PORT = 25566;

    // 네이티브 호스트
    private static volatile WebRtcHost webRtcHost;

    // 공개 방 목록 announcer — 방 열 때 "공개 허용" 체크돼 있으면 같이 시작,
    // 방 닫힐 때 같이 멈춘다(publishPublicRoom/unpublishPublicRoom).
    private static final PublicRoomAnnouncer publicRoomAnnouncer = new PublicRoomAnnouncer();

    // 핑 후 접속 시 roomId 전달용
    private static final int activeLocalPort = LOCAL_PORT;

    private WebRtcBridge() {}

    // ── 주소 파싱 ──────────────────────────────────────────────────────────────

    public static String parseRoomId(String address) {
        if (address == null) return null;
        String trimmed = address.trim();
        return trimmed.startsWith("webrtc.") ? trimmed.substring("webrtc.".length()).trim() : null;
    }

    public static String parseKcpAddress(String address) {
        if (address == null) return null;
        String trimmed = address.trim();
        return trimmed.startsWith("kcp.") ? trimmed.substring("kcp.".length()).trim() : null;
    }

    // ── WebRTC 클라이언트 (Java 네이티브) ──────────────────────────────────────
    // 클라이언트가 아니므로 코드 제거

    // ── Host (Java 네이티브 — WebRtcHost) ─────────────────────────────────────

    public static void startHost(String roomId, String target) {
        stopHost();
        ensureNativeLoaded();
        Roles.refreshAsync();

        LOG.info("[WebRTC] Starting native host: room={} target={}", roomId, target);
        WebRtcHost host = new WebRtcHost(roomId, target);
        webRtcHost = host;
        host.start();
    }

    public static void stopHost() {
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
    public static void publishPublicRoom(String roomCode, String title, String hostNickname, String hostUuid,
                                          int currentPlayers, int maxPlayers) {
        publicRoomAnnouncer.publish(roomCode, title, hostNickname, hostUuid, currentPlayers, maxPlayers);
    }

    public static void unpublishPublicRoom() {
        publicRoomAnnouncer.stop();
    }

    /** 밴(=차단) 목록이 바뀌었을 때 P2PBanManager가 호출 — 지금 공개된 방이 있으면
     * 즉시 새 밴 목록을 실어 재공지한다(PublicRoomAnnouncer.republishNow 참고). */
    public static void republishPublicRoomIfActive() {
        publicRoomAnnouncer.republishNow();
    }

    /** 공개 방 인원(현재/최대)이 바뀔 때마다 호출 — 재접속 없이 메시지만 보낸다(PublicRoomAnnouncer 참고). */
    public static void updatePublicRoomPlayerCount(int currentPlayers, int maxPlayers) {
        publicRoomAnnouncer.updatePlayerCount(currentPlayers, maxPlayers);
    }

    /** 지금 활성화된 호스트 인스턴스를 식별하는 토큰(단순 참조). */
    public static Object currentHostToken() {
        return webRtcHost;
    }

    /**
     * token이 여전히 현재 활성 호스트일 때만 중지한다. 방을 연달아 열면
     * 이전 방을 닫으려던 지연 종료 스레드가 그 사이 새로 열린 방을
     * 대신 죽이는 걸 막기 위한 것 — {@link #startHost}가 이미 이전 인스턴스를
     * 동기적으로 닫으므로, 지연 종료 시점엔 그게 여전히 활성 호스트일 때만 유효하다.
     */
    public static void stopHostIfCurrent(Object token) {
        if (token != null && token == webRtcHost) {
            stopHost();
        }
    }

    // ── 네이티브 로드 ─────────────────────────────────────────────────────────

    private static volatile boolean nativeLoaded = false;

    /**
     * libdatachannel 네이티브를 플러그인 데이터 폴더에 풀어서 로드한다.
     * <p>
     * 라이브러리 기본 동작은 /tmp에 추출하는 것인데, 호스팅 환경에 따라 /tmp가 noexec로
     * 마운트돼 있으면 매핑에 실패한다. 그래서 직접 plugins/&lt;plugin&gt;/native/ 에 풀고
     * {@code libdatachannel.native.datachannel-java.path}로 경로를 넘긴다.
     * 네이티브 jar(classifier)는 InstantP2PLoader가 플랫폼에 맞는 것만 받아 두며,
     * 그 안에 {@code /native/libdatachannel-java.<ext>}로 들어 있다.
     * <p>
     * 같은 JVM에서 다른 클래스로더가 같은 .so를 다시 로드할 수 없으므로 /reload는 지원하지 않는다.
     */

    private static void configureNativeLogLevel(String levelName) {
        try {
            Level level = Level.toLevel(levelName, Level.WARN);
            Configurator.setLevel("tel.schich.libdatachannel", level);
        } catch (NoClassDefFoundError e) {
            LOG.debug("[WebRTC] log4j-core not available; native log level unchanged");
        }
    }

    public static synchronized void ensureNativeLoaded() {
        if (nativeLoaded) return;
        configureNativeLogLevel(InstantP2pBukkit.INSTANCE.config.getNativeLogLevel());
        String file = nativeFileName();
        try {
            Path dir = InstantP2pBukkit.INSTANCE.getDataFolder().toPath().resolve("native");
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

    // ── 유틸 ──────────────────────────────────────────────────────────────────

    private static int findFreePort() {
        try (ServerSocket ignored = new ServerSocket(LOCAL_PORT)) { return LOCAL_PORT; }
        catch (IOException e) {
            try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
            catch (IOException ex) { return LOCAL_PORT; }
        }
    }
}