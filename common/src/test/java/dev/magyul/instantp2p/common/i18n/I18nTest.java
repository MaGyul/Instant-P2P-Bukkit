package dev.magyul.instantp2p.common.i18n;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class I18nTest {

    @Test
    void fallbackFromModLangFile() {
        assertEquals("§a(직결 통신)", I18n.fallback("instant-p2p.msg.join_suffix_direct"));
        assertEquals("no.such.key", I18n.fallback("no.such.key"));
    }

    @Test
    void format() {
        assertEquals("§cA 님이 당신을 강퇴했습니다", I18n.format("instant-p2p.msg.kicked_by", "A"));
    }

    @Test
    void legacyCodes() {
        assertEquals("(직결 통신)", I18n.stripLegacy("§a(직결 통신)"));
        assertEquals("점검 (실패) 재시도", I18n.stripLegacy("§c점검 §f(실패) §c재시도"));
        assertEquals('a', I18n.leadingColorCode("§a(직결 통신)"));
        assertEquals('c', I18n.leadingColorCode("§C..."));
        assertEquals(0, I18n.leadingColorCode("§l굵게"));
        assertEquals(0, I18n.leadingColorCode("plain"));
    }
}
