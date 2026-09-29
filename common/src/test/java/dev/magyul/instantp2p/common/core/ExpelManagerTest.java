package dev.magyul.instantp2p.common.core;

import dev.magyul.instantp2p.common.core.P2PPlatform;
import dev.magyul.instantp2p.common.core.P2PSettings;
import dev.magyul.instantp2p.common.network.packet.Moderation;
import dev.magyul.instantp2p.common.signaling.RolesTestAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
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
        RolesTestAccess.apply("{\"dev\":[\"" + DEV + "\"],\"supporter\":[\"" + SUPPORTER + "\"],\"streamer\":[\"" + STREAMER + "\"]}");
        platform = new FakePlatform(false);
        platform.online.addAll(List.of(HOST, DEV, SUPPORTER, STREAMER, NOBODY, OTHER));
        roomStateRequests = 0;
        expel = new ExpelManager(platform, () -> roomStateRequests++);
    }

    @AfterEach
    void tearDown() {
        RolesTestAccess.apply("{}");
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

    @Test
    void bypassPlayerLimitOnlyForPerkRolesViaTunnel() throws Exception {
        P2PCore core = new P2PCore(platform);
        core.tunnels().register(new InetSocketAddress("127.0.0.1", 50000), "203.0.113.7", "sid");
        InetAddress tunnel = InetAddress.getByAddress(new byte[]{(byte) 203, 0, 113, 7});
        InetAddress direct = InetAddress.getByAddress(new byte[]{(byte) 198, 51, 100, 1});

        for (UUID id : List.of(DEV, SUPPORTER, STREAMER, NOBODY)) core.noteTunnelLogin(id, tunnel);
        assertTrue(core.canBypassPlayerLimit(DEV));
        assertTrue(core.canBypassPlayerLimit(SUPPORTER));
        assertFalse(core.canBypassPlayerLimit(STREAMER));
        assertFalse(core.canBypassPlayerLimit(NOBODY));

        // 서버 주소로 직접 들어온 경우는 해당 없음
        core.noteTunnelLogin(DEV, direct);
        assertFalse(core.canBypassPlayerLimit(DEV));
        assertFalse(core.canBypassPlayerLimit(OTHER));

        // 입장하면 기록을 지운다
        core.onJoin(SUPPORTER);
        assertFalse(core.canBypassPlayerLimit(SUPPORTER));
    }

    @Test
    void p2pMaxPlayersBlocksOnlyTunnelJoinsWhenFull() throws Exception {
        P2PCore core = new P2PCore(platform);
        core.tunnels().register(new InetSocketAddress("127.0.0.1", 50000), "203.0.113.7", "sid");
        InetAddress tunnel = InetAddress.getByAddress(new byte[]{(byte) 203, 0, 113, 7});
        InetAddress direct = InetAddress.getByAddress(new byte[]{(byte) 198, 51, 100, 1});
        core.onJoin(HOST);
        core.onJoin(OTHER);

        // 꺼져 있으면 서버 정원(20)을 따른다
        assertEquals(20, core.maxPlayers());
        assertFalse(core.isP2PFull(NOBODY, "n", tunnel));

        // 켜면 2명 기준 — 터널 접속만 막고, 개발자·서포터는 예외
        platform.applySettings(platform.settings().withMaxPlayers(true, 2));
        assertEquals(2, core.maxPlayers());
        assertTrue(core.isP2PFull(NOBODY, "n", tunnel));
        assertTrue(core.isP2PFull(STREAMER, "s", tunnel));
        assertFalse(core.isP2PFull(DEV, "d", tunnel));
        assertFalse(core.isP2PFull(SUPPORTER, "s", tunnel));
        assertFalse(core.isP2PFull(NOBODY, "n", direct));
        assertFalse(core.isP2PFull(OTHER, "o", tunnel)); // 이미 접속 중(중복 접속)

        // 서버 정원보다 크게 저장돼 있으면 서버 정원으로
        platform.applySettings(platform.settings().withMaxPlayers(true, 50));
        assertEquals(20, core.maxPlayers());
        assertFalse(core.isP2PFull(NOBODY, "n", tunnel));

        // 값이 0(안 정함)이면 켜져 있어도 적용 안 함
        platform.applySettings(platform.settings().withMaxPlayers(true, 0));
        assertEquals(20, core.maxPlayers());

        // 서버 정원이 0이면(바닐라가 전원 거부) 모드에 보이는 정원도 0 — 빈자리가 있는 것처럼 보이지 않게
        platform.serverMax = 0;
        platform.applySettings(platform.settings().withMaxPlayers(true, 3));
        assertEquals(0, core.maxPlayers());
        assertTrue(core.isP2PFull(NOBODY, "n", tunnel));
    }

    private static final class FakePlatform implements P2PPlatform {
        final Set<UUID> online = new HashSet<>();
        P2PSettings applied;
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
            if (applied != null) return applied;
            return new P2PSettings(true, HOST, "1.2.3", "", "Server", true, List.of("normal"),
                    false, allowBroadcast, 0);
        }
        @Override public P2PSettings loadSettings() { return settings(); }
        @Override public void applySettings(P2PSettings settings) { applied = settings; }
        @Override public void saveSettings(java.util.Map<String, Object> values) {}
        @Override public Collection<UUID> bannedPlayers() { return List.of(); }
        int serverMax = 20;
        @Override public int maxPlayers() { return serverMax; }
        @Override public String minecraftVersion() { return "1.21.11"; }
        @Override public int listenPort() { return 25565; }
        @Override public Path dataFolder() { return Path.of("build", "test-data"); }
        @Override public String motd() { return "motd"; }
        @Override public P2PSender console() { return (key, args) -> {}; }
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
