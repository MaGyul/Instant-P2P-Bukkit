package dev.magyul.instantp2p.common.core;

import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;

/**
 * 공통 코드가 서버 플랫폼(Paper/Spigot, Velocity, Fabric)에 요구하는 것.
 * <p>
 * 구현은 플랫폼 패키지에만 둔다. 공통 코드가 플랫폼 클래스를 한 번이라도 건드리면
 * 다른 로더에서 {@code NoClassDefFoundError}가 난다.
 * <p>
 * 스레드: 표시가 없는 메서드는 worker/announcer 스레드에서도 불릴 수 있다.
 * "서버 스레드" 표시가 있는 메서드는 {@link #runSync}로 넘어온 작업이나 플랫폼 이벤트 안에서만 부른다.
 */
public interface P2PPlatform {

    P2PSettings settings();

    /** 서버 스레드. 설정 파일을 다시 읽는다 — 적용은 하지 않는다({@link #applySettings}). 파일 오류면 예외. */
    P2PSettings loadSettings() throws Exception;

    /** 서버 스레드. {@link #settings()}가 이 값을 돌려주게 바꾼다. */
    void applySettings(P2PSettings settings);

    /** 서버에서 밴된 플레이어 UUID — 공개 방 announce에 해시로 실린다. 스냅샷을 돌려준다. */
    Collection<UUID> bannedPlayers();

    int maxPlayers();

    /** room_update.version — 클라이언트가 문자열 비교하므로 실제 서버 MC 버전이어야 한다. 아직 모르면 null(Velocity). */
    String minecraftVersion();

    /** 터널 다이얼 대상 포트 (서버/프록시 리스닝 포트) */
    int listenPort();

    /** 터널 다이얼 대상 호스트 */
    default String targetHost() {
        return "127.0.0.1";
    }

    /** 공개 방 제목 기본값 (설정 title이 비었을 때) — 서버 MOTD 첫 줄 */
    String motd();

    /** 플러그인/모드 데이터 폴더 (설정, 로그인 정보, 상태 파일) */
    Path dataFolder();

    /** 콘솔 — 서버 기동 시 자동 열기 결과 등을 받는다. */
    P2PSender console();

    /** 서버 스레드. */
    boolean isOnline(UUID player);

    /** 서버 스레드. 접속 중이 아니면 null. */
    String playerName(UUID player);

    /** 서버 스레드. serverUuid이거나 instantp2p.host 권한이 있으면 호스트. 권한은 접속 중일 때만 판정된다. */
    boolean isHost(UUID player);

    /** 서버 스레드. 번역 키 + 인자로 킥한다. 텍스트 생성(모드 번역/fallback)은 플랫폼이 맡는다. */
    void kick(UUID player, String translationKey, Object... args);

    /** 콘솔과 instantp2p.notify.host 권한자에게 알린다. */
    void notifyAdmins(String translationKey, Object... args);

    /** 서버 스레드에서 실행한다. */
    void runSync(Runnable task);

    /** 서버 스레드. instant-p2p:room_state 페이로드를 접속자 전원에게 보낸다. */
    void broadcastRoomState(byte[] payload);
}
