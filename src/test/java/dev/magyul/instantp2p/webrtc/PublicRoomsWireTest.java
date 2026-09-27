package dev.magyul.instantp2p.webrtc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 공개 방 로비 ID·샤드·채널 계산 — 원본 모드와 같은 값을 내야 목록에 보인다. 골든 값은 리팩터 전 코드에서 뽑았다. */
class PublicRoomsWireTest {

    private final String savedModVersion = P2PConfig.MOD_VERSION;

    @AfterEach
    void restore() {
        P2PConfig.MOD_VERSION = savedModVersion;
    }

    @Test
    void lobbyIdGolden() {
        P2PConfig.MOD_VERSION = "1.2.3";
        assertEquals("__instant_p2p_public_rooms___317b32c143692b99_c47f5b18b8a430e6_0",
                P2PConfig.publicRoomsLobbyId("normal", 0));
        // 채널 이름은 소문자로 해시한다
        assertEquals("__instant_p2p_public_rooms___317b32c143692b99_c47f5b18b8a430e6_3",
                P2PConfig.publicRoomsLobbyId("Normal", 3));
    }

    @Test
    void lobbyIdDependsOnModVersion() {
        P2PConfig.MOD_VERSION = "1.3.0";
        assertEquals("__instant_p2p_public_rooms___317b32c143692b99_59296d23d623ce0a_2",
                P2PConfig.publicRoomsLobbyId("normal", 2));
    }

    @Test
    void shardGolden() {
        assertEquals(1, P2PConfig.publicRoomShardFor("ABCDEFGHJK"));
        assertEquals(1, P2PConfig.publicRoomShardFor("2345678923"));
        assertEquals(0, P2PConfig.publicRoomShardFor("ZZZZZZZZZZ"));
        assertEquals(2, P2PConfig.publicRoomShardFor("QWERTYPASD"));
    }

    @Test
    void shardIsFloorModOfHashCode() {
        for (String code : List.of("ABCDEFGHJK", "2345678923", "ZZZZZZZZZZ", "QWERTYPASD", "AAAAAAAAAA")) {
            assertEquals(Math.floorMod(code.hashCode(), 4), P2PConfig.publicRoomShardFor(code));
        }
    }

    @Test
    void effectiveChannelsAndJoinsSortedLowercaseWithUnitSeparator() {
        assertEquals(List.of("abc\u001Fnormal\u001Fpvp"),
                P2PConfig.effectiveChannels(List.of("Normal", "abc", "PVP"), true));
        // OR 모드나 채널 1개는 그대로
        assertEquals(List.of("Normal", "abc"), P2PConfig.effectiveChannels(List.of("Normal", "abc"), false));
        assertEquals(List.of("Normal"), P2PConfig.effectiveChannels(List.of("Normal"), true));
    }

    @Test
    void parseChannels() {
        assertEquals(List.of("normal", "pvp"), P2PConfig.parseChannels("normal, pvp,"));
        assertEquals(List.of("normal"), P2PConfig.parseChannels(""));
        assertEquals(List.of("a", "b", "c", "d", "e"), P2PConfig.parseChannels("a,b,c,d,e,f"));
        assertEquals(List.of("A"), P2PConfig.parseChannels("A,a"));
    }

    @Test
    void broadcastTag() {
        assertTrue(P2PConfig.isBroadcastTagged("normal, broadcast"));
        assertFalse(P2PConfig.isBroadcastTagged("normal"));
        assertEquals("normal", P2PConfig.stripBroadcastTag("normal,broadcast"));
    }
}
