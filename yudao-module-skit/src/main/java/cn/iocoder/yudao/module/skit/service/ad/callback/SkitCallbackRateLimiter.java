package cn.iocoder.yudao.module.skit.service.ad.callback;

public interface SkitCallbackRateLimiter {

    void check(String provider, String callbackKey, String clientIp, String callbackType);

    /** Applies the limits using the dispatcher's already-derived, clearable identities. */
    void checkHashed(String provider, byte[] callbackKeyHash,
                     byte[] packedClientAddress, String callbackType);

    /** Applies only the high global address gate before any registry/database lookup. */
    void checkGlobalAddressHashed(byte[] packedClientAddress);

    /** Applies only the legacy per-key business gate after a tenant route is verified. */
    void checkBusinessKeyHashed(String provider, byte[] callbackKeyHash, String callbackType);

    final class RateLimitExceededException extends IllegalStateException {

        public RateLimitExceededException() {
            super("Callback rate limit exceeded");
        }
    }

}
