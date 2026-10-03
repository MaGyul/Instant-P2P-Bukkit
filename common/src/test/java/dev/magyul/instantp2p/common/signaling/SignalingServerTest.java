package dev.magyul.instantp2p.common.signaling;

import dev.magyul.instantp2p.common.core.P2PSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SignalingServerTest {

    @AfterEach
    void reset() {
        P2PConfig.useServer(SignalingServer.OFFICIAL);
    }

    @Test
    void parse() {
        assertEquals(SignalingServer.OFFICIAL, SignalingServer.parse("official"));
        assertEquals(SignalingServer.FRANCHISE, SignalingServer.parse(" Franchise "));
        assertNull(SignalingServer.parse("custom"));
        assertNull(SignalingServer.parse(null));
    }

    @Test
    void franchiseNeedsCurrentTerms() {
        P2PSettings base = new P2PSettings(true, UUID.randomUUID(), "auto", "", "Server", true, List.of("normal"),
                false, false, 0);
        assertEquals(SignalingServer.OFFICIAL, base.effectiveServer());

        // 약관 동의 전에는 가맹점을 골라도 공식 서버
        assertEquals(SignalingServer.OFFICIAL, base.withServer(SignalingServer.FRANCHISE, "").effectiveServer());
        assertEquals(SignalingServer.OFFICIAL, base.withServer(SignalingServer.FRANCHISE, "2026-01-01").effectiveServer());

        P2PSettings agreed = base.withServer(SignalingServer.FRANCHISE, SignalingServer.FRANCHISE_TERMS_VERSION);
        assertTrue(agreed.franchiseTermsAccepted());
        assertEquals(SignalingServer.FRANCHISE, agreed.effectiveServer());
    }

    @Test
    void hardwareIdOnlyForFranchise() {
        assertEquals("", HardwareId.query());
        P2PConfig.useServer(SignalingServer.FRANCHISE);
        String q = HardwareId.query();
        // 물리 어댑터가 없는 환경(일부 CI)이면 hwid가 빈 값일 수 있다 — 형식만 본다
        assertTrue(q.startsWith("&hwid="), q);
        String hwid = HardwareId.get();
        assertTrue(hwid.isEmpty() || hwid.matches("[0-9a-f]{64}"), hwid);
        for (String c : HardwareId.components().split(",")) {
            assertTrue(c.isEmpty() || c.matches("[0-9a-f]{64}"), c);
        }
    }

    @Test
    void urlsFollowServer() {
        P2PConfig.useServer(SignalingServer.FRANCHISE);
        assertEquals("wss://p2p.sionserver.com", P2PConfig.signalingUrl());
        assertEquals("https://p2p.sionserver.com", P2PConfig.signalingHttpUrl());
        assertEquals("stun:turn.sionserver.com:3490", P2PConfig.stunUrl());
        assertEquals("turn:turn.sionserver.com:3490", P2PConfig.turnUrl());
        // 역할은 서버 선택과 관계없이 공식 서버(서명 확인)
        assertEquals("https://kite-private-cloud.kro.kr", P2PConfig.officialHttpUrl());

        P2PConfig.useServer(SignalingServer.OFFICIAL);
        assertEquals("wss://kite-private-cloud.kro.kr", P2PConfig.signalingUrl());
        assertEquals("", SignalingServer.OFFICIAL.codePrefix());
        assertEquals("F-", SignalingServer.FRANCHISE.codePrefix());
    }
}
