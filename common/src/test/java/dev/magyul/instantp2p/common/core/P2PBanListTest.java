package dev.magyul.instantp2p.common.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P2PBanListTest {
    @Test
    void relayEntriesMatchByAccountAndDirectEntriesByAddress(@TempDir Path dir) {
        P2PBanList list = new P2PBanList(dir.resolve("p2p-bans.json"));
        UUID relayUser = UUID.randomUUID();
        list.add(relayUser, "Relay", "240.1.2.3", true, "spam");
        list.add(null, "Direct", "203.0.113.9", false, "grief");

        // 중계 접속자: 주소가 바뀌어도 계정으로 막힌다
        assertTrue(list.find(relayUser, "250.9.9.9").isPresent());
        assertFalse(list.find(UUID.randomUUID(), "240.1.2.3").isPresent()); // 다른 계정이 우연히 같은 합성 주소여도 막지 않는다
        // 직결: 주소로 막는다
        assertTrue(list.find(UUID.randomUUID(), "203.0.113.9").isPresent());
        assertFalse(list.find(UUID.randomUUID(), "203.0.113.10").isPresent());
    }

    @Test
    void addTwiceUpdatesTheReasonAndPardonReturnsRemovedEntries(@TempDir Path dir) {
        P2PBanList list = new P2PBanList(dir.resolve("b.json"));
        P2PBanList.Entry a = list.add(null, "A", "198.51.100.1", false, "one");
        P2PBanList.Entry b = list.add(null, "A", "198.51.100.1", false, "two");
        assertEquals(a.id(), b.id());
        assertEquals(1, list.all().size());
        assertEquals("two", list.all().get(0).reason());

        assertEquals(1, list.remove("a").size()); // 이름은 대소문자 무시
        assertTrue(list.all().isEmpty());
        assertTrue(list.removeById(99).isEmpty());
    }

    @Test
    void persistsAndNeverWritesAnythingButGivenAddress(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("p2p-bans.json");
        UUID id = UUID.randomUUID();
        P2PBanList list = new P2PBanList(f);
        P2PBanList.Entry e = list.add(id, "X", "240.5.5.5", true, "r");
        P2PBanList again = new P2PBanList(f);
        assertEquals(1, again.all().size());
        assertEquals(e.id(), again.all().get(0).id());
        assertTrue(again.find(id, null).isPresent());
        // 새 항목 번호가 이어진다
        assertEquals(e.id() + 1, again.add(null, "Y", "1.1.1.1", false, "").id());
        // 파일에는 우리가 넘긴 주소뿐 — 다른 IP 흔적이 없다
        String text = Files.readString(f);
        assertFalse(text.contains("192.168"));
    }
}
