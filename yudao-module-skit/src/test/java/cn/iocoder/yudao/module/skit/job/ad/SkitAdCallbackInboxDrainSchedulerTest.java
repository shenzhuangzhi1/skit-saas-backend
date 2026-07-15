package cn.iocoder.yudao.module.skit.job.ad;

import cn.iocoder.yudao.module.skit.service.ad.callback.SkitAdCallbackInboxDrainService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SkitAdCallbackInboxDrainSchedulerTest {

    @Test
    void schedulerIsEnabledByDefaultAndUsesAConfigurableFixedDelay() throws Exception {
        ConditionalOnProperty condition =
                SkitAdCallbackInboxDrainScheduler.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("skit.ad.callback.drain", condition.prefix());
        assertArrayEquals(new String[]{"scheduling-enabled"}, condition.name());
        assertEquals("true", condition.havingValue());
        assertTrue(condition.matchIfMissing());

        Method method = SkitAdCallbackInboxDrainScheduler.class.getMethod("scheduledDrain");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertEquals("${skit.ad.callback.drain.fixed-delay-ms:1000}",
                scheduled.fixedDelayString());
    }

    @Test
    void scheduledTriggerRunsTheSameGlobalDrainWithoutTenantParameters() {
        SkitAdCallbackInboxDrainService service = mock(SkitAdCallbackInboxDrainService.class);
        SkitAdCallbackInboxDrainScheduler scheduler =
                new SkitAdCallbackInboxDrainScheduler(service);

        scheduler.scheduledDrain();

        verify(service).drainOnce();
    }

}
