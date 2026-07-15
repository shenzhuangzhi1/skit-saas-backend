package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import cn.iocoder.yudao.module.skit.service.ad.SkitTenantAdCapabilityService;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Data
public class SkitTenantAdReadinessRespVO {

    private Long tenantId;
    private Long adAccountId;
    private String rolloutState;
    private Integer readinessVersion;
    private Integer expectedReadinessVersion;
    private String dedicatedUnlockPlacementId;
    private Boolean dedicatedPlacementVerified;
    private Set<Integer> unlockNetworkFirmIds;
    private Set<Long> shadowTestMemberIds;
    private String minNativeVersion;
    private Integer minProtocolVersion;
    private LocalDateTime enforcedAt;
    private Boolean tenantActive;
    private Boolean accountReady;
    private Boolean callbackKeyConfigured;
    private Integer callbackKeyVersion;
    private LocalDateTime callbackKeyIssuedAt;
    private Boolean rewardSecretConfigured;
    private Integer rewardSecretVersion;
    private LocalDateTime rewardSecretIssuedAt;
    private Boolean callbackPublicUrlHttps;
    private Boolean dedicatedUnlockPlacement;
    private Boolean rewardCallbackTemplateVerified;
    private Boolean impressionCallbackTemplateVerified;
    private Boolean unlockNetworksAuthoritative;
    private Boolean reportingCredentialConfigured;
    private Boolean reportingPermissionVerified;
    private Boolean reportFresh;
    private Boolean signedRewardCallbackObserved;
    private Boolean impressionCallbackObserved;
    private Boolean nativeReleaseReady;
    private Boolean protocolReady;
    private Boolean shadowMembersValid;
    private Boolean shadowReady;
    private Boolean productionReady;
    private List<String> blockers;
    private LocalDateTime lastSignedRewardCallbackAt;
    private LocalDateTime lastImpressionCallbackAt;
    private LocalDateTime lastReportSuccessAt;

    public static SkitTenantAdReadinessRespVO from(SkitTenantAdCapabilityService.ReadinessView source) {
        SkitTenantAdReadinessRespVO result = new SkitTenantAdReadinessRespVO();
        result.setTenantId(source.getTenantId());
        result.setAdAccountId(source.getAdAccountId());
        result.setRolloutState(source.getRolloutState());
        result.setReadinessVersion(source.getReadinessVersion());
        result.setExpectedReadinessVersion(source.getExpectedReadinessVersion());
        result.setDedicatedUnlockPlacementId(source.getDedicatedUnlockPlacementId());
        result.setDedicatedPlacementVerified(source.getDedicatedPlacementVerified());
        result.setUnlockNetworkFirmIds(source.getUnlockNetworkFirmIds());
        result.setShadowTestMemberIds(source.getShadowTestMemberIds());
        result.setMinNativeVersion(source.getMinNativeVersion());
        result.setMinProtocolVersion(source.getMinProtocolVersion());
        result.setEnforcedAt(source.getEnforcedAt());
        result.setTenantActive(source.getTenantActive());
        result.setAccountReady(source.getAccountReady());
        result.setCallbackKeyConfigured(source.getCallbackKeyConfigured());
        result.setCallbackKeyVersion(source.getCallbackKeyVersion());
        result.setCallbackKeyIssuedAt(source.getCallbackKeyIssuedAt());
        result.setRewardSecretConfigured(source.getRewardSecretConfigured());
        result.setRewardSecretVersion(source.getRewardSecretVersion());
        result.setRewardSecretIssuedAt(source.getRewardSecretIssuedAt());
        result.setCallbackPublicUrlHttps(source.getCallbackPublicUrlHttps());
        result.setDedicatedUnlockPlacement(source.getDedicatedUnlockPlacement());
        result.setRewardCallbackTemplateVerified(source.getRewardCallbackTemplateVerified());
        result.setImpressionCallbackTemplateVerified(source.getImpressionCallbackTemplateVerified());
        result.setUnlockNetworksAuthoritative(source.getUnlockNetworksAuthoritative());
        result.setReportingCredentialConfigured(source.getReportingCredentialConfigured());
        result.setReportingPermissionVerified(source.getReportingPermissionVerified());
        result.setReportFresh(source.getReportFresh());
        result.setSignedRewardCallbackObserved(source.getSignedRewardCallbackObserved());
        result.setImpressionCallbackObserved(source.getImpressionCallbackObserved());
        result.setNativeReleaseReady(source.getNativeReleaseReady());
        result.setProtocolReady(source.getProtocolReady());
        result.setShadowMembersValid(source.getShadowMembersValid());
        result.setShadowReady(source.getShadowReady());
        result.setProductionReady(source.getProductionReady());
        result.setBlockers(source.getBlockers());
        result.setLastSignedRewardCallbackAt(source.getLastSignedRewardCallbackAt());
        result.setLastImpressionCallbackAt(source.getLastImpressionCallbackAt());
        result.setLastReportSuccessAt(source.getLastReportSuccessAt());
        return result;
    }

}
