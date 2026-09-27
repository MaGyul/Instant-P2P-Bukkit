package dev.magyul.instantp2p.webrtc;

/**
 * 시그널링 피어 이름·세션 경로 규칙 (원본 모드와의 와이어 호환 — 바꾸지 말 것).
 * <ul>
 *   <li>호스트 로비: {@code h{5hex}}</li>
 *   <li>조인 알림: {@code j{d|r}{sid16}} — 두 번째 글자는 조인자의 중계 강제 여부</li>
 *   <li>입장 전 확인(members probe): {@code jq{sid16}} — 길이가 조인과 같은 18이라 길이만으로 구분하면 안 된다</li>
 *   <li>페어 세션: {@code /{roomId}-{sid}/h{d|r}{sid}}, probe 응답은 {@code /{roomId}-{sid}/hq{sid}}</li>
 * </ul>
 */
final class PeerNames {

    /** 조인/probe peer 이름 길이 ("j" + 플래그 1글자 + sid 16글자) */
    static final int JOIN_NAME_LENGTH = 18;

    /** 로비의 조인 알림 하나. probe면 연결 없이 접속자 해시만 답한다. */
    record Join(String sid, boolean probe, boolean relayForced) {}

    /** 로비 호스트 이름. {@code rnd}는 [0x10000, 0x100000) 범위여야 5자리 hex가 된다. */
    static String lobbyHost(int rnd) {
        return "h" + Integer.toHexString(rnd);
    }

    /** 로비 peer 이름이 조인/probe 알림이면 파싱, 아니면 null. */
    static Join parseJoin(String name) {
        if (name == null || name.length() != JOIN_NAME_LENGTH || !name.startsWith("j")) return null;
        String sid = name.substring(2);
        char flag = name.charAt(1);
        if (flag == 'q') return new Join(sid, true, false);
        return new Join(sid, false, flag == 'r');
    }

    /** 페어 세션 경로 (roomId-sid) */
    static String pairRoom(String roomId, String sid) {
        return roomId + "-" + sid;
    }

    /** 페어 세션의 호스트 이름 — 방의 중계 강제 여부를 두 번째 글자에 싣는다. */
    static String pairHost(boolean relayOnly, String sid) {
        return "h" + (relayOnly ? "r" : "d") + sid;
    }

    /** probe 응답용 호스트 이름 */
    static String probeHost(String sid) {
        return "hq" + sid;
    }

    private PeerNames() {}
}
