package dev.magyul.instantp2p.common.signaling;

import java.io.IOException;

/**
 * [1.3.0] 시그널링 서버가 WebSocket 핸드셰이크를 거부했다(원본 1.5.1).
 * 서버는 거부 이유를 응답 헤더 {@code X-Reject: <코드>}, 풀리는 시각을 {@code X-Reject-Until: <유닉스 초>}로 알려 준다.
 * 메시지에는 방 코드·토큰이 들어갈 일이 없도록 상태 줄과 코드만 담는다.
 */
public final class HandshakeRejectedException extends IOException {
    private final int status;
    private final String code;
    private final long untilEpochSec;

    public HandshakeRejectedException(String statusLine, int status, String code, long untilEpochSec) {
        super("handshake failed: " + statusLine
                + (code != null ? " reject=" + code : "")
                + (untilEpochSec > 0 ? " until=" + untilEpochSec : ""));
        this.status = status;
        this.code = code;
        this.untilEpochSec = untilEpochSec;
    }

    /** HTTP 상태 코드(파싱 못 하면 0). */
    public int status() { return status; }

    /** {@code X-Reject} 값(소문자). 없으면 null. */
    public String code() { return code; }

    /** {@code X-Reject-Until}(유닉스 초). 없으면 0. */
    public long untilEpochSec() { return untilEpochSec; }

    /** 상태 줄("HTTP/1.1 403 Forbidden")에서 상태 코드만 뽑는다. */
    static int parseStatus(String statusLine) {
        if (statusLine == null) return 0;
        String[] p = statusLine.split(" ", 3);
        if (p.length < 2) return 0;
        try {
            return Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
