package cn.iocoder.yudao.module.skit.service.commission;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

public interface SkitCommissionService {

    PlanView getActivePlan();

    PlanView replaceRules(List<RuleView> rules);

    PlanSnapshot getActiveSnapshot();

    void ensureDefaultPlan();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class RuleView {
        private Integer level;
        /** 百分比，例如 12.5 表示 12.5%。 */
        private BigDecimal rate;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class PlanView {
        private Integer version;
        private List<RuleView> rules;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class RuleSnapshot {
        private Integer level;
        private Integer rateBps;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class PlanSnapshot {
        private Long planId;
        private Integer version;
        private List<RuleSnapshot> rules;
    }

}
