package cn.iocoder.yudao.module.skit.framework.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Emits a bounded, payload-free signal when a callback permanently exhausts processing. */
@Component
@Slf4j
public class SkitAdCallbackDrainObservation {

    static final String METRIC_NAME = "skit.ad.callback.dead_letter";

    private final Counter deadLetterCounter;

    public SkitAdCallbackDrainObservation() {
        this(Metrics.globalRegistry);
    }

    SkitAdCallbackDrainObservation(MeterRegistry registry) {
        this.deadLetterCounter = Counter.builder(METRIC_NAME)
                .description("Callback inbox rows that permanently exhausted processing")
                .register(registry);
    }

    public void recordDeadLetter(long tenantId, long adAccountId, long callbackInboxId) {
        deadLetterCounter.increment();
        log.error("[recordDeadLetter][callback inbox exhausted; tenantId={}, adAccountId={}, inboxId={}]",
                tenantId, adAccountId, callbackInboxId);
    }

}
