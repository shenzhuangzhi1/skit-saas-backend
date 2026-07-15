package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackClaimDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import cn.iocoder.yudao.module.skit.framework.observability.SkitAdCallbackDrainObservation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitAdCallbackInboxDrainServiceTest {

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void claimsGloballyThenProcessesTwoTenantsInsideTheirDerivedContext() {
        SkitAdCallbackInboxMapper mapper = mock(SkitAdCallbackInboxMapper.class);
        SkitAdCallbackProcessor processor = mock(SkitAdCallbackProcessor.class);
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        SkitAdCallbackInboxDrainServiceImpl service = service(
                mapper, processor, transactions, "worker-a", 2);
        SkitAdCallbackClaimDO first = claim(9201L, 9211L, 9221L);
        SkitAdCallbackClaimDO second = claim(9301L, 9311L, 9321L);

        TenantContextHolder.setTenantId(999L);
        when(mapper.selectReadyClaimsForUpdate(2)).thenAnswer(invocation -> {
            assertTrue(TenantContextHolder.isIgnore());
            assertEquals(999L, TenantContextHolder.getTenantId());
            return Arrays.asList(first, second);
        });
        when(mapper.claimForProcessingCas(anyLong(), anyLong(), anyLong(), eq("worker-a"), eq(120)))
                .thenAnswer(invocation -> {
                    assertTrue(TenantContextHolder.isIgnore());
                    return 1;
                });
        doAnswer(invocation -> {
            long tenantId = invocation.getArgument(0);
            long accountId = invocation.getArgument(1);
            long inboxId = invocation.getArgument(2);
            assertFalse(TenantContextHolder.isIgnore());
            assertEquals(tenantId, TenantContextHolder.getRequiredTenantId());
            if (tenantId == first.getTenantId()) {
                assertEquals(first.getAdAccountId().longValue(), accountId);
                assertEquals(first.getId().longValue(), inboxId);
            } else {
                assertEquals(second.getTenantId().longValue(), tenantId);
                assertEquals(second.getAdAccountId().longValue(), accountId);
                assertEquals(second.getId().longValue(), inboxId);
            }
            return SkitAdCallbackProcessor.ProcessResult.succeeded();
        }).when(processor).process(anyLong(), anyLong(), anyLong(), eq("worker-a"));

        assertEquals(2, service.drainOnce());

        assertFalse(TenantContextHolder.isIgnore());
        assertEquals(999L, TenantContextHolder.getRequiredTenantId());
        assertEquals(3, transactions.definitions.size(),
                "one global claim and one independent transaction per claimed item");
        for (TransactionDefinition definition : transactions.definitions) {
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                    definition.getPropagationBehavior());
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,
                    definition.getIsolationLevel());
        }
    }

    @Test
    void aLostClaimCasIsNeverProcessed() {
        SkitAdCallbackInboxMapper mapper = mock(SkitAdCallbackInboxMapper.class);
        SkitAdCallbackProcessor processor = mock(SkitAdCallbackProcessor.class);
        SkitAdCallbackClaimDO claim = claim(9401L, 9411L, 9421L);
        when(mapper.selectReadyClaimsForUpdate(1)).thenReturn(java.util.Collections.singletonList(claim));
        when(mapper.claimForProcessingCas(9401L, 9411L, 9421L, "worker-b", 120))
                .thenReturn(0);

        assertEquals(0, service(mapper, processor, new RecordingTransactionManager(),
                "worker-b", 1).drainOnce());

        verify(processor, never()).process(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void anExpiredLeaseAtTheAttemptLimitDeadLettersWithoutAForbiddenNinthClaim() {
        SkitAdCallbackInboxMapper mapper = mock(SkitAdCallbackInboxMapper.class);
        SkitAdCallbackProcessor processor = mock(SkitAdCallbackProcessor.class);
        SkitAdCallbackDrainObservation observation = mock(SkitAdCallbackDrainObservation.class);
        SkitAdCallbackClaimDO claim = claim(9451L, 9461L, 9471L);
        when(mapper.selectReadyClaimsForUpdate(1))
                .thenReturn(java.util.Collections.singletonList(claim));
        when(mapper.markExpiredProcessingDeadLetterCas(9451L, 9461L, 9471L,
                "CALLBACK_LEASE_EXHAUSTED", 8)).thenReturn(1);
        when(mapper.selectUnalertedDeadLetterClaims(1))
                .thenReturn(java.util.Collections.singletonList(claim));
        when(mapper.markDeadLetterAlertedCas(9451L, 9461L, 9471L)).thenReturn(1);

        assertEquals(0, service(mapper, processor, observation,
                new RecordingTransactionManager(), "worker-crash-recovery", 1).drainOnce());

        verify(mapper, never()).claimForProcessingCas(anyLong(), anyLong(), anyLong(),
                anyString(), anyInt());
        verify(processor, never()).process(anyLong(), anyLong(), anyLong(), anyString());
        verify(observation).recordDeadLetter(9451L, 9461L, 9471L);
    }

    @Test
    void unexpectedProcessorFailureSchedulesASecretSafeBoundedRetry() {
        SkitAdCallbackInboxMapper mapper = mock(SkitAdCallbackInboxMapper.class);
        SkitAdCallbackProcessor processor = mock(SkitAdCallbackProcessor.class);
        SkitAdCallbackClaimDO claim = claim(9501L, 9511L, 9521L);
        when(mapper.selectReadyClaimsForUpdate(1)).thenReturn(java.util.Collections.singletonList(claim));
        when(mapper.claimForProcessingCas(9501L, 9511L, 9521L, "worker-c", 120))
                .thenReturn(1);
        when(processor.process(9501L, 9511L, 9521L, "worker-c"))
                .thenThrow(new IllegalStateException("raw-query-must-not-be-persisted"));
        when(mapper.markDeadLetterCas(9501L, 9511L, 9521L, "worker-c",
                "CALLBACK_PROCESSOR_EXCEPTION", 8))
                .thenReturn(0);
        when(mapper.markRetryWaitCas(9501L, 9511L, 9521L, "worker-c",
                "CALLBACK_PROCESSOR_EXCEPTION", 8, 30, 3600)).thenReturn(1);

        assertEquals(1, service(mapper, processor, new RecordingTransactionManager(),
                "worker-c", 1).drainOnce());

        verify(mapper).markRetryWaitCas(9501L, 9511L, 9521L, "worker-c",
                "CALLBACK_PROCESSOR_EXCEPTION", 8, 30, 3600);
    }

    @Test
    void maximumAttemptTransitionsDirectlyToDeadLetter() {
        SkitAdCallbackInboxMapper mapper = mock(SkitAdCallbackInboxMapper.class);
        SkitAdCallbackProcessor processor = mock(SkitAdCallbackProcessor.class);
        SkitAdCallbackDrainObservation observation = mock(SkitAdCallbackDrainObservation.class);
        SkitAdCallbackClaimDO claim = claim(9601L, 9611L, 9621L);
        when(mapper.selectReadyClaimsForUpdate(1)).thenReturn(java.util.Collections.singletonList(claim));
        when(mapper.claimForProcessingCas(9601L, 9611L, 9621L, "worker-d", 120))
                .thenReturn(1);
        when(processor.process(9601L, 9611L, 9621L, "worker-d"))
                .thenThrow(new IllegalStateException("transient"));
        when(mapper.markDeadLetterCas(9601L, 9611L, 9621L, "worker-d",
                "CALLBACK_PROCESSOR_EXCEPTION", 8))
                .thenReturn(1);
        when(mapper.markDeadLetterAlertedCas(9601L, 9611L, 9621L)).thenReturn(1);

        assertEquals(1, service(mapper, processor, observation,
                new RecordingTransactionManager(), "worker-d", 1).drainOnce());

        verify(mapper, never()).markRetryWaitCas(anyLong(), anyLong(), anyLong(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt());
        verify(observation).recordDeadLetter(9601L, 9611L, 9621L);
    }

    @Test
    void aDurableDeadLetterBacklogEmitsOnlyForTheCasWinner() {
        SkitAdCallbackInboxMapper mapper = mock(SkitAdCallbackInboxMapper.class);
        SkitAdCallbackProcessor processor = mock(SkitAdCallbackProcessor.class);
        SkitAdCallbackDrainObservation observation = mock(SkitAdCallbackDrainObservation.class);
        SkitAdCallbackClaimDO claim = claim(9701L, 9711L, 9721L);
        when(mapper.selectUnalertedDeadLetterClaims(1))
                .thenReturn(java.util.Collections.singletonList(claim));
        when(mapper.markDeadLetterAlertedCas(9701L, 9711L, 9721L))
                .thenReturn(1, 0);
        SkitAdCallbackInboxDrainServiceImpl service = service(mapper, processor, observation,
                new RecordingTransactionManager(), "worker-alert", 1);

        assertEquals(0, service.drainOnce());
        assertEquals(0, service.drainOnce());

        verify(mapper, times(2)).markDeadLetterAlertedCas(9701L, 9711L, 9721L);
        verify(observation).recordDeadLetter(9701L, 9711L, 9721L);
    }

    private static SkitAdCallbackInboxDrainServiceImpl service(
            SkitAdCallbackInboxMapper mapper, SkitAdCallbackProcessor processor,
            PlatformTransactionManager transactionManager, String worker, int batchSize) {
        return service(mapper, processor, mock(SkitAdCallbackDrainObservation.class),
                transactionManager, worker, batchSize);
    }

    private static SkitAdCallbackInboxDrainServiceImpl service(
            SkitAdCallbackInboxMapper mapper, SkitAdCallbackProcessor processor,
            SkitAdCallbackDrainObservation observation,
            PlatformTransactionManager transactionManager, String worker, int batchSize) {
        return new SkitAdCallbackInboxDrainServiceImpl(mapper, processor, observation,
                transactionManager, () -> worker, batchSize, 120, 8, 30, 3600);
    }

    private static SkitAdCallbackClaimDO claim(long tenantId, long accountId, long inboxId) {
        return new SkitAdCallbackClaimDO().setTenantId(tenantId)
                .setAdAccountId(accountId).setId(inboxId);
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private final List<TransactionDefinition> definitions = new ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            definitions.add(definition);
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
