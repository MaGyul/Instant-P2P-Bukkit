package dev.magyul.instantp2p.common.quic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HostLogTest {
    private static JsonObject entry(String msg) {
        return JsonParser.parseString(msg).getAsJsonObject().getAsJsonObject("hostlog");
    }

    @Test
    void chainLinksAndHashesMatchTheSpec() {
        List<String> sent = new ArrayList<>();
        HostLog log = new HostLog(m -> { sent.add(m); return true; });
        log.send("{\"k\":\"hb\"}");
        log.send("{\"k\":\"join\",\"by\":\"u\"}");
        JsonObject a = entry(sent.get(0)), b = entry(sent.get(1));
        assertEquals(1, a.get("n").getAsLong());
        assertEquals("", a.get("p").getAsString());
        assertEquals(HostLog.sha256Hex("" + "|" + 1 + "|" + a.get("e").getAsString()), a.get("h").getAsString());
        assertEquals(2, b.get("n").getAsLong());
        assertEquals(a.get("h").getAsString(), b.get("p").getAsString());
        assertEquals(HostLog.sha256Hex(a.get("h").getAsString() + "|2|" + b.get("e").getAsString()), b.get("h").getAsString());
        assertEquals(a.get("s").getAsString(), b.get("s").getAsString());
    }

    @Test
    void queuesWhileDisconnectedAndFlushesInOrder() {
        List<String> sent = new ArrayList<>();
        boolean[] up = {false};
        HostLog log = new HostLog(m -> { if (!up[0]) return false; sent.add(m); return true; });
        log.send("{\"k\":\"a\"}");
        log.send("{\"k\":\"b\"}");
        assertEquals(2, log.pendingCount());
        up[0] = true;
        log.flush();
        assertEquals(0, log.pendingCount());
        assertEquals(1, entry(sent.get(0)).get("n").getAsLong());
        assertEquals(2, entry(sent.get(1)).get("n").getAsLong());
    }

    @Test
    void pendingQueueIsBoundedAndOversizedEventsDropped() {
        HostLog log = new HostLog(m -> false);
        for (int i = 0; i < HostLog.MAX_PENDING + 50; i++) log.send("{\"k\":\"x\"}");
        assertEquals(HostLog.MAX_PENDING, log.pendingCount());
        log.send("x".repeat(HostLog.MAX_EVENT_CHARS + 1));
        assertEquals(HostLog.MAX_PENDING, log.pendingCount());
        assertTrue(HostLog.heartbeat().contains("hb"));
    }
}
