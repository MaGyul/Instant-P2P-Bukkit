package dev.magyul.instantp2p.velocity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BackendVersionTest {

    @Test
    void parsesVersionFromServerBrand() {
        assertEquals("1.21.11", BackendVersion.parse("Paper 1.21.11"));
        assertEquals("1.21", BackendVersion.parse("Spigot 1.21"));
        assertEquals("26.1", BackendVersion.parse("26.1"));
        assertEquals("1.21.4", BackendVersion.parse("Velocity 3.4.0 / 1.21.4")); // 마지막 버전을 쓴다
        assertNull(BackendVersion.parse("CustomBrand"));
        assertNull(BackendVersion.parse(null));
    }
}
