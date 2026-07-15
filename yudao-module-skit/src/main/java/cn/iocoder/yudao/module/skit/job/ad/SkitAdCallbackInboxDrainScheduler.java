package cn.iocoder.yudao.module.skit.job.ad;

import cn.iocoder.yudao.module.skit.service.ad.callback.SkitAdCallbackInboxDrainService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Default automatic trigger for the global callback inbox drain. */
@Component
@ConditionalOnProperty(prefix = "skit.ad.callback.drain", name = "scheduling-enabled",
        havingValue = "true", matchIfMissing = true)
public class SkitAdCallbackInboxDrainScheduler {

    private final SkitAdCallbackInboxDrainService drainService;

    public SkitAdCallbackInboxDrainScheduler(SkitAdCallbackInboxDrainService drainService) {
        this.drainService = Objects.requireNonNull(drainService, "drainService");
    }

    @Scheduled(fixedDelayString = "${skit.ad.callback.drain.fixed-delay-ms:1000}")
    public void scheduledDrain() {
        drainService.drainOnce();
    }

}
