package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class SkitAdAnalyticsTimeseriesRespVO {

    private Long tenantId;
    private LocalDateTime asOf;
    private String timezone;
    private String granularity;
    private List<CurrencySeries> groups = new ArrayList<>();

    @Data
    public static class CurrencySeries {
        private String currency;
        private List<Point> items = new ArrayList<>();
    }

    @Data
    public static class Point {
        private LocalDateTime bucketStart;
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
    }

}
