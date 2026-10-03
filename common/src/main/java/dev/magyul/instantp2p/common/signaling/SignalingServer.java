package dev.magyul.instantp2p.common.signaling;

import java.util.Locale;

/**
 * 방을 올릴 시그널링·STUN·TURN 서버. 한 번에 한쪽만 쓴다(원본 개발자가 허락한 "가맹점" 수정판과 같은 방식).
 * <ul>
 *   <li>{@link #OFFICIAL} 본점 — 원본 instant-p2p 공식 서버</li>
 *   <li>{@link #FRANCHISE} 가맹점 — 수정판 운영자(sion)의 사설 서버. 프로토콜은 공식과 같고, 약관 동의가 있어야 쓴다
 *       ({@link #FRANCHISE_TERMS_VERSION}). 가맹점 클라이언트는 초대 코드 앞 {@code F-}로 가맹점 서버를 고른다.</li>
 * </ul>
 * 가맹점 서버일 때는 가맹점 수정판과 같게 하드웨어 ID({@link HardwareId})와 1분마다 접속 상태({@link Presence})도 보낸다.
 */
public enum SignalingServer {

    OFFICIAL("official", "kite-private-cloud.kro.kr", "kite-private-cloud.kro.kr", ""),
    /** F3.2부터 시그널링은 클라우드플레어 경유 도메인, STUN/TURN은 따로(UDP라 클라우드플레어를 못 거친다). 예전 sion-p2p-server.kro.kr은 닫혔다. */
    FRANCHISE("franchise", "p2p.sionserver.com", "turn.sionserver.com", "F-");

    /**
     * 가맹점 약관 버전 — 가맹점 수정판의 {@code TERMS_VERSION}(F3.3 기준, 약관 문구는 i18n {@code instant-p2p-server.terms.body}).
     * 약관이 바뀌면 다시 동의받는다 — 이전 버전에 동의한 설정은 공식 서버로 돌아가고 경고가 남는다.
     */
    public static final String FRANCHISE_TERMS_VERSION = "2026-10-01.1";

    private final String id;
    private final String host;
    private final String turnHost;
    private final String codePrefix;

    SignalingServer(String id, String host, String turnHost, String codePrefix) {
        this.id = id;
        this.host = host;
        this.turnHost = turnHost;
        this.codePrefix = codePrefix;
    }

    /** 설정 파일·명령어에 쓰는 이름 */
    public String id() { return id; }

    public String host() { return host; }

    /** 초대 코드를 보여 줄 때 앞에 붙이는 표시 — 가맹점 클라이언트가 이걸 보고 서버를 고른다. 시그널링에는 붙이지 않는다. */
    public String codePrefix() { return codePrefix; }

    public String signalingUrl() { return "wss://" + host; }

    public String stunUrl() { return "stun:" + turnHost + ":3490"; }

    public String turnUrl() { return "turn:" + turnHost + ":3490"; }

    /** 설정 값 → 서버, 모르는 값이면 null */
    public static SignalingServer parse(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT);
        for (SignalingServer s : values()) {
            if (s.id.equals(v)) return s;
        }
        return null;
    }
}
