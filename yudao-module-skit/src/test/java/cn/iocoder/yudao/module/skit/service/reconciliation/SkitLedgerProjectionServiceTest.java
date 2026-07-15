package cn.iocoder.yudao.module.skit.service.reconciliation;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.reconciliation.SkitAdReconciliationAllocationDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitCommissionLedgerDO;
import cn.iocoder.yudao.module.skit.dal.mysql.reconciliation.SkitAdReconciliationAllocationMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitCommissionLedgerMapper;
import cn.iocoder.yudao.module.skit.service.commission.SkitMoneyAllocator;
import cn.iocoder.yudao.module.skit.service.commission.SkitPolicySnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.AGENT_BENEFICIARY_ID;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.AGENT_LEDGER_LEVEL;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.BENEFICIARY_AGENT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.BENEFICIARY_MEMBER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitLedgerProjectionServiceTest {

    private static final long TENANT_ID = 17L;
    private static final long EVENT_ID = 91L;
    private static final long SNAPSHOT_ID = 73L;

    private SkitCommissionLedgerMapper ledgerMapper;
    private SkitAdReconciliationAllocationMapper allocationMapper;
    private SkitPolicySnapshotService snapshotService;
    private SkitLedgerProjectionService service;
    private final Map<String, SkitCommissionLedgerDO> ledger = new LinkedHashMap<>();
    private final Map<String, SkitAdReconciliationAllocationDO> allocations = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(TENANT_ID);
        ledgerMapper = mock(SkitCommissionLedgerMapper.class);
        allocationMapper = mock(SkitAdReconciliationAllocationMapper.class);
        snapshotService = mock(SkitPolicySnapshotService.class);
        service = new SkitLedgerProjectionServiceImpl(ledgerMapper, allocationMapper,
                snapshotService, new SkitMoneyAllocator());
        SkitPolicySnapshotService.PolicySnapshot policySnapshot = snapshot();
        when(snapshotService.getRequired(SNAPSHOT_ID)).thenReturn(policySnapshot);
        installPersistenceDoubles();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void firstRevisionReleasesEveryFrozenEstimateThenSettlesActualTargets() {
        seedFrozenEstimates(100L, 50L, 20L, 30L);

        SkitLedgerProjectionService.ProjectionResult result = service.project(
                command(700L, 1, rewardedEvent(), 80L));

        assertEquals(6, result.getLedgerEntryCount());
        assertLedger("ESTIMATE_RELEASE", 0, BENEFICIARY_MEMBER, 42L, 0, -50L);
        assertLedger("ESTIMATE_RELEASE", 0, BENEFICIARY_MEMBER, 84L, 1, -20L);
        assertLedger("ESTIMATE_RELEASE", 0, BENEFICIARY_AGENT, 0L, -1, -30L);
        assertLedger("SETTLEMENT", 1, BENEFICIARY_MEMBER, 42L, 0, 40L);
        assertLedger("SETTLEMENT", 1, BENEFICIARY_MEMBER, 84L, 1, 16L);
        assertLedger("SETTLEMENT", 1, BENEFICIARY_AGENT, 0L, -1, 24L);
        assertEquals(80L, allocations.values().stream()
                .mapToLong(SkitAdReconciliationAllocationDO::getCumulativeTargetUnits).sum());
    }

    @Test
    void laterDownwardRevisionAppendsOnlyTargetDeltaAdjustments() {
        seedFrozenEstimates(100L, 50L, 20L, 30L);
        service.project(command(700L, 1, rewardedEvent(), 80L));

        SkitLedgerProjectionService.ProjectionResult result = service.project(
                command(701L, 2, rewardedEvent(), 50L));

        assertEquals(3, result.getLedgerEntryCount());
        assertLedger("ADJUSTMENT", 2, BENEFICIARY_MEMBER, 42L, 0, -15L);
        assertLedger("ADJUSTMENT", 2, BENEFICIARY_MEMBER, 84L, 1, -6L);
        assertLedger("ADJUSTMENT", 2, BENEFICIARY_AGENT, 0L, -1, -9L);
        assertEquals(3L, ledger.values().stream()
                .filter(row -> "ESTIMATE_RELEASE".equals(row.getEntryType())).count());
        assertEquals(50L, allocations.values().stream()
                .filter(row -> row.getRevisionNo() == 2)
                .mapToLong(SkitAdReconciliationAllocationDO::getCumulativeTargetUnits).sum());

        SkitLedgerProjectionService.ProjectionResult suspenseUnwind = service.project(
                command(702L, 3, rewardedEvent(), 0L));
        assertEquals(3, suspenseUnwind.getLedgerEntryCount());
        assertLedger("ADJUSTMENT", 3, BENEFICIARY_MEMBER, 42L, 0, -25L);
        assertLedger("ADJUSTMENT", 3, BENEFICIARY_MEMBER, 84L, 1, -10L);
        assertLedger("ADJUSTMENT", 3, BENEFICIARY_AGENT, 0L, -1, -15L);
        assertEquals(0L, allocations.values().stream()
                .filter(row -> row.getRevisionNo() == 3)
                .mapToLong(SkitAdReconciliationAllocationDO::getCumulativeTargetUnits).sum());
    }

    @Test
    void nonRewardedActualRevenueIsOneHundredPercentAgentRetention() {
        seedFrozenAgentEstimate(100L);

        service.project(command(800L, 1,
                rewardedEvent().setRewardQualificationStatus("NON_REWARDED"), 79L));

        assertLedger("ESTIMATE_RELEASE", 0, BENEFICIARY_AGENT, 0L, -1, -100L);
        assertLedger("SETTLEMENT", 1, BENEFICIARY_AGENT, 0L, -1, 79L);
        // The original frozen estimate remains append-only alongside its release and settlement.
        assertEquals(3, ledger.size());
    }

    @Test
    void duplicateRevisionAndPartialRetryAreCanonicalAndIdempotent() {
        seedFrozenEstimates(100L, 50L, 20L, 30L);
        SkitLedgerProjectionService.ProjectionCommand command =
                command(700L, 1, rewardedEvent(), 80L);

        service.project(command);
        int rowsAfterFirst = ledger.size();
        int allocationsAfterFirst = allocations.size();
        service.project(command);

        assertEquals(rowsAfterFirst, ledger.size());
        assertEquals(allocationsAfterFirst, allocations.size());
        assertEquals(80L, allocations.values().stream()
                .mapToLong(SkitAdReconciliationAllocationDO::getCumulativeTargetUnits).sum());
    }

    @Test
    void crossTenantOrConflictingCumulativeTargetFailsBeforeNewMoney() {
        seedFrozenAgentEstimate(100L);
        SkitAdRevenueEventDO foreign = rewardedEvent();
        foreign.setTenantId(TENANT_ID + 1);
        assertThrows(IllegalStateException.class,
                () -> service.project(command(700L, 1, foreign, 80L)));

        SkitAdReconciliationAllocationDO conflict = allocation(700L, 1,
                BENEFICIARY_MEMBER, 42L, 0, 99L);
        allocations.put(allocationKey(conflict), conflict);
        assertThrows(IllegalStateException.class,
                () -> service.project(command(700L, 1, rewardedEvent(), 80L)));
    }

    @Test
    void unsafeEventEnvelopeNeverWritesLedgerOrAllocation() {
        SkitAdRevenueEventDO event = rewardedEvent().setLegacyUnverified(true);

        assertThrows(IllegalStateException.class,
                () -> service.project(command(700L, 1, event, 80L)));

        verify(ledgerMapper, never()).insertCanonicalReconciliationEntry(any());
        verify(allocationMapper, never()).insertCanonical(any());
    }

    private void installPersistenceDoubles() {
        AtomicLong ids = new AtomicLong(1_000L);
        doAnswer(invocation -> {
            SkitCommissionLedgerDO row = invocation.getArgument(0);
            String key = ledgerKey(row);
            SkitCommissionLedgerDO existing = ledger.get(key);
            if (existing == null) {
                row.setId(ids.incrementAndGet());
                ledger.put(key, row);
            } else {
                row.setId(existing.getId());
            }
            return 1;
        }).when(ledgerMapper).insertCanonicalReconciliationEntry(any(SkitCommissionLedgerDO.class));
        when(ledgerMapper.selectCanonicalEntryForUpdate(anyLong(), anyLong(), anyInt(), anyLong(),
                anyInt(), anyString(), anyInt())).thenAnswer(invocation -> ledger.get(String.join("|",
                String.valueOf((Object) invocation.getArgument(1)),
                String.valueOf((Object) invocation.getArgument(2)),
                String.valueOf((Object) invocation.getArgument(3)),
                String.valueOf((Object) invocation.getArgument(4)),
                String.valueOf((Object) invocation.getArgument(5)),
                String.valueOf((Object) invocation.getArgument(6)))));
        when(ledgerMapper.selectEntriesForEventAndTypeForUpdate(TENANT_ID, EVENT_ID, "ESTIMATE"))
                .thenAnswer(invocation -> ledger.values().stream()
                        .filter(row -> "ESTIMATE".equals(row.getEntryType()))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));

        doAnswer(invocation -> {
            SkitAdReconciliationAllocationDO row = invocation.getArgument(0);
            String key = allocationKey(row);
            SkitAdReconciliationAllocationDO existing = allocations.get(key);
            if (existing == null) {
                row.setId(ids.incrementAndGet());
                allocations.put(key, row);
            } else {
                row.setId(existing.getId());
            }
            return 1;
        }).when(allocationMapper).insertCanonical(any(SkitAdReconciliationAllocationDO.class));
        when(allocationMapper.selectCanonicalForUpdate(anyLong(), anyLong(), anyLong(), anyInt(),
                anyInt(), anyLong(), anyInt(), anyLong())).thenAnswer(invocation -> allocations.get(
                String.join("|", String.valueOf((Object) invocation.getArgument(1)),
                        String.valueOf((Object) invocation.getArgument(2)),
                        String.valueOf((Object) invocation.getArgument(3)),
                        String.valueOf((Object) invocation.getArgument(4)),
                        String.valueOf((Object) invocation.getArgument(5)),
                        String.valueOf((Object) invocation.getArgument(6)),
                        String.valueOf((Object) invocation.getArgument(7)))));
        when(allocationMapper.selectLatestBeforeRevisionForUpdate(anyLong(), anyLong(), anyInt(),
                anyLong(), anyInt(), anyInt())).thenAnswer(invocation -> allocations.values().stream()
                .filter(row -> row.getTenantId().equals(invocation.getArgument(0)))
                .filter(row -> row.getEventId().equals(invocation.getArgument(1)))
                .filter(row -> row.getBeneficiaryType().equals(invocation.getArgument(2)))
                .filter(row -> row.getBeneficiaryMemberId().equals(invocation.getArgument(3)))
                .filter(row -> row.getLevelNo().equals(invocation.getArgument(4)))
                .filter(row -> row.getRevisionNo() < (Integer) invocation.getArgument(5))
                .max((left, right) -> Integer.compare(left.getRevisionNo(), right.getRevisionNo()))
                .orElse(null));
    }

    private void seedFrozenEstimates(long gross, long viewer, long ancestor, long agent) {
        seedEstimate(BENEFICIARY_MEMBER, 42L, 0, 5_000, gross, viewer);
        seedEstimate(BENEFICIARY_MEMBER, 84L, 1, 2_000, gross, ancestor);
        seedEstimate(BENEFICIARY_AGENT, AGENT_BENEFICIARY_ID, AGENT_LEDGER_LEVEL,
                3_000, gross, agent);
    }

    private void seedFrozenAgentEstimate(long gross) {
        seedEstimate(BENEFICIARY_AGENT, AGENT_BENEFICIARY_ID, AGENT_LEDGER_LEVEL,
                10_000, gross, gross);
    }

    private void seedEstimate(int type, long member, int level, int rate,
                              long gross, long amount) {
        SkitCommissionLedgerDO row = ledgerRow("ESTIMATE", 0, type, member, level,
                rate, gross, amount, null);
        row.setId((long) ledger.size() + 1L);
        ledger.put(ledgerKey(row), row);
    }

    private void assertLedger(String entryType, int revision, int type,
                              long member, int level, long units) {
        SkitCommissionLedgerDO row = ledger.get(String.join("|", String.valueOf(EVENT_ID),
                String.valueOf(type), String.valueOf(member), String.valueOf(level),
                entryType, String.valueOf(revision)));
        if (row == null) {
            throw new AssertionError("missing ledger row " + entryType + "/" + revision + "/" + member);
        }
        assertEquals(units, row.getAmountUnits().longValue());
        assertEquals("ESTIMATE_RELEASE".equals(entryType) ? "FROZEN" : "AVAILABLE",
                row.getBalanceBucket());
        assertEquals(Boolean.FALSE, row.getLegacyUnverified());
    }

    private String ledgerKey(SkitCommissionLedgerDO row) {
        return String.join("|", String.valueOf(row.getEventId()),
                String.valueOf(row.getBeneficiaryType()), String.valueOf(row.getBeneficiaryMemberId()),
                String.valueOf(row.getLevelNo()), row.getEntryType(), String.valueOf(row.getRevisionNo()));
    }

    private String allocationKey(SkitAdReconciliationAllocationDO row) {
        return String.join("|", String.valueOf(row.getEventId()),
                String.valueOf(row.getReconciliationRevisionId()), String.valueOf(row.getRevisionNo()),
                String.valueOf(row.getBeneficiaryType()), String.valueOf(row.getBeneficiaryMemberId()),
                String.valueOf(row.getLevelNo()), String.valueOf(row.getPolicySnapshotId()));
    }

    private SkitAdReconciliationAllocationDO allocation(long revisionId, int revision,
                                                         int type, long member, int level, long target) {
        SkitAdReconciliationAllocationDO row = new SkitAdReconciliationAllocationDO()
                .setReconciliationBucketId(600L).setReconciliationRevisionId(revisionId)
                .setRevisionNo(revision).setEventId(EVENT_ID).setBeneficiaryType(type)
                .setBeneficiaryMemberId(member).setLevelNo(level).setPolicySnapshotId(SNAPSHOT_ID)
                .setCurrency("USD").setAmountScale(8).setCumulativeTargetUnits(target);
        row.setTenantId(TENANT_ID);
        return row;
    }

    private SkitLedgerProjectionService.ProjectionCommand command(long revisionId, int revisionNo,
                                                                   SkitAdRevenueEventDO event,
                                                                   long target) {
        return new SkitLedgerProjectionService.ProjectionCommand(TENANT_ID, 600L, revisionId,
                revisionNo, event, target);
    }

    private SkitAdRevenueEventDO rewardedEvent() {
        SkitAdRevenueEventDO event = SkitAdRevenueEventDO.builder()
                .id(EVENT_ID).adAccountId(29L).adSessionId(55L).callbackInboxId(92L)
                .provider("TAKU").sourceMemberId(42L).policySnapshotId(SNAPSHOT_ID)
                .ruleVersion(4).sourceType("TAKU_IMPRESSION")
                .estimatedAmountUnits(100L).amountScale(8).sourceCurrency("USD")
                .matchStatus("MATCHED").sourceVerificationStatus("UNSIGNED_OBSERVATION")
                .rewardQualificationStatus("REWARDED").reconciliationStatus("FROZEN")
                .legacyUnverified(false).build();
        event.setTenantId(TENANT_ID);
        return event;
    }

    private SkitCommissionLedgerDO ledgerRow(String entryType, int revision, int type,
                                              long member, int level, int rate, long gross,
                                              long amount, Long reconciliationRevisionId) {
        SkitCommissionLedgerDO row = SkitCommissionLedgerDO.builder()
                .eventId(EVENT_ID).beneficiaryType(type).beneficiaryMemberId(member).levelNo(level)
                .grossAmount(BigDecimal.valueOf(gross, 8)).rateBps(rate)
                .amount(BigDecimal.valueOf(amount, 8)).ruleVersion(4).status(0)
                .entryType(entryType).balanceBucket("ESTIMATE".equals(entryType)
                        || "ESTIMATE_RELEASE".equals(entryType) ? "FROZEN" : "AVAILABLE")
                .currency("USD").grossAmountUnits(gross).amountUnits(amount).amountScale(8)
                .reconciliationRevisionId(reconciliationRevisionId).policySnapshotId(SNAPSHOT_ID)
                .revisionNo(revision).legacyUnverified(false).build();
        row.setTenantId(TENANT_ID);
        return row;
    }

    private SkitPolicySnapshotService.PolicySnapshot snapshot() {
        SkitPolicySnapshotService.BeneficiarySlot viewer =
                mock(SkitPolicySnapshotService.BeneficiarySlot.class);
        when(viewer.getLevel()).thenReturn(0);
        when(viewer.getRateBps()).thenReturn(5_000);
        when(viewer.getMemberId()).thenReturn(42L);
        when(viewer.isEligible()).thenReturn(true);
        SkitPolicySnapshotService.BeneficiarySlot ancestor =
                mock(SkitPolicySnapshotService.BeneficiarySlot.class);
        when(ancestor.getLevel()).thenReturn(1);
        when(ancestor.getRateBps()).thenReturn(2_000);
        when(ancestor.getMemberId()).thenReturn(84L);
        when(ancestor.isEligible()).thenReturn(true);
        SkitPolicySnapshotService.PolicySnapshot snapshot =
                mock(SkitPolicySnapshotService.PolicySnapshot.class);
        when(snapshot.getId()).thenReturn(SNAPSHOT_ID);
        when(snapshot.getTenantId()).thenReturn(TENANT_ID);
        when(snapshot.getSourceMemberId()).thenReturn(42L);
        when(snapshot.getRuleVersion()).thenReturn(4);
        when(snapshot.getEligibleRateBps()).thenReturn(7_000);
        when(snapshot.getBeneficiaries()).thenReturn(Arrays.asList(viewer, ancestor));
        return snapshot;
    }
}
