package cn.iocoder.yudao.module.skit.service.commission;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public interface SkitPolicySnapshotService {

    PolicySnapshot createSnapshot(Long sourceMemberId);

    PolicySnapshot getRequired(Long snapshotId);

    enum EligibilityReason {
        ELIGIBLE,
        MISSING_ANCESTOR,
        DISABLED,
        INVALID_STATUS,
        TENANT_MISMATCH,
        OWNER_MISMATCH
    }

    final class ChainNode {

        private final int level;
        private final Long memberId;
        private final Integer memberStatus;
        private final boolean eligible;
        private final EligibilityReason reason;

        ChainNode(int level, Long memberId, Integer memberStatus,
                  boolean eligible, EligibilityReason reason) {
            this.level = level;
            this.memberId = memberId;
            this.memberStatus = memberStatus;
            this.eligible = eligible;
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public int getLevel() {
            return level;
        }

        public Long getMemberId() {
            return memberId;
        }

        public Integer getMemberStatus() {
            return memberStatus;
        }

        public boolean isEligible() {
            return eligible;
        }

        public EligibilityReason getReason() {
            return reason;
        }

    }

    final class BeneficiarySlot {

        private final int level;
        private final int rateBps;
        private final Long memberId;
        private final Integer memberStatus;
        private final boolean eligible;
        private final EligibilityReason reason;

        BeneficiarySlot(int level, int rateBps, Long memberId, Integer memberStatus,
                        boolean eligible, EligibilityReason reason) {
            this.level = level;
            this.rateBps = rateBps;
            this.memberId = memberId;
            this.memberStatus = memberStatus;
            this.eligible = eligible;
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public int getLevel() {
            return level;
        }

        public int getRateBps() {
            return rateBps;
        }

        public Long getMemberId() {
            return memberId;
        }

        public Integer getMemberStatus() {
            return memberStatus;
        }

        public boolean isEligible() {
            return eligible;
        }

        public EligibilityReason getReason() {
            return reason;
        }

    }

    final class PolicySnapshot {

        private final Long id;
        private final Long tenantId;
        private final Integer tenantStatus;
        private final Long planId;
        private final Long sourceMemberId;
        private final Integer ruleVersion;
        private final Integer schemaVersion;
        private final String snapshotJson;
        private final byte[] snapshotHash;
        private final LocalDateTime policySnapshotAt;
        private final List<ChainNode> chain;
        private final List<BeneficiarySlot> beneficiaries;
        private final int configuredRateBps;
        private final int eligibleRateBps;

        PolicySnapshot(Long id, Long tenantId, Integer tenantStatus, Long planId,
                       Long sourceMemberId, Integer ruleVersion, Integer schemaVersion,
                       String snapshotJson, byte[] snapshotHash,
                       LocalDateTime policySnapshotAt,
                       List<ChainNode> chain, List<BeneficiarySlot> beneficiaries,
                       int configuredRateBps, int eligibleRateBps) {
            this.id = id;
            this.tenantId = tenantId;
            this.tenantStatus = tenantStatus;
            this.planId = planId;
            this.sourceMemberId = sourceMemberId;
            this.ruleVersion = ruleVersion;
            this.schemaVersion = schemaVersion;
            this.snapshotJson = Objects.requireNonNull(snapshotJson, "snapshotJson");
            this.snapshotHash = Objects.requireNonNull(snapshotHash, "snapshotHash").clone();
            this.policySnapshotAt = Objects.requireNonNull(policySnapshotAt, "policySnapshotAt");
            this.chain = Collections.unmodifiableList(new ArrayList<>(chain));
            this.beneficiaries = Collections.unmodifiableList(new ArrayList<>(beneficiaries));
            this.configuredRateBps = configuredRateBps;
            this.eligibleRateBps = eligibleRateBps;
        }

        public Long getId() {
            return id;
        }

        public Long getTenantId() {
            return tenantId;
        }

        public Integer getTenantStatus() {
            return tenantStatus;
        }

        public Long getPlanId() {
            return planId;
        }

        public Long getSourceMemberId() {
            return sourceMemberId;
        }

        public Integer getRuleVersion() {
            return ruleVersion;
        }

        public Integer getSchemaVersion() {
            return schemaVersion;
        }

        public String getSnapshotJson() {
            return snapshotJson;
        }

        public byte[] getSnapshotHash() {
            return snapshotHash.clone();
        }

        public LocalDateTime getPolicySnapshotAt() {
            return policySnapshotAt;
        }

        public List<BeneficiarySlot> getBeneficiaries() {
            return beneficiaries;
        }

        public List<ChainNode> getChain() {
            return chain;
        }

        public int getConfiguredRateBps() {
            return configuredRateBps;
        }

        public int getEligibleRateBps() {
            return eligibleRateBps;
        }

    }

}
