package cn.iocoder.yudao.module.skit.service.reconciliation;

import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;

public interface SkitLedgerProjectionService {

    ProjectionResult project(ProjectionCommand command);

    final class ProjectionCommand {
        private final long tenantId;
        private final long reconciliationBucketId;
        private final long reconciliationRevisionId;
        private final int revisionNo;
        private final SkitAdRevenueEventDO event;
        private final long cumulativeActualUnits;

        public ProjectionCommand(long tenantId, long reconciliationBucketId,
                                 long reconciliationRevisionId, int revisionNo,
                                 SkitAdRevenueEventDO event, long cumulativeActualUnits) {
            this.tenantId = tenantId;
            this.reconciliationBucketId = reconciliationBucketId;
            this.reconciliationRevisionId = reconciliationRevisionId;
            this.revisionNo = revisionNo;
            this.event = event;
            this.cumulativeActualUnits = cumulativeActualUnits;
        }

        public long getTenantId() { return tenantId; }
        public long getReconciliationBucketId() { return reconciliationBucketId; }
        public long getReconciliationRevisionId() { return reconciliationRevisionId; }
        public int getRevisionNo() { return revisionNo; }
        public SkitAdRevenueEventDO getEvent() { return event; }
        public long getCumulativeActualUnits() { return cumulativeActualUnits; }
    }

    final class ProjectionResult {
        private final int ledgerEntryCount;
        private final int allocationCount;

        public ProjectionResult(int ledgerEntryCount, int allocationCount) {
            this.ledgerEntryCount = ledgerEntryCount;
            this.allocationCount = allocationCount;
        }

        public int getLedgerEntryCount() { return ledgerEntryCount; }
        public int getAllocationCount() { return allocationCount; }
    }
}
