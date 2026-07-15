package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class SkitReconciliationDetailRespVO extends SkitReconciliationRespVO {

    private LocalDateTime asOf;
    private String timezone;
    private String reportTimezone;
    private String appId;
    private String placementId;
    private String adFormat;
    private Integer networkFirmId;
    private String networkAccountId;
    private String adsourceId;
    private List<ReportPull> reportPulls = new ArrayList<>();
    private List<UnmatchedItem> unmatchedItems = new ArrayList<>();
    private List<Revision> revisions = new ArrayList<>();

    @Data
    public static class ReportPull {
        private Long id;
        private String status;
        private LocalDateTime rangeStart;
        private LocalDateTime rangeEnd;
        private LocalDateTime pulledAt;
        private String errorCode;
    }

    @Data
    public static class UnmatchedItem {
        private Long eventId;
        private String providerTransactionId;
        private String estimatedAmount;
        private String reason;
    }

    @Data
    public static class Revision {
        private Long id;
        private Integer revisionNo;
        private String targetActualAmount;
        private String unmatchedActualAmount;
        private Boolean finalRevision;
        private String status;
        private LocalDateTime reconciledAt;
    }

}
