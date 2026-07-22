package cn.iocoder.yudao.module.skit.service.ad;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public interface SkitTenantAdCapabilityService {

    int CURRENT_PROTOCOL_VERSION = 1;

    ReadinessView getReadiness();

    CapabilityView configure(ConfigurationCommand command);

    CapabilityView transition(TransitionCommand command);

    List<NetworkCapabilityView> listNetworkCapabilities(Long adAccountId);

    NetworkCapabilityView verifyNetworkCapability(NetworkCapabilityCommand command);

    /** Locks the tenant singleton and verifies the credential mutation target/version. */
    void lockCredentialMutationTarget(Long adAccountId, Integer expectedReadinessVersion);

    void checkClientAccess(Long memberId, ClientRuntime runtime, AccessOperation operation);

    enum AccessOperation {
        PLAYER_GRANT,
        AD_SESSION,
        PROTECTED_CONTENT
    }

    @Data
    @AllArgsConstructor
    class ClientRuntime {
        private String nativeVersion;
        private Integer protocolVersion;
    }

    @Data
    class ConfigurationCommand {
        private Long adAccountId;
        private String dedicatedUnlockPlacementId;
        private Boolean dedicatedPlacementVerified;
        private Boolean rewardCallbackTemplateVerified;
        private Boolean impressionCallbackTemplateVerified;
        private Set<Integer> unlockNetworkFirmIds = new LinkedHashSet<>();
        private Set<Long> shadowTestMemberIds = new LinkedHashSet<>();
        private String minNativeVersion;
        private Integer minProtocolVersion;
        private Integer expectedReadinessVersion;
    }

    @Data
    class TransitionCommand {
        private String targetState;
        private String minNativeVersion;
        private Integer minProtocolVersion;
        private Integer expectedReadinessVersion;
    }

    @Data
    class NetworkCapabilityCommand {
        private Long adAccountId;
        private Integer networkFirmId;
        private String rewardAuthority;
        private Boolean enabled;
        private Boolean supportsUserId;
        private Boolean supportsCustomData;
        private Boolean supportsStableTransaction;
        private Boolean supportsImpressionRevenue;
        private Boolean supportsReporting;
        private Integer expectedReadinessVersion;
    }

    @Data
    class CapabilityView {
        private Long tenantId;
        private Long adAccountId;
        private String rolloutState;
        private String dedicatedUnlockPlacementId;
        private Set<Integer> unlockNetworkFirmIds = Collections.emptySet();
        private Set<Long> shadowTestMemberIds = Collections.emptySet();
        private String minNativeVersion;
        private Integer minProtocolVersion;
        private Integer readinessVersion;
        private LocalDateTime enforcedAt;
    }

    @Data
    class ReadinessView {
        private Long tenantId;
        private Long adAccountId;
        private String rolloutState;
        private Integer readinessVersion;
        private Integer expectedReadinessVersion;
        private String dedicatedUnlockPlacementId;
        private Boolean dedicatedPlacementVerified;
        private Set<Integer> unlockNetworkFirmIds = Collections.emptySet();
        private Set<Long> shadowTestMemberIds = Collections.emptySet();
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
        private List<String> blockers = Collections.emptyList();
        private List<NetworkCapabilityView> availableNetworkCapabilities = Collections.emptyList();
        private List<NetworkReadinessView> networkReadiness = Collections.emptyList();
        private Set<Integer> missingSignedRewardNetworkFirmIds = Collections.emptySet();
        private Set<Integer> missingImpressionNetworkFirmIds = Collections.emptySet();
        private LocalDateTime lastSignedRewardCallbackAt;
        private LocalDateTime lastImpressionCallbackAt;
        private LocalDateTime lastReportSuccessAt;

        public boolean isShadowReady() {
            return Boolean.TRUE.equals(shadowReady);
        }

        public boolean isProductionReady() {
            return Boolean.TRUE.equals(productionReady);
        }

        public boolean isCallbackKeyConfigured() {
            return Boolean.TRUE.equals(callbackKeyConfigured);
        }

        public boolean isRewardSecretConfigured() {
            return Boolean.TRUE.equals(rewardSecretConfigured);
        }

        public boolean isReportingCredentialConfigured() {
            return Boolean.TRUE.equals(reportingCredentialConfigured);
        }

        public boolean isCallbackPublicUrlHttps() {
            return Boolean.TRUE.equals(callbackPublicUrlHttps);
        }
    }

    @Data
    class NetworkCapabilityView {
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
        private boolean selectable;
        private List<String> blockers = Collections.emptyList();
    }

    @Data
    class NetworkReadinessView {
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
        private boolean signedRewardObserved;
        private boolean impressionObserved;
        private LocalDateTime lastSignedRewardCallbackAt;
        private LocalDateTime lastImpressionCallbackAt;
        private List<String> sourceRefs = Collections.emptyList();
        private List<String> signedRewardSourceRefs = Collections.emptyList();
        private List<String> impressionSourceRefs = Collections.emptyList();
        private List<String> blockers = Collections.emptyList();
    }

}
