package dev.magyul.instantp2p;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** members 해시와 초대 코드 형식 — 원본 모드와 같은 규칙. */
class MembersHashTest {

    private static final UUID A = UUID.fromString("feb58aa8-56f6-4728-8546-82071e39dd24");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void golden() {
        assertEquals("UssJZIAmANg,qvEQhbkQIxw", Utils.encodePlayerHashes(List.of(A, B), "ABCDEFGHJK"));
    }

    @Test
    void matchesSpec() throws Exception {
        // base64url_nopad(sha256(code + ":" + uuid)[0:8])
        byte[] d = MessageDigest.getInstance("SHA-256")
                .digest(("ABCDEFGHJK:" + A).getBytes(StandardCharsets.UTF_8));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(d, 8));
        assertEquals(expected, Utils.encodePlayerHashes(List.of(A), "ABCDEFGHJK"));
    }

    @Test
    void empty() {
        assertEquals("", Utils.encodePlayerHashes(List.of(), "ABCDEFGHJK"));
    }

    @Test
    void inviteCodeFormat() {
        for (int i = 0; i < 200; i++) {
            assertTrue(Utils.generateCode().matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{10}"));
        }
    }
}
