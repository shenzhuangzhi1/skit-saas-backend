package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TakuRewardSignatureVerifierTest {

    private static final String SECRET_TEXT = "cyvmwmzrqts7csphhexpqxqxxgljfisb";
    private static final byte[] SECRET = SECRET_TEXT.getBytes(StandardCharsets.US_ASCII);

    private final TakuCallbackCanonicalizer canonicalizer = new TakuCallbackCanonicalizer();
    private final TakuRewardSignatureVerifier verifier = new TakuRewardSignatureVerifier(new ObjectMapper());

    @Test
    void verifiesTheDocumentedTakuLabelSeparatorOrderButWithholdsAuthorityWithoutSignedIlrd() {
        // Taku publishes the input expression and sample values, but not its resulting digest.
        // f343... is an independently calculated public fixture for that documented expression.
        String query = "user_id=user-1&trans_id=xxxxxxxxxxxxxxxx&placement_id=b5b449fb3d89d7"
                + "&adsource_id=56789&reward_amount=1&reward_name=coin&extra_data=token-1"
                + "&network_firm_id=66&sign=f3430a708850870011b76c70f4c64583";

        TakuRewardSignatureVerifier.VerificationResult result = verifier.verify(
                canonicalizer.canonicalizeReward(query), SECRET.clone());

        assertTrue(result.isCoreDigestValid());
        assertEquals(TakuRewardSignatureVerifier.Status.MISSING_SIGNED_ILRD, result.getStatus());
        assertNull(result.getAuthority(), "phase-one production authority requires signed ILRD evidence");
        assertFalse(result.hasSignedRewardAuthority());
    }

    @Test
    void documentedIlrdExpressionMatchesBeforeStrictIlrdClassificationRejectsNonJsonFixture() {
        // The official page uses literal "abc" as its ILRD example and does not publish a digest.
        // afa3... is independently calculated from that documented label/separator expression.
        String query = "user_id=user-1&trans_id=xxxxxxxxxxxxxxxx&placement_id=b5b449fb3d89d7"
                + "&adsource_id=56789&reward_amount=1&reward_name=coin&extra_data=token-1"
                + "&network_firm_id=66&sign=afa3656c9bae5acec9a1a43c34dd8e7b&ilrd=abc";

        TakuRewardSignatureVerifier.VerificationResult result = verifier.verify(
                canonicalizer.canonicalizeReward(query), SECRET.clone());

        assertTrue(result.isCoreDigestValid(), "digest must follow the official expression exactly");
        assertEquals(TakuRewardSignatureVerifier.Status.INVALID_SIGNED_ILRD, result.getStatus());
        assertNull(result.getAuthority(), "non-JSON ILRD must never become signed network authority");
        assertFalse(result.hasSignedRewardAuthority());
    }

    @Test
    void acceptsStrictSignedIlrdAndExposesOnlyIdentifierClassificationEvidence() {
        TakuRewardSignatureVerifier.VerificationResult result = verifyStrictIlrd(
                "{\"network_firm_id\":66,\"adsource_id\":\"56789\",\"id\":\"show-123\","
                        + "\"adunit_id\":\"b5b449fb3d89d7\",\"publisher_revenue\":9.99}"
        );

        assertEquals(TakuRewardSignatureVerifier.Status.SIGNED_REWARD, result.getStatus());
        assertTrue(result.hasSignedRewardAuthority());
        TakuRewardSignatureVerifier.SignedIlrdEvidence evidence =
                result.getAuthority().getSignedIlrdEvidence();
        assertEquals(66, evidence.getNetworkFirmId());
        assertEquals("56789", evidence.getAdsourceId());
        assertEquals("show-123", evidence.getShowId());
        assertEquals("b5b449fb3d89d7", evidence.getAdUnitId());
        assertAuthorityHasNoUnsignedOrRevenueFields();
    }

    @Test
    void signedShowCustomExtIsOnlyAvailableWhenItIsCoveredByTheRewardDigest() throws Exception {
        String sessionId = "0123456789abcdefghijkl";
        String ilrd = strictIlrd().replace("}", ",\"show_custom_ext\":\"" + sessionId + "\"}");

        TakuRewardSignatureVerifier.VerificationResult result = verifyStrictIlrd(ilrd);
        TakuRewardSignatureVerifier.SignedIlrdEvidence evidence =
                result.getAuthority().getSignedIlrdEvidence();

        assertEquals(sessionId, evidence.getShowCustomExt());
        assertInvalidSignature(strictSignedQuery(
                ilrd.replace(sessionId, "zyxwvutsrqponmlkjihgfe"), md5(signingInput(ilrd))));
    }

    @Test
    void tamperingAnySignedCoreValueFailsTheDigest() {
        String original = strictSignedQuery(strictIlrd(), md5(signingInput(strictIlrd())));

        assertInvalidSignature(original.replace("trans_id=show-123", "trans_id=show-124"));
        assertInvalidSignature(original.replace("placement_id=b5b449fb3d89d7", "placement_id=other-placement"));
        assertInvalidSignature(original.replace("adsource_id=56789", "adsource_id=56788"));
        assertInvalidSignature(original.replace("reward_amount=1", "reward_amount=2"));
        assertInvalidSignature(original.replace("reward_name=coin", "reward_name=coins"));
    }

    @Test
    void changingUnsignedTopLevelUserFieldsDoesNotAlterSignatureOrCreateAuthority() {
        String ilrd = strictIlrd();
        String query = strictSignedQuery(ilrd, md5(signingInput(ilrd)))
                .replace("user_id=user-1", "user_id=attacker")
                .replace("extra_data=token-1", "extra_data=attacker-token");

        TakuRewardSignatureVerifier.VerificationResult result = verifier.verify(
                canonicalizer.canonicalizeReward(query), SECRET.clone());

        assertEquals(TakuRewardSignatureVerifier.Status.SIGNED_REWARD, result.getStatus());
        assertTrue(result.isCoreDigestValid());
        assertTrue(result.hasSignedRewardAuthority());
        assertAuthorityHasNoUnsignedOrRevenueFields();
    }

    @Test
    void unsignedTopLevelNetworkMustCrossCheckButNeverBecomesSignedEvidence() {
        String ilrd = strictIlrd();
        String query = strictSignedQuery(ilrd, md5(signingInput(ilrd)))
                .replace("network_firm_id=66", "network_firm_id=35");

        TakuRewardSignatureVerifier.VerificationResult result = verifier.verify(
                canonicalizer.canonicalizeReward(query), SECRET.clone());

        assertTrue(result.isCoreDigestValid(), "top-level network_firm_id is outside Taku's MD5");
        assertEquals(TakuRewardSignatureVerifier.Status.SIGNED_ILRD_MISMATCH, result.getStatus());
        assertNull(result.getAuthority());
        assertFalse(result.hasSignedRewardAuthority());
    }

    @Test
    void signedIlrdIdentifiersMustCrossCheckSignedCoreIdentifiers() {
        assertIlrdMismatch(strictIlrd().replace("\"56789\"", "\"99999\""));
        assertIlrdMismatch(strictIlrd().replace("\"b5b449fb3d89d7\"", "\"other-placement\""));
    }

    @Test
    void signedIlrdShowIdentifierIsEvidenceButIsNotAssumedEqualToTheRewardTransaction() {
        TakuRewardSignatureVerifier.VerificationResult result = verifyStrictIlrd(
                strictIlrd().replace("\"show-123\"", "\"independent-impression-id\""));

        assertTrue(result.hasSignedRewardAuthority());
        assertEquals("independent-impression-id",
                result.getAuthority().getSignedIlrdEvidence().getShowId());
    }

    @Test
    void signedNetworkAloneProducesAuthorityAndOptionalIdentifiersCrossCheckOnlyWhenPresent() {
        TakuRewardSignatureVerifier.VerificationResult result = verifyStrictIlrd(
                "{\"network_firm_id\":66}");

        assertTrue(result.hasSignedRewardAuthority());
        TakuRewardSignatureVerifier.SignedIlrdEvidence evidence =
                result.getAuthority().getSignedIlrdEvidence();
        assertEquals(66, evidence.getNetworkFirmId());
        assertNull(evidence.getAdsourceId());
        assertNull(evidence.getShowId());
        assertNull(evidence.getAdUnitId());
    }

    @Test
    void malformedDuplicateMissingNetworkOrWrongTypeIlrdNeverProducesAuthority() {
        assertInvalidIlrd("not-json");
        assertInvalidIlrd("{\"network_firm_id\":66,\"network_firm_id\":35,"
                + "\"adsource_id\":\"56789\",\"id\":\"show-123\","
                + "\"adunit_id\":\"b5b449fb3d89d7\"}");
        assertInvalidIlrd("{\"network_firm_id\":\"66\",\"adsource_id\":\"56789\","
                + "\"id\":\"show-123\",\"adunit_id\":\"b5b449fb3d89d7\"}");
        assertInvalidIlrd("{\"adsource_id\":\"56789\",\"id\":\"show-123\","
                + "\"adunit_id\":\"b5b449fb3d89d7\"}");
        assertInvalidIlrd("{\"network_firm_id\":66,\"adsource_id\":99}");
    }

    @Test
    void exactDecodedIlrdLexicalStringIsSignedWithoutJsonReserialization() {
        String compact = strictIlrd();
        String spaced = "{ \"network_firm_id\" : 66, \"adsource_id\" : \"56789\","
                + " \"id\" : \"show-123\", \"adunit_id\" : \"b5b449fb3d89d7\" }";
        String compactSignature = md5(signingInput(compact));

        TakuRewardSignatureVerifier.VerificationResult result = verifier.verify(
                canonicalizer.canonicalizeReward(strictSignedQuery(spaced, compactSignature)), SECRET.clone());

        assertEquals(TakuRewardSignatureVerifier.Status.INVALID_SIGNATURE, result.getStatus());
        assertFalse(result.isCoreDigestValid());
        assertFalse(result.hasSignedRewardAuthority());
    }

    @Test
    void upperCaseHexIsDecodedThenComparedInConstantTime() {
        String ilrd = strictIlrd();
        String upper = md5(signingInput(ilrd)).toUpperCase(Locale.ROOT);

        TakuRewardSignatureVerifier.VerificationResult result = verifier.verify(
                canonicalizer.canonicalizeReward(strictSignedQuery(ilrd, upper)), SECRET.clone());

        assertEquals(TakuRewardSignatureVerifier.Status.SIGNED_REWARD, result.getStatus());
        assertTrue(result.hasSignedRewardAuthority());
    }

    @Test
    void implementationUsesDecodedDigestConstantTimeComparison() throws Exception {
        Path source = locateVerifierSource();
        String java = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertTrue(java.contains("MessageDigest.isEqual"));
        assertFalse(java.contains("getSignatureHex().equals"));
        assertFalse(java.contains("equalsIgnoreCase(expected"));
    }

    @Test
    void exactUnexpandedProbeNeverRunsBusinessSignatureVerification() {
        String probe = "user_id=%7Buser_id%7D&trans_id=%7Btrans_id%7D"
                + "&reward_amount=%7Breward_amount%7D&reward_name=%7Breward_name%7D"
                + "&placement_id=%7Bplacement_id%7D&extra_data=%7Bextra_data%7D"
                + "&network_firm_id=%7Bnetwork_firm_id%7D&adsource_id=%7Badsource_id%7D"
                + "&sign=%7Bsign%7D&ilrd=%7Bilrd%7D&is_test=1";

        TakuRewardSignatureVerifier.VerificationResult result = verifier.verify(
                canonicalizer.canonicalizeReward(probe), null);

        assertEquals(TakuRewardSignatureVerifier.Status.HEALTH_TEST_PROBE, result.getStatus());
        assertFalse(result.isCoreDigestValid());
        assertFalse(result.hasSignedRewardAuthority());
        assertNull(result.getAuthority());
    }

    @Test
    void secretMustBeBoundedPrintableAsciiBytesAndIsNeverRetainedByResult() {
        TakuRewardCallback callback = canonicalizer.canonicalizeReward(
                strictSignedQuery(strictIlrd(), md5(signingInput(strictIlrd()))));

        assertThrows(IllegalArgumentException.class, () -> verifier.verify(callback, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(callback,
                "bad\nsecret".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(callback,
                repeat('x', 257).getBytes(StandardCharsets.US_ASCII)));
        for (Field field : TakuRewardSignatureVerifier.VerificationResult.class.getDeclaredFields()) {
            assertFalse(field.getName().toLowerCase(Locale.ROOT).contains("secret"));
        }
    }

    @Test
    void verifierConsumesTheExistingWipeableByteSecretLeaseWithoutSecretStringsOrCharArrays() throws Exception {
        TakuRewardCallback callback = canonicalizer.canonicalizeReward(
                strictSignedQuery(strictIlrd(), md5(signingInput(strictIlrd()))));
        AtomicReference<byte[]> leasedWorkingCopy = new AtomicReference<>();
        TakuRewardSignatureVerifier.VerificationResult result;
        try (SkitAdCredentialVersionService.ResolvedRewardSecret resolved =
                     new SkitAdCredentialVersionService.ResolvedRewardSecret(
                             1L, 2L, 3, true, null, SECRET.clone())) {
            result = resolved.withSecret(secret -> {
                leasedWorkingCopy.set(secret);
                return verifier.verify(callback, secret);
            });
            assertThrows(IllegalStateException.class,
                    () -> resolved.withSecret(secret -> verifier.verify(callback, secret)));
        }

        assertTrue(result.hasSignedRewardAuthority());
        assertTrue(allZero(leasedWorkingCopy.get()), "the credential lease must erase its working copy");
        Path source = locateVerifierSource();
        String java = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertFalse(java.contains("char[] rewardSecret"));
        assertFalse(java.contains("new String(rewardSecret"));
    }

    private TakuRewardSignatureVerifier.VerificationResult verifyStrictIlrd(String ilrd) {
        return verifier.verify(canonicalizer.canonicalizeReward(
                strictSignedQuery(ilrd, md5(signingInput(ilrd)))), SECRET.clone());
    }

    private void assertInvalidSignature(String query) {
        TakuRewardSignatureVerifier.VerificationResult result = verifier.verify(
                canonicalizer.canonicalizeReward(query), SECRET.clone());
        assertEquals(TakuRewardSignatureVerifier.Status.INVALID_SIGNATURE, result.getStatus());
        assertFalse(result.isCoreDigestValid());
        assertFalse(result.hasSignedRewardAuthority());
        assertNull(result.getAuthority());
    }

    private void assertIlrdMismatch(String ilrd) {
        TakuRewardSignatureVerifier.VerificationResult result = verifyStrictIlrd(ilrd);
        assertTrue(result.isCoreDigestValid());
        assertEquals(TakuRewardSignatureVerifier.Status.SIGNED_ILRD_MISMATCH, result.getStatus());
        assertFalse(result.hasSignedRewardAuthority());
        assertNull(result.getAuthority());
    }

    private void assertInvalidIlrd(String ilrd) {
        TakuRewardSignatureVerifier.VerificationResult result = verifyStrictIlrd(ilrd);
        assertTrue(result.isCoreDigestValid());
        assertEquals(TakuRewardSignatureVerifier.Status.INVALID_SIGNED_ILRD, result.getStatus());
        assertFalse(result.hasSignedRewardAuthority());
        assertNull(result.getAuthority());
    }

    private static void assertAuthorityHasNoUnsignedOrRevenueFields() {
        for (Field field : TakuRewardSignatureVerifier.SignedRewardAuthority.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase(Locale.ROOT);
            assertFalse(name.contains("user"), field.getName());
            assertFalse(name.contains("extra"), field.getName());
            assertFalse(name.contains("networkfirm"), field.getName());
            assertFalse(name.contains("revenue"), field.getName());
            assertFalse(name.contains("price"), field.getName());
        }
    }

    private static String strictIlrd() {
        return "{\"network_firm_id\":66,\"adsource_id\":\"56789\","
                + "\"id\":\"show-123\",\"adunit_id\":\"b5b449fb3d89d7\"}";
    }

    private static String strictSignedQuery(String ilrd, String signature) {
        return "user_id=user-1&trans_id=show-123&placement_id=b5b449fb3d89d7"
                + "&adsource_id=56789&reward_amount=1&reward_name=coin&extra_data=token-1"
                + "&network_firm_id=66&sign=" + signature + "&ilrd=" + percentEncode(ilrd);
    }

    private static String signingInput(String ilrd) {
        return "trans_id=show-123&placement_id=b5b449fb3d89d7&adsource_id=56789"
                + "&reward_amount=1&reward_name=coin&sec_key=" + SECRET_TEXT
                + "&ilrd=" + ilrd;
    }

    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte item : digest) {
                hex.append(String.format("%02x", item & 0xff));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static String percentEncode(String value) {
        StringBuilder encoded = new StringBuilder();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte item : bytes) {
            int current = item & 0xff;
            if ((current >= 'a' && current <= 'z') || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9') || current == '-' || current == '_'
                    || current == '.' || current == '~') {
                encoded.append((char) current);
            } else {
                encoded.append('%');
                encoded.append("0123456789ABCDEF".charAt(current >>> 4));
                encoded.append("0123456789ABCDEF".charAt(current & 0x0f));
            }
        }
        return encoded.toString();
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static boolean allZero(byte[] value) {
        if (value == null || value.length == 0) {
            return false;
        }
        for (byte item : value) {
            if (item != 0) {
                return false;
            }
        }
        return true;
    }

    private static Path locateVerifierSource() {
        Path workingDirectory = Paths.get("").toAbsolutePath();
        Path moduleRelative = workingDirectory.resolve("src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/"
                + "TakuRewardSignatureVerifier.java");
        if (Files.isRegularFile(moduleRelative)) {
            return moduleRelative;
        }
        return workingDirectory.resolve("yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/"
                + "service/ad/callback/TakuRewardSignatureVerifier.java");
    }

}
