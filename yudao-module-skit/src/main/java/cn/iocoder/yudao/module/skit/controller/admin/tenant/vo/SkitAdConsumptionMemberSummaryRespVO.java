package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Overview of the settled per-member ad consumption view. Only events that
 * settled normally (reconciled + rewarded, trusted, non-mock) are counted.
 */
@Data
public class SkitAdConsumptionMemberSummaryRespVO {

    private Long tenantId;
    private LocalDateTime asOf;
    private String timezone;
    /** Distinct members with at least one settled impression in the window. */
    private Long memberCount = 0L;
    private Long settledImpressionCount = 0L;
    private List<CurrencyAmount> currencyGroups = new ArrayList<>();

    @Data
    public static class CurrencyAmount {
        private String currency;
        private Integer amountScale;
        private Long settledImpressionCount;
        private Long settledAmountUnits;
        private Long estimatedAmountUnits;
    }

}
