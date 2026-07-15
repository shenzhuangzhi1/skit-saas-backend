package cn.iocoder.yudao.module.skit.service.reconciliation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Objects;

/** Deterministic identity of the exact Taku report scope authorized by one credential version. */
public final class SkitReportRequestScopeFingerprint {

    private static final String PROVIDER = "TAKU";

    private SkitReportRequestScopeFingerprint() {
    }

    public static byte[] fingerprint(long tenantId, long adAccountId, String appId,
                                     String placementId, String adFormat, LocalDate reportDate,
                                     String reportTimezone, String currency, int amountScale,
                                     int credentialVersion) {
        if (tenantId <= 0L || adAccountId <= 0L || credentialVersion <= 0
                || amountScale < 0 || amountScale > 18
                || !Arrays.asList("UTC-8", "UTC+8", "UTC+0").contains(reportTimezone)
                || currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Taku report scope money, timezone, or identity is invalid");
        }
        StringBuilder canonical = new StringBuilder();
        append(canonical, PROVIDER, tenantId, adAccountId, canonical(appId, 128),
                canonical(placementId, 128), canonical(adFormat, 32),
                Objects.requireNonNull(reportDate, "reportDate"), reportTimezone,
                currency, amountScale, credentialVersion);
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String canonical(String value, int maximum) {
        if (value == null || value.isEmpty() || value.length() > maximum
                || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Taku report scope text is not canonical");
        }
        return value;
    }

    private static void append(StringBuilder target, Object... values) {
        for (Object value : values) {
            String text = String.valueOf(value);
            target.append(text.length()).append(':').append(text);
        }
    }
}
