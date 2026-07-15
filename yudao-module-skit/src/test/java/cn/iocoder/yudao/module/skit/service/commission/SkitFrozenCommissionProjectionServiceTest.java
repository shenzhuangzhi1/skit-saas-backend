package cn.iocoder.yudao.module.skit.service.commission;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitCommissionLedgerDO;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitCommissionLedgerMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.AGENT_BENEFICIARY_ID;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.AGENT_LEDGER_LEVEL;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.BENEFICIARY_AGENT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.BENEFICIARY_MEMBER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkitFrozenCommissionProjectionServiceTest {

    private static final long TENANT_ID = 17L;
    private static final long EVENT_ID = 91L;
    private static final long SNAPSHOT_ID = 73L;
    private static final long VIEWER_ID = 42L;

    private SkitCommissionLedgerMapper ledgerMapper;
    private SkitPolicySnapshotService snapshotService;
    private SkitFrozenCommissionProjectionService service;
    private List<SkitCommissionLedgerDO> canonicalRows;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(TENANT_ID);
        ledgerMapper = mock(SkitCommissionLedgerMapper.class);
        snapshotService = mock(SkitPolicySnapshotService.class);
        canonicalRows = new ArrayList<>();
        service = new SkitFrozenCommissionProjectionServiceImpl(
                ledgerMapper, snapshotService, new SkitMoneyAllocator());
        when(snapshotService.getRequired(SNAPSHOT_ID)).thenReturn(snapshot(
                eligible(0, VIEWER_ID, 5_000), eligible(1, 84L, 2_000)));
        AtomicLong ids = new AtomicLong(500L);
        doAnswer(invocation -> {
            SkitCommissionLedgerDO candidate = invocation.getArgument(0);
            candidate.setId(ids.incrementAndGet());
            canonicalRows.add(candidate);
            return 1;
        }).when(ledgerMapper).insertCanonicalEstimate(any(SkitCommissionLedgerDO.class));
        when(ledgerMapper.selectCanonicalEntryForUpdate(anyLong(), anyLong(), anyInt(), anyLong(),
                anyInt(), anyString(), anyInt())).thenAnswer(invocation -> canonicalRows.stream()
                .filter(row -> row.getTenantId().equals(invocation.getArgument(0)))
                .filter(row -> row.getEventId().equals(invocation.getArgument(1)))
                .filter(row -> row.getBeneficiaryType().equals(invocation.getArgument(2)))
                .filter(row -> row.getBeneficiaryMemberId().equals(invocation.getArgument(3)))
                .filter(row -> row.getLevelNo().equals(invocation.getArgument(4)))
                .filter(row -> row.getEntryType().equals(invocation.getArgument(5)))
                .filter(row -> row.getRevisionNo().equals(invocation.getArgument(6)))
                .findFirst().orElse(null));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void rewardedEstimateUsesImmutableSnapshotAndPreservesEveryIntegerUnit() {
        SkitAdRevenueEventDO event = rewardedEvent(1_000_003L, 6);

        SkitFrozenCommissionProjectionService.ProjectionResult result =
                service.projectRewardedEstimate(event);

        ArgumentCaptor<SkitCommissionLedgerDO> rows =
                ArgumentCaptor.forClass(SkitCommissionLedgerDO.class);
        verify(ledgerMapper, org.mockito.Mockito.times(3)).insertCanonicalEstimate(rows.capture());
        List<SkitCommissionLedgerDO> values = rows.getAllValues();
        assertLedger(values.get(0), BENEFICIARY_MEMBER, VIEWER_ID, 0, 5_000,
                1_000_003L, 500_001L);
        assertLedger(values.get(1), BENEFICIARY_MEMBER, 84L, 1, 2_000,
                1_000_003L, 200_000L);
        assertLedger(values.get(2), BENEFICIARY_AGENT, AGENT_BENEFICIARY_ID,
                AGENT_LEDGER_LEVEL, 3_000, 1_000_003L, 300_002L);
        assertEquals(1_000_003L, values.stream().mapToLong(SkitCommissionLedgerDO::getAmountUnits).sum());
        assertEquals(3, result.getEntryCount());
        assertEquals(1_000_003L, result.getProjectedAmountUnits());
    }

    @Test
    void ineligibleOrMissingAncestorSharesAndFloorRemaindersStayWithAgent() {
        when(snapshotService.getRequired(SNAPSHOT_ID)).thenReturn(snapshot(
                eligible(0, VIEWER_ID, 4_000),
                ineligible(1, 84L, 3_000, SkitPolicySnapshotService.EligibilityReason.DISABLED),
                ineligible(2, null, 1_000,
                        SkitPolicySnapshotService.EligibilityReason.MISSING_ANCESTOR)));

        service.projectRewardedEstimate(rewardedEvent(101L, 6));

        ArgumentCaptor<SkitCommissionLedgerDO> rows =
                ArgumentCaptor.forClass(SkitCommissionLedgerDO.class);
        verify(ledgerMapper, org.mockito.Mockito.times(2)).insertCanonicalEstimate(rows.capture());
        assertLedger(rows.getAllValues().get(0), BENEFICIARY_MEMBER, VIEWER_ID, 0, 4_000,
                101L, 40L);
        assertLedger(rows.getAllValues().get(1), BENEFICIARY_AGENT, AGENT_BENEFICIARY_ID,
                AGENT_LEDGER_LEVEL, 6_000, 101L, 61L);
    }

    @Test
    void nonRewardedImpressionCreatesOnlyAgentFrozenEstimate() {
        SkitAdRevenueEventDO event = rewardedEvent(12_345L, 9)
                .setRewardQualificationStatus("NON_REWARDED");

        service.projectNonRewardedEstimate(event);

        ArgumentCaptor<SkitCommissionLedgerDO> row =
                ArgumentCaptor.forClass(SkitCommissionLedgerDO.class);
        verify(ledgerMapper).insertCanonicalEstimate(row.capture());
        assertLedger(row.getValue(), BENEFICIARY_AGENT, AGENT_BENEFICIARY_ID,
                AGENT_LEDGER_LEVEL, 10_000, 12_345L, 12_345L);
    }

    @Test
    void exactExistingEntryIsIdempotentButConflictingCanonicalEntryFailsClosed() {
        SkitAdRevenueEventDO event = rewardedEvent(100L, 6)
                .setRewardQualificationStatus("NON_REWARDED");
        SkitCommissionLedgerDO existing = expectedAgentRow(event, 100L, 10_000);
        when(ledgerMapper.selectCanonicalEntryForUpdate(TENANT_ID, EVENT_ID, BENEFICIARY_AGENT,
                AGENT_BENEFICIARY_ID, AGENT_LEDGER_LEVEL, "ESTIMATE", 0))
                .thenReturn(existing);

        SkitFrozenCommissionProjectionService.ProjectionResult first =
                service.projectNonRewardedEstimate(event);

        assertEquals(1, first.getEntryCount());
        existing.setAmountUnits(99L);
        assertThrows(IllegalStateException.class,
                () -> service.projectNonRewardedEstimate(event));
    }

    @Test
    void tenantOrSnapshotEnvelopeMismatchNeverWritesLedger() {
        SkitAdRevenueEventDO event = rewardedEvent(100L, 6);
        event.setTenantId(TENANT_ID + 1);
        assertThrows(IllegalStateException.class, () -> service.projectRewardedEstimate(event));

        event.setTenantId(TENANT_ID);
        when(snapshotService.getRequired(SNAPSHOT_ID)).thenReturn(snapshotForTenant(TENANT_ID + 1,
                eligible(0, VIEWER_ID, 5_000)));
        assertThrows(IllegalStateException.class, () -> service.projectRewardedEstimate(event));

        verify(ledgerMapper, never()).insertCanonicalEstimate(any());
    }

    @Test
    void pendingRewardOrUnverifiedEventCannotCreateMoney() {
        SkitAdRevenueEventDO pending = rewardedEvent(100L, 6)
                .setRewardQualificationStatus("PENDING_REWARD");
        assertThrows(IllegalStateException.class, () -> service.projectRewardedEstimate(pending));

        SkitAdRevenueEventDO legacy = rewardedEvent(100L, 6).setLegacyUnverified(true);
        assertThrows(IllegalStateException.class, () -> service.projectRewardedEstimate(legacy));
        verify(ledgerMapper, never()).insertCanonicalEstimate(any());
    }

    @Test
    void nullVerificationFlagOrIncompleteSourceEnvelopeCannotCreateMoney() {
        SkitAdRevenueEventDO nullVerification = rewardedEvent(100L, 6)
                .setLegacyUnverified(null);
        assertThrows(IllegalStateException.class,
                () -> service.projectRewardedEstimate(nullVerification));

        SkitAdRevenueEventDO missingAccount = rewardedEvent(100L, 6)
                .setAdAccountId(null);
        assertThrows(IllegalStateException.class,
                () -> service.projectRewardedEstimate(missingAccount));

        SkitAdRevenueEventDO missingSession = rewardedEvent(100L, 6)
                .setAdSessionId(null);
        assertThrows(IllegalStateException.class,
                () -> service.projectRewardedEstimate(missingSession));

        SkitAdRevenueEventDO missingInbox = rewardedEvent(100L, 6)
                .setCallbackInboxId(null);
        assertThrows(IllegalStateException.class,
                () -> service.projectRewardedEstimate(missingInbox));

        verify(ledgerMapper, never()).insertCanonicalEstimate(any());
    }

    @Test
    void conflictingLegacyMirrorOrAppendOnlyReferencesFailClosed() {
        SkitAdRevenueEventDO event = rewardedEvent(100L, 6)
                .setRewardQualificationStatus("NON_REWARDED");
        SkitCommissionLedgerDO conflicting = expectedAgentRow(event, 100L, 10_000)
                .setAmount(new BigDecimal("0.00000001"))
                .setReversalOfId(88L);
        when(ledgerMapper.selectCanonicalEntryForUpdate(TENANT_ID, EVENT_ID, BENEFICIARY_AGENT,
                AGENT_BENEFICIARY_ID, AGENT_LEDGER_LEVEL, "ESTIMATE", 0))
                .thenReturn(conflicting);

        assertThrows(IllegalStateException.class,
                () -> service.projectNonRewardedEstimate(event));
    }

    @Test
    void canonicalLookupCannotResurrectLogicallyDeletedLedgerRows() throws Exception {
        Method method = SkitCommissionLedgerMapper.class.getMethod(
                "selectCanonicalEntryForUpdate", long.class, long.class, int.class,
                long.class, int.class, String.class, int.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ").toLowerCase();

        assertTrue(sql.contains("`deleted`=b'0'"));
    }

    private void assertLedger(SkitCommissionLedgerDO row, int beneficiaryType, long memberId,
                              int level, int rateBps, long grossAmountUnits, long amountUnits) {
        assertEquals(Long.valueOf(TENANT_ID), row.getTenantId());
        assertEquals(Long.valueOf(EVENT_ID), row.getEventId());
        assertEquals(Integer.valueOf(beneficiaryType), row.getBeneficiaryType());
        assertEquals(Long.valueOf(memberId), row.getBeneficiaryMemberId());
        assertEquals(Integer.valueOf(level), row.getLevelNo());
        assertEquals(Integer.valueOf(rateBps), row.getRateBps());
        assertEquals(Long.valueOf(grossAmountUnits), row.getGrossAmountUnits());
        assertEquals(Long.valueOf(amountUnits), row.getAmountUnits());
        assertEquals("ESTIMATE", row.getEntryType());
        assertEquals("FROZEN", row.getBalanceBucket());
        assertEquals("USD", row.getCurrency());
        assertEquals(Long.valueOf(SNAPSHOT_ID), row.getPolicySnapshotId());
        assertEquals(Integer.valueOf(0), row.getRevisionNo());
        assertEquals(Boolean.FALSE, row.getLegacyUnverified());
    }

    private SkitCommissionLedgerDO expectedAgentRow(SkitAdRevenueEventDO event,
                                                     long amountUnits, int rateBps) {
        SkitCommissionLedgerDO row = SkitCommissionLedgerDO.builder()
                .id(701L).eventId(event.getId()).beneficiaryType(BENEFICIARY_AGENT)
                .beneficiaryMemberId(AGENT_BENEFICIARY_ID).levelNo(AGENT_LEDGER_LEVEL)
                .grossAmount(decimal(event.getEstimatedAmountUnits(), event.getAmountScale()))
                .rateBps(rateBps).amount(decimal(amountUnits, event.getAmountScale()))
                .ruleVersion(event.getRuleVersion()).status(0).entryType("ESTIMATE")
                .balanceBucket("FROZEN").currency(event.getSourceCurrency())
                .grossAmountUnits(event.getEstimatedAmountUnits()).amountUnits(amountUnits)
                .amountScale(event.getAmountScale()).policySnapshotId(event.getPolicySnapshotId())
                .revisionNo(0).legacyUnverified(false).build();
        row.setTenantId(TENANT_ID);
        return row;
    }

    private SkitAdRevenueEventDO rewardedEvent(long amountUnits, int scale) {
        SkitAdRevenueEventDO event = SkitAdRevenueEventDO.builder()
                .id(EVENT_ID).adAccountId(29L).adSessionId(55L).callbackInboxId(92L)
                .provider("TAKU").sourceMemberId(VIEWER_ID).policySnapshotId(SNAPSHOT_ID)
                .ruleVersion(4).sourceType("TAKU_IMPRESSION")
                .estimatedAmountUnits(amountUnits).amountScale(scale).sourceCurrency("USD")
                .matchStatus("MATCHED").sourceVerificationStatus("UNSIGNED_OBSERVATION")
                .rewardQualificationStatus("REWARDED").reconciliationStatus("FROZEN")
                .legacyUnverified(false).build();
        event.setTenantId(TENANT_ID);
        return event;
    }

    private SkitPolicySnapshotService.PolicySnapshot snapshot(
            SkitPolicySnapshotService.BeneficiarySlot... slots) {
        return snapshotForTenant(TENANT_ID, slots);
    }

    private SkitPolicySnapshotService.PolicySnapshot snapshotForTenant(
            long tenantId, SkitPolicySnapshotService.BeneficiarySlot... slots) {
        int configured = Arrays.stream(slots).mapToInt(
                SkitPolicySnapshotService.BeneficiarySlot::getRateBps).sum();
        int eligible = Arrays.stream(slots).filter(
                SkitPolicySnapshotService.BeneficiarySlot::isEligible).mapToInt(
                SkitPolicySnapshotService.BeneficiarySlot::getRateBps).sum();
        List<SkitPolicySnapshotService.ChainNode> chain = new ArrayList<>();
        for (SkitPolicySnapshotService.BeneficiarySlot slot : slots) {
            if (slot.getMemberId() != null) {
                chain.add(new SkitPolicySnapshotService.ChainNode(slot.getLevel(), slot.getMemberId(),
                        slot.getMemberStatus(), slot.isEligible(), slot.getReason()));
            }
        }
        return new SkitPolicySnapshotService.PolicySnapshot(SNAPSHOT_ID, tenantId, 0, 31L,
                VIEWER_ID, 4, 1, "{}", new byte[32], LocalDateTime.of(2026, 7, 14, 20, 0),
                chain, Arrays.asList(slots), configured, eligible);
    }

    private static SkitPolicySnapshotService.BeneficiarySlot eligible(
            int level, long memberId, int rateBps) {
        return new SkitPolicySnapshotService.BeneficiarySlot(level, rateBps, memberId, 0,
                true, SkitPolicySnapshotService.EligibilityReason.ELIGIBLE);
    }

    private static SkitPolicySnapshotService.BeneficiarySlot ineligible(
            int level, Long memberId, int rateBps,
            SkitPolicySnapshotService.EligibilityReason reason) {
        return new SkitPolicySnapshotService.BeneficiarySlot(level, rateBps, memberId,
                memberId == null ? null : 1, false, reason);
    }

    private static BigDecimal decimal(long units, int scale) {
        return BigDecimal.valueOf(units, scale).setScale(8, java.math.RoundingMode.DOWN);
    }

}
