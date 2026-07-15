package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class SkitMemberSubtreeSummaryRespVO {

    private Long tenantId;
    private Long memberId;
    private LocalDateTime asOf;
    private String timezone;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String currency;
    private String provider;
    private String statisticBasis;
    private Long memberCount;
    private Long descendantCount;
    private Long contributingMemberCount;
    private Long rewardedEventCount;
    /** One exact bucket per persisted amount scale; different scales are never summed together. */
    private List<MoneySummary> amounts = new ArrayList<>();

    @Data
    public static class MoneySummary {
        private Integer amountScale;
        private String grossRevenue;
        private String grossRevenueUnits;
        private String memberAllocation;
        private String memberAllocationUnits;
        private String agentRetention;
        private String agentRetentionUnits;
        private Boolean conserved;
    }
}
