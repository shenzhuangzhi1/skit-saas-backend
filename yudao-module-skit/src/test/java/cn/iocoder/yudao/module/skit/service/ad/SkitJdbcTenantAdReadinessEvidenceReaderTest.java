package cn.iocoder.yudao.module.skit.service.ad;

import cn.iocoder.yudao.module.skit.service.reconciliation.SkitReportRequestScopeFingerprint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    private static byte[] fingerprint(String placementId, int credentialVersion) {
        return SkitReportRequestScopeFingerprint.fingerprint(
                TENANT_ID, ACCOUNT_ID, "taku-app", placementId, "rewarded_video",
                REPORT_DATE, "UTC+8", "CNY", 8, credentialVersion);
    }

}
