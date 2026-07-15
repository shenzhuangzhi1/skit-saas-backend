package cn.iocoder.yudao.module.skit.job.ad;

import cn.iocoder.yudao.framework.quartz.core.handler.JobHandler;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitAdCallbackEvidenceRetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitAdCallbackEvidenceRetentionJobTest {

    @Test
    void blankParametersRunGlobalRetentionWithoutTenantEnumeration() throws Exception {
        SkitAdCallbackEvidenceRetentionService service = mock(SkitAdCallbackEvidenceRetentionService.class);
        SkitAdCallbackEvidenceRetentionService.RetentionResult result =
                new SkitAdCallbackEvidenceRetentionService.RetentionResult(2, 3, 4);
        when(service.runOnce()).thenReturn(result);
        SkitAdCallbackEvidenceRetentionJob job = new SkitAdCallbackEvidenceRetentionJob(service);

        assertTrue(job instanceof JobHandler);
        assertEquals("清理广告回调密文 2 条、投递证据 3 条、边缘证据 4 条", job.execute(""));
        verify(service).runOnce();
    }

    @Test
    void nonBlankParametersCannotSelectATenantOrRetentionWindow() {
        SkitAdCallbackEvidenceRetentionJob job = new SkitAdCallbackEvidenceRetentionJob(
                mock(SkitAdCallbackEvidenceRetentionService.class));

        assertThrows(IllegalArgumentException.class, () -> job.execute("days=1"));
    }

    @Test
    void automaticSchedulerCanBeDisabledWithoutRemovingTheManualJob() throws Exception {
        for (Method method : SkitAdCallbackEvidenceRetentionJob.class.getMethods()) {
            assertFalse(method.isAnnotationPresent(Scheduled.class),
                    "manual JobHandler must not own an unconditional scheduler");
        }

        ConditionalOnProperty condition =
                SkitAdCallbackEvidenceRetentionScheduler.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("skit.ad.callback.retention", condition.prefix());
        assertArrayEquals(new String[]{"scheduling-enabled"}, condition.name());
        assertEquals("true", condition.havingValue());
        assertTrue(condition.matchIfMissing());

        SkitAdCallbackEvidenceRetentionService service = mock(SkitAdCallbackEvidenceRetentionService.class);
        when(service.runOnce()).thenReturn(
                new SkitAdCallbackEvidenceRetentionService.RetentionResult(0, 0, 0));
        Method scheduledMethod = SkitAdCallbackEvidenceRetentionScheduler.class.getMethod("runScheduled");
        Scheduled scheduled = scheduledMethod.getAnnotation(Scheduled.class);
        assertEquals("${skit.ad.callback.retention.fixed-delay-ms:3600000}",
                scheduled.fixedDelayString());

        scheduledMethod.invoke(new SkitAdCallbackEvidenceRetentionScheduler(service));

        verify(service).runOnce();
    }

}
