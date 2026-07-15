package cn.iocoder.yudao.module.skit.service.commission;

import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;

/**
 * Appends the revision-zero frozen ledger projection for one verified impression event.
 */
public interface SkitFrozenCommissionProjectionService {

    ProjectionResult projectRewardedEstimate(SkitAdRevenueEventDO event);

    ProjectionResult projectNonRewardedEstimate(SkitAdRevenueEventDO event);

    final class ProjectionResult {

        private final int entryCount;
        private final long projectedAmountUnits;

        ProjectionResult(int entryCount, long projectedAmountUnits) {
            this.entryCount = entryCount;
            this.projectedAmountUnits = projectedAmountUnits;
        }

        public int getEntryCount() {
            return entryCount;
        }

        public long getProjectedAmountUnits() {
            return projectedAmountUnits;
        }
    }

}
