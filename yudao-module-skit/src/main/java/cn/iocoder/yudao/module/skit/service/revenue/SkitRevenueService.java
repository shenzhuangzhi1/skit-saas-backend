package cn.iocoder.yudao.module.skit.service.revenue;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface SkitRevenueService {

    PageResult<LedgerView> getLedgerPage(PageParam pageParam, Long beneficiaryUserId, Integer beneficiaryType,
                                          LocalDateTime[] createTime);

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
