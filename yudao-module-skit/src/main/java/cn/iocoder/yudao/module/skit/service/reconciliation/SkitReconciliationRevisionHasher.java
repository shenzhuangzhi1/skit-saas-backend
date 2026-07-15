package cn.iocoder.yudao.module.skit.service.reconciliation;

import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Builds the immutable allocation-input identity for an append-only reconciliation revision. */
final class SkitReconciliationRevisionHasher {

    private static final int ALLOCATION_INPUT_VERSION = 1;

    private SkitReconciliationRevisionHasher() {
    }

    static byte[] hash(long tenantId, long adAccountId, String bucketKey, LocalDate reportDate,
                       long reportActualUnits, Long reportImpressions, String currency,
                       int amountScale, boolean finalRevision, boolean dimensionsComplete,
                       List<SkitAdRevenueEventDO> suppliedEvents) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, ALLOCATION_INPUT_VERSION, tenantId, adAccountId, bucketKey,
                reportDate, reportActualUnits,
                reportImpressions, currency, amountScale, finalRevision);
        append(canonical, dimensionsComplete);
        List<SkitAdRevenueEventDO> events = new ArrayList<>(suppliedEvents);
        events.sort(Comparator.comparingLong(SkitAdRevenueEventDO::getId));
        for (SkitAdRevenueEventDO event : events) {
            requireImmutableAllocationInput(event);
            append(canonical, event.getId(), event.getEstimatedAmountUnits(),
                    event.getRewardQualificationStatus(), event.getSourceMemberId(),
                    event.getPolicySnapshotId(), event.getCallbackInboxId());
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requireImmutableAllocationInput(SkitAdRevenueEventDO event) {
        if (event == null || event.getId() == null || event.getId() <= 0L
                || event.getEstimatedAmountUnits() == null || event.getEstimatedAmountUnits() < 0L
                || (!"REWARDED".equals(event.getRewardQualificationStatus())
                && !"NON_REWARDED".equals(event.getRewardQualificationStatus()))
                || event.getSourceMemberId() == null || event.getSourceMemberId() <= 0L
                || event.getPolicySnapshotId() == null || event.getPolicySnapshotId() <= 0L
                || event.getCallbackInboxId() == null || event.getCallbackInboxId() <= 0L) {
            throw new IllegalStateException("Reconciliation revision input is incomplete");
        }
    }

    private static void append(StringBuilder target, Object... values) {
        for (Object value : values) {
            String text = String.valueOf(value);
            target.append(text.length()).append(':').append(text);
        }
    }
}
