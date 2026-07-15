package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SkitAdRewardSecretRotateRespVO {

    private Long tenantId;
    private Long adAccountId;
    private Integer version;
    private Boolean configured;
    private LocalDateTime activatedAt;
    private LocalDateTime priorVersionAcceptUntil;

}
