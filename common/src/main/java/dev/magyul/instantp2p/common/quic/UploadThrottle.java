package dev.magyul.instantp2p.common.quic;

/**
 * [1.3.0] 접속자 → 서버 방향 전송량 제한(원본 1.5.1 {@code QuicPump.Throttle}과 같은 값).
 * <p>
 * 정상 마인크래프트 클라이언트가 서버로 보내는 양은 작다(초당 수십 KB). 초당 {@link #RATE_BPS}를 넘겨 계속 밀어 넣는
 * 연결은 속도를 눌러 주고, 60초 창 안에서 {@link #ABUSE_MS} 넘게 눌렸으면 {@link #abusive}가 true가 되어
 * 호출자가 연결을 끊고 그 출발지를 잠시 막는다.
 */
final class UploadThrottle {
    static final long RATE_BPS = 1_000_000L;
    static final long BURST = 2_000_000L;
    static final long WINDOW_MS = 60_000L;
    static final long ABUSE_MS = 20_000L;

    private double tokens = BURST;
    private long last;
    private long windowStart;
    private long blockedInWindow;
    private boolean abusive;

    /**
     * {@code n}바이트를 보내기 전에 호출한다.
     *
     * @return 이만큼(ms) 기다린 뒤 써야 한다(0이면 바로)
     */
    synchronized long take(int n, long now) {
        if (last == 0) {
            last = now;
            windowStart = now;
        }
        tokens = Math.min(BURST, tokens + (now - last) * (RATE_BPS / 1000.0));
        last = now;
        tokens -= n;
        if (now - windowStart >= WINDOW_MS) {
            windowStart = now;
            blockedInWindow = 0;
        }
        if (tokens >= 0) return 0;
        long wait = (long) Math.ceil(-tokens / (RATE_BPS / 1000.0));
        blockedInWindow += wait;
        if (blockedInWindow > ABUSE_MS) abusive = true;
        return wait;
    }

    synchronized boolean abusive() {
        return abusive;
    }
}
