package dev.magyul.instantp2p.common.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelayLinksTest {
    @Test
    void remembersAddressesPerAccountAndPersists(@TempDir Path dir) {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Path file = dir.resolve("relay-links.json");
        RelayLinks links = new RelayLinks(file);
        links.remember(a, "240.1.1.1");
        links.remember(a, "241.2.2.2");
        links.remember(a, "240.1.1.1"); // 중복
        links.remember(b, "250.9.9.9");
        assertEquals(Set.of("240.1.1.1", "241.2.2.2"), links.addresses(a));
        assertEquals(Set.of("250.9.9.9"), links.addresses(b));
        assertEquals(Set.of("240.1.1.1", "241.2.2.2"), new RelayLinks(file).addresses(a)); // 재시작 뒤에도
    }

    @Test
    void keepsOnlyTheMostRecentAddresses(@TempDir Path dir) {
        UUID a = UUID.randomUUID();
        RelayLinks links = new RelayLinks(dir.resolve("r.json"));
        for (int i = 0; i < RelayLinks.MAX_PER_ACCOUNT + 5; i++) links.remember(a, "240.0.0." + i);
        Set<String> got = links.addresses(a);
        assertEquals(RelayLinks.MAX_PER_ACCOUNT, got.size());
        assertTrue(got.contains("240.0.0." + (RelayLinks.MAX_PER_ACCOUNT + 4)));
        assertTrue(!got.contains("240.0.0.0"));
    }

    @Test
    void parsesBannedIpsFileFormat() {
        Set<String> ips = BannedIps.parse(JsonParser.parseString(
                "[{\"ip\":\"240.86.69.91\",\"created\":\"x\",\"source\":\"Console\",\"expires\":\"forever\",\"reason\":\"r\"},{\"nope\":1}]"));
        assertEquals(Set.of("240.86.69.91"), ips);
        assertTrue(BannedIps.parse(JsonParser.parseString("{}")).isEmpty());
    }
}
