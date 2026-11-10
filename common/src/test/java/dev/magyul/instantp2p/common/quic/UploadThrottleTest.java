package dev.magyul.instantp2p.common.quic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UploadThrottleTest {
    @Test
    void normalTrafficIsNeverDelayed() {
        UploadThrottle t = new UploadThrottle();
        long now = 1000;
        for (int i = 0; i < 1000; i++) {
            assertEquals(0, t.take(20_000, now));
            now += 100; // 초당 200KB
        }
        assertFalse(t.abusive());
    }

    @Test
    void burstThenThrottled() {
        UploadThrottle t = new UploadThrottle();
        assertEquals(0, t.take(2_000_000, 1000)); // 버스트는 통과
        long wait = t.take(1_000_000, 1000);
        assertEquals(1000, wait); // 1MB/s이므로 1초
    }

    @Test
    void sustainedFloodBecomesAbusive() {
        UploadThrottle t = new UploadThrottle();
        long now = 1000;
        for (int i = 0; i < 100 && !t.abusive(); i++) {
            long wait = t.take(1_000_000, now);
            now += wait; // 기다린 만큼 시간이 흐른다 → 계속 최대 속도로 밀어 넣음
        }
        assertTrue(t.abusive());
    }
}
