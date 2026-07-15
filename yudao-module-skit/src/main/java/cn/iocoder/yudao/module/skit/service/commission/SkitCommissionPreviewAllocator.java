package cn.iocoder.yudao.module.skit.service.commission;

import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionRuleVO;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.COMMISSION_RULE_INVALID;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.RATE_BASE;

/** Exact integer-unit allocator used by management preview and plan validation. */
@Component
public class SkitCommissionPreviewAllocator {

    private static final BigInteger RATE_BASE_INTEGER = BigInteger.valueOf(RATE_BASE);

    public Result allocate(long grossAmountUnits, List<SkitCommissionRuleVO> suppliedRules) {
        if (grossAmountUnits < 0L) {
            throw exception(COMMISSION_RULE_INVALID, "预览金额不能为负数");
        }
        List<SkitCommissionRuleVO> rules = normalize(suppliedRules);
        BigInteger gross = BigInteger.valueOf(grossAmountUnits);
        long memberTotal = 0L;
        int configuredRate = 0;
        List<Allocation> allocations = new ArrayList<>(rules.size());
        for (SkitCommissionRuleVO rule : rules) {
            long amountUnits = gross.multiply(BigInteger.valueOf(rule.getRateBps()))
                    .divide(RATE_BASE_INTEGER).longValueExact();
            memberTotal = Math.addExact(memberTotal, amountUnits);
            configuredRate = Math.addExact(configuredRate, rule.getRateBps());
            allocations.add(new Allocation(rule.getLevelNo(), rule.getRateBps(), amountUnits));
        }
        long agentAmount = Math.subtractExact(grossAmountUnits, memberTotal);
        return new Result(allocations, memberTotal, agentAmount, configuredRate,
                RATE_BASE - configuredRate);
    }

    public List<SkitCommissionRuleVO> normalize(List<SkitCommissionRuleVO> suppliedRules) {
        if (suppliedRules == null || suppliedRules.isEmpty()) {
            throw exception(COMMISSION_RULE_INVALID, "至少配置 level 0 本人比例");
        }
        List<SkitCommissionRuleVO> rules = new ArrayList<>(suppliedRules.size());
        Set<Integer> levels = new HashSet<>();
        long totalRate = 0L;
        for (SkitCommissionRuleVO supplied : suppliedRules) {
            if (supplied == null || supplied.getLevelNo() == null || supplied.getLevelNo() < 0) {
                throw exception(COMMISSION_RULE_INVALID, "levelNo 必须大于等于 0");
            }
            if (!levels.add(supplied.getLevelNo())) {
                throw exception(COMMISSION_RULE_INVALID, "levelNo 不能重复");
            }
            if (supplied.getRateBps() == null || supplied.getRateBps() < 0
                    || supplied.getRateBps() > RATE_BASE) {
                throw exception(COMMISSION_RULE_INVALID, "rateBps 必须在 0 到 10000 之间");
            }
            totalRate += supplied.getRateBps();
            if (totalRate > RATE_BASE) {
                throw exception(COMMISSION_RULE_INVALID, "全部层级比例之和不能超过 10000");
            }
            rules.add(new SkitCommissionRuleVO().setLevelNo(supplied.getLevelNo())
                    .setRateBps(supplied.getRateBps()));
        }
        if (!levels.contains(0)) {
            throw exception(COMMISSION_RULE_INVALID, "必须配置 level 0 本人比例");
        }
        rules.sort(Comparator.comparingInt(SkitCommissionRuleVO::getLevelNo));
        return Collections.unmodifiableList(rules);
    }

    public static final class Allocation {
        private final int levelNo;
        private final int rateBps;
        private final long amountUnits;

        private Allocation(int levelNo, int rateBps, long amountUnits) {
            this.levelNo = levelNo;
            this.rateBps = rateBps;
            this.amountUnits = amountUnits;
        }

        public int getLevelNo() {
            return levelNo;
        }

        public int getRateBps() {
            return rateBps;
        }

        public long getAmountUnits() {
            return amountUnits;
        }
    }

    public static final class Result {
        private final List<Allocation> allocations;
        private final long memberTotalUnits;
        private final long agentAmountUnits;
        private final int totalMemberRateBps;
        private final int agentRateBps;

        private Result(List<Allocation> allocations, long memberTotalUnits,
                       long agentAmountUnits, int totalMemberRateBps, int agentRateBps) {
            this.allocations = Collections.unmodifiableList(new ArrayList<>(allocations));
            this.memberTotalUnits = memberTotalUnits;
            this.agentAmountUnits = agentAmountUnits;
            this.totalMemberRateBps = totalMemberRateBps;
            this.agentRateBps = agentRateBps;
        }

        public List<Allocation> getAllocations() {
            return allocations;
        }

        public long getMemberTotalUnits() {
            return memberTotalUnits;
        }

        public long getAgentAmountUnits() {
            return agentAmountUnits;
        }

        public int getTotalMemberRateBps() {
            return totalMemberRateBps;
        }

        public int getAgentRateBps() {
            return agentRateBps;
        }
    }
}
