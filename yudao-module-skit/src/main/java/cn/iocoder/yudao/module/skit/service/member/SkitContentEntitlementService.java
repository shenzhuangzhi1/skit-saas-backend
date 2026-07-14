package cn.iocoder.yudao.module.skit.service.member;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;
import java.util.List;

public interface SkitContentEntitlementService {

    PlayerGrantIssue issuePlayerGrant(Long memberId, Long dramaId);

    PlayerGrantReference resolvePlayerGrant(String grantToken);

    PlayerGrantScope lockAndUsePlayerGrant(PlayerGrantReference reference, Long expectedDramaId);

    List<Integer> listGrantedEpisodes(Long memberId, Long dramaId);

    List<Integer> listGrantedEpisodesForPlayerGrant(String grantToken);

    boolean ownsEpisodeForUpdate(Long memberId, Long dramaId, Integer episodeNo);

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
