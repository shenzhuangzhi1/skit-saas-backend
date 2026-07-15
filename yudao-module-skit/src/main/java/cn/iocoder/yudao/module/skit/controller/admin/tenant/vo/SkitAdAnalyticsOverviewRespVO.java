package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Schema(description = "管理后台 - 广告分析总览 Response VO")
@Data
public class SkitAdAnalyticsOverviewRespVO {

    private Long tenantId;
    private LocalDateTime asOf;
    private String timezone;
    private PlatformHealth platformHealth;
    private Freshness freshness;
    private List<CurrencyGroup> groups = new ArrayList<>();

    @Data
    public static class PlatformHealth {
        private String status;
        private String callbackSuccessRate;
        private String reportStatus;
        private Long openAlertCount;
    }

    @Data
    public static class Freshness {
        private LocalDateTime lastSessionAt;
        private LocalDateTime lastSignedRewardAt;
        private LocalDateTime lastImpressionAt;
        private LocalDateTime lastReportSuccessAt;
    }

    @Data
    public static class CurrencyGroup {
        private String currency;
        private Long requestCount;
        private Long displayCount;
        private Long clientRewardCount;
        private Long verifiedRewardCount;
        private Long skipCount;
        private Long failureCount;
        private Long uniqueMemberCount;
        private String frozenRevenue;
        private String reconciledRevenue;
        private String preReportSuspenseRevenue;
        private String reportSuspenseRevenue;
        private String suspenseRevenue;
        private String agentRetainedRevenue;
        private List<LevelShare> levelShares = new ArrayList<>();
    }

    @Data
    public static class LevelShare {
        private Integer levelNo;
        private String amount;
    }

}
