package cn.iocoder.yudao.module.skit.service.ad.callback;

public interface SkitCallbackRateLimiter {

    void check(String callbackKey, String clientIp, String callbackType);

    final class RateLimitExceededException extends IllegalStateException {

        public RateLimitExceededException() {
            super("Callback rate limit exceeded");
        }
    }

}
