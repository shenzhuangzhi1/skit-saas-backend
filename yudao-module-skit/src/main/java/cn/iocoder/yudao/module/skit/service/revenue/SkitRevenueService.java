package cn.iocoder.yudao.module.skit.service.revenue;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

public interface SkitRevenueService {

    ReportResult report(Long sourceMemberId, ReportCommand command);

    PageResult<LedgerView> getLedgerPage(PageParam pageParam, Long beneficiaryUserId, Integer beneficiaryType,
                                          LocalDateTime[] createTime);

    @Data
    class ReportCommand {
        private String provider;
        private String externalEventId;
        private String placementId;
        private BigDecimal grossAmount;
        private OffsetDateTime occurredTime;
        private Boolean completed;
        private Boolean mock;
        private String rawData;
    }

    @Data
    class ReportResult {
        private Long eventId;
        private Long adRecordId;
        private String status;
        private Boolean idempotent;
        private BigDecimal estimatedCommissionAmount;
    }

    @Data
    class LedgerView {
        private Long id;
        private Long eventId;
        private Long adRecordId;
        private Integer beneficiaryType;
        private Long beneficiaryUserId;
        private String beneficiaryNickname;
        private Long sourceMemberId;
        private Long sourceUserId;
        private String sourceNickname;
        private String sourceMemberName;
        private Integer level;
        private BigDecimal revenueAmount;
        private BigDecimal rate;
        private BigDecimal commissionAmount;
        private Integer ruleVersion;
        private String status;
        private LocalDateTime createTime;
    }
}
