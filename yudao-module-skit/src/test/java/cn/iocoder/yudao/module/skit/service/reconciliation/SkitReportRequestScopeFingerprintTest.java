package cn.iocoder.yudao.module.skit.service.reconciliation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SkitReportRequestScopeFingerprintTest {

    @Test
    void fingerprintIsStableOnlyForTheSameCurrentReportScopeAndCredentialVersion() {
        byte[] baseline = fingerprint("app", "placement", "rewarded_video",
                "UTC+8", "USD", 8, 3, LocalDate.of(2026, 7, 14));

        assertArrayEquals(baseline, fingerprint("app", "placement", "rewarded_video",
                "UTC+8", "USD", 8, 3, LocalDate.of(2026, 7, 14)));
        assertDifferent(baseline, fingerprint("new-app", "placement", "rewarded_video",
                "UTC+8", "USD", 8, 3, LocalDate.of(2026, 7, 14)));
        assertDifferent(baseline, fingerprint("app", "new-placement", "rewarded_video",
                "UTC+8", "USD", 8, 3, LocalDate.of(2026, 7, 14)));
        assertDifferent(baseline, fingerprint("app", "placement", "rewarded_video",
                "UTC+0", "USD", 8, 3, LocalDate.of(2026, 7, 14)));
        assertDifferent(baseline, fingerprint("app", "placement", "rewarded_video",
                "UTC+8", "CNY", 8, 3, LocalDate.of(2026, 7, 14)));
        assertDifferent(baseline, fingerprint("app", "placement", "rewarded_video",
                "UTC+8", "USD", 6, 3, LocalDate.of(2026, 7, 14)));
        assertDifferent(baseline, fingerprint("app", "placement", "rewarded_video",
                "UTC+8", "USD", 8, 4, LocalDate.of(2026, 7, 14)));
        assertDifferent(baseline, fingerprint("app", "placement", "rewarded_video",
                "UTC+8", "USD", 8, 3, LocalDate.of(2026, 7, 13)));
    }

    private byte[] fingerprint(String appId, String placementId, String adFormat,
                               String timezone, String currency, int scale,
                               int credentialVersion, LocalDate reportDate) {
        return SkitReportRequestScopeFingerprint.fingerprint(31L, 41L, appId, placementId,
                adFormat, reportDate, timezone, currency, scale, credentialVersion);
    }

    private void assertDifferent(byte[] left, byte[] right) {
        assertFalse(Arrays.equals(left, right));
    }
}
