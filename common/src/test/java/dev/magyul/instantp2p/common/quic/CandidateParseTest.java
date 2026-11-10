package dev.magyul.instantp2p.common.quic;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** 상대 후보 검증 — 원본 1.5.1과 같은 신뢰 경계(링크 로컬·사설망은 host만). */
class CandidateParseTest {
    @Test
    void publicAddressesAreAccepted() {
        assertNotNull(QuicIce.Candidate.parse("8.8.8.8 4000 srflx"));
        assertNotNull(QuicIce.Candidate.parse("203.0.113.5 4000 relay"));
        assertNotNull(QuicIce.Candidate.parse("172.32.0.1 4000 srflx")); // 172.16/12 밖
    }

    @Test
    void privateAddressesAreOnlyHostCandidates() {
        for (String ip : new String[]{"10.0.0.5", "172.16.0.1", "172.31.255.1", "192.168.1.2", "100.64.0.1", "100.127.1.1"}) {
            assertNotNull(QuicIce.Candidate.parse(ip + " 4000 host"), ip + " host");
            assertNull(QuicIce.Candidate.parse(ip + " 4000 srflx"), ip + " srflx");
            assertNull(QuicIce.Candidate.parse(ip + " 4000 relay"), ip + " relay");
        }
    }

    @Test
    void linkLocalLoopbackAndMulticastAreRejectedEverywhere() {
        for (String type : new String[]{"host", "srflx", "relay"}) {
            assertNull(QuicIce.Candidate.parse("169.254.169.254 4000 " + type));
            assertNull(QuicIce.Candidate.parse("127.0.0.1 4000 " + type));
            assertNull(QuicIce.Candidate.parse("0.1.2.3 4000 " + type));
            assertNull(QuicIce.Candidate.parse("224.0.0.1 4000 " + type));
        }
    }

    @Test
    void malformedLinesAreRejected() {
        assertNull(QuicIce.Candidate.parse(null));
        assertNull(QuicIce.Candidate.parse("example.com 4000 host"));
        assertNull(QuicIce.Candidate.parse("8.8.8.8 0 host"));
        assertNull(QuicIce.Candidate.parse("8.8.8.8 70000 host"));
        assertNull(QuicIce.Candidate.parse("8.8.8.8 4000 prflx"));
    }
}
