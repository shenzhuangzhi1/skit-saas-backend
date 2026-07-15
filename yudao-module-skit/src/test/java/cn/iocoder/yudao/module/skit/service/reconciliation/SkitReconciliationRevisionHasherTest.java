package cn.iocoder.yudao.module.skit.service.reconciliation;

import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SkitReconciliationRevisionHasherTest {

    @Test
    void replayOfSameAllocationInputsIsStableRegardlessOfQueryOrderOrMutableProjectionState() {
        SkitAdRevenueEventDO first = event(11L, 70L, "REWARDED", 101L, 501L, 701L);
        SkitAdRevenueEventDO second = event(12L, 30L, "NON_REWARDED", 102L, 502L, 702L);

        byte[] original = hash(false, first, second);
        first.setVersion(99).setSourceVerificationStatus("REPORT_CONFIRMED")
                .setReconciliationStatus("RECONCILED").setReconciledAmountUnits(700L);
        second.setVersion(100).setReconciliationStatus("SUSPENSE");

        assertArrayEquals(original, hash(false, second, first));
    }

    @Test
    void lateEventOrChangedImmutableAllocationInputAppendsANewRevision() {
        SkitAdRevenueEventDO first = event(11L, 70L, "REWARDED", 101L, 501L, 701L);
        byte[] original = hash(false, first);

        assertFalse(Arrays.equals(original, hash(false, first,
                event(12L, 30L, "REWARDED", 102L, 502L, 702L))));
        assertFalse(Arrays.equals(original, hash(false,
                event(11L, 71L, "REWARDED", 101L, 501L, 701L))));
        assertFalse(Arrays.equals(original, hash(false,
                event(11L, 70L, "NON_REWARDED", 101L, 501L, 701L))));
        assertFalse(Arrays.equals(original, hash(false,
                event(11L, 70L, "REWARDED", 101L, 999L, 701L))));
    }

    @Test
    void providerMaturityIsAnIndependentRevisionInput() {
        SkitAdRevenueEventDO event = event(11L, 70L, "REWARDED", 101L, 501L, 701L);

        assertFalse(Arrays.equals(hash(false, event), hash(true, event)));
    }

    @Test
    void dimensionAmbiguityIsAnIndependentFailClosedRevisionInput() {
        SkitAdRevenueEventDO event = event(11L, 70L, "REWARDED", 101L, 501L, 701L);

        assertFalse(Arrays.equals(hash(false, true, event), hash(false, false, event)));
    }

    private byte[] hash(boolean finalRevision, SkitAdRevenueEventDO... events) {
        return hash(finalRevision, true, events);
    }

    private byte[] hash(boolean finalRevision, boolean dimensionsComplete,
                        SkitAdRevenueEventDO... events) {
        return SkitReconciliationRevisionHasher.hash(31L, 41L, "bucket-key",
                LocalDate.of(2026, 7, 12), 10_000L, 2L, "USD", 8,
                finalRevision, dimensionsComplete, Arrays.asList(events));
    }

    private SkitAdRevenueEventDO event(long id, long estimate, String reward,
                                       long memberId, long snapshotId, long inboxId) {
        return new SkitAdRevenueEventDO().setId(id).setEstimatedAmountUnits(estimate)
                .setRewardQualificationStatus(reward).setSourceMemberId(memberId)
                .setPolicySnapshotId(snapshotId).setCallbackInboxId(inboxId)
                .setVersion(0).setSourceVerificationStatus("UNSIGNED_OBSERVATION")
                .setReconciliationStatus("FROZEN");
    }
}
