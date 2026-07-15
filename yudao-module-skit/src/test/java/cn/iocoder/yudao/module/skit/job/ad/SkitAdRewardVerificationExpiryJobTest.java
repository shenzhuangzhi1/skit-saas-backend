package cn.iocoder.yudao.module.skit.job.ad;

import cn.iocoder.yudao.framework.quartz.core.handler.JobHandler;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitAdRewardVerificationExpiryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitAdRewardVerificationExpiryJobTest {

    @Test
    void blankParametersRunOneGlobalSweepWithoutTenantEnumeration() throws Exception {
        SkitAdRewardVerificationExpiryService service = mock(SkitAdRewardVerificationExpiryService.class);
        when(service.sweepOnce()).thenReturn(6);
        SkitAdRewardVerificationExpiryJob job = new SkitAdRewardVerificationExpiryJob(service);

        assertTrue(job instanceof JobHandler);
        assertEquals("处理广告奖励验证超时 6 条", job.execute(" "));
        verify(service).sweepOnce();
    }

    @Test
    void nonBlankParametersCannotSelectATenant() {
        SkitAdRewardVerificationExpiryJob job = new SkitAdRewardVerificationExpiryJob(
                mock(SkitAdRewardVerificationExpiryService.class));

        assertThrows(IllegalArgumentException.class, () -> job.execute("tenantId=1"));
    }

    @Test
    void automaticSchedulerCanBeDisabledWithoutRemovingTheManualJob() throws Exception {
        for (Method method : SkitAdRewardVerificationExpiryJob.class.getMethods()) {
            assertFalse(method.isAnnotationPresent(Scheduled.class),
                    "manual JobHandler must not own an unconditional scheduler");
        }

        ConditionalOnProperty condition =
                SkitAdRewardVerificationExpiryScheduler.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("skit.ad.reward-expiry", condition.prefix());
        assertArrayEquals(new String[]{"scheduling-enabled"}, condition.name());
        assertEquals("true", condition.havingValue());
        assertTrue(condition.matchIfMissing());

        SkitAdRewardVerificationExpiryService service = mock(SkitAdRewardVerificationExpiryService.class);
        Method scheduledMethod = SkitAdRewardVerificationExpiryScheduler.class.getMethod("runScheduled");
        Scheduled scheduled = scheduledMethod.getAnnotation(Scheduled.class);
        assertEquals("${skit.ad.reward-expiry.fixed-delay-ms:10000}", scheduled.fixedDelayString());

        scheduledMethod.invoke(new SkitAdRewardVerificationExpiryScheduler(service));

        verify(service).sweepOnce();
    }

}
