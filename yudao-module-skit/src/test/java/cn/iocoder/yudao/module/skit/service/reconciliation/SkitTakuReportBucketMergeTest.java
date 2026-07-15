package cn.iocoder.yudao.module.skit.service.reconciliation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitTakuReportBucketMergeTest {

    @Test
    void oneCallbackRouteWithMultipleNetworkAccountsIsMarkedMixedAndCannotAllocate() {
        TakuReportingClient.ReportResponse response = response(
                record("network-a", "1.25", 2L), record("network-b", "2.75", 3L));

        List<SkitAdReportPullServiceImpl.MergedRecord> merged =
                SkitAdReportPullServiceImpl.merge(response, 2);

        assertEquals(2, merged.size());
        assertFalse(merged.get(0).isDimensionsComplete());
        assertFalse(merged.get(1).isDimensionsComplete());
    }

    @Test
    void duplicateRowsForTheSameFullDimensionMergeWithoutLosingMoneyOrImpressions() {
        TakuReportingClient.ReportResponse response = response(
                record("network-a", "1.25", 2L), record("network-a", "2.75", 3L));

        List<SkitAdReportPullServiceImpl.MergedRecord> merged =
                SkitAdReportPullServiceImpl.merge(response, 2);

        assertEquals(1, merged.size());
        assertEquals(400L, merged.get(0).getActualUnits());
        assertEquals("network-a", merged.get(0).getNetworkAccountId());
        assertTrue(merged.get(0).isDimensionsComplete());
    }

    private TakuReportingClient.ReportResponse response(TakuReportingClient.ReportRecord... records) {
        return new TakuReportingClient.ReportResponse("UTC+8", "USD", Arrays.asList(records));
    }

    private TakuReportingClient.ReportRecord record(String networkAccount, String revenue,
                                                     Long impressions) {
        return new TakuReportingClient.ReportRecord(LocalDate.of(2026, 7, 14), "app", "placement",
                "rewarded_video", 7, networkAccount, "adsource", revenue, impressions);
    }
}
