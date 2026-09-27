package dev.magyul.instantp2p.common.signaling;

/**
 * 시그널링 피어 이름·세션 경로 규칙 (원본 모드 1.3 QUIC과의 와이어 호환 — 바꾸지 말 것).
 * <ul>
 *   <li>호스트 로비: {@code h{4자리 숫자}} (1000~9999)</li>
 *   <li>조인 알림: {@code j{d|r}{sid16}} — 두 번째 글자는 조인자의 중계 강제 여부</li>
 *   <li>입장 전 확인(members probe): {@code jq{sid16}} — 길이가 조인과 같은 18이라 길이만으로 구분하면 안 된다</li>
 *   <li>페어 세션: {@code /{roomId}-{sid}/h{sid}} (조인자는 {@code j{sid}}), probe 응답은 {@code /{roomId}-{sid}/hq{sid}}</li>
 * </ul>
 * WebRTC 시절(1.2.x)에는 로비가 {@code h{5hex}}, 페어 호스트가 {@code h{d|r}{sid}}였다.
 */
public final class PeerNames {

    /** 조인/probe peer 이름 길이 ("j" + 플래그 1글자 + sid 16글자) */
    static final int JOIN_NAME_LENGTH = 18;

    /** 로비의 조인 알림 하나. probe면 연결 없이 접속자 해시만 답한다. */
    public record Join(String sid, boolean probe, boolean relayForced) {}

    /** 로비 호스트 이름. {@code number}는 1000~9999. */
    public static String lobbyHost(int number) {
        return "h" + number;
    }

    /** 로비 peer 이름이 조인/probe 알림이면 파싱, 아니면 null. */
    public static Join parseJoin(String name) {
        if (name == null || name.length() != JOIN_NAME_LENGTH || !name.startsWith("j")) return null;
        String sid = name.substring(2);
        char flag = name.charAt(1);
        if (flag == 'q') return new Join(sid, true, false);
        return new Join(sid, false, flag == 'r');
    }

    /** 페어 세션 경로 (roomId-sid) */
    public static String pairRoom(String roomId, String sid) {
        return roomId + "-" + sid;
    }

    /** 페어 세션의 호스트 이름 */
    public static String pairHost(String sid) {
        return "h" + sid;
    }

    /** probe 응답용 호스트 이름 */
    public static String probeHost(String sid) {
        return "hq" + sid;
    }

    private PeerNames() {}
}
