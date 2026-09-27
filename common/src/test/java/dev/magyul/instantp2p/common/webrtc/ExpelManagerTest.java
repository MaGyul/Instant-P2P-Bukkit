package dev.magyul.instantp2p.common.webrtc;

import dev.magyul.instantp2p.common.core.P2PPlatform;
import dev.magyul.instantp2p.common.core.P2PSettings;
import dev.magyul.instantp2p.common.network.packet.Moderation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** expel/kick 권한 규칙 — CLAUDE.md "서버 로직". */
class ExpelManagerTest {

    private static final UUID HOST = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID DEV = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID SUPPORTER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID STREAMER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NOBODY = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private FakePlatform platform;
    private ExpelManager expel;
    private int roomStateRequests;

    @BeforeEach
    void setUp() {
        Roles.apply("{\"dev\":[\"" + DEV + "\"],\"supporter\":[\"" + SUPPORTER + "\"],\"streamer\":[\"" + STREAMER + "\"]}");
        platform = new FakePlatform(false);
        platform.online.addAll(List.of(HOST, DEV, SUPPORTER, STREAMER, NOBODY, OTHER));
        roomStateRequests = 0;
        expel = new ExpelManager(platform, () -> roomStateRequests++);
    }

    @AfterEach
    void tearDown() {
        Roles.apply("{}");
    }

    @Test
    void priorities() {
        assertEquals(3, ExpelManager.priority(DEV));
        assertEquals(2, ExpelManager.priority(SUPPORTER));
        assertEquals(1, ExpelManager.priority(STREAMER));
        assertEquals(0, ExpelManager.priority(NOBODY));
        assertEquals(0, ExpelManager.priority(null));
    }

    @Test
    void requestStateAllowedForAnyone() {
        expel.handleRequest(NOBODY, Moderation.ACTION_REQUEST_STATE, NOBODY);
        assertEquals(1, roomStateRequests);
    }

    @Test
    void higherRankExpelsAndKicks() {
        expel.handleRequest(DEV, Moderation.ACTION_EXPEL, SUPPORTER);
        assertTrue(expel.isExpelled(SUPPORTER));
        assertEquals(List.of(SUPPORTER + ":instant-p2p.msg.expelled_by:[" + DEV + "]"), platform.kicks);
    }

    @Test
    void sameOrLowerRankIgnored() {
        expel.handleRequest(SUPPORTER, Moderation.ACTION_EXPEL, DEV);
        expel.handleRequest(NOBODY, Moderation.ACTION_EXPEL, OTHER);
        assertFalse(expel.isExpelled(DEV));
        assertFalse(expel.isExpelled(OTHER));
        assertTrue(platform.kicks.isEmpty());
    }

    @Test
    void hostOutranksEveryoneButCannotBeExpelled() {
        expel.handleRequest(HOST, Moderation.ACTION_EXPEL, DEV);
        assertTrue(expel.isExpelled(DEV));

        expel.handleRequest(DEV, Moderation.ACTION_EXPEL, HOST);
        expel.handleRequest(DEV, Moderation.ACTION_KICK, HOST);
        assertFalse(expel.isExpelled(HOST));
        assertEquals(1, platform.kicks.size());
    }

    @Test
    void streamerNeedsAllowBroadcastExceptReadmit() {
        expel.handleRequest(STREAMER, Moderation.ACTION_EXPEL, NOBODY);
        assertFalse(expel.isExpelled(NOBODY));

        FakePlatform broadcast = new FakePlatform(true);
        broadcast.online.addAll(platform.online);
        ExpelManager allowed = new ExpelManager(broadcast, () -> {});
        allowed.handleRequest(STREAMER, Moderation.ACTION_EXPEL, NOBODY);
        assertTrue(allowed.isExpelled(NOBODY));

        // 방송 허용이 없어도 해제는 된다
        ExpelManager other = new ExpelManager(platform, () -> {});
        FakePlatform.withBroadcast(platform, true, () -> other.handleRequest(STREAMER, Moderation.ACTION_EXPEL, NOBODY));
        assertTrue(other.isExpelled(NOBODY));
        other.handleRequest(STREAMER, Moderation.ACTION_READMIT, NOBODY);
        assertFalse(other.isExpelled(NOBODY));
    }

    @Test
    void offlineTargetIsRecordedWithoutKick() {
        platform.online.remove(OTHER);
        expel.handleRequest(DEV, Moderation.ACTION_EXPEL, OTHER);
        assertTrue(expel.isExpelled(OTHER));
        assertTrue(platform.kicks.isEmpty());
    }

    @Test
    void releasedOnlyWhenAllHoldersLeave() {
        expel.handleRequest(DEV, Moderation.ACTION_EXPEL, NOBODY);
        expel.handleRequest(HOST, Moderation.ACTION_EXPEL, NOBODY);
        assertEquals(1, platform.kicks.size()); // 이미 추방 상태면 다시 킥하지 않는다

        expel.onDisconnect(DEV);
        assertTrue(expel.isExpelled(NOBODY));
        expel.onDisconnect(HOST);
        assertFalse(expel.isExpelled(NOBODY));
    }

    @Test
    void kickDoesNotBlockRejoin() {
        expel.handleRequest(DEV, Moderation.ACTION_KICK, NOBODY);
        assertFalse(expel.isExpelled(NOBODY));
        assertEquals(List.of(NOBODY + ":instant-p2p.msg.kicked_by:[" + DEV + "]"), platform.kicks);
    }

    @Test
    void unknownNameFallsBack() {
        platform.names = false;
        expel.handleRequest(DEV, Moderation.ACTION_KICK, NOBODY);
        assertEquals(List.of(NOBODY + ":instant-p2p.msg.kicked_by:[?]"), platform.kicks);
    }

    private static final class FakePlatform implements P2PPlatform {
        final Set<UUID> online = new HashSet<>();
        final List<String> kicks = new ArrayList<>();
        boolean names = true;
        boolean allowBroadcast;

        FakePlatform(boolean allowBroadcast) {
            this.allowBroadcast = allowBroadcast;
        }

        static void withBroadcast(FakePlatform p, boolean value, Runnable r) {
            boolean prev = p.allowBroadcast;
            p.allowBroadcast = value;
            try { r.run(); } finally { p.allowBroadcast = prev; }
        }

        @Override public P2PSettings settings() {
            return new P2PSettings(true, HOST, "1.2.3", "", "Server", true, List.of("normal"),
                    false, allowBroadcast, false);
        }
        @Override public Collection<UUID> bannedPlayers() { return List.of(); }
        @Override public int maxPlayers() { return 20; }
        @Override public String minecraftVersion() { return "1.21.11"; }
        @Override public int listenPort() { return 25565; }
        @Override public Path dataFolder() { return Path.of("."); }
        @Override public boolean isOnline(UUID player) { return online.contains(player); }
        @Override public String playerName(UUID player) { return names && online.contains(player) ? player.toString() : null; }
        @Override public boolean isHost(UUID player) { return HOST.equals(player); }
        @Override public void kick(UUID player, String key, Object... args) {
            kicks.add(player + ":" + key + ":" + List.of(args));
        }
        @Override public void notifyAdmins(String key, Object... args) {}
        @Override public void runSync(Runnable task) { task.run(); }
        @Override public void broadcastRoomState(byte[] payload) {}
    }
}
