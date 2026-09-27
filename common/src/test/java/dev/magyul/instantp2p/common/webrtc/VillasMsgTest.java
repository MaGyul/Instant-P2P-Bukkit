package dev.magyul.instantp2p.common.webrtc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 시그널링 JSON 형식 — "spd" 키(서버 오타 그대로)와 구조는 프로토콜이다. */
class VillasMsgTest {

    @Test
    void hello() {
        assertEquals("{}", VillasMsg.hello());
    }

    @Test
    void descriptionUsesSpdKey() {
        assertEquals("{\"description\":{\"spd\":\"v=0\\r\\no=- 1\",\"type\":\"answer\"}}",
                VillasMsg.description("answer", "v=0\r\no=- 1"));
    }

    @Test
    void candidateUsesSpdAndMid() {
        assertEquals("{\"candidate\":{\"spd\":\"candidate:1 1 UDP 1 1.2.3.4 5 typ host\",\"mid\":\"0\"}}",
                VillasMsg.candidate("candidate:1 1 UDP 1 1.2.3.4 5 typ host", "0"));
    }

    @Test
    void parseOfferDescription() {
        String json = "{\"description\":{\"spd\":\"v=0\\r\\na=x:\\\"q\\\"\",\"type\":\"offer\"}}";
        assertTrue(VillasMsg.has(json, "description"));
        String desc = VillasMsg.object(json, "description");
        assertEquals("offer", VillasMsg.field(desc, "type"));
        assertEquals("v=0\r\na=x:\"q\"", VillasMsg.field(desc, "spd"));
    }

    @Test
    void descriptionRoundTrip() {
        String sdp = "v=0\r\na=ice-ufrag:abcd\r\na=fingerprint:sha-256 AB:CD\r\n";
        String desc = VillasMsg.object(VillasMsg.description("offer", sdp), "description");
        assertEquals(sdp, VillasMsg.field(desc, "spd"));
    }

    @Test
    void parseCandidate() {
        String json = "{\"candidate\":{\"spd\":\"candidate:2 1 udp 3 10.0.0.1 9 typ relay\",\"mid\":\"0\"}}";
        String cand = VillasMsg.object(json, "candidate");
        assertEquals("candidate:2 1 udp 3 10.0.0.1 9 typ relay", VillasMsg.field(cand, "spd"));
        assertEquals("0", VillasMsg.field(cand, "mid"));
    }

    @Test
    void parseControlPeers() {
        String json = "{\"control\":{\"peer_id\":1,\"peers\":["
                + "{\"name\":\"h1a2b3\",\"id\":1,\"remote\":\"ip-aaaaaaaaaaaa:1234\"},"
                + "{\"name\":\"jd0123456789abcdef\",\"id\":2,\"remote\":\"ip-bbbbbbbbbbbb:5678\"},"
                + "{\"name\":\"jq0123456789abcdef\",\"id\":3}]}}";
        assertTrue(VillasMsg.has(json, "control"));
        List<String[]> peers = VillasMsg.peers(json);
        assertEquals(3, peers.size());
        assertArrayEquals(new String[]{"h1a2b3", "ip-aaaaaaaaaaaa:1234"}, peers.get(0));
        assertArrayEquals(new String[]{"jd0123456789abcdef", "ip-bbbbbbbbbbbb:5678"}, peers.get(1));
        assertArrayEquals(new String[]{"jq0123456789abcdef", null}, peers.get(2));
    }

    @Test
    void parseServers() {
        String json = "{\"servers\":[{\"url\":\"stun:a:3478\"},"
                + "{\"url\":\"turn:b:3478\",\"user\":\"u\",\"pass\":\"p\",\"realm\":\"r\",\"expires\":1}]}";
        List<String[]> servers = VillasMsg.servers(json);
        assertEquals(2, servers.size());
        assertArrayEquals(new String[]{"stun:a:3478", null, null}, servers.get(0));
        assertArrayEquals(new String[]{"turn:b:3478", "u", "p"}, servers.get(1));
    }

    @Test
    void roomUpdateGolden() {
        assertEquals("{\"room_update\":{\"code\":\"ABCDEFGHJK\",\"title\":\"T \\\"1\\\"\",\"nickname\":\"N\","
                        + "\"channel\":\"normal,broadcast\",\"channel_and\":false,\"current\":2,\"max\":20,"
                        + "\"version\":\"1.21.11\",\"host_uuid\":\"u\",\"banned_hashes\":\"a,b\","
                        + "\"host_rtt_ms\":42,\"opened_at_ms\":1000}}",
                VillasMsg.roomUpdate("ABCDEFGHJK", "T \"1\"", "N", "normal,broadcast", false,
                        2, 20, "1.21.11", "u", "a,b", 42, 1000));
    }
}
