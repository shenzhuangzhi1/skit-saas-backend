package cn.iocoder.yudao.module.skit.service.ad;

import cn.iocoder.yudao.module.skit.service.reconciliation.SkitReportRequestScopeFingerprint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitJdbcTenantAdReadinessEvidenceReaderTest {

    private static final long TENANT_ID = 42L;
    private static final long ACCOUNT_ID = 4201L;
    private static final int CREDENTIAL_VERSION = 7;
    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 7, 14);
    private static final LocalDateTime PULLED_AT = LocalDateTime.of(2026, 7, 15, 1, 30);

    @Test
    void freshnessAcceptsOnlyTheCurrentFullReportScopeAndActiveCredential() {
        Map<String, Object> account = account("unlock-placement");
        Map<String, Object> stalePlacement = pull(fingerprint(
                "old-placement", CREDENTIAL_VERSION), CREDENTIAL_VERSION, PULLED_AT.plusMinutes(2));
        Map<String, Object> staleCredential = pull(fingerprint(
                "unlock-placement", CREDENTIAL_VERSION - 1), CREDENTIAL_VERSION - 1,
                PULLED_AT.plusMinutes(1));
        Map<String, Object> current = pull(fingerprint(
                "unlock-placement", CREDENTIAL_VERSION), CREDENTIAL_VERSION, PULLED_AT);

        LocalDateTime matched = SkitJdbcTenantAdReadinessEvidenceReader
                .latestMatchingReportSuccess(TENANT_ID, ACCOUNT_ID, account,
                        CREDENTIAL_VERSION, Arrays.asList(stalePlacement, staleCredential, current));

        assertEquals(PULLED_AT, matched);
    }

    @Test
    void oldScopeCannotMakeReadinessFreshAfterPlacementChanges() {
        Map<String, Object> currentAccount = account("new-placement");
        Map<String, Object> oldSuccessfulPull = pull(fingerprint(
                "old-placement", CREDENTIAL_VERSION), CREDENTIAL_VERSION, PULLED_AT);

        assertNull(SkitJdbcTenantAdReadinessEvidenceReader.latestMatchingReportSuccess(
                TENANT_ID, ACCOUNT_ID, currentAccount, CREDENTIAL_VERSION,
                java.util.Collections.singletonList(oldSuccessfulPull)));
    }

    @Test
    void knownAndNewSelectionStaysBlockedWhenOnlyKnownNetworkHasEvidence() {
        LocalDateTime verifiedAt = PULLED_AT.minusDays(2);
        List<Map<String, Object>> capabilities = Arrays.asList(
                capability(66, verifiedAt), capability(46, verifiedAt));
        List<Map<String, Object>> rewards = Collections.singletonList(
                callback(66, "adx-source-66", PULLED_AT.minusMinutes(2)));
        List<Map<String, Object>> impressions = Collections.singletonList(
                callback(66, "adx-source-66", PULLED_AT.minusMinutes(1)));

        List<SkitTenantAdReadinessEvidence.NetworkEvidence> evidence =
                SkitJdbcTenantAdReadinessEvidenceReader.evaluateNetworkEvidence(
                        TENANT_ID, ACCOUNT_ID, new LinkedHashSet<>(Arrays.asList(46, 66)),
                        capabilities, rewards, impressions);

        assertEquals(2, evidence.size());
        SkitTenantAdReadinessEvidence.NetworkEvidence network46 = evidence.get(0);
        SkitTenantAdReadinessEvidence.NetworkEvidence network66 = evidence.get(1);
        assertEquals(46, network46.getNetworkFirmId());
        assertTrue(network46.isAuthoritative());
        assertFalse(network46.isSignedRewardObserved());
        assertFalse(network46.isImpressionObserved());
        assertTrue(network46.getBlockers().contains("REAL_SIGNED_REWARD_CALLBACK_MISSING"));
        assertTrue(network46.getBlockers().contains("REAL_IMPRESSION_CALLBACK_MISSING"));
        assertTrue(network66.isAuthoritative());
        assertTrue(network66.isSignedRewardObserved());
        assertTrue(network66.isImpressionObserved());
        assertFalse(SkitJdbcTenantAdReadinessEvidenceReader.allSelectedNetworksPass(
                evidence, SkitTenantAdReadinessEvidence.NetworkEvidence::isSignedRewardObserved));
        assertFalse(SkitJdbcTenantAdReadinessEvidenceReader.allSelectedNetworksPass(
                evidence, SkitTenantAdReadinessEvidence.NetworkEvidence::isImpressionObserved));
        assertEquals(Collections.singleton(46),
                SkitJdbcTenantAdReadinessEvidenceReader.missingNetworks(
                        evidence, SkitTenantAdReadinessEvidence.NetworkEvidence::isSignedRewardObserved));
        assertEquals(Collections.singleton(46),
                SkitJdbcTenantAdReadinessEvidenceReader.missingNetworks(
                        evidence, SkitTenantAdReadinessEvidence.NetworkEvidence::isImpressionObserved));
    }

    @Test
    void sourceSpecificEvidenceUsesStableTwelveCharacterRefsWithoutLeakingAdsourceIds() {
        LocalDateTime verifiedAt = PULLED_AT.minusDays(2);
        String sourceOne = "baidu-source-one";
        String sourceTwo = "baidu-source-two";
        String sourceThree = "baidu-source-three";
        List<Map<String, Object>> rewards = Arrays.asList(
                callback(46, sourceOne, PULLED_AT.minusMinutes(4)),
                callback(46, sourceTwo, PULLED_AT.minusMinutes(3)),
                callback(46, sourceThree, PULLED_AT.minusMinutes(2)));
        List<Map<String, Object>> impressions = Arrays.asList(
                callback(46, sourceOne, PULLED_AT.minusMinutes(3)),
                callback(46, sourceTwo, PULLED_AT.minusMinutes(2)),
                callback(46, sourceThree, PULLED_AT.minusMinutes(1)));

        SkitTenantAdReadinessEvidence.NetworkEvidence evidence =
                SkitJdbcTenantAdReadinessEvidenceReader.evaluateNetworkEvidence(
                        TENANT_ID, ACCOUNT_ID, Collections.singleton(46),
                        Collections.singletonList(capability(46, verifiedAt)), rewards, impressions)
                        .get(0);

        assertEquals(3, evidence.getSourceRefs().size());
        assertEquals(evidence.getSourceRefs(), evidence.getSignedRewardSourceRefs());
        assertEquals(evidence.getSourceRefs(), evidence.getImpressionSourceRefs());
        assertTrue(evidence.isPairedSourceObserved());
        assertEquals(evidence.getSourceRefs(), evidence.getPairedSourceRefs());
        assertTrue(evidence.getSourceRefs().stream().allMatch(value -> value.matches("[0-9a-f]{12}")));
        assertFalse(evidence.toString().contains(sourceOne));
        assertFalse(evidence.toString().contains(sourceTwo));
        assertFalse(evidence.toString().contains(sourceThree));
        assertTrue(evidence.getSourceRefs().contains(
                SkitJdbcTenantAdReadinessEvidenceReader.sourceRef(
                        TENANT_ID, ACCOUNT_ID, 46, sourceOne)));
    }

    @Test
    void differentRewardAndImpressionSourcesCannotBeSplicedIntoProductionEvidence() {
        LocalDateTime verifiedAt = PULLED_AT.minusDays(2);
        String rewardSource = "reward-source-a";
        String impressionSource = "impression-source-b";

        SkitTenantAdReadinessEvidence.NetworkEvidence evidence =
                SkitJdbcTenantAdReadinessEvidenceReader.evaluateNetworkEvidence(
                        TENANT_ID, ACCOUNT_ID, Collections.singleton(46),
                        Collections.singletonList(capability(46, verifiedAt)),
                        Collections.singletonList(callback(
                                46, rewardSource, PULLED_AT.minusMinutes(2))),
                        Collections.singletonList(callback(
                                46, impressionSource, PULLED_AT.minusMinutes(1))))
                        .get(0);

        assertTrue(evidence.isSignedRewardObserved());
        assertTrue(evidence.isImpressionObserved());
        assertFalse(evidence.isPairedSourceObserved());
        assertTrue(evidence.getPairedSourceRefs().isEmpty());
        assertTrue(evidence.getBlockers().contains("PAIRED_SOURCE_EVIDENCE_MISSING"));
        assertEquals(Collections.singletonList(SkitJdbcTenantAdReadinessEvidenceReader.sourceRef(
                TENANT_ID, ACCOUNT_ID, 46, rewardSource)), evidence.getSignedRewardSourceRefs());
        assertEquals(Collections.singletonList(SkitJdbcTenantAdReadinessEvidenceReader.sourceRef(
                TENANT_ID, ACCOUNT_ID, 46, impressionSource)), evidence.getImpressionSourceRefs());
    }

    @Test
    void sameSourceRewardAndImpressionCreatePairedProductionEvidence() {
        LocalDateTime verifiedAt = PULLED_AT.minusDays(2);
        String source = "same-source";

        SkitTenantAdReadinessEvidence.NetworkEvidence evidence =
                SkitJdbcTenantAdReadinessEvidenceReader.evaluateNetworkEvidence(
                        TENANT_ID, ACCOUNT_ID, Collections.singleton(46),
                        Collections.singletonList(capability(46, verifiedAt)),
                        Collections.singletonList(callback(46, source, PULLED_AT.minusMinutes(2))),
                        Collections.singletonList(callback(46, source, PULLED_AT.minusMinutes(1))))
                        .get(0);

        String expectedRef = SkitJdbcTenantAdReadinessEvidenceReader.sourceRef(
                TENANT_ID, ACCOUNT_ID, 46, source);
        assertTrue(evidence.isPairedSourceObserved());
        assertEquals(Collections.singletonList(expectedRef), evidence.getPairedSourceRefs());
        assertFalse(evidence.getBlockers().contains("PAIRED_SOURCE_EVIDENCE_MISSING"));
    }

    private static Map<String, Object> account(String placementId) {
        Map<String, Object> result = new HashMap<>();
        result.put("app_id", "taku-app");
        result.put("placement_id", placementId);
        result.put("ad_format", "rewarded_video");
        result.put("report_timezone", "UTC+8");
        result.put("report_currency", "CNY");
        result.put("report_amount_scale", 8);
        return result;
    }

    private static Map<String, Object> pull(byte[] requestHash, int credentialVersion,
                                             LocalDateTime pulledAt) {
        Map<String, Object> result = new HashMap<>();
        result.put("report_date", REPORT_DATE);
        result.put("request_hash", requestHash);
        result.put("credential_version", credentialVersion);
        result.put("pulled_at", pulledAt);
        return result;
    }

    private static Map<String, Object> capability(int networkFirmId, LocalDateTime verifiedAt) {
        Map<String, Object> result = new HashMap<>();
        result.put("tenant_id", TENANT_ID);
        result.put("ad_account_id", ACCOUNT_ID);
        result.put("network_firm_id", networkFirmId);
        result.put("reward_authority", "SIGNED_REWARD");
        result.put("supports_user_id", true);
        result.put("supports_custom_data", true);
        result.put("supports_stable_transaction", true);
        result.put("supports_impression_revenue", true);
        result.put("supports_reporting", true);
        result.put("enabled", true);
        result.put("verified_at", verifiedAt);
        return result;
    }

    private static Map<String, Object> callback(int networkFirmId, String adsourceId,
                                                 LocalDateTime observedAt) {
        Map<String, Object> result = new HashMap<>();
        result.put("network_firm_id", networkFirmId);
        result.put("adsource_id", adsourceId);
        result.put("observed_at", observedAt);
        return result;
    }

    private static byte[] fingerprint(String placementId, int credentialVersion) {
        return SkitReportRequestScopeFingerprint.fingerprint(
                TENANT_ID, ACCOUNT_ID, "taku-app", placementId, "rewarded_video",
                REPORT_DATE, "UTC+8", "CNY", 8, credentialVersion);
    }

}
