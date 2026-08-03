package cn.iocoder.yudao.module.skit.job.ad;

import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionAttributionDrainService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnProperty(prefix = "skit.ad.provider-impression.attribution",
        name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class SkitProviderImpressionAttributionScheduler {

    private final SkitProviderImpressionAttributionDrainService drainService;

    public SkitProviderImpressionAttributionScheduler(
            SkitProviderImpressionAttributionDrainService drainService) {
        this.drainService = Objects.requireNonNull(drainService, "drainService");
    }

    @Scheduled(fixedDelayString =
            "${skit.ad.provider-impression.attribution.fixed-delay-ms:1000}")
    public void scheduledDrain() {
        drainService.drainOnce();
    }
}
