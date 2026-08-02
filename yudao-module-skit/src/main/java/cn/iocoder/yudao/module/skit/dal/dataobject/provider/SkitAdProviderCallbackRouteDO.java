package cn.iocoder.yudao.module.skit.dal.dataobject.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

@TenantIgnore
@TableName("skit_ad_provider_callback_route")
@Data
public class SkitAdProviderCallbackRouteDO {
    @TableId private Long id;
    private Long providerConnectionId;
    private Integer routeVersion;
    private Long callbackRouteRegistryId;
    private String callbackKeyFingerprint;
    private String canonicalOrigin;
    private Integer callbackPathVersion;
    private Integer callbackTemplateVersion;
    @JsonIgnore @ToString.Exclude private byte[] callbackOriginFingerprint;
    @JsonIgnore @ToString.Exclude private byte[] callbackContractFingerprint;
    private String purpose;
    private String state;
    private String routeSlot;
    private LocalDateTime issuedAt;
    private Long issuedByUserId;
    private String submissionTicket;
    private String submissionReference;
    private String submissionRecipient;
    private Long submittedByUserId;
    private LocalDateTime submittedAt;
    private LocalDateTime blockedAt;
    private LocalDateTime abandonedAt;
    private LocalDateTime acceptUntil;
    private Long createdByUserId;
    private LocalDateTime createdAt;
    private Long updatedByUserId;
    private LocalDateTime updatedAt;
}
