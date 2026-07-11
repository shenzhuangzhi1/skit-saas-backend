package cn.iocoder.yudao.module.skit.service.commission;

import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionPlanDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionRuleDO;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionPlanMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionRuleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.COMMISSION_PLAN_NOT_EXISTS;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.COMMISSION_RULE_INVALID;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.*;

@Service
public class SkitCommissionServiceImpl implements SkitCommissionService {

    @Resource
    private SkitCommissionPlanMapper planMapper;
    @Resource
    private SkitCommissionRuleMapper ruleMapper;

    @Override
    public PlanView getActivePlan() {
        PlanSnapshot snapshot = getActiveSnapshot();
        List<RuleView> rules = new ArrayList<>();
        for (RuleSnapshot rule : snapshot.getRules()) {
            rules.add(new RuleView(rule.getLevel(), BigDecimal.valueOf(rule.getRateBps(), 2)));
        }
        return new PlanView(snapshot.getVersion(), rules);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanView replaceRules(List<RuleView> rules) {
        List<RuleSnapshot> normalized = normalizeRules(rules);
        SkitCommissionPlanDO active = planMapper.selectActive();
        if (active != null) {
            planMapper.updateById(new SkitCommissionPlanDO().setId(active.getId())
                    .setStatus(COMMISSION_PLAN_ARCHIVED));
        }
        SkitCommissionPlanDO latest = planMapper.selectLatest();
        int version = latest == null ? 1 : latest.getVersion() + 1;
        SkitCommissionPlanDO plan = SkitCommissionPlanDO.builder().version(version)
                .status(COMMISSION_PLAN_ACTIVE).publishedTime(LocalDateTime.now()).build();
        planMapper.insert(plan);
        List<SkitCommissionRuleDO> entities = new ArrayList<>();
        for (RuleSnapshot rule : normalized) {
            entities.add(SkitCommissionRuleDO.builder().planId(plan.getId()).levelNo(rule.getLevel())
                    .rateBps(rule.getRateBps()).build());
        }
        ruleMapper.insertBatch(entities);
        return getActivePlan();
    }

    @Override
    public PlanSnapshot getActiveSnapshot() {
        SkitCommissionPlanDO plan = planMapper.selectActive();
        if (plan == null) {
            throw exception(COMMISSION_PLAN_NOT_EXISTS);
        }
        List<RuleSnapshot> rules = new ArrayList<>();
        for (SkitCommissionRuleDO rule : ruleMapper.selectListByPlanId(plan.getId())) {
            rules.add(new RuleSnapshot(rule.getLevelNo(), rule.getRateBps()));
        }
        return new PlanSnapshot(plan.getId(), plan.getVersion(), rules);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ensureDefaultPlan() {
        if (planMapper.selectActive() == null) {
            replaceRules(Collections.singletonList(new RuleView(0, new BigDecimal("100"))));
        }
    }

    private List<RuleSnapshot> normalizeRules(List<RuleView> rules) {
        if (rules == null || rules.isEmpty()) {
            throw exception(COMMISSION_RULE_INVALID, "至少配置 level 0");
        }
        List<RuleSnapshot> result = new ArrayList<>();
        Set<Integer> levels = new HashSet<>();
        int total = 0;
        for (RuleView rule : rules) {
            if (rule == null || rule.getLevel() == null || rule.getLevel() < 0 || rule.getLevel() > 100) {
                throw exception(COMMISSION_RULE_INVALID, "level 必须在 0 到 100 之间");
            }
            if (!levels.add(rule.getLevel())) {
                throw exception(COMMISSION_RULE_INVALID, "level 不能重复");
            }
            if (rule.getRate() == null || rule.getRate().signum() < 0) {
                throw exception(COMMISSION_RULE_INVALID, "rate 不能为空且不能为负数");
            }
            final int rateBps;
            try {
                rateBps = rule.getRate().multiply(BigDecimal.valueOf(100))
                        .setScale(0, RoundingMode.UNNECESSARY).intValueExact();
            } catch (ArithmeticException ex) {
                throw exception(COMMISSION_RULE_INVALID, "rate 最多支持两位小数");
            }
            if (rateBps > RATE_BASE) {
                throw exception(COMMISSION_RULE_INVALID, "单层比例不能超过 100% ");
            }
            total += rateBps;
            result.add(new RuleSnapshot(rule.getLevel(), rateBps));
        }
        if (!levels.contains(0)) {
            throw exception(COMMISSION_RULE_INVALID, "必须配置 level 0 本人比例");
        }
        if (total > RATE_BASE) {
            throw exception(COMMISSION_RULE_INVALID, "全部层级比例之和不能超过 100% ");
        }
        Collections.sort(result, Comparator.comparing(RuleSnapshot::getLevel));
        return result;
    }

}
