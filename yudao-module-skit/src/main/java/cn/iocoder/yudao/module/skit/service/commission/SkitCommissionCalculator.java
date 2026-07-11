package cn.iocoder.yudao.module.skit.service.commission;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MONEY_SCALE;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.RATE_BASE;

/**
 * 动态层级广告分成计算器。
 *
 * <p>level 0 表示广告触发会员本人，level 1..N 表示逐级祖先。没有对应祖先、没有配置规则以及
 * 小数舍入产生的余额，都归代理商。该类无 I/O，是分账规则唯一的纯计算 seam。</p>
 */
public class SkitCommissionCalculator {

    private static final BigDecimal RATE_BASE_DECIMAL = BigDecimal.valueOf(RATE_BASE);

    public Result calculate(BigDecimal grossAmount, Map<Integer, Long> membersByLevel, List<Rule> rules) {
        if (grossAmount == null || grossAmount.signum() < 0) {
            throw new IllegalArgumentException("grossAmount 必须大于等于 0");
        }
        BigDecimal gross = grossAmount.setScale(MONEY_SCALE, RoundingMode.DOWN);
        List<Rule> normalizedRules = normalizeRules(rules);

        List<Allocation> allocations = new ArrayList<>();
        BigDecimal memberTotal = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.DOWN);
        int appliedRateBps = 0;
        Map<Integer, Long> levelMembers = membersByLevel == null
                ? Collections.<Integer, Long>emptyMap() : membersByLevel;
        for (Rule rule : normalizedRules) {
            Long memberId = levelMembers.get(rule.getLevelNo());
            if (memberId == null) {
                continue;
            }
            BigDecimal amount = gross.multiply(BigDecimal.valueOf(rule.getRateBps()))
                    .divide(RATE_BASE_DECIMAL, MONEY_SCALE, RoundingMode.DOWN);
            allocations.add(new Allocation(rule.getLevelNo(), memberId, rule.getRateBps(), amount));
            memberTotal = memberTotal.add(amount);
            appliedRateBps += rule.getRateBps();
        }
        BigDecimal agentAmount = gross.subtract(memberTotal).setScale(MONEY_SCALE, RoundingMode.DOWN);
        return new Result(allocations, agentAmount, RATE_BASE - appliedRateBps);
    }

    private List<Rule> normalizeRules(List<Rule> rules) {
        List<Rule> result = new ArrayList<>(rules == null ? Collections.<Rule>emptyList() : rules);
        Collections.sort(result, Comparator.comparing(Rule::getLevelNo));
        Set<Integer> levels = new HashSet<>();
        int totalRate = 0;
        for (Rule rule : result) {
            if (rule == null || rule.getLevelNo() == null || rule.getLevelNo() < 0) {
                throw new IllegalArgumentException("levelNo 必须大于等于 0");
            }
            if (rule.getRateBps() == null || rule.getRateBps() < 0 || rule.getRateBps() > RATE_BASE) {
                throw new IllegalArgumentException("rateBps 必须在 0 到 10000 之间");
            }
            if (!levels.add(rule.getLevelNo())) {
                throw new IllegalArgumentException("levelNo 不能重复");
            }
            totalRate += rule.getRateBps();
        }
        if (totalRate > RATE_BASE) {
            throw new IllegalArgumentException("全部层级比例之和不能超过 10000");
        }
        return result;
    }

    public static final class Rule {
        private final Integer levelNo;
        private final Integer rateBps;

        public Rule(Integer levelNo, Integer rateBps) {
            this.levelNo = levelNo;
            this.rateBps = rateBps;
        }

        public Integer getLevelNo() {
            return levelNo;
        }

        public Integer getRateBps() {
            return rateBps;
        }
    }

    public static final class Allocation {
        private final Integer levelNo;
        private final Long memberId;
        private final Integer rateBps;
        private final BigDecimal amount;

        public Allocation(Integer levelNo, Long memberId, Integer rateBps, BigDecimal amount) {
            this.levelNo = levelNo;
            this.memberId = memberId;
            this.rateBps = rateBps;
            this.amount = amount;
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

        public BigDecimal getAmount() {
            return amount;
        }
    }

    public static final class Result {
        private final List<Allocation> allocations;
        private final BigDecimal agentAmount;
        private final Integer agentRateBps;

        public Result(List<Allocation> allocations, BigDecimal agentAmount, Integer agentRateBps) {
            this.allocations = Collections.unmodifiableList(new ArrayList<>(allocations));
            this.agentAmount = agentAmount;
            this.agentRateBps = agentRateBps;
        }

        public List<Allocation> getAllocations() {
            return allocations;
        }

        public BigDecimal getAgentAmount() {
            return agentAmount;
        }

        public Integer getAgentRateBps() {
            return agentRateBps;
        }
    }

}
