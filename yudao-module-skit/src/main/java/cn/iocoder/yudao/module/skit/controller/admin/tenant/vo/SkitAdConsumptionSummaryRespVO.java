package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class SkitAdConsumptionSummaryRespVO {

    private Long tenantId;
    private LocalDateTime asOf;
    private String timezone;
    private Long sessionCount = 0L;
    /** Count backed by an observed client SHOWN event, not an inferred SDK load. */
    private Long clientShownCount = 0L;
    private Long clientRewardObservedCount = 0L;
    private Long signedVerifiedCount = 0L;
    /** Signed callbacks within the client-reward-observed cohort; safe numerator for that funnel. */
    private Long signedVerifiedAndClientObservedCount = 0L;
    private Long entitledCount = 0L;
    /** Sessions that entered through a real tenant-bound native grant; not grants issued by this ad. */
    private Long nativeGrantAccessCount = 0L;
    private Long failedCount = 0L;
    private Long earlyClosedCount = 0L;
    /** Platform impression facts that have a trusted revenue event. */
    private Long platformImpressionCount = 0L;
    private List<CurrencyAmount> currencyGroups = new ArrayList<>();

    @Data
    public static class CurrencyAmount {
        private String currency;
        private Integer amountScale;
        private Long platformImpressionCount;
        private String estimatedAmount;
        private String reconciledAmount;
        private String estimatedEcpm;
        private String reconciledEcpm;
    }

}
