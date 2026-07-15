package cn.iocoder.yudao.module.skit.dal.dataobject.ad;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

@TableName("skit_ad_callback_inbox")
@KeySequence("skit_ad_callback_inbox_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAdCallbackInboxDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long adAccountId;
    private Long adSessionId;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long adSessionRefId;
    private Integer callbackKeyVersion;
    private Integer rewardSecretVersion;
    private String provider;
    private String callbackType;
    private String idempotencyKey;
    private String providerUserId;
    @JsonIgnore
    @ToString.Exclude
    private byte[] extraDataHash;
    private String providerTransactionId;
    private String providerShowId;
    private String providerRequestId;
    private String placementId;
    private String adsourceId;
    private Integer networkFirmId;
    private String sourceCurrency;
    private Long sourceAmountUnits;
    private Integer amountScale;
    private Long signedFieldMask;
    private String evidenceProvenance;
    @JsonIgnore
    @ToString.Exclude
    private byte[] canonicalPayloadHash;
    private String authenticationLevel;
    private String signatureStatus;
    private String deliveryIntegrityStatus;
    private LocalDateTime integrityConflictAt;
    private String processingStatus;
    @JsonIgnore
    @ToString.Exclude
    private byte[] payloadCiphertext;
    @JsonIgnore
    @ToString.Exclude
    private byte[] payloadNonce;
    private String payloadKeyId;
    private Integer payloadEnvelopeVersion;
    private LocalDateTime payloadExpiresAt;
    private String errorCode;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private Integer processingAttemptCount;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime receivedAt;
    private LocalDateTime processedAt;
    private LocalDateTime deadLetterAlertedAt;
    private Integer ingressResponseCode;

}
