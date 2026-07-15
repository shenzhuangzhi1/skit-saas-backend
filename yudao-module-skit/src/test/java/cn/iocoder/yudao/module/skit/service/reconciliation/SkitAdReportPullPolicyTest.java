package cn.iocoder.yudao.module.skit.service.reconciliation;

import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdReportPullPolicyTest {

    @Test
    void successfulAccountIsNotEligibleAgainBeforeTheTwoHourProviderRefreshCadence() {
        assertEquals(7_200, SkitAdReportPullServiceImpl.SUCCESS_DELAY_SECONDS);
        assertEquals(300, SkitAdReportPullServiceImpl.FAILURE_BASE_BACKOFF_SECONDS);
        assertEquals(3_600, SkitAdReportPullServiceImpl.FAILURE_MAX_BACKOFF_SECONDS);
    }

    @Test
    void providerWindowsArePulledInStableD1D2D3OrderAndOnlyD3IsMature() {
        LocalDate today = LocalDate.of(2026, 7, 15);

        List<SkitAdReportPullServiceImpl.ProviderWindow> windows =
                SkitAdReportPullServiceImpl.providerWindows(today);

        assertEquals(3, windows.size());
        assertEquals(today.minusDays(1), windows.get(0).getReportDate());
        assertEquals(today.minusDays(2), windows.get(1).getReportDate());
        assertEquals(today.minusDays(3), windows.get(2).getReportDate());
        assertFalse(windows.get(0).isFinalRevision());
        assertFalse(windows.get(1).isFinalRevision());
        assertTrue(windows.get(2).isFinalRevision());
    }

    @Test
    void disabledAccountPullsOnlyMatureD3SoItsPendingWindowsDrainFinitely() {
        LocalDate today = LocalDate.of(2026, 7, 15);

        List<SkitAdReportPullServiceImpl.ProviderWindow> windows =
                SkitAdReportPullServiceImpl.providerWindows(today, 1);

        assertEquals(1, windows.size());
        assertEquals(today.minusDays(3), windows.get(0).getReportDate());
        assertTrue(windows.get(0).isFinalRevision());
    }

    @Test
    void platformMaturityNeverHidesPartialOrSuspenseAttribution() {
        assertEquals("APPLIED", SkitAdReportPullServiceImpl.revisionStatus("RECONCILED"));
        assertEquals("PARTIAL", SkitAdReportPullServiceImpl.revisionStatus("PARTIAL"));
        assertEquals("SUSPENSE", SkitAdReportPullServiceImpl.revisionStatus("SUSPENSE"));
    }

    @Test
    void onlyAPriorFormalSettlementIsUnwoundWhenALaterRevisionBecomesSuspense() {
        SkitAdRevenueEventDO settled = new SkitAdRevenueEventDO()
                .setReconciliationRevisionId(71L).setReconciliationBucketId(61L)
                .setReconciledAmountUnits(50L).setSourceVerificationStatus("REPORT_CONFIRMED")
                .setReconciliationStatus("RECONCILED");
        SkitAdRevenueEventDO firstSuspense = new SkitAdRevenueEventDO()
                .setReconciliationRevisionId(72L).setReconciliationBucketId(62L)
                .setReconciledAmountUnits(0L).setSourceVerificationStatus("UNSIGNED_OBSERVATION")
                .setReconciliationStatus("SUSPENSE");

        assertTrue(SkitAdReportPullServiceImpl.hasPriorFormalSettlement(settled));
        assertFalse(SkitAdReportPullServiceImpl.hasPriorFormalSettlement(firstSuspense));
    }
}
