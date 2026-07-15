package cn.iocoder.yudao.module.skit.job.ad;

import cn.iocoder.yudao.module.skit.service.ad.callback.SkitAdRewardVerificationExpiryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Optional automatic trigger; the Quartz-compatible manual job remains available when disabled. */
@Component
@ConditionalOnProperty(prefix = "skit.ad.reward-expiry", name = "scheduling-enabled",
        havingValue = "true", matchIfMissing = true)
public class SkitAdRewardVerificationExpiryScheduler {

    private final SkitAdRewardVerificationExpiryService service;

    public SkitAdRewardVerificationExpiryScheduler(SkitAdRewardVerificationExpiryService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(fixedDelayString = "${skit.ad.reward-expiry.fixed-delay-ms:10000}")
    public void runScheduled() {
        service.sweepOnce();
    }

}
