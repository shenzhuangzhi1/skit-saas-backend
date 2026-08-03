package cn.iocoder.yudao.module.skit.job.ad;

import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionAttributionDrainService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SkitProviderImpressionAttributionSchedulerTest {

    @Test
    void schedulerIsOnByDefaultAndDelegatesToGlobalDrain() throws Exception {
        ConditionalOnProperty condition = SkitProviderImpressionAttributionScheduler.class
                .getAnnotation(ConditionalOnProperty.class);
        assertEquals("skit.ad.provider-impression.attribution", condition.prefix());
        assertEquals("scheduling-enabled", condition.name()[0]);
        assertEquals("true", condition.havingValue());
        assertTrue(condition.matchIfMissing());
        Method method = SkitProviderImpressionAttributionScheduler.class
                .getMethod("scheduledDrain");
        assertEquals("${skit.ad.provider-impression.attribution.fixed-delay-ms:1000}",
                method.getAnnotation(Scheduled.class).fixedDelayString());

        SkitProviderImpressionAttributionDrainService drain =
                mock(SkitProviderImpressionAttributionDrainService.class);
        new SkitProviderImpressionAttributionScheduler(drain).scheduledDrain();
        verify(drain).drainOnce();
    }
}
