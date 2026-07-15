package cn.iocoder.yudao.module.skit.service.ad;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

public interface SkitAdSessionService {

    CreateResult createForMember(Long memberId, CreateCommand command);

    CreateResult createForNativeGrant(String grantToken, CreateCommand command);

    SessionView getForMember(Long memberId, String sessionId,
                             SkitTenantAdCapabilityService.ClientRuntime runtime);

    SessionView getForNativeGrant(String grantToken, String sessionId,
                                  SkitTenantAdCapabilityService.ClientRuntime runtime);

    SessionView recordClientEvents(Long memberId, String sessionId, List<ClientEventCommand> events,
                                   SkitTenantAdCapabilityService.ClientRuntime runtime);

    SessionView recordClientEventsForNativeGrant(String grantToken, String sessionId,
                                                 List<ClientEventCommand> events,
                                                 SkitTenantAdCapabilityService.ClientRuntime runtime);

    @Data
    class CreateCommand {
        private Long dramaId;
        private Integer episodeNo;
        private String nativeVersion;
        private Integer protocolVersion;
    }

    @Data
    class ClientEventCommand {
        private Integer protocolVersion;
        private String clientEventId;
        private Integer callbackSequence;
        private String sessionId;
        private String provider;
        private String placementId;
        private String eventType;
        private String nativeState;
        private String sdkRequestId;
        private String providerShowId;
        private Integer networkFirmId;
        private String adsourceId;
        private Boolean clientRewardObserved;
        private Boolean closed;
    }

    final class CreateResult {

        private final String outcome;
        private final Integer protocolVersion;
        private final String sessionId;
        private final String provider;
        private final String placementId;
        private final String userId;
        private final String customData;
        private final String scene;
        private final LocalDateTime loadExpiresAt;
        private final LocalDateTime rewardAcceptUntil;

        CreateResult(String outcome, Integer protocolVersion, String sessionId, String provider,
                     String placementId, String userId, String customData, String scene,
                     LocalDateTime loadExpiresAt, LocalDateTime rewardAcceptUntil) {
            this.outcome = outcome;
            this.protocolVersion = protocolVersion;
            this.sessionId = sessionId;
            this.provider = provider;
            this.placementId = placementId;
            this.userId = userId;
            this.customData = customData;
            this.scene = scene;
            this.loadExpiresAt = loadExpiresAt;
            this.rewardAcceptUntil = rewardAcceptUntil;
        }

        public String getOutcome() { return outcome; }
        public Integer getProtocolVersion() { return protocolVersion; }
        public String getSessionId() { return sessionId; }
        public String getProvider() { return provider; }
        public String getPlacementId() { return placementId; }
        public String getUserId() { return userId; }
        public String getCustomData() { return customData; }
        public String getScene() { return scene; }
        public LocalDateTime getLoadExpiresAt() { return loadExpiresAt; }
        public LocalDateTime getRewardAcceptUntil() { return rewardAcceptUntil; }

        @Override
        public String toString() {
            return "CreateResult{outcome='" + outcome + "', protocolVersion=" + protocolVersion
                    + ", sessionId='" + sessionId + "', provider='" + provider
                    + "', placementId='" + placementId + "', userId='" + userId
                    + "', customData=<redacted>, scene='" + scene + "', loadExpiresAt="
                    + loadExpiresAt + ", rewardAcceptUntil=" + rewardAcceptUntil + '}';
        }
    }

    final class SessionView {

        private final String sessionId;
        private final String clientLifecycleStatus;
        private final String rewardVerificationStatus;
        private final String entitlementStatus;
        private final String revenueStatus;
        private final String providerShowId;
        private final LocalDateTime loadExpiresAt;
        private final LocalDateTime rewardAcceptUntil;

        SessionView(String sessionId, String clientLifecycleStatus, String rewardVerificationStatus,
                    String entitlementStatus, String revenueStatus, String providerShowId,
                    LocalDateTime loadExpiresAt, LocalDateTime rewardAcceptUntil) {
            this.sessionId = sessionId;
            this.clientLifecycleStatus = clientLifecycleStatus;
            this.rewardVerificationStatus = rewardVerificationStatus;
            this.entitlementStatus = entitlementStatus;
            this.revenueStatus = revenueStatus;
            this.providerShowId = providerShowId;
            this.loadExpiresAt = loadExpiresAt;
            this.rewardAcceptUntil = rewardAcceptUntil;
        }

        public String getSessionId() { return sessionId; }
        public String getClientLifecycleStatus() { return clientLifecycleStatus; }
        public String getRewardVerificationStatus() { return rewardVerificationStatus; }
        public String getEntitlementStatus() { return entitlementStatus; }
        public String getRevenueStatus() { return revenueStatus; }
        public String getProviderShowId() { return providerShowId; }
        public LocalDateTime getLoadExpiresAt() { return loadExpiresAt; }
        public LocalDateTime getRewardAcceptUntil() { return rewardAcceptUntil; }
    }

}
