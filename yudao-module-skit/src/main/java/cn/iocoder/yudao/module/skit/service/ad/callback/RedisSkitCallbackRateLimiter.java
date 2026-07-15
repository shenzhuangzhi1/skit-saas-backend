package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.ratelimiter.core.redis.RateLimiterRedisDAO;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Applies a per-callback-key business limit plus a much higher global IP DDoS gate. */
@Component
public class RedisSkitCallbackRateLimiter implements SkitCallbackRateLimiter {

    private static final int GLOBAL_IP_REQUESTS_PER_MINUTE = 12000;
    private static final int CALLBACK_KEY_REQUESTS_PER_MINUTE = 120;

    private final RateLimiterRedisDAO redis;

    public RedisSkitCallbackRateLimiter(RateLimiterRedisDAO redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public void check(String callbackKey, String clientIp, String callbackType) {
        String type = requireType(callbackType);
        String ipGateKey = "skit:ad-callback:ddos:ip:"
                + sha256Hex("client-ip\0" + requireValue(clientIp));
        String businessKey = "skit:ad-callback:" + type + ":key:"
                + sha256Hex("callback-key\0" + requireValue(callbackKey));
        if (!Boolean.TRUE.equals(redis.tryAcquire(ipGateKey, GLOBAL_IP_REQUESTS_PER_MINUTE,
                60, TimeUnit.SECONDS))) {
            throw new RateLimitExceededException();
        }
        if (!Boolean.TRUE.equals(redis.tryAcquire(businessKey, CALLBACK_KEY_REQUESTS_PER_MINUTE,
                60, TimeUnit.SECONDS))) {
            throw new RateLimitExceededException();
        }
    }

    private static String requireType(String value) {
        String normalized = requireValue(value).toUpperCase(Locale.ROOT);
        if (!"REWARD".equals(normalized) && !"IMPRESSION".equals(normalized)) {
            throw new IllegalArgumentException("Unsupported callback type");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String requireValue(String value) {
        if (value == null || value.isEmpty() || value.length() > 512) {
            throw new IllegalArgumentException("Callback rate-limit identity is invalid");
        }
        return value;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

}
