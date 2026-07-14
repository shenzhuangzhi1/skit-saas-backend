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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TakuCallbackCanonicalizerTest {

    private static final String ILRD = "{\"network_firm_id\":66,\"adsource_id\":\"56789\","
            + "\"id\":\"show-123\",\"adunit_id\":\"b5b449fb3d89d7\","
            + "\"publisher_revenue\":9.99}";
    private static final String ENCODED_ILRD = "%7B%22network_firm_id%22%3A66%2C%22adsource_id%22%3A%2256789%22%2C"
            + "%22id%22%3A%22show-123%22%2C%22adunit_id%22%3A%22b5b449fb3d89d7%22%2C"
            + "%22publisher_revenue%22%3A9.99%7D";
    private static final String SIGNATURE = "cf6f90c97201f49570e10e4c0bf77d86";

    private final TakuCallbackCanonicalizer canonicalizer = new TakuCallbackCanonicalizer();

    @Test
    void canonicalizesOfficialRewardFieldsAndPreservesExactDecodedIlrdLexicalValue() {
        TakuRewardCallback callback = canonicalizer.canonicalizeReward(validRewardQuery());

        assertEquals("member-pseudo-42", callback.getUserId());
        assertEquals("show-123", callback.getTransactionId());
        assertEquals("1", callback.getRewardAmountLexical());
        assertEquals("金币", callback.getRewardName());
        assertEquals("b5b449fb3d89d7", callback.getPlacementId());
        assertEquals("token_0123456789abcdef0123456789abcdef", callback.getExtraData());
        assertEquals(66, callback.getObservedNetworkFirmId().intValue());
        assertEquals("56789", callback.getAdsourceId());
        assertEquals("scene-1", callback.getScenarioId());
        assertEquals("com.example.skit", callback.getPackageName());
        assertEquals("1", callback.getPlatform());
        assertEquals(SIGNATURE, callback.getSignatureHex());
        assertEquals(ILRD, callback.getExactIlrd());
        assertEquals("0.1375", callback.getObservedExchangeRateLexical());
        assertFalse(callback.isHealthTestProbe());
    }

    @Test
    void canonicalHashIgnoresRawParameterOrderButNotDecodedLexicalValues() {
        TakuRewardCallback first = canonicalizer.canonicalizeReward(validRewardQuery());
        String reordered = "sign=" + SIGNATURE + "&ilrd=" + ENCODED_ILRD
                + "&network_firm_id=66&extra_data=token_0123456789abcdef0123456789abcdef"
                + "&placement_id=b5b449fb3d89d7&reward_name=%E9%87%91%E5%B8%81&reward_amount=1"
                + "&trans_id=show-123&user_id=member-pseudo-42&adsource_id=56789"
                + "&platform=1&package_name=com.example.skit&scenario_id=scene-1&exch_rate_c2u=0.1375";
        TakuRewardCallback second = canonicalizer.canonicalizeReward(reordered);
        String lexicalChange = validRewardQuery().replace("%22publisher_revenue%22%3A9.99",
                "%22publisher_revenue%22%3A9.990");

        assertArrayEquals(first.getCanonicalPayloadHash(), second.getCanonicalPayloadHash());
        assertFalse(Arrays.equals(first.getCanonicalPayloadHash(),
                canonicalizer.canonicalizeReward(lexicalChange).getCanonicalPayloadHash()));
    }

    @Test
    void decodesExactlyOnceWithStrictFormQuerySemantics() {
        String query = validRewardQuery()
                .replace("token_0123456789abcdef0123456789abcdef", "%252Fstill-percent-encoded")
                .replace("%E9%87%91%E5%B8%81", "gold+coin");

        TakuRewardCallback callback = canonicalizer.canonicalizeReward(query);

        assertEquals("%2Fstill-percent-encoded", callback.getExtraData());
        assertEquals("gold coin", callback.getRewardName());
    }

    @Test
    void rejectsDuplicateSecurityFieldsInsteadOfTakingFirstOrLast() {
        assertRejected(TakuCallbackCanonicalizer.ErrorCode.DUPLICATE_PARAMETER,
                validRewardQuery() + "&trans_id=attacker-show");
        assertRejected(TakuCallbackCanonicalizer.ErrorCode.DUPLICATE_PARAMETER,
                validRewardQuery() + "&extra_data=attacker-token");
        assertRejected(TakuCallbackCanonicalizer.ErrorCode.DUPLICATE_PARAMETER,
                validRewardQuery() + "&network_firm_id=35");
    }

    @Test
    void rejectsUnknownMissingOrEmptyRewardFields() {
        assertRejected(TakuCallbackCanonicalizer.ErrorCode.UNKNOWN_PARAMETER,
                validRewardQuery() + "&tenant_id=999");
        assertRejected(TakuCallbackCanonicalizer.ErrorCode.MISSING_PARAMETER,
                validRewardQuery().replace("&extra_data=token_0123456789abcdef0123456789abcdef", ""));
        assertRejected(TakuCallbackCanonicalizer.ErrorCode.INVALID_VALUE,
                validRewardQuery().replace("trans_id=show-123", "trans_id="));
    }

    @Test
    void rejectsMalformedPercentEncodingInvalidUtf8AndControlCharacters() {
        assertRejected(TakuCallbackCanonicalizer.ErrorCode.INVALID_ENCODING,
                validRewardQuery().replace("member-pseudo-42", "%ZZ"));
        assertRejected(TakuCallbackCanonicalizer.ErrorCode.INVALID_ENCODING,
                validRewardQuery().replace("member-pseudo-42", "%C3%28"));
        assertRejected(TakuCallbackCanonicalizer.ErrorCode.INVALID_VALUE,
                validRewardQuery().replace("member-pseudo-42", "member%0D%0Ainjected"));
    }

    @Test
    void rejectsOversizedQueryAndFieldValuesBeforeBusinessProcessing() {
        assertRejected(TakuCallbackCanonicalizer.ErrorCode.VALUE_TOO_LONG,
                validRewardQuery().replace("scene-1", repeat('x', 513)));
        assertRejected(TakuCallbackCanonicalizer.ErrorCode.QUERY_TOO_LONG,
                "x=" + repeat('a', TakuCallbackCanonicalizer.MAX_RAW_QUERY_LENGTH));
    }

    @Test
    void isolatesTheExactUnexpandedTakuConfigurationProbe() {
        String probe = "user_id=%7Buser_id%7D&trans_id=%7Btrans_id%7D"
                + "&reward_amount=%7Breward_amount%7D&reward_name=%7Breward_name%7D"
                + "&placement_id=%7Bplacement_id%7D&extra_data=%7Bextra_data%7D"
                + "&network_firm_id=%7Bnetwork_firm_id%7D&adsource_id=%7Badsource_id%7D"
                + "&sign=%7Bsign%7D&ilrd=%7Bilrd%7D&is_test=1";

        TakuRewardCallback callback = canonicalizer.canonicalizeReward(probe);

        assertTrue(callback.isHealthTestProbe());
        assertEquals("{trans_id}", callback.getTransactionId());
        assertEquals("{ilrd}", callback.getExactIlrd());
    }

    @Test
    void testFlagCannotTurnRealOrPartiallyExpandedCallbacksIntoProbes() {
        assertRejected(TakuCallbackCanonicalizer.ErrorCode.INVALID_PROBE,
                validRewardQuery() + "&is_test=1");
        assertRejected(TakuCallbackCanonicalizer.ErrorCode.INVALID_PROBE,
                "user_id=%7Buser_id%7D&trans_id=real-show&reward_amount=%7Breward_amount%7D"
                        + "&reward_name=%7Breward_name%7D&placement_id=%7Bplacement_id%7D"
                        + "&extra_data=%7Bextra_data%7D&network_firm_id=%7Bnetwork_firm_id%7D"
                        + "&adsource_id=%7Badsource_id%7D&sign=%7Bsign%7D&ilrd=%7Bilrd%7D&is_test=1");
    }

    @Test
    void canonicalizesTheOfficialImpressionAllowListAsUnsignedObservation() {
        String query = "user_id=member-pseudo-42&req_id=req-100&geo_short=CN"
                + "&package_name=com.example.skit&adformat=1&placement_id=b5b449fb3d89d7"
                + "&nw_firm_id=66&adsource_id=56789&adsource_price=3.2400&currency=USD"
                + "&timestamp=1783987200123&client_ip=203.0.113.7&gaid=gaid-1&oaid=oaid-1"
                + "&imei=imei-1&idfa=idfa-1&idfv=idfv-1&amazon_id=amazon-1"
                + "&show_custom_ext=AAECAwQFBgcICQoLDA0ODw";

        TakuImpressionCallback callback = canonicalizer.canonicalizeImpression(query);

        assertEquals(TakuImpressionCallback.AuthenticationLevel.UNSIGNED_PROVIDER_OBSERVATION,
                callback.getAuthenticationLevel());
        assertEquals("req-100", callback.getRequestId());
        assertEquals("56789", callback.getAdsourceId());
        assertEquals("3.2400", callback.getAdsourcePriceLexical());
        assertEquals("USD", callback.getCurrency());
        assertEquals("1783987200123", callback.getTimestampLexical());
        assertEquals("AAECAwQFBgcICQoLDA0ODw", callback.getShowCustomExt());
        assertEquals(66, callback.getObservedNetworkFirmId().intValue());
        assertEquals(1, callback.getAdFormat());
        assertEquals("com.example.skit", callback.getPackageName());
    }

    @Test
    void preservesEmptyOptionalLexicalObservationsWithoutRejectingTheCallback() {
        TakuRewardCallback reward = canonicalizer.canonicalizeReward(validRewardQuery()
                .replace("scenario_id=scene-1", "scenario_id="));
        String impressionQuery = validImpressionQuery()
                + "&geo_short=&client_ip=&gaid=&oaid=&imei=&idfa=&idfv=&amazon_id=";

        TakuImpressionCallback impression = canonicalizer.canonicalizeImpression(impressionQuery);

        assertEquals("", reward.getScenarioId());
        assertEquals("req-100", impression.getRequestId());
    }

    @Test
    void enforcesTheDocumentedRewardAmountUpperBound() {
        TakuRewardCallback maximum = canonicalizer.canonicalizeReward(validRewardQuery()
                .replace("reward_amount=1", "reward_amount=100000000"));

        assertEquals("100000000", maximum.getRewardAmountLexical());
        assertRejected(TakuCallbackCanonicalizer.ErrorCode.INVALID_VALUE,
                validRewardQuery().replace("reward_amount=1", "reward_amount=100000001"));
    }

    @Test
    void impressionFailsClosedUnlessItIsTheDedicatedRewardedVideoFormat() {
        assertRejectedImpression(TakuCallbackCanonicalizer.ErrorCode.INVALID_VALUE,
                validImpressionQuery().replace("adformat=1", "adformat=3"));
    }

    @Test
    void impressionPreservesMissingEmptyAndArbitraryShowCustomExtForUnmatchedClassification() {
        String minimal = validImpressionQuery();
        TakuImpressionCallback missing = canonicalizer.canonicalizeImpression(
                minimal.replace("&show_custom_ext=AAECAwQFBgcICQoLDA0ODw", ""));
        TakuImpressionCallback empty = canonicalizer.canonicalizeImpression(
                minimal.replace("show_custom_ext=AAECAwQFBgcICQoLDA0ODw", "show_custom_ext="));
        TakuImpressionCallback arbitrary = canonicalizer.canonicalizeImpression(
                minimal.replace("AAECAwQFBgcICQoLDA0ODw", "%7B%22tag%22%3A11%7D"));

        assertNull(missing.getShowCustomExt());
        assertEquals("", empty.getShowCustomExt());
        assertEquals("{\"tag\":11}", arbitrary.getShowCustomExt());
        assertFalse(Arrays.equals(missing.getCanonicalPayloadHash(), empty.getCanonicalPayloadHash()),
                "missing and explicitly empty observations must remain distinguishable");
    }

    @Test
    void impressionParsingRejectsDuplicateUnknownAndOversizedFields() {
        String minimal = validImpressionQuery();
        assertRejectedImpression(TakuCallbackCanonicalizer.ErrorCode.DUPLICATE_PARAMETER,
                minimal + "&req_id=attacker");
        assertRejectedImpression(TakuCallbackCanonicalizer.ErrorCode.UNKNOWN_PARAMETER,
                minimal + "&sign=not-applicable");
        assertRejectedImpression(TakuCallbackCanonicalizer.ErrorCode.VALUE_TOO_LONG,
                minimal.replace("AAECAwQFBgcICQoLDA0ODw", repeat('x', 1025)));
    }

    @Test
    void impressionNormalizesTheOfficialPositiveIntegerAdsourceIdBeforeHashing() {
        TakuImpressionCallback canonical = canonicalizer.canonicalizeImpression(validImpressionQuery());
        TakuImpressionCallback leadingZeros = canonicalizer.canonicalizeImpression(
                validImpressionQuery().replace("adsource_id=56789", "adsource_id=00056789"));

        assertEquals("56789", leadingZeros.getAdsourceId());
        assertArrayEquals(canonical.getCanonicalPayloadHash(), leadingZeros.getCanonicalPayloadHash());
        assertRejectedImpression(TakuCallbackCanonicalizer.ErrorCode.INVALID_VALUE,
                validImpressionQuery().replace("adsource_id=56789", "adsource_id=0"));
        assertRejectedImpression(TakuCallbackCanonicalizer.ErrorCode.INVALID_VALUE,
                validImpressionQuery().replace("adsource_id=56789", "adsource_id=not-an-integer"));
    }

    @Test
    void rawQueryParserStreamsBoundedPairsInsteadOfSplittingTheWholeRequest() throws Exception {
        Path source = locateCanonicalizerSource();
        String java = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertFalse(java.contains("rawQuery.split("),
                "whole-query split allocates every attacker-controlled pair before validation");
        assertTrue(java.contains("MAX_PARAMETER_COUNT"),
                "the streaming parser still needs an explicit pair-count ceiling");
    }

    @Test
    void returnedHashesAreDefensiveCopiesAndCallbackDtosHaveFinalState() {
        TakuRewardCallback callback = canonicalizer.canonicalizeReward(validRewardQuery());
        byte[] first = callback.getCanonicalPayloadHash();
        first[0] ^= 0x7f;

        assertNotEquals(first[0], callback.getCanonicalPayloadHash()[0]);
        assertAllInstanceFieldsFinal(TakuRewardCallback.class);
        assertAllInstanceFieldsFinal(TakuImpressionCallback.class);
    }

    private void assertRejected(TakuCallbackCanonicalizer.ErrorCode expected, String query) {
        TakuCallbackCanonicalizer.CallbackParseException error = assertThrows(
                TakuCallbackCanonicalizer.CallbackParseException.class,
                () -> canonicalizer.canonicalizeReward(query));
        assertEquals(expected, error.getErrorCode());
        assertFalse(error.getMessage().contains("token_0123456789abcdef0123456789abcdef"));
    }

    private void assertRejectedImpression(TakuCallbackCanonicalizer.ErrorCode expected, String query) {
        TakuCallbackCanonicalizer.CallbackParseException error = assertThrows(
                TakuCallbackCanonicalizer.CallbackParseException.class,
                () -> canonicalizer.canonicalizeImpression(query));
        assertEquals(expected, error.getErrorCode());
    }

    private static void assertAllInstanceFieldsFinal(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                assertTrue(Modifier.isFinal(field.getModifiers()), field.getName() + " must be final");
            }
        }
    }

    private static String validRewardQuery() {
        return "user_id=member-pseudo-42&trans_id=show-123&reward_amount=1"
                + "&reward_name=%E9%87%91%E5%B8%81&placement_id=b5b449fb3d89d7"
                + "&extra_data=token_0123456789abcdef0123456789abcdef&network_firm_id=66"
                + "&adsource_id=56789&scenario_id=scene-1&package_name=com.example.skit&platform=1"
                + "&sign=" + SIGNATURE + "&ilrd=" + ENCODED_ILRD + "&exch_rate_c2u=0.1375";
    }

    private static String validImpressionQuery() {
        return "user_id=member-pseudo-42&req_id=req-100&package_name=com.example.skit&adformat=1"
                + "&placement_id=b5b449fb3d89d7"
                + "&adsource_id=56789&adsource_price=3.2400&currency=USD&timestamp=1783987200123"
                + "&show_custom_ext=AAECAwQFBgcICQoLDA0ODw";
    }

    private static String repeat(char value, int length) {
        char[] chars = new char[length];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static Path locateCanonicalizerSource() {
        Path workingDirectory = Paths.get("").toAbsolutePath();
        Path moduleRelative = workingDirectory.resolve("src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/"
                + "TakuCallbackCanonicalizer.java");
        if (Files.isRegularFile(moduleRelative)) {
            return moduleRelative;
        }
        return workingDirectory.resolve("yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/"
                + "service/ad/callback/TakuCallbackCanonicalizer.java");
    }

}
