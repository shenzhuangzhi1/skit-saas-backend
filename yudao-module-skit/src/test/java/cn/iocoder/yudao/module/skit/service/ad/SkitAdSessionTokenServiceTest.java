package cn.iocoder.yudao.module.skit.service.ad;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdSessionTokenServiceTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.US_ASCII);

    @Test
    void tokenIsDeterministicForHashOnlySessionReuseAndNeverLeaksThroughSerialization() throws Exception {
        SkitAdSessionTokenService service = new SkitHmacAdSessionTokenService(3,
                Collections.singletonMap(3, KEY));

        SkitAdSessionTokenService.IssuedToken issued = service.issue("session-global-1");
        SkitAdSessionTokenService.IssuedToken restored = service.restore("session-global-1", 3);
        String raw = issued.consumeCustomData();

        assertEquals(43, raw.length(), "a SHA-256 PRF output is 256-bit base64url without padding");
        assertTrue(raw.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(raw, restored.consumeCustomData());
        assertArrayEquals(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.US_ASCII)), issued.getTokenHash());
        assertTrue(service.matches(raw, issued.getTokenHash()));
        assertFalse(issued.toString().contains(raw));
        assertFalse(new ObjectMapper().writeValueAsString(issued).contains(raw));
        assertThrows(IllegalStateException.class, issued::consumeCustomData);
    }

    @Test
    void domainSeparationProducesDifferentSessionAndPseudonymousMemberValues() {
        SkitAdSessionTokenService service = new SkitHmacAdSessionTokenService(1,
                Collections.singletonMap(1, KEY));

        String first = service.issue("session-1").consumeCustomData();
        String second = service.issue("session-2").consumeCustomData();
        String member = service.pseudonymousUserId(9L, 27L);

        assertNotEquals(first, second);
        assertNotEquals(first, member);
        assertEquals(member, service.pseudonymousUserId(9L, 27L));
        assertNotEquals(member, service.pseudonymousUserId(10L, 27L));
        assertNotEquals(member, service.pseudonymousUserId(9L, 28L));
        assertNotEquals("9:27", member);
    }

    @Test
    void retainedVersionRestoresOldSessionAndUnknownVersionFailsClosed() {
        byte[] oldKey = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.US_ASCII);
        SkitAdSessionTokenService oldService = new SkitHmacAdSessionTokenService(1,
                Collections.singletonMap(1, oldKey));
        String oldToken = oldService.issue("old-session").consumeCustomData();

        Map<Integer, byte[]> rotatedKeys = new LinkedHashMap<>();
        rotatedKeys.put(1, oldKey);
        rotatedKeys.put(2, KEY);
        SkitAdSessionTokenService rotated = new SkitHmacAdSessionTokenService(2, rotatedKeys);

        assertEquals(oldToken, rotated.restore("old-session", 1).consumeCustomData());
        assertEquals(2, rotated.issue("new-session").getKeyVersion());
        assertThrows(IllegalStateException.class, () -> rotated.restore("old-session", 99));
    }

    @Test
    void weakMissingOrConflictingKeysFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new SkitHmacAdSessionTokenService(1, Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class,
                () -> new SkitHmacAdSessionTokenService(1,
                        Collections.singletonMap(1, "too-short".getBytes(StandardCharsets.US_ASCII))));
        assertThrows(IllegalArgumentException.class,
                () -> new SkitHmacAdSessionTokenService(0, Collections.singletonMap(0, KEY)));
    }

}
