package cn.iocoder.yudao.module.skit.service.ad.callback;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PangleRewardCallbackCanonicalizerTest {

    private static final String SIGNATURE =
            "0000000000000000000000000000000000000000000000000000000000000000";

    private final PangleRewardCallbackCanonicalizer canonicalizer =
            new PangleRewardCallbackCanonicalizer();

    @Test
    void canonicalizesExactlyTheSixDocumentedPangleFields() {
        PangleRewardCallback callback = canonicalizer.canonicalize(validQuery());

        assertEquals("u1", callback.getUserId());
        assertEquals("tx-1", callback.getTransactionId());
        assertEquals("coin reward", callback.getRewardName());
        assertEquals("1", callback.getRewardAmountLexical());
        assertEquals("session-token", callback.getExtra());
        assertEquals(SIGNATURE, callback.getSignatureHex());
        assertEquals(32, callback.getCanonicalPayloadHash().length);
    }

    @Test
    void canonicalHashIsIndependentOfQueryOrderButBindsDecodedValues() {
        PangleRewardCallback first = canonicalizer.canonicalize(validQuery());
        PangleRewardCallback reordered = canonicalizer.canonicalize("sign=" + SIGNATURE
                + "&extra=session-token&reward_amount=1&reward_name=coin+reward"
                + "&trans_id=tx-1&user_id=u1");
        PangleRewardCallback changed = canonicalizer.canonicalize(
                validQuery().replace("session-token", "other-token"));

        assertArrayEquals(first.getCanonicalPayloadHash(), reordered.getCanonicalPayloadHash());
        assertFalse(Arrays.equals(first.getCanonicalPayloadHash(), changed.getCanonicalPayloadHash()));
    }

    @Test
    void decodesFormValuesExactlyOnceWithStrictUtf8() {
        PangleRewardCallback callback = canonicalizer.canonicalize(validQuery()
                .replace("session-token", "%252Fstill-percent-encoded")
                .replace("coin+reward", "%E5%A5%96%E5%8A%B1+coin"));

        assertEquals("%2Fstill-percent-encoded", callback.getExtra());
        assertEquals("奖励 coin", callback.getRewardName());
    }

    @Test
    void rejectsUnknownDuplicateMissingAndEmptyFields() {
        assertRejected(PangleRewardCallbackCanonicalizer.ErrorCode.UNKNOWN_PARAMETER,
                validQuery() + "&placement_id=attacker");
        assertRejected(PangleRewardCallbackCanonicalizer.ErrorCode.DUPLICATE_PARAMETER,
                validQuery() + "&user_id=attacker");
        assertRejected(PangleRewardCallbackCanonicalizer.ErrorCode.MISSING_PARAMETER,
                validQuery().replace("&extra=session-token", ""));
        assertRejected(PangleRewardCallbackCanonicalizer.ErrorCode.INVALID_VALUE,
                validQuery().replace("trans_id=tx-1", "trans_id="));
    }

    @Test
    void rejectsMalformedEncodingControlsOversizedInputsAndMalformedSignatures() {
        assertRejected(PangleRewardCallbackCanonicalizer.ErrorCode.INVALID_ENCODING,
                validQuery().replace("u1", "%ZZ"));
        assertRejected(PangleRewardCallbackCanonicalizer.ErrorCode.INVALID_ENCODING,
                validQuery().replace("u1", "%C3%28"));
        assertRejected(PangleRewardCallbackCanonicalizer.ErrorCode.INVALID_VALUE,
                validQuery().replace("u1", "u%0D%0Ainjected"));
        assertRejected(PangleRewardCallbackCanonicalizer.ErrorCode.VALUE_TOO_LONG,
                validQuery().replace("session-token", repeat('x', 1025)));
        assertRejected(PangleRewardCallbackCanonicalizer.ErrorCode.QUERY_TOO_LONG,
                "user_id=" + repeat('x', PangleRewardCallbackCanonicalizer.MAX_RAW_QUERY_LENGTH));
        assertRejected(PangleRewardCallbackCanonicalizer.ErrorCode.INVALID_SIGNATURE,
                validQuery().replace(SIGNATURE, "not-a-valid-sha256"));
    }

    @Test
    void acceptsTheDocumentedOneKibibytePersistenceBoundaryButRejectsNonAsciiTransactionIds() {
        String boundary = repeat('x', 1024);
        PangleRewardCallback callback = canonicalizer.canonicalize(
                "user_id=" + boundary + "&trans_id=" + boundary
                        + "&reward_name=" + boundary + "&reward_amount=1"
                        + "&extra=session-token&sign=" + SIGNATURE);

        assertEquals(1024, callback.getUserId().length());
        assertEquals(1024, callback.getTransactionId().length());
        assertEquals(1024, callback.getRewardName().length());
        assertRejected(PangleRewardCallbackCanonicalizer.ErrorCode.INVALID_VALUE,
                validQuery().replace("trans_id=tx-1", "trans_id=%E4%BA%A4%E6%98%93"));
    }

    @Test
    void requiresAPositiveBoundedIntegerRewardAmount() {
        assertEquals("100000000", canonicalizer.canonicalize(
                validQuery().replace("reward_amount=1", "reward_amount=100000000"))
                .getRewardAmountLexical());
        assertRejected(PangleRewardCallbackCanonicalizer.ErrorCode.INVALID_VALUE,
                validQuery().replace("reward_amount=1", "reward_amount=0"));
        assertRejected(PangleRewardCallbackCanonicalizer.ErrorCode.INVALID_VALUE,
                validQuery().replace("reward_amount=1", "reward_amount=100000001"));
        assertRejected(PangleRewardCallbackCanonicalizer.ErrorCode.INVALID_VALUE,
                validQuery().replace("reward_amount=1", "reward_amount=1.0"));
    }

    @Test
    void returnedHashIsDefensiveAndCallbackStateIsImmutable() {
        PangleRewardCallback callback = canonicalizer.canonicalize(validQuery());
        byte[] first = callback.getCanonicalPayloadHash();
        first[0] ^= 0x7f;

        assertNotEquals(first[0], callback.getCanonicalPayloadHash()[0]);
        for (Field field : PangleRewardCallback.class.getDeclaredFields()) {
            if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                assertTrue(Modifier.isFinal(field.getModifiers()), field.getName() + " must be final");
            }
        }
    }

    @Test
    void implementationStreamsBoundedPairsInsteadOfSplittingTheWholeQuery() throws Exception {
        Path source = locateCanonicalizerSource();
        String java = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertFalse(java.contains("rawQuery.split("));
        assertTrue(java.contains("MAX_RAW_QUERY_LENGTH"));
    }

    private void assertRejected(PangleRewardCallbackCanonicalizer.ErrorCode expected, String query) {
        PangleRewardCallbackCanonicalizer.CallbackFormatException error = assertThrows(
                PangleRewardCallbackCanonicalizer.CallbackFormatException.class,
                () -> canonicalizer.canonicalize(query));
        assertEquals(expected, error.getErrorCode());
        assertFalse(error.getMessage().contains("session-token"));
    }

    private static String validQuery() {
        return "user_id=u1&trans_id=tx-1&reward_name=coin+reward&reward_amount=1"
                + "&extra=session-token&sign=" + SIGNATURE;
    }

    private static String repeat(char value, int length) {
        char[] chars = new char[length];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static Path locateCanonicalizerSource() {
        Path workingDirectory = Paths.get("").toAbsolutePath();
        Path moduleRelative = workingDirectory.resolve("src/main/java/cn/iocoder/yudao/module/skit/"
                + "service/ad/callback/PangleRewardCallbackCanonicalizer.java");
        if (Files.isRegularFile(moduleRelative)) {
            return moduleRelative;
        }
        return workingDirectory.resolve("yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/"
                + "service/ad/callback/PangleRewardCallbackCanonicalizer.java");
    }
}
