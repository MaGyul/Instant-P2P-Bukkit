package dev.magyul.instantp2p.common.signaling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModVersionTest {

    @Test
    void autoValues() {
        assertTrue(ModVersion.isAuto("auto"));
        assertTrue(ModVersion.isAuto(" AUTO "));
        assertTrue(ModVersion.isAuto(""));
        assertTrue(ModVersion.isAuto(null));
        assertFalse(ModVersion.isAuto("1.3"));
    }

    @Test
    void explicitVersionSkipsNetwork() {
        assertEquals("1.2.3", ModVersion.resolve(" 1.2.3 "));
    }

    @Test
    void parseServerResponse() {
        assertEquals("1.3", ModVersion.parse("{\"current\":\"1.3\",\"notice\":\"과거 버전을 사용중이십니다. 업데이트 해주세요\"}"));
        assertEquals("1.4.0-beta", ModVersion.parse("{\"current\":\" 1.4.0-beta \"}"));
        assertNull(ModVersion.parse("{\"notice\":\"x\"}"));
        assertNull(ModVersion.parse("{\"current\":\"\"}"));
        assertNull(ModVersion.parse("{\"current\":\"1.3 <script>\"}"));
        assertNull(ModVersion.parse("{\"current\":{}}"));
        assertNull(ModVersion.parse("not json"));
    }
}
