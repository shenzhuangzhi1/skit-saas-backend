package cn.iocoder.yudao.module.skit.framework.security;

import cn.hutool.crypto.SecureUtil;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.ratelimiter.core.keyresolver.RateLimiterKeyResolver;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import org.aspectj.lang.JoinPoint;
import org.springframework.stereotype.Component;

/** Member-only limiter key; event ids and other rotating request arguments are intentionally excluded. */
@Component
public class SkitMemberRateLimiterKeyResolver implements RateLimiterKeyResolver {

    @Override
    public String resolver(JoinPoint joinPoint, RateLimiter rateLimiter) {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        String subject = loginUser == null ? "anonymous"
                : loginUser.getTenantId() + ":" + loginUser.getId() + ":" + loginUser.getUserType();
        return SecureUtil.md5(joinPoint.getSignature().toLongString() + ":" + subject);
    }
}
