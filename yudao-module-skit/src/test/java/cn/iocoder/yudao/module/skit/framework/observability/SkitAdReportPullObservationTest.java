package cn.iocoder.yudao.module.skit.framework.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SkitAdReportPullObservationTest {

    @Test
    void reportPullMetricsUseOnlyBoundedProviderOutcomeAndReasonTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SkitAdReportPullObservation observation = new SkitAdReportPullObservation(registry);

        observation.recordSuccess(Duration.ofMillis(12));
        observation.recordFailure(Duration.ofMillis(34), "UPSTREAM_FAILURE");
        observation.recordRepeatedFailureAlert();

        assertEquals(1.0D, registry.get(SkitAdReportPullObservation.COUNTER_NAME)
                .tag("provider", "TAKU").tag("outcome", "success").counter().count());
        assertEquals(1.0D, registry.get(SkitAdReportPullObservation.COUNTER_NAME)
                .tag("provider", "TAKU").tag("outcome", "failure").counter().count());
        assertEquals(1L, registry.get(SkitAdReportPullObservation.TIMER_NAME)
                .tag("provider", "TAKU").tag("outcome", "failure").timer().count());
        assertEquals(1.0D, registry.get(SkitAdReportPullObservation.ALERT_NAME)
                .tag("provider", "TAKU").tag("reason", "consecutive_failures").counter().count());

        Set<String> tagKeys = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey()).collect(Collectors.toSet());
        assertEquals(new HashSet<>(Arrays.asList("provider", "outcome", "reason")), tagKeys);
        for (Meter meter : registry.getMeters()) {
            String rendered = meter.getId().toString();
            assertFalse(rendered.contains("tenantId"));
            assertFalse(rendered.contains("adAccountId"));
            assertFalse(rendered.contains("publisher"));
        }
    }
}
