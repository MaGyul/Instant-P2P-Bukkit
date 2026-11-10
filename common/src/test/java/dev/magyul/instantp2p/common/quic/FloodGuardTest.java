package dev.magyul.instantp2p.common.quic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FloodGuardTest {
    @Test
    void knownAddressesAlwaysPass() {
        FloodGuard g = new FloodGuard(on -> {});
        for (int i = 0; i < 5000; i++) assertTrue(g.admit("1.1.1.1", true, 0));
    }

    @Test
    void perIpLimitApplies() {
        FloodGuard g = new FloodGuard(on -> {});
        int ok = 0;
        for (int i = 0; i < 100; i++) if (g.admit("2.2.2.2", false, 10)) ok++;
        assertEquals(FloodGuard.PER_IP_PPS, ok);
        // 다음 초에는 다시 받는다
        assertTrue(g.admit("2.2.2.2", false, 1100));
    }

    @Test
    void floodTripsDefenseAndAnnouncesOnce() {
        List<Boolean> events = new ArrayList<>();
        FloodGuard g = new FloodGuard(events::add);
        for (int i = 0; i <= FloodGuard.UNKNOWN_PPS + 5; i++) g.admit("10." + (i % 250) + ".0.1", false, 5);
        assertEquals(List.of(true), events);
        assertTrue(g.defending(6));
        assertFalse(g.admit("9.9.9.9", false, 2000));
        assertTrue(g.admit("1.1.1.1", true, 2000)); // 아는 주소는 방어 중에도 통과
        // 방어가 끝나면 해제를 알린다
        assertTrue(g.admit("9.9.9.9", false, 5 + FloodGuard.DEFENSE_MS + 1));
        assertEquals(List.of(true, false), events);
    }
}
