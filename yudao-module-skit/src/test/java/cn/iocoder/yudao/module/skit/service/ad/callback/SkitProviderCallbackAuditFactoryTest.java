package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionCaptureService.ProviderIngressEvidence;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkitProviderCallbackAuditFactoryTest {

    private static final String MASTER_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-08-02T12:34:56Z");
    private static final LocalDateTime RECEIVED_AT =
            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Test
    void derivesStableDailyHashesButFreshCorrelationIdsAndNeverPrintsRawMetadata()
            throws Exception {
        IncrementingSecureRandom random = new IncrementingSecureRandom();
        SkitProviderCallbackAuditFactory factory = factory(MASTER_KEY, random, NOW);
        String sentinelIp = "203.0.113.91";
        String sentinelUserAgent = "sentinel-user-agent";
        ProviderIngressEvidence first;
        ProviderIngressEvidence second;
        try (SkitCallbackRequestMetadata metadata = SkitCallbackRequestMetadata.of(
                sentinelIp, "application/json", "gzip", "text/plain", sentinelUserAgent)) {
            first = factory.create(metadata, RECEIVED_AT);
        }
        try (SkitCallbackRequestMetadata metadata = SkitCallbackRequestMetadata.of(
                sentinelIp, "application/json", "gzip", "text/plain", sentinelUserAgent)) {
            second = factory.create(metadata, RECEIVED_AT);
        }

        byte[] firstRemote = first.getRemoteAddressHash();
        byte[] secondRemote = second.getRemoteAddressHash();
        byte[] firstUserAgent = first.getUserAgentHash();
        byte[] secondUserAgent = second.getUserAgentHash();
        byte[] firstHeaders = first.getRequestHeaderFingerprint();
        byte[] secondHeaders = second.getRequestHeaderFingerprint();
        byte[] firstCorrelation = first.getCorrelationId();
        byte[] secondCorrelation = second.getCorrelationId();
        try {
            assertArrayEquals(firstRemote, secondRemote);
            assertArrayEquals(firstUserAgent, secondUserAgent);
            assertArrayEquals(firstHeaders, secondHeaders);
            assertFalse(Arrays.equals(firstCorrelation, secondCorrelation));
            assertFalse(first.toString().contains(sentinelIp));
            assertFalse(first.toString().contains(sentinelUserAgent));
            assertFalse(factory.toString().contains(MASTER_KEY));
            assertEquals("\"<redacted>\"",
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(first));
        } finally {
            wipe(firstRemote, secondRemote, firstUserAgent, secondUserAgent,
                    firstHeaders, secondHeaders, firstCorrelation, secondCorrelation);
            first.close();
            second.close();
        }
    }

    @Test
    void exactHeaderAllowlistAndPackedAddressAreDomainSeparated() {
        SkitProviderCallbackAuditFactory factory = factory(MASTER_KEY,
                new IncrementingSecureRandom(), NOW);
        ProviderIngressEvidence baseline = evidence(factory, "203.0.113.1",
                "text/plain", "gzip", "application/json", "agent-a");
        ProviderIngressEvidence changedIp = evidence(factory, "203.0.113.2",
                "text/plain", "gzip", "application/json", "agent-a");
        ProviderIngressEvidence changedHeader = evidence(factory, "203.0.113.1",
                "text/html", "gzip", "application/json", "agent-a");
        try {
            assertNotEquals(hex(baseline.getRemoteAddressHash()),
                    hex(changedIp.getRemoteAddressHash()));
            assertNotEquals(hex(baseline.getRequestHeaderFingerprint()),
                    hex(changedHeader.getRequestHeaderFingerprint()));
            assertNotEquals(hex(baseline.getRemoteAddressHash()),
                    hex(baseline.getUserAgentHash()));
        } finally {
            baseline.close();
            changedIp.close();
            changedHeader.close();
        }
    }

    @Test
    void utcDayRotationChangesAuditHashes() {
        SkitProviderCallbackAuditFactory firstDay = factory(MASTER_KEY,
                new IncrementingSecureRandom(), NOW);
        Instant nextDayInstant = NOW.plusSeconds(24 * 60 * 60);
        SkitProviderCallbackAuditFactory nextDay = factory(MASTER_KEY,
                new IncrementingSecureRandom(), nextDayInstant);
        ProviderIngressEvidence first = evidence(firstDay, "2001:db8::1",
                null, null, null, null, RECEIVED_AT);
        ProviderIngressEvidence second = evidence(nextDay, "2001:db8::1",
                null, null, null, null,
                LocalDateTime.ofInstant(nextDayInstant, ZoneOffset.UTC));
        try {
            assertNotEquals(hex(first.getRemoteAddressHash()),
                    hex(second.getRemoteAddressHash()));
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void missingShortUnsafeOrStaleConfigurationFailsClosedAtCaptureBoundary() {
        SkitCallbackRequestMetadata metadata = SkitCallbackRequestMetadata.of(
                "127.0.0.1", null, null, null, null);
        try {
            assertThrows(IllegalStateException.class,
                    () -> factory("", new IncrementingSecureRandom(), NOW)
                            .create(metadata, RECEIVED_AT));
            assertThrows(IllegalStateException.class,
                    () -> factory("short", new IncrementingSecureRandom(), NOW)
                            .create(metadata, RECEIVED_AT));
            assertThrows(IllegalStateException.class,
                    () -> factory(repeat('a', 31) + "\n", new IncrementingSecureRandom(), NOW)
                            .create(metadata, RECEIVED_AT));
            assertThrows(IllegalArgumentException.class,
                    () -> factory(MASTER_KEY, new IncrementingSecureRandom(), NOW)
                            .create(metadata, RECEIVED_AT.minusMinutes(3)));
        } finally {
            metadata.close();
        }
    }

    private static ProviderIngressEvidence evidence(
            SkitProviderCallbackAuditFactory factory, String ip, String accept,
            String acceptEncoding, String contentType, String userAgent) {
        return evidence(factory, ip, accept, acceptEncoding, contentType, userAgent, RECEIVED_AT);
    }

    private static ProviderIngressEvidence evidence(
            SkitProviderCallbackAuditFactory factory, String ip, String accept,
            String acceptEncoding, String contentType, String userAgent,
            LocalDateTime receivedAt) {
        try (SkitCallbackRequestMetadata metadata = SkitCallbackRequestMetadata.of(
                ip, accept, acceptEncoding, contentType, userAgent)) {
            return factory.create(metadata, receivedAt);
        }
    }

    private static SkitProviderCallbackAuditFactory factory(
            String key, SecureRandom random, Instant now) {
        return new SkitProviderCallbackAuditFactory(key, random,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static String hex(byte[] value) {
        try {
            StringBuilder result = new StringBuilder(value.length * 2);
            for (byte item : value) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } finally {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static void wipe(byte[]... values) {
        for (byte[] value : values) {
            if (value != null) {
                Arrays.fill(value, (byte) 0);
            }
        }
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        Arrays.fill(result, value);
        return new String(result);
    }

    private static final class IncrementingSecureRandom extends SecureRandom {

        private int next;

        @Override
        public void nextBytes(byte[] bytes) {
            Arrays.fill(bytes, (byte) ++next);
        }
    }
}
