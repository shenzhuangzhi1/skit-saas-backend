package cn.iocoder.yudao.module.skit.job.ad;

import cn.iocoder.yudao.framework.quartz.core.handler.JobHandler;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionRetentionService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionRetentionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class SkitProviderImpressionRetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-08-03T08:30:00Z");

    @Test
    void manualJobAcceptsNoRoutingParametersAndReportsOnlyTheBoundedCount() {
        SkitProviderImpressionRetentionService service =
                mock(SkitProviderImpressionRetentionService.class);
        LocalDateTime expectedNow = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(service.purgeExpiredCiphertexts("provider-node-a", expectedNow)).thenReturn(12);
        SkitProviderImpressionRetentionJob job = new SkitProviderImpressionRetentionJob(
                service, "provider-node-a", Clock.fixed(NOW, ZoneOffset.UTC));

        assertTrue(job instanceof JobHandler);
        assertEquals("清理供应商广告展示回调密文 12 条", job.execute(""));
        assertThrows(IllegalArgumentException.class, () -> job.execute("connection=1"));
        verify(service).purgeExpiredCiphertexts("provider-node-a", expectedNow);
    }

    @Test
    void optionalSchedulerDelegatesToTheSameClusterSafeUtcJobBoundary() throws Exception {
        for (Method method : SkitProviderImpressionRetentionJob.class.getMethods()) {
            assertFalse(method.isAnnotationPresent(Scheduled.class));
        }
        ConditionalOnProperty condition = SkitProviderImpressionRetentionScheduler.class
                .getAnnotation(ConditionalOnProperty.class);
        assertEquals("skit.ad.provider-impression.retention", condition.prefix());
        assertArrayEquals(new String[]{"scheduling-enabled"}, condition.name());
        assertEquals("true", condition.havingValue());
        assertTrue(condition.matchIfMissing());
        Method scheduled = SkitProviderImpressionRetentionScheduler.class
                .getMethod("runScheduled");
        assertEquals("${skit.ad.provider-impression.retention.fixed-delay-ms:300000}",
                scheduled.getAnnotation(Scheduled.class).fixedDelayString());

        SkitProviderImpressionRetentionJob job = mock(SkitProviderImpressionRetentionJob.class);
        SkitProviderImpressionRetentionProperties properties =
                new SkitProviderImpressionRetentionProperties();
        properties.setBatchSize(2);
        properties.setMaxBatchesPerRun(4);
        when(job.runOnce()).thenReturn(2, 2, 1);
        new SkitProviderImpressionRetentionScheduler(job, properties).runScheduled();

        verify(job, times(3)).runOnce();
    }
}
