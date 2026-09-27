package dev.magyul.instantp2p.common.webrtc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 시그널링 피어 이름 규칙 — CLAUDE.md "건드리면 안 되는 것". */
class PeerNamesTest {

    private static final String SID = "0123456789abcdef";

    @Test
    void lobbyHostIsHPlusFiveHex() {
        assertEquals("h10000", PeerNames.lobbyHost(0x10000));
        assertEquals("hfffff", PeerNames.lobbyHost(0xFFFFF));
    }

    @Test
    void directJoin() {
        PeerNames.Join j = PeerNames.parseJoin("jd" + SID);
        assertNotNull(j);
        assertEquals(SID, j.sid());
        assertFalse(j.probe());
        assertFalse(j.relayForced());
    }

    @Test
    void relayForcedJoin() {
        PeerNames.Join j = PeerNames.parseJoin("jr" + SID);
        assertNotNull(j);
        assertTrue(j.relayForced());
        assertFalse(j.probe());
    }

    @Test
    void probeHasSameLengthAsJoinButIsNotAJoin() {
        String name = "jq" + SID;
        assertEquals(("jd" + SID).length(), name.length());
        PeerNames.Join j = PeerNames.parseJoin(name);
        assertNotNull(j);
        assertTrue(j.probe());
        assertFalse(j.relayForced());
        assertEquals(SID, j.sid());
    }

    @Test
    void nonJoinNamesIgnored() {
        assertNull(PeerNames.parseJoin(null));
        assertNull(PeerNames.parseJoin("h12345"));
        assertNull(PeerNames.parseJoin("hd" + SID));   // 같은 길이의 호스트 이름
        assertNull(PeerNames.parseJoin("p" + SID));
        assertNull(PeerNames.parseJoin("jd" + SID + "0"));
        assertNull(PeerNames.parseJoin("jd" + SID.substring(1)));
    }

    @Test
    void pairSessionNames() {
        assertEquals("ABCDEFGHJK-" + SID, PeerNames.pairRoom("ABCDEFGHJK", SID));
        assertEquals("hd" + SID, PeerNames.pairHost(false, SID));
        assertEquals("hr" + SID, PeerNames.pairHost(true, SID));
        assertEquals("hq" + SID, PeerNames.probeHost(SID));
    }
}
