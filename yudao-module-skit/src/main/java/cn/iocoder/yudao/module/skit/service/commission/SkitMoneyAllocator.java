package cn.iocoder.yudao.module.skit.service.commission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.RATE_BASE;

/**
 * Integer-only commission allocator for one currency and precision.
 *
 * <p>Configured ratios are expressed in basis points. Only present and eligible members receive
 * their floored share; every other unit remains with the agent. One invocation never mixes
 * currencies or precisions.</p>
 */
public class SkitMoneyAllocator {

    /**
     * Allocates an amount from the immutable, server-verified policy snapshot boundary.
     */
    public Result allocate(String currency, int amountScale, long sourceAmountUnits,
                           SkitPolicySnapshotService.PolicySnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("verified policy snapshot is required");
        }
        List<LevelShare> shares = new ArrayList<>();
        for (SkitPolicySnapshotService.BeneficiarySlot beneficiary : snapshot.getBeneficiaries()) {
            shares.add(new LevelShare(beneficiary.getLevel(), beneficiary.getMemberId(),
                    beneficiary.getRateBps(), beneficiary.isEligible()));
        }
        return allocate(currency, amountScale, sourceAmountUnits, shares);
    }

    Result allocate(String currency, int amountScale, long sourceAmountUnits, List<LevelShare> shares) {
        validateMoney(currency, amountScale, sourceAmountUnits);
        List<LevelShare> normalizedShares = normalizeShares(shares);

        List<MemberAllocation> memberAllocations = new ArrayList<>();
        long memberTotal = 0L;
        for (LevelShare share : normalizedShares) {
            if (share.getRateBps() == null || share.getMemberId() == null || !share.isEligible()) {
                continue;
            }
            long amountUnits = floorMultiplyByRate(sourceAmountUnits, share.getRateBps());
            memberTotal = addExact(memberTotal, amountUnits, "member allocation total overflow");
            memberAllocations.add(new MemberAllocation(
                    share.getLevelNo(), share.getMemberId(), share.getRateBps(), amountUnits));
        }

        long agentRetentionUnits;
        try {
            agentRetentionUnits = Math.subtractExact(sourceAmountUnits, memberTotal);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("agent retention overflow", ex);
        }
        if (agentRetentionUnits < 0) {
            throw new IllegalArgumentException("member allocation total cannot exceed source amount");
        }
        return new Result(currency, amountScale, sourceAmountUnits, memberAllocations, agentRetentionUnits);
    }

    private void validateMoney(String currency, int amountScale, long sourceAmountUnits) {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be a three-letter uppercase code");
        }
        if (amountScale < 0 || amountScale > 18) {
            throw new IllegalArgumentException("amountScale must be between 0 and 18");
        }
        if (sourceAmountUnits < 0) {
            throw new IllegalArgumentException("sourceAmountUnits must be greater than or equal to 0");
        }
    }

    private List<LevelShare> normalizeShares(List<LevelShare> shares) {
        List<LevelShare> result = new ArrayList<>(
                shares == null ? Collections.<LevelShare>emptyList() : shares);

        Set<Integer> levels = new HashSet<>();
        long totalRateBps = 0L;
        for (LevelShare share : result) {
            if (share == null) {
                throw new IllegalArgumentException("level share is required");
            }
            if (share.getLevelNo() == null || share.getLevelNo() < 0) {
                throw new IllegalArgumentException("levelNo must be greater than or equal to 0");
            }
            if (!levels.add(share.getLevelNo())) {
                throw new IllegalArgumentException("levelNo must be unique");
            }
            if (share.getMemberId() != null && share.getMemberId() <= 0) {
                throw new IllegalArgumentException("memberId must be greater than 0 when present");
            }
            Integer rateBps = share.getRateBps();
            if (rateBps == null) {
                continue;
            }
            if (rateBps < 0 || rateBps > RATE_BASE) {
                throw new IllegalArgumentException("rateBps must be between 0 and " + RATE_BASE);
            }
            totalRateBps = addExact(totalRateBps, rateBps.longValue(), "total rate overflow");
            if (totalRateBps > RATE_BASE) {
                throw new IllegalArgumentException("total rateBps cannot exceed " + RATE_BASE);
            }
        }
        Collections.sort(result, Comparator.comparingInt(share -> share.getLevelNo()));
        return result;
    }

    private long floorMultiplyByRate(long sourceAmountUnits, int rateBps) {
        long quotient = sourceAmountUnits / RATE_BASE;
        long remainder = sourceAmountUnits % RATE_BASE;
        long whole = multiplyExact(quotient, rateBps, "allocation overflow");
        long fraction = multiplyExact(remainder, rateBps, "allocation overflow") / RATE_BASE;
        return addExact(whole, fraction, "allocation overflow");
    }

    private long addExact(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(message, ex);
        }
    }

    private long multiplyExact(long left, long right, String message) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(message, ex);
        }
    }

    public static final class LevelShare {

        private final Integer levelNo;
        private final Long memberId;
        private final Integer rateBps;
        private final boolean eligible;

        LevelShare(Integer levelNo, Long memberId, Integer rateBps, boolean eligible) {
            this.levelNo = levelNo;
            this.memberId = memberId;
            this.rateBps = rateBps;
            this.eligible = eligible;
        }

        public Integer getLevelNo() {
            return levelNo;
        }

        public Long getMemberId() {
            return memberId;
        }

        public Integer getRateBps() {
            return rateBps;
        }

        public boolean isEligible() {
            return eligible;
        }
    }

    public static final class MemberAllocation {

        private final int levelNo;
        private final Long memberId;
        private final int rateBps;
        private final long amountUnits;

        private MemberAllocation(int levelNo, Long memberId, int rateBps, long amountUnits) {
            this.levelNo = levelNo;
            this.memberId = memberId;
            this.rateBps = rateBps;
            this.amountUnits = amountUnits;
        }

        public int getLevelNo() {
            return levelNo;
        }

        public Long getMemberId() {
            return memberId;
        }

        public int getRateBps() {
            return rateBps;
        }

        public long getAmountUnits() {
            return amountUnits;
        }
    }

    public static final class Result {

        private final String currency;
        private final int amountScale;
        private final long sourceAmountUnits;
        private final List<MemberAllocation> memberAllocations;
        private final long agentRetentionUnits;

        private Result(String currency, int amountScale, long sourceAmountUnits,
                       List<MemberAllocation> memberAllocations, long agentRetentionUnits) {
            this.currency = currency;
            this.amountScale = amountScale;
            this.sourceAmountUnits = sourceAmountUnits;
            this.memberAllocations = Collections.unmodifiableList(new ArrayList<>(memberAllocations));
            this.agentRetentionUnits = agentRetentionUnits;
        }

        public String getCurrency() {
            return currency;
        }

        public int getAmountScale() {
            return amountScale;
        }

        public long getSourceAmountUnits() {
            return sourceAmountUnits;
        }

        public List<MemberAllocation> getMemberAllocations() {
            return memberAllocations;
        }

        public long getAgentRetentionUnits() {
            return agentRetentionUnits;
        }
    }

}
