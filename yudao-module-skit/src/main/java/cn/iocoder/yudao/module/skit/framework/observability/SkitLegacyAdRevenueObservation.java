package cn.iocoder.yudao.module.skit.framework.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Observes use of the retired client-trusted revenue endpoint without retaining attacker-controlled identifiers.
 */
@Component
@Slf4j
public class SkitLegacyAdRevenueObservation {

    static final String METRIC_NAME = "skit.ad.legacy.report.acknowledged";
    static final String STATUS_TAG = "legacy_unverified";

    private final Counter acknowledgedCounter;

    public SkitLegacyAdRevenueObservation() {
        this(Metrics.globalRegistry);
    }

    SkitLegacyAdRevenueObservation(MeterRegistry registry) {
        this.acknowledgedCounter = Counter.builder(METRIC_NAME)
                .description("Acknowledged calls to the retired client-trusted ad revenue endpoint")
                .tag("status", STATUS_TAG)
                .register(registry);
    }

    public void recordAcknowledged() {
        acknowledgedCounter.increment();
        log.info("[recordAcknowledged][legacy ad revenue compatibility call; status=LEGACY_UNVERIFIED]");
    }

}
