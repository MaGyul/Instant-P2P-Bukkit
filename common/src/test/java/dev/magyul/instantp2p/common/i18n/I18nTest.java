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

    @Test
    void serverMessagesGetPrefix() {
        // 서버판 메시지에는 [P2P], 모드 키·조각·약관 본문 줄에는 없음
        assertTrue(I18n.fallback("instant-p2p-server.reload.done").startsWith(I18n.PREFIX));
        assertTrue(I18n.fallback("instant-p2p-server.terms.heading").startsWith(I18n.PREFIX));
        assertFalse(I18n.fallback("instant-p2p.msg.join_suffix_direct").startsWith(I18n.PREFIX));
        for (String k : new String[]{"copy", "status.direct", "server.franchise", "terms.body", "terms.disclaimer", "terms.accept"}) {
            assertFalse(I18n.fallback("instant-p2p-server." + k).startsWith(I18n.PREFIX), k);
        }
        assertFalse(I18n.formatLog("instant-p2p-server.reload.done").startsWith(I18n.PREFIX));
        assertTrue(I18n.format("instant-p2p-server.login.success", "A").startsWith(I18n.PREFIX));
        assertFalse(I18n.format("instant-p2p-server.login.success", "A").contains("[instant-p2p]"));
        // 관리자 알림 모드 키 — 머리말은 붙지만 번역 fallback 자체(plainFallback)에는 없다
        assertTrue(I18n.hasPrefix("instant-p2p.msg.signaling_unreachable"));
        assertTrue(I18n.format("instant-p2p.msg.signaling_recovered").startsWith(I18n.PREFIX));
        assertFalse(I18n.plainFallback("instant-p2p.msg.signaling_recovered").startsWith(I18n.PREFIX));
        assertFalse(I18n.hasPrefix("instant-p2p.msg.kicked_by"));
    }
}
