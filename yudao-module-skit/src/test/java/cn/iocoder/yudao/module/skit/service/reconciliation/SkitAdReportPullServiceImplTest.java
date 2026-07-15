package cn.iocoder.yudao.module.skit.service.reconciliation;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation.SkitAdReportPullDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.reconciliation.SkitAdReconciliationBucketMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.reconciliation.SkitAdReconciliationRevisionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.reconciliation.SkitAdReconciliationEventLinkMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.reconciliation.SkitAdReportPullMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitAdRevenueEventMapper;
import cn.iocoder.yudao.module.skit.framework.observability.SkitAdReportPullObservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitAdReportPullServiceImplTest {

    private static final long TENANT_ID = 17L;
    private static final long ACCOUNT_ID = 29L;
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-15T02:10:00Z"), ZoneOffset.UTC);

    @Test
    void successfulAccountPullsD1D2D3SeriallyAndMovesTheAccountTwoHoursForward() {
        Fixture fixture = fixture(null);

        assertEquals(1, fixture.service.pullDueAccounts());

        assertEquals(3, fixture.client.requests.size());
        assertEquals(LocalDate.of(2026, 7, 14), fixture.client.requests.get(0).getStartDate());
        assertEquals(LocalDate.of(2026, 7, 13), fixture.client.requests.get(1).getStartDate());
        assertEquals(LocalDate.of(2026, 7, 12), fixture.client.requests.get(2).getStartDate());
        assertEquals(3, fixture.pulls.size());
        assertFalse(fixture.pulls.get(0).getFinalWindow());
        assertFalse(fixture.pulls.get(1).getFinalWindow());
        assertTrue(fixture.pulls.get(2).getFinalWindow());
        assertEquals(3, fixture.credentials.markedVersions.size());
        verify(fixture.accountMapper).selectDueReportRoutes(3);
        verify(fixture.accountMapper).completeReportPullLeaseCas(
                TENANT_ID, ACCOUNT_ID, "report-worker", 7_200);
        verify(fixture.accountMapper, never()).failReportPullLeaseCas(
                anyLong(), anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    void oneFailedDateDoesNotRollbackSuccessfulDatesAndUsesBoundedFailureBackoff() {
        Fixture fixture = fixture(LocalDate.of(2026, 7, 14));

        assertEquals(1, fixture.service.pullDueAccounts());

        assertEquals(3, fixture.client.requests.size());
        assertEquals(3, fixture.pulls.size());
        assertEquals(LocalDate.of(2026, 7, 14), fixture.pulls.get(0).getReportDate());
        assertEquals("FAILED", fixture.pulls.get(0).getStatus());
        assertEquals("UPSTREAM_FAILURE", fixture.pulls.get(0).getErrorCode());
        assertEquals(LocalDate.of(2026, 7, 13), fixture.pulls.get(1).getReportDate());
        assertEquals(LocalDate.of(2026, 7, 12), fixture.pulls.get(2).getReportDate());
        assertFalse(fixture.pulls.get(1).getFinalWindow());
        assertTrue(fixture.pulls.get(2).getFinalWindow());
        assertEquals(2, fixture.credentials.markedVersions.size());
        assertEquals(1.0D, fixture.metrics.get(SkitAdReportPullObservation.COUNTER_NAME)
                .tag("provider", "TAKU").tag("outcome", "failure").counter().count());
        assertEquals(2.0D, fixture.metrics.get(SkitAdReportPullObservation.COUNTER_NAME)
                .tag("provider", "TAKU").tag("outcome", "success").counter().count());
        verify(fixture.accountMapper).failReportPullLeaseCas(
                TENANT_ID, ACCOUNT_ID, "report-worker", 300, 3_600);
        verify(fixture.accountMapper, never()).completeReportPullLeaseCas(
                anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void downtimeCatchUpAddsAtMostTwoOldestMatureDatesBeforeRegularWindows() {
        Fixture fixture = fixture(null);
        when(fixture.eventMapper.selectHistoricalPendingEventTimes(
                eq(TENANT_ID), eq(ACCOUNT_ID), any(), eq(64)))
                .thenReturn(java.util.Arrays.asList(
                        LocalDateTime.of(2026, 7, 7, 18, 0),
                        LocalDateTime.of(2026, 7, 8, 18, 0),
                        LocalDateTime.of(2026, 7, 9, 18, 0)));
        when(fixture.pullMapper.selectPendingFinalReportDates(
                TENANT_ID, ACCOUNT_ID, LocalDate.of(2026, 7, 12), "UTC+8", "USD", 6, 8))
                .thenReturn(Collections.singletonList(LocalDate.of(2026, 7, 8)));

        assertEquals(1, fixture.service.pullDueAccounts());

        assertEquals(5, fixture.client.requests.size());
        assertEquals(LocalDate.of(2026, 7, 8), fixture.client.requests.get(0).getStartDate());
        assertEquals(LocalDate.of(2026, 7, 9), fixture.client.requests.get(1).getStartDate());
        assertEquals(LocalDate.of(2026, 7, 14), fixture.client.requests.get(2).getStartDate());
        assertTrue(fixture.pulls.get(0).getFinalWindow());
        assertTrue(fixture.pulls.get(1).getFinalWindow());
    }

    private Fixture fixture(LocalDate failedDate) {
        SkitAdAccountMapper accountMapper = mock(SkitAdAccountMapper.class);
        SkitAdReportPullMapper pullMapper = mock(SkitAdReportPullMapper.class);
        SkitAdReconciliationBucketMapper bucketMapper = mock(SkitAdReconciliationBucketMapper.class);
        SkitAdReconciliationRevisionMapper revisionMapper = mock(SkitAdReconciliationRevisionMapper.class);
        SkitAdReconciliationEventLinkMapper eventLinkMapper =
                mock(SkitAdReconciliationEventLinkMapper.class);
        SkitAdRevenueEventMapper eventMapper = mock(SkitAdRevenueEventMapper.class);
        SkitLedgerProjectionService ledger = mock(SkitLedgerProjectionService.class);
        FakeCredentialService credentials = new FakeCredentialService();
        FakeReportingClient client = new FakeReportingClient(failedDate);
        List<SkitAdReportPullDO> pulls = new ArrayList<>();
        SkitAdAccountDO route = account(null);
        SkitAdAccountDO locked = account("report-worker");
        when(accountMapper.selectDueReportRoutes(3)).thenReturn(Collections.singletonList(route));
        when(accountMapper.claimReportPullLeaseCas(TENANT_ID, ACCOUNT_ID,
                "report-worker", 900)).thenReturn(1);
        when(accountMapper.selectReportAccountForUpdate(TENANT_ID, ACCOUNT_ID)).thenReturn(locked);
        when(accountMapper.completeReportPullLeaseCas(TENANT_ID, ACCOUNT_ID,
                "report-worker", 7_200)).thenReturn(1);
        when(accountMapper.failReportPullLeaseCas(TENANT_ID, ACCOUNT_ID,
                "report-worker", 300, 3_600)).thenReturn(1);
        when(eventMapper.selectReportEventRoutesForUpdate(anyLong(), anyLong(), anyString(),
                any(), any())).thenReturn(Collections.emptyList());
        when(eventMapper.selectHistoricalPendingEventTimes(anyLong(), anyLong(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(pullMapper.selectPendingFinalReportDates(anyLong(), anyLong(), any(), anyString(),
                anyString(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
        when(pullMapper.insert(any())).thenAnswer(invocation -> {
            SkitAdReportPullDO pull = invocation.getArgument(0);
            pull.setId(100L + pulls.size());
            pulls.add(pull);
            return 1;
        });
        when(pullMapper.selectCanonicalForUpdate(anyLong(), anyLong(), any(), any(),
                any(), any(), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> pulls.get(pulls.size() - 1));
        PlatformTransactionManager transactions = new NoOpTransactionManager();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        SkitAdReportPullServiceImpl service = new SkitAdReportPullServiceImpl(
                accountMapper, pullMapper, bucketMapper, revisionMapper, eventLinkMapper, eventMapper,
                credentials, client, new SkitReconciliationAllocator(), ledger,
                new ObjectMapper(), transactions, CLOCK, () -> "report-worker",
                new SkitAdReportPullObservation(metrics));
        return new Fixture(service, accountMapper, pullMapper, eventMapper,
                credentials, client, pulls, metrics);
    }

    private SkitAdAccountDO account(String leaseOwner) {
        SkitAdAccountDO result = SkitAdAccountDO.builder()
                .id(ACCOUNT_ID).provider("TAKU").accountId("publisher-account")
                .appId("app-id")
                .configData("{\"placementId\":\"placement-id\","
                        + "\"adFormat\":\"rewarded_video\"}")
                .status(0).reportTimezone("UTC+8").reportCurrency("USD")
                .reportAmountScale(6).reportPullLeaseOwner(leaseOwner)
                .reportFailureCount(0).build();
        result.setTenantId(TENANT_ID);
        return result;
    }

    private static final class FakeCredentialService implements SkitReportingCredentialService {
        private final List<Integer> markedVersions = new ArrayList<>();

        @Override
        public Metadata configure(long tenantId, long adAccountId, byte[] publisherKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Metadata getMetadata(long tenantId, long adAccountId) {
            return new Metadata(tenantId, adAccountId, 4, true,
                    LocalDateTime.of(2026, 7, 15, 2, 0));
        }

        @Override
        public <T> T withActivePublisherKey(long tenantId, long adAccountId,
                                            Function<byte[], T> consumer) {
            return consumer.apply(new byte[]{1});
        }

        @Override
        public <T> T withActivePublisherKeyVersion(long tenantId, long adAccountId,
                                                   BiFunction<Integer, byte[], T> consumer) {
            return consumer.apply(4, new byte[]{1});
        }

        @Override
        public void markPermissionVerified(long tenantId, long adAccountId,
                                           int credentialVersion) {
            markedVersions.add(credentialVersion);
        }
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // Mapper calls are mocked; the unit test only needs TransactionTemplate boundaries.
        }

        @Override
        public void rollback(TransactionStatus status) {
            // Mapper calls are mocked; the unit test only needs TransactionTemplate boundaries.
        }
    }

    private static final class FakeReportingClient implements TakuReportingClient {
        private final LocalDate failedDate;
        private final List<ReportRequest> requests = new ArrayList<>();

        private FakeReportingClient(LocalDate failedDate) {
            this.failedDate = failedDate;
        }

        @Override
        public ReportResponse fetch(ReportRequest request, byte[] publisherKey) {
            requests.add(request);
            if (request.getStartDate().equals(failedDate)) {
                throw new IllegalStateException("fixture failure");
            }
            return new ReportResponse(request.getReportTimezone(), request.getCurrency(),
                    Collections.emptyList());
        }
    }

    private static final class Fixture {
        private final SkitAdReportPullServiceImpl service;
        private final SkitAdAccountMapper accountMapper;
        private final SkitAdReportPullMapper pullMapper;
        private final SkitAdRevenueEventMapper eventMapper;
        private final FakeCredentialService credentials;
        private final FakeReportingClient client;
        private final List<SkitAdReportPullDO> pulls;
        private final SimpleMeterRegistry metrics;

        private Fixture(SkitAdReportPullServiceImpl service,
                        SkitAdAccountMapper accountMapper,
                        SkitAdReportPullMapper pullMapper,
                        SkitAdRevenueEventMapper eventMapper,
                        FakeCredentialService credentials,
                        FakeReportingClient client,
                        List<SkitAdReportPullDO> pulls,
                        SimpleMeterRegistry metrics) {
            this.service = service;
            this.accountMapper = accountMapper;
            this.pullMapper = pullMapper;
            this.eventMapper = eventMapper;
            this.credentials = credentials;
            this.client = client;
            this.pulls = pulls;
            this.metrics = metrics;
        }
    }
}
