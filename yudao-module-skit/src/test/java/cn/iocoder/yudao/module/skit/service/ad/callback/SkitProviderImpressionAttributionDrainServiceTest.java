package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderImpressionInboxDO;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderImpressionInboxMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitProviderImpressionAttributionDrainServiceTest {

    @Test
    void claimsReadyProviderObservationAndRunsAttributionProcessor() {
        SkitProviderImpressionInboxMapper mapper = mock(SkitProviderImpressionInboxMapper.class);
        SkitProviderImpressionAttributionProcessor processor =
                mock(SkitProviderImpressionAttributionProcessor.class);
        when(mapper.selectReadyAttributionClaimsForUpdate(10))
                .thenReturn(Collections.singletonList(candidate()));
        when(mapper.markExpiredAttributionDeadLetterCas(101L, 201L,
                "ATTRIBUTION_LEASE_EXHAUSTED", 3)).thenReturn(0);
        when(mapper.claimForAttributionCas(101L, 201L, "worker-1", 60, 3)).thenReturn(1);

        assertEquals(1, service(mapper, processor).drainOnce());
        verify(processor).process(101L, 201L, "worker-1");
    }

    @Test
    void lostClaimIsNotProcessed() {
        SkitProviderImpressionInboxMapper mapper = mock(SkitProviderImpressionInboxMapper.class);
        SkitProviderImpressionAttributionProcessor processor =
                mock(SkitProviderImpressionAttributionProcessor.class);
        when(mapper.selectReadyAttributionClaimsForUpdate(10))
                .thenReturn(Collections.singletonList(candidate()));
        when(mapper.markExpiredAttributionDeadLetterCas(101L, 201L,
                "ATTRIBUTION_LEASE_EXHAUSTED", 3)).thenReturn(0);
        when(mapper.claimForAttributionCas(101L, 201L, "worker-1", 60, 3)).thenReturn(0);

        assertEquals(0, service(mapper, processor).drainOnce());
        verify(processor, never()).process(101L, 201L, "worker-1");
    }

    @Test
    void transientProcessorFailureSchedulesLeaseBoundRetry() {
        SkitProviderImpressionInboxMapper mapper = mock(SkitProviderImpressionInboxMapper.class);
        SkitProviderImpressionAttributionProcessor processor =
                mock(SkitProviderImpressionAttributionProcessor.class);
        when(mapper.selectReadyAttributionClaimsForUpdate(10))
                .thenReturn(Collections.singletonList(candidate()));
        when(mapper.markExpiredAttributionDeadLetterCas(101L, 201L,
                "ATTRIBUTION_LEASE_EXHAUSTED", 3)).thenReturn(0);
        when(mapper.claimForAttributionCas(101L, 201L, "worker-1", 60, 3)).thenReturn(1);
        when(processor.process(101L, 201L, "worker-1"))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(mapper.markAttributionDeadLetterCas(101L, 201L, "worker-1",
                "ATTRIBUTION_PROCESSOR_EXCEPTION", 3)).thenReturn(0);
        when(mapper.markAttributionRetryWaitCas(101L, 201L, "worker-1",
                "ATTRIBUTION_PROCESSOR_EXCEPTION", 3, 5)).thenReturn(1);

        assertEquals(1, service(mapper, processor).drainOnce());
        verify(mapper).markAttributionRetryWaitCas(101L, 201L, "worker-1",
                "ATTRIBUTION_PROCESSOR_EXCEPTION", 3, 5);
    }

    private static SkitProviderImpressionInboxDO candidate() {
        return new SkitProviderImpressionInboxDO()
                .setProviderConnectionId(101L).setId(201L).setCanonicalAttemptId(301L)
                .setProcessingAttemptCount(0);
    }

    private static SkitProviderImpressionAttributionDrainServiceImpl service(
            SkitProviderImpressionInboxMapper mapper,
            SkitProviderImpressionAttributionProcessor processor) {
        return new SkitProviderImpressionAttributionDrainServiceImpl(
                mapper, processor, new RecordingTransactionManager(),
                () -> "worker-1", 10, 60, 3, 5);
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
