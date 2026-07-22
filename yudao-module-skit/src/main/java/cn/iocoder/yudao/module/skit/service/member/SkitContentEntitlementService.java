package cn.iocoder.yudao.module.skit.service.member;

import cn.iocoder.yudao.module.skit.service.ad.SkitTenantAdCapabilityService;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;
import java.util.List;

public interface SkitContentEntitlementService {

    PlayerGrantIssue issuePlayerGrant(Long memberId, Long dramaId,
                                      SkitTenantAdCapabilityService.ClientRuntime runtime);

    PlayerGrantReference resolvePlayerGrant(String grantToken);

    PlayerGrantScope lockAndUsePlayerGrant(PlayerGrantReference reference, Long expectedDramaId);

    List<Integer> listGrantedEpisodes(Long memberId, Long dramaId,
                                      SkitTenantAdCapabilityService.ClientRuntime runtime);

    List<Integer> listGrantedEpisodesForPlayerGrant(
            String grantToken, SkitTenantAdCapabilityService.ClientRuntime runtime);

    /**
     * Returns the immutable signed reward provenance for one already-granted episode, or
     * {@code null} when no single valid proof can be established.
     */
    VerifiedRewardProvenance findVerifiedRewardProvenanceForPlayerGrant(
            String grantToken, Integer episodeNo,
            SkitTenantAdCapabilityService.ClientRuntime runtime);

    boolean ownsEpisodeForUpdate(Long memberId, Long dramaId, Integer episodeNo);

    /** Activates one exact signed reward lease after its canonical rewarded close is committed. */
    void activateVerifiedRewardLeaseOnClose(Long memberId, Long adSessionId, Long dramaId,
                                            Integer episodeNo, LocalDateTime closedAt);

    final class VerifiedRewardProvenance {

        private final Integer episodeNo;
        private final String sessionId;
        private final String provider;
        private final String providerShowId;

        public VerifiedRewardProvenance(Integer episodeNo, String sessionId, String provider,
                                        String providerShowId) {
            this.episodeNo = episodeNo;
            this.sessionId = sessionId;
            this.provider = provider;
            this.providerShowId = providerShowId;
        }

        public Integer getEpisodeNo() {
            return episodeNo;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getProvider() {
            return provider;
        }

        public String getProviderShowId() {
            return providerShowId;
        }

        @Override
        public String toString() {
            return "VerifiedRewardProvenance{episodeNo=" + episodeNo
                    + ", sessionId=<redacted>, provider=" + provider
                    + ", providerShowId=<redacted>}";
        }
    }

    final class PlayerGrantIssue {

        private final Long grantId;
        private final Long dramaId;
        private final LocalDateTime expiresAt;
        @JsonIgnore
        private String grantToken;

        PlayerGrantIssue(Long grantId, Long dramaId, LocalDateTime expiresAt, String grantToken) {
            this.grantId = grantId;
            this.dramaId = dramaId;
            this.expiresAt = expiresAt;
            this.grantToken = grantToken;
        }

        public Long getGrantId() {
            return grantId;
        }

        public Long getDramaId() {
            return dramaId;
        }

        public LocalDateTime getExpiresAt() {
            return expiresAt;
        }

        @JsonIgnore
        public synchronized String consumeGrantToken() {
            if (grantToken == null) {
                throw new IllegalStateException("Player grant token has already been consumed");
            }
            String value = grantToken;
            grantToken = null;
            return value;
        }

        @Override
        public synchronized String toString() {
            return "PlayerGrantIssue{grantId=" + grantId + ", dramaId=" + dramaId
                    + ", expiresAt=" + expiresAt + ", grantToken=<write-only>}";
        }
    }

    final class PlayerGrantReference {

        private final Long tenantId;
        private final Long grantId;
        private final Long memberId;
        private final Long dramaId;
        @JsonIgnore
        private final byte[] grantTokenHash;

        PlayerGrantReference(Long tenantId, Long grantId, Long memberId, Long dramaId, byte[] grantTokenHash) {
            this.tenantId = tenantId;
            this.grantId = grantId;
            this.memberId = memberId;
            this.dramaId = dramaId;
            this.grantTokenHash = grantTokenHash.clone();
        }

        public Long getTenantId() {
            return tenantId;
        }

        public Long getGrantId() {
            return grantId;
        }

        public Long getMemberId() {
            return memberId;
        }

        public Long getDramaId() {
            return dramaId;
        }

        @JsonIgnore
        byte[] getGrantTokenHash() {
            return grantTokenHash.clone();
        }

        @Override
        public String toString() {
            return "PlayerGrantReference{tenantId=" + tenantId + ", grantId=" + grantId
                    + ", memberId=" + memberId + ", dramaId=" + dramaId
                    + ", grantTokenHash=<redacted>}";
        }
    }

    final class PlayerGrantScope {

        private final Long tenantId;
        private final Long grantId;
        private final Long memberId;
        private final Long dramaId;

        PlayerGrantScope(Long tenantId, Long grantId, Long memberId, Long dramaId) {
            this.tenantId = tenantId;
            this.grantId = grantId;
            this.memberId = memberId;
            this.dramaId = dramaId;
        }

        public Long getTenantId() {
            return tenantId;
        }

        public Long getGrantId() {
            return grantId;
        }

        public Long getMemberId() {
            return memberId;
        }

        public Long getDramaId() {
            return dramaId;
        }
    }

}
