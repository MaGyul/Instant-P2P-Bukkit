package dev.magyul.instantp2p.common.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReportEvidenceTest {

    @Test
    void signTextJoinsNonEmptyTrimmedLines() {
        assertEquals("a / b / c", ReportEvidence.signText(Arrays.asList(" a ", "", "b", "  ", "c")));
        assertNull(ReportEvidence.signText(Arrays.asList("", "  ", null)));
        assertEquals(ReportEvidence.MAX_SIGN_TEXT, ReportEvidence.signText(List.of("x".repeat(400))).length());
    }

    @Test
    void bookHashMatchesOriginalClientFormat() throws Exception {
        // 원본 클라이언트: kind \n title \n author, 쪽마다 \u0001 + (\r\n → \n, 뒤 공백 제거) 의 SHA-256 hex
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("written\nTitle\nAuthor\u0001page one\u0001page two\n\nx".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(expected, ReportEvidence.bookHash("written", " Title ", "Author", List.of("page one  ", "page two\r\n\r\nx")));
        // 쓰는 중인 책은 제목·저자가 비어 종류만 다르다
        assertNotEquals(ReportEvidence.bookHash("written", "", "", List.of("a")), ReportEvidence.bookHash("writable", "", "", List.of("a")));
    }

    @Test
    void chatWindowFiltersByTimeAndCapsLines() throws Exception {
        ReportEvidence ev = new ReportEvidence();
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 200; i++) ev.addChat("Steve", "<Steve> " + "m".repeat(i % 2 == 0 ? 10 : 300));
        long t1 = System.currentTimeMillis();

        JsonArray all = JsonParser.parseString(ev.chatWindowJson(t0 - 1, t1 + 1)).getAsJsonArray();
        assertEquals(150, all.size()); // 최근 150줄
        for (var e : all) {
            var o = e.getAsJsonObject();
            assertEquals("Steve", o.get("from").getAsString());
            assertTrue(o.get("line").getAsString().length() <= 200);
            assertTrue(o.get("t").getAsLong() >= t0);
        }
        assertEquals(0, JsonParser.parseString(ev.chatWindowJson(t1 + 10_000, t1 + 20_000)).getAsJsonArray().size());

        ev.clear();
        assertEquals("[]", ev.chatWindowJson(0, Long.MAX_VALUE));
    }

    @Test
    void chatKeepsOnlyRecent600() {
        ReportEvidence ev = new ReportEvidence();
        for (int i = 0; i < 700; i++) ev.addChat("a", "line " + i);
        // 600줄만 남지만 응답은 150줄까지 — 가장 최근 줄이 포함돼야 한다
        String json = ev.chatWindowJson(0, Long.MAX_VALUE);
        assertTrue(json.contains("line 699"));
        assertFalse(json.contains("\"line 549\""));
    }
}
