package cn.iocoder.yudao.module.skit.framework.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkitAdCallbackDrainObservationTest {

    @Test
    void deadLetterObservationUsesOneBoundedMetricWithoutPayloadTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SkitAdCallbackDrainObservation observation =
                new SkitAdCallbackDrainObservation(registry);

        observation.recordDeadLetter(7101L, 7111L, 7121L);

        assertEquals(1.0D, registry.get(SkitAdCallbackDrainObservation.METRIC_NAME)
                .counter().count());
        assertEquals(0, registry.get(SkitAdCallbackDrainObservation.METRIC_NAME)
                .counter().getId().getTags().size());
    }

}
