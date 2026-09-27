package dev.magyul.instantp2p.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftVersionsTest {

    @Test
    void comparesNumerically() {
        assertTrue(MinecraftVersions.atLeast("1.21", 1, 21));
        assertTrue(MinecraftVersions.atLeast("1.21.0", 1, 21));
        assertTrue(MinecraftVersions.atLeast("1.21.11", 1, 21));
        assertTrue(MinecraftVersions.atLeast("26.1", 1, 21));
        assertFalse(MinecraftVersions.atLeast("1.20.6", 1, 21));
        assertFalse(MinecraftVersions.atLeast("1.18.2", 1, 21));
        assertFalse(MinecraftVersions.atLeast("1.9", 1, 21)); // 문자열 비교면 틀린다
    }

    @Test
    void unparseableIsAllowed() {
        assertTrue(MinecraftVersions.atLeast("25w14a", 1, 21));
        assertTrue(MinecraftVersions.atLeast(null, 1, 21));
    }
}
