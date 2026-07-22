package cn.iocoder.yudao.module.skit.service.ad;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Set;
import java.util.List;

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
    private boolean pairedSourceEvidenceObserved;
    private boolean nativeReleaseReady;
    private boolean protocolReady;
    private boolean shadowMembersBelongToTenant;
    private boolean shadowMembersValid;
    private LocalDateTime lastSignedRewardCallbackAt;
    private LocalDateTime lastImpressionCallbackAt;
    private LocalDateTime lastReportSuccessAt;
    private List<NetworkEvidence> availableNetworkCapabilities = Collections.emptyList();
    private List<NetworkEvidence> networkReadiness = Collections.emptyList();
    private Set<Integer> missingSignedRewardNetworkFirmIds = Collections.emptySet();
    private Set<Integer> missingImpressionNetworkFirmIds = Collections.emptySet();
    private Set<Integer> missingPairedSourceNetworkFirmIds = Collections.emptySet();

    @Data
    public static class NetworkEvidence {
        private Integer networkFirmId;
        private String rewardAuthority;
        private boolean enabled;
        private boolean verified;
        private LocalDateTime verifiedAt;
        private boolean supportsUserId;
        private boolean supportsCustomData;
        private boolean supportsStableTransaction;
        private boolean supportsImpressionRevenue;
        private boolean supportsReporting;
        private boolean authoritative;
        private boolean selectable;
        private boolean signedRewardObserved;
        private boolean impressionObserved;
        private boolean pairedSourceObserved;
        private LocalDateTime lastSignedRewardCallbackAt;
        private LocalDateTime lastImpressionCallbackAt;
        private List<String> sourceRefs = Collections.emptyList();
        private List<String> signedRewardSourceRefs = Collections.emptyList();
        private List<String> impressionSourceRefs = Collections.emptyList();
        private List<String> pairedSourceRefs = Collections.emptyList();
        private List<String> capabilityBlockers = Collections.emptyList();
        private List<String> blockers = Collections.emptyList();
    }

}
