package dev.magyul.instantp2p.common.quic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class RoomInfoTest {
    @Test
    void defaultsMatchVanilla() {
        Properties p = new Properties();
        assertEquals("survival", RoomInfo.gameMode(p));
        assertTrue(RoomInfo.signedChat(p));
    }

    @Test
    void readsServerProperties() {
        Properties p = new Properties();
        p.setProperty("gamemode", "Creative");
        p.setProperty("enforce-secure-profile", "false");
        assertEquals("creative", RoomInfo.gameMode(p));
        assertFalse(RoomInfo.signedChat(p));
        p.setProperty("gamemode", "2");
        assertEquals("adventure", RoomInfo.gameMode(p));
        p.setProperty("gamemode", "weird");
        assertEquals("survival", RoomInfo.gameMode(p));
    }
}
