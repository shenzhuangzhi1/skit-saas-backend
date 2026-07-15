package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.ratelimiter.core.redis.RateLimiterRedisDAO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

        limiter.check(rawKey, rawIp, "REWARD");

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
    void sameProviderIpDoesNotMakeTwoCallbackKeysConsumeEachOthersBusinessQuota() {
        installInMemoryQuotaAnswer();
        String sharedProviderIp = "203.0.113.18";
        for (int request = 0; request < 120; request++) {
            limiter.check("tenant-a-key", sharedProviderIp, "REWARD");
            limiter.check("tenant-b-key", sharedProviderIp, "REWARD");
        }

        assertThrows(SkitCallbackRateLimiter.RateLimitExceededException.class,
                () -> limiter.check("tenant-a-key", sharedProviderIp, "REWARD"));
        assertThrows(SkitCallbackRateLimiter.RateLimitExceededException.class,
                () -> limiter.check("tenant-b-key", sharedProviderIp, "REWARD"));
    }

    @Test
    void impressionUsesTheSamePerKeyLimitIndependentlyFromReward() {
        installInMemoryQuotaAnswer();
        for (int request = 0; request < 120; request++) {
            limiter.check("same-key", "203.0.113.19", "REWARD");
            limiter.check("same-key", "203.0.113.19", "IMPRESSION");
        }

        assertThrows(SkitCallbackRateLimiter.RateLimitExceededException.class,
                () -> limiter.check("same-key", "203.0.113.19", "REWARD"));
        assertThrows(SkitCallbackRateLimiter.RateLimitExceededException.class,
                () -> limiter.check("same-key", "203.0.113.19", "IMPRESSION"));
    }

    @Test
    void globalIpGateStillStopsForgedKeyFloodAndRedisFailureRemainsTransient() {
        when(redis.tryAcquire(anyString(), anyInt(), eq(60), eq(TimeUnit.SECONDS)))
                .thenAnswer(invocation -> !((String) invocation.getArgument(0)).contains(":ddos:ip:"));
        assertThrows(SkitCallbackRateLimiter.RateLimitExceededException.class,
                () -> limiter.check("forged-key", "127.0.0.1", "REWARD"));

        when(redis.tryAcquire(anyString(), anyInt(), eq(60), eq(TimeUnit.SECONDS)))
                .thenThrow(new IllegalStateException("redis unavailable"));
        assertThrows(IllegalStateException.class,
                () -> limiter.check("key", "127.0.0.1", "REWARD"));
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

}
