package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Per-member aggregate of settled (reconciled + rewarded) ad consumption.
 * Money is exposed as exact integer units at {@code amountScale} so the client
 * can render with BigInt-safe arithmetic; no formatted money string is needed.
 */
@Data
public class SkitAdConsumptionMemberRespVO {

    private Long tenantId;
    private Long memberId;
    private String memberNickname;
    private String memberMobileMasked;
    private String currency;
    private Integer amountScale;
    /** Number of settled impressions (reconciled + rewarded revenue events). */
    private Long settledImpressionCount = 0L;
    /** Exact integer units of the settled amount, rendered at amountScale. */
    private Long settledAmountUnits;
    /** Exact integer units of the platform estimate for the same impressions. */
    private Long estimatedAmountUnits;
    private LocalDateTime firstConsumedAt;
    private LocalDateTime lastConsumedAt;

}
