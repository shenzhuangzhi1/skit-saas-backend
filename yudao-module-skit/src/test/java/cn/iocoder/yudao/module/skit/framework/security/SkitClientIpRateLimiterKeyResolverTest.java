package cn.iocoder.yudao.module.skit.framework.security;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.ratelimiter.core.aop.RateLimiterAspect;
import cn.iocoder.yudao.framework.ratelimiter.core.redis.RateLimiterRedisDAO;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class SkitClientIpRateLimiterKeyResolverTest {

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void forgedForwardedForStillHitsSameLoginLimiterKeyOnEleventhAttempt() {
        SkitTrustedProxyProperties properties = new SkitTrustedProxyProperties();
        properties.setTrustedProxyCidrs(Collections.singletonList("172.16.0.0/12"));
        SkitClientIpRateLimiterKeyResolver resolver = new SkitClientIpRateLimiterKeyResolver(
                new SkitTrustedProxyClientIpResolver(properties));
        JoinPoint joinPoint = mock(JoinPoint.class);
        Signature signature = mock(Signature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toLongString()).thenReturn("member-login");
        RateLimiter rateLimiter = mock(RateLimiter.class);
        doReturn(SkitClientIpRateLimiterKeyResolver.class).when(rateLimiter).keyResolver();
        when(rateLimiter.count()).thenReturn(10);
        when(rateLimiter.time()).thenReturn(60);
        when(rateLimiter.timeUnit()).thenReturn(TimeUnit.SECONDS);
        RateLimiterRedisDAO redis = mock(RateLimiterRedisDAO.class);
        Set<String> resolvedKeys = new HashSet<>();
        final int[] requests = {0};
        when(redis.tryAcquire(anyString(), eq(10), eq(60), eq(TimeUnit.SECONDS)))
                .thenAnswer(invocation -> {
                    resolvedKeys.add(invocation.getArgument(0));
                    requests[0]++;
                    return requests[0] <= 10;
                });
        RateLimiterAspect aspect = new RateLimiterAspect(
                Collections.singletonList(resolver), redis);

        for (int attempt = 1; attempt <= 10; attempt++) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("172.20.0.5");
            request.addHeader("X-Real-IP", "198.51.100.88");
            request.addHeader("X-Forwarded-For", "203.0.113." + attempt);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            aspect.beforePointCut(joinPoint, rateLimiter);
        }
        MockHttpServletRequest eleventh = new MockHttpServletRequest();
        eleventh.setRemoteAddr("172.20.0.5");
        eleventh.addHeader("X-Real-IP", "198.51.100.88");
        eleventh.addHeader("X-Forwarded-For", "203.0.113.11");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(eleventh));

        assertThrows(ServiceException.class, () -> aspect.beforePointCut(joinPoint, rateLimiter));
        assertEquals(1, resolvedKeys.size(),
                "the forged XFF value must not rotate the login rate-limit identity");
    }
}
