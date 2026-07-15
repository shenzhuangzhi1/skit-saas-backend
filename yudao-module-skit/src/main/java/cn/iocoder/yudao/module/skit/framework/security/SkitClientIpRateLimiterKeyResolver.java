package cn.iocoder.yudao.module.skit.framework.security;

import cn.hutool.crypto.SecureUtil;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.ratelimiter.core.keyresolver.RateLimiterKeyResolver;
import org.aspectj.lang.JoinPoint;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** IP-only limiter key; request arguments are intentionally excluded so attackers cannot rotate payloads. */
@Component
public class SkitClientIpRateLimiterKeyResolver implements RateLimiterKeyResolver {

    private final SkitTrustedProxyClientIpResolver clientIpResolver;

    public SkitClientIpRateLimiterKeyResolver(SkitTrustedProxyClientIpResolver clientIpResolver) {
        this.clientIpResolver = Objects.requireNonNull(clientIpResolver, "clientIpResolver");
    }

    @Override
    public String resolver(JoinPoint joinPoint, RateLimiter rateLimiter) {
        return SecureUtil.md5(joinPoint.getSignature().toLongString() + ":"
                + clientIpResolver.resolveCurrentRequest());
    }
}
