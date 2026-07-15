package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class SkitReconciliationRespVO {

    private Long tenantId;
    private Long id;
    private Long adAccountId;
    private LocalDate reportDate;
    private String status;
    private String currency;
    private String estimatedAmount;
    private String actualAmount;
    private String differenceAmount;
    private Long reportImpressions;
    private Long matchedImpressions;
    private Integer latestRevisionNo;
    private LocalDateTime reconciledAt;

}
