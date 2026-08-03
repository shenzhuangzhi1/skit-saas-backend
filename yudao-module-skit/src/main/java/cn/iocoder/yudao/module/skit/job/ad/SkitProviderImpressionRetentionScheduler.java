package cn.iocoder.yudao.module.skit.job.ad;

import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionRetentionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Optional automatic trigger; the Quartz-compatible manual job remains available when disabled. */
@Component
@ConditionalOnProperty(prefix = "skit.ad.provider-impression.retention",
        name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class SkitProviderImpressionRetentionScheduler {

    private final SkitProviderImpressionRetentionJob job;
    private final SkitProviderImpressionRetentionProperties properties;

    public SkitProviderImpressionRetentionScheduler(
            SkitProviderImpressionRetentionJob job,
            SkitProviderImpressionRetentionProperties properties) {
        this.job = Objects.requireNonNull(job, "job");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Scheduled(fixedDelayString =
            "${skit.ad.provider-impression.retention.fixed-delay-ms:300000}")
    public void runScheduled() {
        int batchSize = properties.getBatchSize();
        int maxBatches = properties.getMaxBatchesPerRun();
        for (int batch = 0; batch < maxBatches; batch++) {
            int purged = job.runOnce();
            if (purged < 0 || purged > batchSize) {
                throw new IllegalStateException(
                        "Provider impression retention returned an invalid batch count");
            }
            if (purged < batchSize) {
                return;
            }
        }
    }
}
