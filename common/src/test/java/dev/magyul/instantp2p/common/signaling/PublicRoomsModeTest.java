package dev.magyul.instantp2p.common.signaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PublicRoomsModeTest {
    @Test
    void offMeansPaused() {
        PublicRoomsMode m = PublicRoomsMode.parse("{\"mode\":\"off\",\"notice\":\"점검 중입니다\"}");
        assertFalse(m.enabled());
        assertEquals("점검 중입니다", m.notice());
    }

    @Test
    void onAndRolesAreEnabled() {
        assertTrue(PublicRoomsMode.parse("{\"mode\":\"on\"}").enabled());
        PublicRoomsMode roles = PublicRoomsMode.parse("{\"mode\":\"roles\",\"notice\":\"\"}");
        assertTrue(roles.enabled());
        assertTrue(roles.rolesOnly());
    }

    @Test
    void malformedIsNull() {
        assertNull(PublicRoomsMode.parse("{}"));
        assertNull(PublicRoomsMode.parse("not json"));
    }
}
