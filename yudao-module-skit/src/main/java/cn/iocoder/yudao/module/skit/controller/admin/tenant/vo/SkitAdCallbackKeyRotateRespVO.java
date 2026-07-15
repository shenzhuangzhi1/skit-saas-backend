package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
public class SkitAdCallbackKeyRotateRespVO {

    private Long tenantId;
    private Long adAccountId;
    private Integer version;
    private Boolean configured;
    private LocalDateTime activatedAt;
    private LocalDateTime priorVersionAcceptUntil;
    @ToString.Exclude
    private String callbackKey;
    @ToString.Exclude
    private String rewardCallbackUrl;
    @ToString.Exclude
    private String impressionCallbackUrl;

}
