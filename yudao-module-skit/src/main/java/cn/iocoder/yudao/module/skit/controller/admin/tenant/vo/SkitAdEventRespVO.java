package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SkitAdEventRespVO {

    private Long tenantId;
    private Long id;
    private String sessionId;
    private Long memberId;
    private Long adAccountId;
    private String provider;
    private String placementId;
    private String matchStatus;
    private String sourceVerificationStatus;
    private String rewardQualificationStatus;
    private String reconciliationStatus;
    private String currency;
    private String estimatedAmount;
    private String reconciledAmount;
    private LocalDateTime occurredTime;

}
