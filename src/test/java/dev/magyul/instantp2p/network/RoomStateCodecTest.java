package dev.magyul.instantp2p.network;

import dev.magyul.instantp2p.network.packet.Moderation;
import dev.magyul.instantp2p.network.packet.RoomState;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 플러그인 채널 페이로드 — 원본 모드 코덱과 바이트 단위로 같아야 한다. */
class RoomStateCodecTest {

    private static final UUID A = UUID.fromString("feb58aa8-56f6-4728-8546-82071e39dd24");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** VarInt 300, UUID host, bool true, VarInt 2, (A, 3), (B, 1) */
    private static final String GOLDEN =
            "ac02" + "feb58aa856f64728854682071e39dd24" + "01" + "02"
            + "feb58aa856f64728854682071e39dd24" + "03"
            + "00000000000000000000000000000001" + "01";

    @Test
    void channelIds() {
        assertEquals("instant-p2p:room_state", RoomState.ID);
        assertEquals("instant-p2p:moderation", Moderation.ID);
    }

    @Test
    void encodeGolden() {
        Map<UUID, Integer> ranks = new LinkedHashMap<>();
        ranks.put(A, 3);
        ranks.put(B, 1);
        PacketByteBuf buf = PacketByteBuf.allocate();
        new RoomState(300, A, true, ranks).write(buf);
        assertEquals(GOLDEN, HexFormat.of().formatHex(buf.toByteArray()));
    }

    @Test
    void decodeGolden() {
        RoomState s = new RoomState(PacketByteBuf.wrap(HexFormat.of().parseHex(GOLDEN)));
        assertEquals(300, s.maxPlayers());
        assertEquals(A, s.hostUuid());
        assertTrue(s.allowBroadcast());
        assertEquals(Map.of(A, 3, B, 1), s.ranks());
    }

    @Test
    void decodeModeration() {
        // VarInt action(2 = kick) + UUID target
        byte[] bytes = HexFormat.of().parseHex("02" + "feb58aa856f64728854682071e39dd24");
        Moderation m = new Moderation(PacketByteBuf.wrap(bytes));
        assertEquals(P2PNet.ACTION_KICK, m.action());
        assertEquals(A, m.target());
    }

    @Test
    void actionIds() {
        assertEquals(0, P2PNet.ACTION_EXPEL);
        assertEquals(1, P2PNet.ACTION_READMIT);
        assertEquals(2, P2PNet.ACTION_KICK);
        assertEquals(3, P2PNet.ACTION_REQUEST_STATE);
    }

    @Test
    void varIntBoundaries() {
        for (int v : new int[]{0, 1, 127, 128, 255, 16383, 16384, Integer.MAX_VALUE, -1}) {
            PacketByteBuf buf = PacketByteBuf.allocate();
            buf.writeVarInt(v);
            assertEquals(v, PacketByteBuf.wrap(buf.toByteArray()).readVarInt());
        }
    }
}
