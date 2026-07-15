package cn.iocoder.yudao.module.skit.service.invite;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

public interface SkitInviteCodeRegistryService {

    ResolvedOwner resolveActive(String code);

    boolean isClaimed(String code);

    ResolvedOwner claimAgent(Long tenantId, Long agentId, String code);

    ResolvedOwner claimMember(Long tenantId, Long memberId, String code);

    ResolvedOwner lockActive(OwnerType type, Long tenantId, Long ownerId, String code);

    void rotate(ResolvedOwner owner, LocalDateTime rotatedAt);

    enum OwnerType {
        AGENT,
        MEMBER
    }

    @Getter
    @ToString
    @EqualsAndHashCode
    final class ResolvedOwner {

        private final Long id;
        private final Long tenantId;
        private final OwnerType ownerType;
        private final Long agentId;
        private final Long memberId;
        private final String code;
        private final String normalizedCode;
        private final String status;
        private final LocalDateTime rotatedAt;

        public ResolvedOwner(Long id, Long tenantId, OwnerType ownerType, Long agentId, Long memberId,
                             String code, String normalizedCode, String status, LocalDateTime rotatedAt) {
            this.id = id;
            this.tenantId = tenantId;
            this.ownerType = ownerType;
            this.agentId = agentId;
            this.memberId = memberId;
            this.code = code;
            this.normalizedCode = normalizedCode;
            this.status = status;
            this.rotatedAt = rotatedAt;
        }

        public Long getOwnerId() {
            return ownerType == OwnerType.AGENT ? agentId : memberId;
        }

    }

}
