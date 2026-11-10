package dev.magyul.instantp2p.common.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class JreGuardTest {
    private static final String EM = "CN=emSign ECC Root CA - G3,O=eMudhra Technologies Limited,C=IN";
    private static final String COMODO = "O=Comodo CA Limited,CN=AAA Certificate Services,L=Salford,C=GB"; // 속성 순서 무관
    private static final String OTHER = "CN=ISRG Root X1,O=Internet Security Research Group,C=US";

    @Test
    void rootsAbsentIsPatched() {
        assertEquals(JreGuard.Patch.ROOTS_ABSENT, JreGuard.decide(List.of(OTHER), 39));
    }

    @Test
    void rootPresentWithOldBlocklistIsUnpatched() {
        assertEquals(JreGuard.Patch.UNPATCHED, JreGuard.decide(List.of(OTHER, EM), 39));
        assertEquals(JreGuard.Patch.UNPATCHED, JreGuard.decide(List.of(COMODO), 0));
    }

    @Test
    void rootPresentWithUpdatedBlocklistIsPatched() {
        assertEquals(JreGuard.Patch.BLOCKLIST, JreGuard.decide(List.of(EM), 87));
        assertEquals(JreGuard.Patch.BLOCKLIST, JreGuard.decide(List.of(EM), JreGuard.BLOCKLIST_PATCHED_MIN));
    }

    @Test
    void emptyTrustListIsUnknown() {
        assertEquals(JreGuard.Patch.UNKNOWN, JreGuard.decide(List.of(), 87));
    }

    @Test
    void versionTableBoundaries() {
        assertEquals(JreGuard.Level.VULNERABLE, JreGuard.classify(List.of(21, 0, 13)));
        assertEquals(JreGuard.Level.OK, JreGuard.classify(List.of(21, 0, 14)));
        assertEquals(JreGuard.Level.VULNERABLE, JreGuard.classify(List.of(25, 0, 5)));
        assertEquals(JreGuard.Level.OK, JreGuard.classify(List.of(25, 0, 6)));
        assertEquals(JreGuard.Level.WARN, JreGuard.classify(List.of(26, 0, 1)));
        assertEquals(JreGuard.Level.OK, JreGuard.classify(List.of(28, 0, 0)));
        assertEquals(JreGuard.Level.VULNERABLE, JreGuard.classify(List.of(22, 0, 2))); // 지원 끝난 줄
    }
}
