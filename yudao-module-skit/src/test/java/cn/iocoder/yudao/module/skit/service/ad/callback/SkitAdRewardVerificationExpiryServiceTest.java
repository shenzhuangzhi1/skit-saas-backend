package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdRewardExpiryClaimDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitAdRevenueEventMapper;
import cn.iocoder.yudao.module.skit.service.commission.SkitFrozenCommissionProjectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitAdRewardVerificationExpiryServiceTest {

    private static final LocalDateTime FIXTURE_TIME = LocalDateTime.of(2026, 7, 15, 3, 4, 5);

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void globallyProjectsRoutesThenExpiresEachTenantInsideAnIndependentTransaction() {
        SkitAdSessionMapper sessionMapper = mock(SkitAdSessionMapper.class);
        SkitAdRevenueEventMapper eventMapper = mock(SkitAdRevenueEventMapper.class);
        SkitFrozenCommissionProjectionService projection = mock(SkitFrozenCommissionProjectionService.class);
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        SkitAdRewardVerificationExpiryServiceImpl service = service(
                sessionMapper, eventMapper, projection, transactions, 10);
        SkitAdRewardExpiryClaimDO withImpression = claim(7101L, 7111L, 7121L);
        SkitAdRewardExpiryClaimDO withoutImpression = claim(7201L, 7211L, 7221L);
        SkitAdSessionDO firstSession = pendingSession(withImpression, 7131L, 7141L,
                "IMPRESSION_PENDING_REWARD", 3);
        SkitAdSessionDO secondSession = pendingSession(withoutImpression, 7231L, 7241L,
                "NONE", 4);
        SkitAdRevenueEventDO impression = pendingImpression(firstSession, 7151L);

        TenantContextHolder.setTenantId(999L);
        when(sessionMapper.selectExpiredRewardClaims(10)).thenAnswer(invocation -> {
            assertTrue(TenantContextHolder.isIgnore());
            assertEquals(999L, TenantContextHolder.getRequiredTenantId());
            return Arrays.asList(withImpression, withoutImpression);
        });
        when(sessionMapper.selectByTenantAccountAndIdForUpdate(7101L, 7111L, 7121L))
                .thenAnswer(invocation -> assertTenantAndReturn(7101L, firstSession));
        when(sessionMapper.selectByTenantAccountAndIdForUpdate(7201L, 7211L, 7221L))
                .thenAnswer(invocation -> assertTenantAndReturn(7201L, secondSession));
        when(eventMapper.selectByTenantSessionAndSourceForUpdate(7101L, 7121L, "TAKU_IMPRESSION"))
                .thenReturn(impression);
        when(eventMapper.selectByTenantSessionAndSourceForUpdate(7201L, 7221L, "TAKU_IMPRESSION"))
                .thenReturn(null);
        when(eventMapper.markNonRewardedFrozenOnTimeoutCas(
                7101L, 7151L, 7121L, 7111L, 0)).thenReturn(1);
        when(sessionMapper.markRewardVerifyTimeoutByAccountCas(
                7101L, 7121L, 7111L, 7131L, 3,
                "IMPRESSION_PENDING_REWARD", "FROZEN")).thenReturn(1);
        when(sessionMapper.markRewardVerifyTimeoutByAccountCas(
                7201L, 7221L, 7211L, 7231L, 4,
                "NONE", "NONE")).thenReturn(1);

        assertEquals(2, service.sweepOnce());

        assertFalse(TenantContextHolder.isIgnore());
        assertEquals(999L, TenantContextHolder.getRequiredTenantId());
        assertEquals("NON_REWARDED", impression.getRewardQualificationStatus());
        assertEquals(1, impression.getVersion());
        verify(projection).projectNonRewardedEstimate(impression);
        assertEquals(3, transactions.definitions.size(),
                "one global route projection and one transaction per candidate");
        for (TransactionDefinition definition : transactions.definitions) {
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                    definition.getPropagationBehavior());
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,
                    definition.getIsolationLevel());
        }
    }

    @Test
    void aReceiptOrIrreversibleTerminalDecisionAlwaysWinsOverTimeout() {
        SkitAdSessionMapper sessionMapper = mock(SkitAdSessionMapper.class);
        SkitAdRevenueEventMapper eventMapper = mock(SkitAdRevenueEventMapper.class);
        SkitFrozenCommissionProjectionService projection = mock(SkitFrozenCommissionProjectionService.class);
        List<SkitAdRewardExpiryClaimDO> claims = Arrays.asList(
                claim(7301L, 7311L, 7321L),
                claim(7301L, 7311L, 7322L),
                claim(7301L, 7311L, 7323L));
        when(sessionMapper.selectExpiredRewardClaims(10)).thenReturn(claims);

        SkitAdSessionDO receiptWon = pendingSession(claims.get(0), 7331L, 7341L, "NONE", 1)
                .setRewardCallbackInboxId(7351L).setRewardCallbackReceivedAt(FIXTURE_TIME.minusMinutes(2));
        SkitAdSessionDO signed = pendingSession(claims.get(1), 7331L, 7341L, "FROZEN", 2)
                .setRewardVerificationStatus("SIGNED_VERIFIED").setEntitlementStatus("GRANTED")
                .setActiveScopeHash(null).setActiveScopeReleasedAt(FIXTURE_TIME.minusMinutes(1))
                .setActiveScopeReleaseReason("ENTITLEMENT_GRANTED");
        SkitAdSessionDO rejected = pendingSession(claims.get(2), 7331L, 7341L, "SUSPENSE", 3)
                .setRewardVerificationStatus("REJECTED").setActiveScopeHash(null)
                .setActiveScopeReleasedAt(FIXTURE_TIME.minusMinutes(1))
                .setActiveScopeReleaseReason("REWARD_REJECTED");
        when(sessionMapper.selectByTenantAccountAndIdForUpdate(7301L, 7311L, 7321L)).thenReturn(receiptWon);
        when(sessionMapper.selectByTenantAccountAndIdForUpdate(7301L, 7311L, 7322L)).thenReturn(signed);
        when(sessionMapper.selectByTenantAccountAndIdForUpdate(7301L, 7311L, 7323L)).thenReturn(rejected);

        assertEquals(0, service(sessionMapper, eventMapper, projection,
                new RecordingTransactionManager(), 10).sweepOnce());

        verify(eventMapper, never()).markNonRewardedFrozenOnTimeoutCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any(Integer.class));
        verify(sessionMapper, never()).markRewardVerifyTimeoutByAccountCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any(Integer.class),
                any(String.class), any(String.class));
        verify(projection, never()).projectNonRewardedEstimate(any());
    }

    @Test
    void terminalReceiptCandidateDelegatesToSharedCompensationInsideItsTenantTransaction() {
        SkitAdSessionMapper sessionMapper = mock(SkitAdSessionMapper.class);
        SkitAdRevenueEventMapper eventMapper = mock(SkitAdRevenueEventMapper.class);
        SkitAdRewardReceiptResolutionService resolver =
                mock(SkitAdRewardReceiptResolutionService.class);
        SkitFrozenCommissionProjectionService projection =
                mock(SkitFrozenCommissionProjectionService.class);
        SkitAdRewardExpiryClaimDO claim = claim(7351L, 7361L, 7371L);
        SkitAdSessionDO session = pendingSession(claim, 7381L, 7391L, "NONE", 2)
                .setRewardCallbackInboxId(7401L)
                .setRewardCallbackReceivedAt(FIXTURE_TIME.minusMinutes(2));
        when(sessionMapper.selectExpiredRewardClaims(1))
                .thenReturn(Collections.singletonList(claim));
        when(sessionMapper.selectByTenantAccountAndIdForUpdate(7351L, 7361L, 7371L))
                .thenReturn(session);
        when(sessionMapper.selectDatabaseNow()).thenReturn(FIXTURE_TIME);
        when(resolver.resolveTerminalReceipt(session, FIXTURE_TIME)).thenAnswer(invocation -> {
            assertEquals(7351L, TenantContextHolder.getRequiredTenantId());
            return true;
        });

        assertEquals(1, service(sessionMapper, eventMapper, resolver, projection,
                new RecordingTransactionManager(), 1).sweepOnce());

        verify(resolver).resolveTerminalReceipt(session, FIXTURE_TIME);
        verify(eventMapper, never()).selectByTenantSessionAndSourceForUpdate(
                anyLong(), anyLong(), any(String.class));
        verify(sessionMapper, never()).markRewardVerifyTimeoutByAccountCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any(Integer.class),
                any(String.class), any(String.class));
    }

    @Test
    void malformedTerminalReceiptCannotStarveALaterClaim() {
        SkitAdSessionMapper sessionMapper = mock(SkitAdSessionMapper.class);
        SkitAdRevenueEventMapper eventMapper = mock(SkitAdRevenueEventMapper.class);
        SkitAdRewardReceiptResolutionService resolver =
                mock(SkitAdRewardReceiptResolutionService.class);
        SkitFrozenCommissionProjectionService projection =
                mock(SkitFrozenCommissionProjectionService.class);
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        SkitAdRewardExpiryClaimDO malformed = claim(7361L, 7371L, 7381L);
        SkitAdRewardExpiryClaimDO healthy = claim(7391L, 7401L, 7411L);
        SkitAdSessionDO malformedSession = pendingSession(
                malformed, 7421L, 7431L, "NONE", 2)
                .setRewardCallbackInboxId(7441L)
                .setRewardCallbackReceivedAt(FIXTURE_TIME.minusMinutes(2));
        SkitAdSessionDO healthySession = pendingSession(
                healthy, 7451L, 7461L, "NONE", 3)
                .setRewardCallbackInboxId(7471L)
                .setRewardCallbackReceivedAt(FIXTURE_TIME.minusMinutes(2));
        when(sessionMapper.selectExpiredRewardClaims(2))
                .thenReturn(Arrays.asList(malformed, healthy));
        when(sessionMapper.selectByTenantAccountAndIdForUpdate(7361L, 7371L, 7381L))
                .thenReturn(malformedSession);
        when(sessionMapper.selectByTenantAccountAndIdForUpdate(7391L, 7401L, 7411L))
                .thenReturn(healthySession);
        when(sessionMapper.selectDatabaseNow()).thenReturn(FIXTURE_TIME);
        when(resolver.resolveTerminalReceipt(malformedSession, FIXTURE_TIME))
                .thenThrow(new IllegalStateException("malformed terminal receipt"));
        when(resolver.resolveTerminalReceipt(healthySession, FIXTURE_TIME)).thenReturn(true);

        assertEquals(1, service(sessionMapper, eventMapper, resolver, projection,
                transactions, 2).sweepOnce());

        verify(resolver).resolveTerminalReceipt(malformedSession, FIXTURE_TIME);
        verify(resolver).resolveTerminalReceipt(healthySession, FIXTURE_TIME);
        assertEquals(3, transactions.definitions.size(),
                "the route projection and both claims use independent transactions");
        assertEquals(2, transactions.commits);
        assertEquals(1, transactions.rollbacks);
    }

    @Test
    void anImpressionMustRemainPendingAndBoundToTheSameImmutableEnvelope() {
        SkitAdSessionMapper sessionMapper = mock(SkitAdSessionMapper.class);
        SkitAdRevenueEventMapper eventMapper = mock(SkitAdRevenueEventMapper.class);
        SkitFrozenCommissionProjectionService projection = mock(SkitFrozenCommissionProjectionService.class);
        SkitAdRewardExpiryClaimDO claim = claim(7401L, 7411L, 7421L);
        SkitAdSessionDO session = pendingSession(claim, 7431L, 7441L,
                "IMPRESSION_PENDING_REWARD", 2);
        SkitAdRevenueEventDO forged = pendingImpression(session, 7451L).setAdAccountId(999999L);
        when(sessionMapper.selectExpiredRewardClaims(1)).thenReturn(Collections.singletonList(claim));
        when(sessionMapper.selectByTenantAccountAndIdForUpdate(7401L, 7411L, 7421L)).thenReturn(session);
        when(eventMapper.selectByTenantSessionAndSourceForUpdate(7401L, 7421L, "TAKU_IMPRESSION"))
                .thenReturn(forged);

        RecordingTransactionManager transactions = new RecordingTransactionManager();
        assertEquals(0, service(sessionMapper, eventMapper, projection,
                transactions, 1).sweepOnce());

        verify(sessionMapper, never()).markRewardVerifyTimeoutByAccountCas(
                anyLong(), anyLong(), anyLong(), anyLong(), any(Integer.class),
                any(String.class), any(String.class));
        verify(projection, never()).projectNonRewardedEstimate(any());
        assertEquals(1, transactions.commits);
        assertEquals(1, transactions.rollbacks);
    }

    @Test
    void aLostTimeoutCasRollsBackTheEventTransitionAndNeverProjectsMoney() {
        SkitAdSessionMapper sessionMapper = mock(SkitAdSessionMapper.class);
        SkitAdRevenueEventMapper eventMapper = mock(SkitAdRevenueEventMapper.class);
        SkitFrozenCommissionProjectionService projection = mock(SkitFrozenCommissionProjectionService.class);
        SkitAdRewardExpiryClaimDO claim = claim(7501L, 7511L, 7521L);
        SkitAdSessionDO session = pendingSession(claim, 7531L, 7541L,
                "IMPRESSION_PENDING_REWARD", 5);
        SkitAdRevenueEventDO event = pendingImpression(session, 7551L);
        when(sessionMapper.selectExpiredRewardClaims(1)).thenReturn(Collections.singletonList(claim));
        when(sessionMapper.selectByTenantAccountAndIdForUpdate(7501L, 7511L, 7521L)).thenReturn(session);
        when(eventMapper.selectByTenantSessionAndSourceForUpdate(7501L, 7521L, "TAKU_IMPRESSION"))
                .thenReturn(event);
        when(eventMapper.markNonRewardedFrozenOnTimeoutCas(7501L, 7551L, 7521L, 7511L, 0))
                .thenReturn(1);
        when(sessionMapper.markRewardVerifyTimeoutByAccountCas(
                7501L, 7521L, 7511L, 7531L, 5,
                "IMPRESSION_PENDING_REWARD", "FROZEN")).thenReturn(0);

        RecordingTransactionManager transactions = new RecordingTransactionManager();
        assertEquals(0, service(sessionMapper, eventMapper, projection,
                transactions, 1).sweepOnce());

        verify(projection, never()).projectNonRewardedEstimate(any());
        assertEquals(1, transactions.commits);
        assertEquals(1, transactions.rollbacks);
    }

    private static SkitAdRewardVerificationExpiryServiceImpl service(
            SkitAdSessionMapper sessionMapper, SkitAdRevenueEventMapper eventMapper,
            SkitFrozenCommissionProjectionService projection,
            PlatformTransactionManager transactionManager, int batchSize) {
        SkitAdRewardReceiptResolutionService resolver =
                mock(SkitAdRewardReceiptResolutionService.class);
        when(sessionMapper.selectDatabaseNow()).thenReturn(FIXTURE_TIME);
        return service(sessionMapper, eventMapper, resolver, projection,
                transactionManager, batchSize);
    }

    private static SkitAdRewardVerificationExpiryServiceImpl service(
            SkitAdSessionMapper sessionMapper, SkitAdRevenueEventMapper eventMapper,
            SkitAdRewardReceiptResolutionService resolver,
            SkitFrozenCommissionProjectionService projection,
            PlatformTransactionManager transactionManager, int batchSize) {
        return new SkitAdRewardVerificationExpiryServiceImpl(sessionMapper, eventMapper,
                resolver, projection, transactionManager, batchSize);
    }

    private static SkitAdRewardExpiryClaimDO claim(long tenantId, long accountId, long sessionId) {
        return new SkitAdRewardExpiryClaimDO().setTenantId(tenantId)
                .setAdAccountId(accountId).setId(sessionId);
    }

    private static SkitAdSessionDO pendingSession(SkitAdRewardExpiryClaimDO claim,
                                                  long memberId, long snapshotId,
                                                  String revenueStatus, int version) {
        SkitAdSessionDO session = new SkitAdSessionDO()
                .setId(claim.getId()).setAdAccountId(claim.getAdAccountId())
                .setMemberId(memberId).setPolicySnapshotId(snapshotId)
                .setRewardVerificationStatus("PENDING").setEntitlementStatus("NONE")
                .setRevenueStatus(revenueStatus).setRewardAcceptUntil(FIXTURE_TIME.minusSeconds(1))
                .setRewardCallbackInboxId(null).setRewardCallbackReceivedAt(null)
                .setActiveScopeHash(new byte[32]).setActiveScopeReleasedAt(null)
                .setActiveScopeReleaseReason(null).setVersion(version);
        session.setTenantId(claim.getTenantId());
        return session;
    }

    private static SkitAdRevenueEventDO pendingImpression(SkitAdSessionDO session, long eventId) {
        SkitAdRevenueEventDO event = new SkitAdRevenueEventDO()
                .setId(eventId).setAdAccountId(session.getAdAccountId())
                .setAdSessionId(session.getId()).setSourceMemberId(session.getMemberId())
                .setPolicySnapshotId(session.getPolicySnapshotId()).setSourceType("TAKU_IMPRESSION")
                .setRewardQualificationStatus("PENDING_REWARD")
                .setSourceVerificationStatus("UNSIGNED_OBSERVATION")
                .setReconciliationStatus("FROZEN").setLegacyUnverified(false).setVersion(0);
        event.setTenantId(session.getTenantId());
        return event;
    }

    private static <T> T assertTenantAndReturn(long tenantId, T value) {
        assertFalse(TenantContextHolder.isIgnore());
        assertEquals(tenantId, TenantContextHolder.getRequiredTenantId());
        return value;
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private final List<TransactionDefinition> definitions = new ArrayList<>();
        private int commits;
        private int rollbacks;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            definitions.add(definition);
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            commits++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks++;
        }
    }

}
