package cn.iocoder.yudao.module.skit.framework.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkitLegacyAdRevenueObservationTest {

    @Test
    void recordsOnlyFixedLowCardinalityStatusTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SkitLegacyAdRevenueObservation observation = new SkitLegacyAdRevenueObservation(registry);

        observation.recordAcknowledged();
        observation.recordAcknowledged();

        Counter counter = registry.get(SkitLegacyAdRevenueObservation.METRIC_NAME).counter();
        assertEquals(2.0D, counter.count());
        assertEquals(1, counter.getId().getTags().size());
        assertEquals("status", counter.getId().getTags().get(0).getKey());
        assertEquals(SkitLegacyAdRevenueObservation.STATUS_TAG, counter.getId().getTags().get(0).getValue());
    }

}
