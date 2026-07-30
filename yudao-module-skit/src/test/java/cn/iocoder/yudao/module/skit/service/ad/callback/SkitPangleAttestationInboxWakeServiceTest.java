package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitPangleAttestationInboxWakeServiceTest {

    @Test
    void wakeAlwaysRunsInAnIndependentTransaction() throws Exception {
        Transactional transactional = SkitPangleAttestationInboxWakeService.class
                .getMethod("wakeRetry", Long.class, Long.class, Long.class, Integer.class)
                .getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
    }

    @Test
    void matchingPendingRetryIsWokenExactlyOnce() {
        SkitAdCallbackInboxMapper mapper = mock(SkitAdCallbackInboxMapper.class);
        when(mapper.wakePangleAttestationPendingRewardCas(11L, 22L, 33L, 4))
                .thenReturn(1, 0);
        SkitPangleAttestationInboxWakeService service =
                new SkitPangleAttestationInboxWakeService(mapper);

        assertTrue(service.wakeRetry(11L, 22L, 33L, 4));
        assertFalse(service.wakeRetry(11L, 22L, 33L, 4));

        verify(mapper, times(2))
                .wakePangleAttestationPendingRewardCas(11L, 22L, 33L, 4);
    }

    @Test
    void ineligibleTerminalExpiredOrWrongScopeIsANoOp() {
        SkitAdCallbackInboxMapper mapper = mock(SkitAdCallbackInboxMapper.class);
        SkitPangleAttestationInboxWakeService service =
                new SkitPangleAttestationInboxWakeService(mapper);

        assertFalse(service.wakeRetry(11L, 22L, 33L, 4));

        verify(mapper).wakePangleAttestationPendingRewardCas(11L, 22L, 33L, 4);
    }

    @Test
    void multipleChangedRowsFailClosed() {
        SkitAdCallbackInboxMapper mapper = mock(SkitAdCallbackInboxMapper.class);
        when(mapper.wakePangleAttestationPendingRewardCas(11L, 22L, 33L, 4))
                .thenReturn(2);
        SkitPangleAttestationInboxWakeService service =
                new SkitPangleAttestationInboxWakeService(mapper);

        assertThrows(IllegalStateException.class,
                () -> service.wakeRetry(11L, 22L, 33L, 4));
    }

}
