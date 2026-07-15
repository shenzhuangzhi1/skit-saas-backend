package cn.iocoder.yudao.module.skit.dal.dataobject.ad;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

@TableName("skit_ad_session")
@KeySequence("skit_ad_session_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class SkitAdSessionDO extends TenantBaseDO {

    @TableId
    private Long id;
    private String sessionId;
    @JsonIgnore
    @ToString.Exclude
    private byte[] sessionTokenHash;
    private Integer sessionTokenKeyVersion;
    private Integer protocolVersion;
    private Long memberId;
    private Long adAccountId;
    private Long policySnapshotId;
    private Integer callbackKeyVersion;
    private Integer rewardSecretVersion;
    private String provider;
    private String placementId;
    private String scenarioId;
    private String businessType;
    private Long dramaId;
    private Integer episodeFrom;
    private Integer episodeTo;
    private String unlockScope;
    @JsonIgnore
    @ToString.Exclude
    private byte[] activeScopeHash;
    private LocalDateTime activeScopeReleasedAt;
    private String activeScopeReleaseReason;
    private String pseudonymousUserId;
    private String accessMode;
    private Long nativePlayerGrantId;
    private String clientLifecycleStatus;
    private String rewardVerificationStatus;
    private String entitlementStatus;
    private String revenueStatus;
    private LocalDateTime loadExpiresAt;
    private LocalDateTime rewardAcceptUntil;
    private Long rewardCallbackInboxId;
    private LocalDateTime rewardCallbackReceivedAt;
    private LocalDateTime rewardVerifiedAt;
    private LocalDateTime entitledAt;
    private String sdkRequestId;
    private String providerShowId;
    private String providerTransactionId;
    private Integer networkFirmId;
    private String adsourceId;
    private Integer lastCallbackSequence;
    private String lastClientEvent;
    private String failureReason;
    private Integer version;

}
