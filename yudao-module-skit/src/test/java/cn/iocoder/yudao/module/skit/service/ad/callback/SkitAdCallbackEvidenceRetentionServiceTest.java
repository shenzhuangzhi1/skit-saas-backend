package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdRetentionClaimDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackEdgeAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitAdCallbackEvidenceRetentionServiceTest {

    private static final int RETENTION_DAYS = 180;

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void globallyProjectsRetentionRoutesThenMutatesOnlyInsideDerivedTenantContexts() {
        SkitAdCallbackInboxMapper inboxMapper = mock(SkitAdCallbackInboxMapper.class);
        SkitAdCallbackAttemptMapper attemptMapper = mock(SkitAdCallbackAttemptMapper.class);
        SkitAdCallbackEdgeAttemptMapper edgeMapper = mock(SkitAdCallbackEdgeAttemptMapper.class);
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        SkitAdCallbackEvidenceRetentionServiceImpl service = service(
                inboxMapper, attemptMapper, edgeMapper, transactions, 10);
        SkitAdRetentionClaimDO payload = claim(8101L, 8111L, 8121L);
        SkitAdRetentionClaimDO attempt = claim(8201L, 8211L, 8221L);
        SkitAdRetentionClaimDO knownEdge = claim(8301L, 8311L, 8321L);
        SkitAdRetentionClaimDO unknownEdge = claim(null, null, 8421L);

        TenantContextHolder.setTenantId(999L);
        when(inboxMapper.selectExpiredTerminalPayloadClaims(10)).thenAnswer(invocation -> {
            assertTrue(TenantContextHolder.isIgnore());
            return Collections.singletonList(payload);
        });
        when(attemptMapper.selectExpiredRetentionClaims(RETENTION_DAYS, 10)).thenAnswer(invocation -> {
            assertTrue(TenantContextHolder.isIgnore());
            return Collections.singletonList(attempt);
        });
        when(edgeMapper.selectExpiredRetentionClaims(RETENTION_DAYS, 10)).thenAnswer(invocation -> {
            assertTrue(TenantContextHolder.isIgnore());
            return Arrays.asList(knownEdge, unknownEdge);
        });
        when(inboxMapper.eraseExpiredTerminalPayloadCas(8101L, 8111L, 8121L))
                .thenAnswer(invocation -> assertTenantAndReturn(8101L, 1));
        when(attemptMapper.deleteExpiredRetentionClaimCas(8201L, 8211L, 8221L, RETENTION_DAYS))
                .thenAnswer(invocation -> assertTenantAndReturn(8201L, 1));
        when(edgeMapper.deleteExpiredKnownRouteClaimCas(8301L, 8311L, 8321L, RETENTION_DAYS))
                .thenAnswer(invocation -> assertTenantAndReturn(8301L, 1));
        when(edgeMapper.deleteExpiredUnknownRouteClaimCas(8421L, RETENTION_DAYS)).thenAnswer(invocation -> {
            assertTrue(TenantContextHolder.isIgnore());
            assertEquals(999L, TenantContextHolder.getRequiredTenantId());
            return 1;
        });

        SkitAdCallbackEvidenceRetentionService.RetentionResult result = service.runOnce();

        assertEquals(1, result.getErasedPayloadCount());
        assertEquals(1, result.getDeletedAttemptCount());
        assertEquals(2, result.getDeletedEdgeAttemptCount());
        assertFalse(TenantContextHolder.isIgnore());
        assertEquals(999L, TenantContextHolder.getRequiredTenantId());
        assertEquals(5, transactions.definitions.size(),
                "one global projection and one transaction per retention claim");
    }

    @Test
    void aStalePayloadClaimIsHarmlessAndNeverDeletesTheInboxFact() {
        SkitAdCallbackInboxMapper inboxMapper = mock(SkitAdCallbackInboxMapper.class);
        SkitAdCallbackAttemptMapper attemptMapper = mock(SkitAdCallbackAttemptMapper.class);
        SkitAdCallbackEdgeAttemptMapper edgeMapper = mock(SkitAdCallbackEdgeAttemptMapper.class);
        SkitAdRetentionClaimDO stale = claim(8501L, 8511L, 8521L);
        when(inboxMapper.selectExpiredTerminalPayloadClaims(1)).thenReturn(Collections.singletonList(stale));
        when(attemptMapper.selectExpiredRetentionClaims(RETENTION_DAYS, 1)).thenReturn(Collections.emptyList());
        when(edgeMapper.selectExpiredRetentionClaims(RETENTION_DAYS, 1)).thenReturn(Collections.emptyList());
        when(inboxMapper.eraseExpiredTerminalPayloadCas(8501L, 8511L, 8521L)).thenReturn(0);

        SkitAdCallbackEvidenceRetentionService.RetentionResult result = service(
                inboxMapper, attemptMapper, edgeMapper, new RecordingTransactionManager(), 1).runOnce();

        assertEquals(0, result.getErasedPayloadCount());
        verify(inboxMapper).eraseExpiredTerminalPayloadCas(8501L, 8511L, 8521L);
    }

    @Test
    void aMalformedGlobalRouteProjectionFailsClosedBeforeAnyDeletion() {
        SkitAdCallbackInboxMapper inboxMapper = mock(SkitAdCallbackInboxMapper.class);
        SkitAdCallbackAttemptMapper attemptMapper = mock(SkitAdCallbackAttemptMapper.class);
        SkitAdCallbackEdgeAttemptMapper edgeMapper = mock(SkitAdCallbackEdgeAttemptMapper.class);
        when(inboxMapper.selectExpiredTerminalPayloadClaims(1))
                .thenReturn(Collections.singletonList(claim(0L, 1L, 2L)));
        when(attemptMapper.selectExpiredRetentionClaims(RETENTION_DAYS, 1)).thenReturn(Collections.emptyList());
        when(edgeMapper.selectExpiredRetentionClaims(RETENTION_DAYS, 1)).thenReturn(Collections.emptyList());

        assertThrows(IllegalStateException.class, () -> service(
                inboxMapper, attemptMapper, edgeMapper,
                new RecordingTransactionManager(), 1).runOnce());
    }

    private static SkitAdCallbackEvidenceRetentionServiceImpl service(
            SkitAdCallbackInboxMapper inboxMapper, SkitAdCallbackAttemptMapper attemptMapper,
            SkitAdCallbackEdgeAttemptMapper edgeMapper,
            PlatformTransactionManager transactionManager, int batchSize) {
        return new SkitAdCallbackEvidenceRetentionServiceImpl(inboxMapper, attemptMapper,
                edgeMapper, transactionManager, batchSize, RETENTION_DAYS);
    }

    private static SkitAdRetentionClaimDO claim(Long tenantId, Long accountId, long id) {
        return new SkitAdRetentionClaimDO().setTenantId(tenantId)
                .setAdAccountId(accountId).setId(id);
    }

    private static <T> T assertTenantAndReturn(long tenantId, T value) {
        assertFalse(TenantContextHolder.isIgnore());
        assertEquals(tenantId, TenantContextHolder.getRequiredTenantId());
        return value;
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private final List<TransactionDefinition> definitions = new ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            definitions.add(definition);
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                    definition.getPropagationBehavior());
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,
                    definition.getIsolationLevel());
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
