package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.ratelimiter.core.redis.RateLimiterRedisDAO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitCallbackRateLimiterTest {

    private RateLimiterRedisDAO redis;
    private RedisSkitCallbackRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redis = mock(RateLimiterRedisDAO.class);
        limiter = new RedisSkitCallbackRateLimiter(redis);
    }

    @Test
    void usesHighGlobalIpGateAndPerTypePerKeyBusinessBucketWithoutLeakingRawValues() {
        String rawKey = "secret-callback-key-value";
        String rawIp = "203.0.113.17";
        when(redis.tryAcquire(anyString(), anyInt(), eq(60), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        limiter.check("TAKU", rawKey, rawIp, "REWARD");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(redis, times(2)).tryAcquire(keys.capture(), limits.capture(),
                eq(60), eq(TimeUnit.SECONDS));
        List<String> values = keys.getAllValues();
        assertFalse(values.get(0).contains(rawKey) || values.get(0).contains(rawIp));
        assertFalse(values.get(1).contains(rawKey) || values.get(1).contains(rawIp));
        int ddosIndex = values.get(0).contains(":ddos:ip:") ? 0 : 1;
        int businessIndex = ddosIndex == 0 ? 1 : 0;
        assertEquals(12000, limits.getAllValues().get(ddosIndex));
        assertEquals(120, limits.getAllValues().get(businessIndex));
        assertTrue(values.get(businessIndex).contains(":reward:key:"));
    }

    @Test
    void pangleAndTakuHaveSeparateBusinessBucketsButShareTheIpDdosGate() {
        String callbackKey = "shared-callback-key";
        String clientIp = "203.0.113.20";
        when(redis.tryAcquire(anyString(), anyInt(), eq(60), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        limiter.check("TAKU", callbackKey, clientIp, "REWARD");
        limiter.check("PANGLE", callbackKey, clientIp, "REWARD");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(redis, times(4)).tryAcquire(keys.capture(), anyInt(),
                eq(60), eq(TimeUnit.SECONDS));
        List<String> ddosKeys = keys.getAllValues().stream()
                .filter(key -> key.contains(":ddos:ip:"))
                .collect(java.util.stream.Collectors.toList());
        List<String> businessKeys = keys.getAllValues().stream()
                .filter(key -> key.contains(":reward:key:"))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(2, ddosKeys.size());
        assertEquals(ddosKeys.get(0), ddosKeys.get(1));
        assertEquals(2, businessKeys.size());
        assertFalse(businessKeys.get(0).equals(businessKeys.get(1)));
        assertTrue(businessKeys.stream().anyMatch(key -> key.contains(":taku:reward:key:")));
        assertTrue(businessKeys.stream().anyMatch(key -> key.contains(":pangle:reward:key:")));
    }

    @Test
    void sameProviderIpDoesNotMakeTwoCallbackKeysConsumeEachOthersBusinessQuota() {
        installInMemoryQuotaAnswer();
        String sharedProviderIp = "203.0.113.18";
        for (int request = 0; request < 120; request++) {
            limiter.check("TAKU", "tenant-a-key", sharedProviderIp, "REWARD");
            limiter.check("TAKU", "tenant-b-key", sharedProviderIp, "REWARD");
        }

        assertThrows(SkitCallbackRateLimiter.RateLimitExceededException.class,
                () -> limiter.check("TAKU", "tenant-a-key", sharedProviderIp, "REWARD"));
        assertThrows(SkitCallbackRateLimiter.RateLimitExceededException.class,
                () -> limiter.check("TAKU", "tenant-b-key", sharedProviderIp, "REWARD"));
    }

    @Test
    void impressionUsesTheSamePerKeyLimitIndependentlyFromReward() {
        installInMemoryQuotaAnswer();
        for (int request = 0; request < 120; request++) {
            limiter.check("TAKU", "same-key", "203.0.113.19", "REWARD");
            limiter.check("TAKU", "same-key", "203.0.113.19", "IMPRESSION");
        }

        assertThrows(SkitCallbackRateLimiter.RateLimitExceededException.class,
                () -> limiter.check("TAKU", "same-key", "203.0.113.19", "REWARD"));
        assertThrows(SkitCallbackRateLimiter.RateLimitExceededException.class,
                () -> limiter.check("TAKU", "same-key", "203.0.113.19", "IMPRESSION"));
    }

    @Test
    void globalIpGateStillStopsForgedKeyFloodAndRedisFailureRemainsTransient() {
        when(redis.tryAcquire(anyString(), anyInt(), eq(60), eq(TimeUnit.SECONDS)))
                .thenAnswer(invocation -> !((String) invocation.getArgument(0)).contains(":ddos:ip:"));
        assertThrows(SkitCallbackRateLimiter.RateLimitExceededException.class,
                () -> limiter.check("TAKU", "forged-key", "127.0.0.1", "REWARD"));

        when(redis.tryAcquire(anyString(), anyInt(), eq(60), eq(TimeUnit.SECONDS)))
                .thenThrow(new IllegalStateException("redis unavailable"));
        assertThrows(IllegalStateException.class,
                () -> limiter.check("TAKU", "key", "127.0.0.1", "REWARD"));
    }

    @Test
    void hashedDispatchIdentityUsesTheExistingDigestWithoutRetainingRawKeyOrAddress() throws Exception {
        String rawKey = "acct_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKL";
        byte[] callbackKeyHash = domainHash("callback-key\0", rawKey);
        byte[] originalHash = callbackKeyHash.clone();
        byte[] packedAddress = java.net.InetAddress.getByName("203.0.113.31").getAddress();
        byte[] originalAddress = packedAddress.clone();
        when(redis.tryAcquire(anyString(), anyInt(), eq(60), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        limiter.checkHashed("TAKU", callbackKeyHash, packedAddress, "IMPRESSION");

        assertTrue(java.util.Arrays.equals(originalHash, callbackKeyHash));
        assertTrue(java.util.Arrays.equals(originalAddress, packedAddress));
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(redis, times(2)).tryAcquire(keys.capture(), anyInt(),
                eq(60), eq(TimeUnit.SECONDS));
        String expectedHash = lowerHex(originalHash);
        assertTrue(keys.getAllValues().stream().anyMatch(
                key -> key.endsWith(":" + expectedHash)));
        assertFalse(keys.getAllValues().stream().anyMatch(
                key -> key.contains(rawKey) || key.contains("203.0.113.31")));
        java.util.Arrays.fill(callbackKeyHash, (byte) 0);
        java.util.Arrays.fill(originalHash, (byte) 0);
        java.util.Arrays.fill(packedAddress, (byte) 0);
        java.util.Arrays.fill(originalAddress, (byte) 0);
    }

    @Test
    void splitHashedGatesAllowProviderDispatchToSkipTheLegacyBusinessBucket() throws Exception {
        byte[] callbackKeyHash = domainHash("callback-key\0", "provider-key");
        byte[] packedAddress = java.net.InetAddress.getByName("203.0.113.32").getAddress();
        when(redis.tryAcquire(anyString(), anyInt(), eq(60), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        limiter.checkGlobalAddressHashed(packedAddress);
        limiter.checkBusinessKeyHashed("TAKU", callbackKeyHash, "REWARD");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(redis, times(2)).tryAcquire(keys.capture(), limits.capture(),
                eq(60), eq(TimeUnit.SECONDS));
        assertTrue(keys.getAllValues().get(0).contains(":ddos:ip:"));
        assertEquals(12000, limits.getAllValues().get(0));
        assertTrue(keys.getAllValues().get(1).contains(":taku:reward:key:"));
        assertEquals(120, limits.getAllValues().get(1));
        assertFalse(keys.getAllValues().stream().anyMatch(
                key -> key.contains("provider-key") || key.contains("203.0.113.32")));
    }

    @Test
    void splitTenantGatesReuseLegacyRedisBucketsForIpv4AndIpv6() throws Exception {
        String rawKey = "acct_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKL";
        for (String clientIp : new String[]{"203.0.113.33", "2001:db8::1"}) {
            org.mockito.Mockito.reset(redis);
            when(redis.tryAcquire(anyString(), anyInt(), eq(60), eq(TimeUnit.SECONDS)))
                    .thenReturn(true);
            byte[] callbackKeyHash = domainHash("callback-key\0", rawKey);
            byte[] packedAddress = com.google.common.net.InetAddresses
                    .forString(clientIp).getAddress();

            limiter.check("TAKU", rawKey, clientIp, "REWARD");
            limiter.checkGlobalAddressHashed(packedAddress);
            limiter.checkBusinessKeyHashed("TAKU", callbackKeyHash, "REWARD");

            ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
            verify(redis, times(4)).tryAcquire(keys.capture(), anyInt(),
                    eq(60), eq(TimeUnit.SECONDS));
            assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(2));
            assertEquals(keys.getAllValues().get(1), keys.getAllValues().get(3));
            java.util.Arrays.fill(callbackKeyHash, (byte) 0);
            java.util.Arrays.fill(packedAddress, (byte) 0);
        }
    }

    private void installInMemoryQuotaAnswer() {
        Map<String, Integer> used = new HashMap<>();
        when(redis.tryAcquire(anyString(), anyInt(), eq(60), eq(TimeUnit.SECONDS)))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    int limit = invocation.getArgument(1);
                    int current = used.merge(key, 1, Integer::sum);
                    return current <= limit;
                });
    }

    private static String lowerHex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }

    private static byte[] domainHash(String domain, String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(domain.getBytes(StandardCharsets.US_ASCII));
        return digest.digest(value.getBytes(StandardCharsets.US_ASCII));
    }

}
