package cn.iocoder.yudao.module.skit.job.ad;

import cn.iocoder.yudao.module.skit.service.ad.callback.SkitAdCallbackEvidenceRetentionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Optional automatic trigger; the Quartz-compatible manual job remains available when disabled. */
@Component
@ConditionalOnProperty(prefix = "skit.ad.callback.retention", name = "scheduling-enabled",
        havingValue = "true", matchIfMissing = true)
public class SkitAdCallbackEvidenceRetentionScheduler {

    private final SkitAdCallbackEvidenceRetentionService service;

    public SkitAdCallbackEvidenceRetentionScheduler(SkitAdCallbackEvidenceRetentionService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Scheduled(fixedDelayString = "${skit.ad.callback.retention.fixed-delay-ms:3600000}")
    public void runScheduled() {
        service.runOnce();
    }

}
