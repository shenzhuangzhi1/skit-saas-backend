package cn.iocoder.yudao.module.skit.service.ad;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Only non-secret readiness metadata crosses this seam. Credential bytes and provider responses are never loaded.
 */
@Data
public class SkitTenantAdReadinessEvidence {

    private boolean tenantActive;
    private boolean accountBelongsToTenant;
    private boolean accountReady;
    private boolean callbackKeyConfigured;
    private Integer callbackKeyVersion;
    private LocalDateTime callbackKeyIssuedAt;
    private boolean rewardSecretConfigured;
    private Integer rewardSecretVersion;
    private LocalDateTime rewardSecretIssuedAt;
    private boolean callbackPublicUrlHttps;
    private boolean dedicatedUnlockPlacement;
    private boolean rewardCallbackTemplateVerified;
    private boolean impressionCallbackTemplateVerified;
    private boolean unlockNetworksAuthoritative;
    private boolean reportingCredentialConfigured;
    private boolean reportingPermissionVerified;
    private boolean reportFresh;
    private boolean signedRewardCallbackObserved;
    private boolean impressionCallbackObserved;
    private boolean nativeReleaseReady;
    private boolean protocolReady;
    private boolean shadowMembersBelongToTenant;
    private boolean shadowMembersValid;
    private LocalDateTime lastSignedRewardCallbackAt;
    private LocalDateTime lastImpressionCallbackAt;
    private LocalDateTime lastReportSuccessAt;

}
