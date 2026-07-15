package cn.iocoder.yudao.module.skit.job.ad;

import cn.iocoder.yudao.framework.quartz.core.handler.JobHandler;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitAdCallbackInboxDrainService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitAdCallbackInboxDrainJobTest {

    @Test
    void blankParametersRunOneGlobalDrainWithoutTenantSelection() throws Exception {
        SkitAdCallbackInboxDrainService service = mock(SkitAdCallbackInboxDrainService.class);
        when(service.drainOnce()).thenReturn(7);
        SkitAdCallbackInboxDrainJob job = new SkitAdCallbackInboxDrainJob(service);

        assertTrue(job instanceof JobHandler);
        assertEquals("处理广告回调收件箱 7 条", job.execute("  "));
        verify(service).drainOnce();
    }

    @Test
    void nonBlankParametersCannotSelectATenantOrRoutingKey() {
        SkitAdCallbackInboxDrainService service = mock(SkitAdCallbackInboxDrainService.class);
        SkitAdCallbackInboxDrainJob job = new SkitAdCallbackInboxDrainJob(service);

        assertThrows(IllegalArgumentException.class, () -> job.execute("tenantId=1"));
    }

}
