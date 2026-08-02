package cn.iocoder.yudao.module.skit.dal.dataobject.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

@TenantIgnore
@InterceptorIgnore(tenantLine = "true")
@TableName("skit_provider_callback_attempt")
@Data
public class SkitProviderCallbackAttemptDO {

    @TableId
    private Long id;
    @JsonIgnore
    @ToString.Exclude
    private byte[] correlationId;
    private Long providerConnectionId;
    private Long inboxId;
    private String dedupeScheme;
    @JsonIgnore
    @ToString.Exclude
    private byte[] wirePayloadHash;
    @JsonIgnore
    @ToString.Exclude
    private byte[] materialIntegrityHash;
    private String deliveryIntegrityStatus;
    private String responseDecision;
    @JsonIgnore
    @ToString.Exclude
    private byte[] payloadCiphertext;
    @JsonIgnore
    @ToString.Exclude
    private byte[] payloadNonce;
    private String payloadKeyId;
    private String payloadPurpose;
    private Integer payloadEnvelopeVersion;
    private LocalDateTime payloadExpiresAt;
    private LocalDateTime payloadPurgedAt;
    private Integer wireSizeBytes;
    private Integer parameterCount;
    @JsonIgnore
    @ToString.Exclude
    private byte[] remoteAddressHash;
    @JsonIgnore
    @ToString.Exclude
    private byte[] userAgentHash;
    @JsonIgnore
    @ToString.Exclude
    private byte[] requestHeaderFingerprint;
    private String traceId;
    private LocalDateTime receivedAt;
}
