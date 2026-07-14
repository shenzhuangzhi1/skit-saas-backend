package cn.iocoder.yudao.module.skit.service.commission;

import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionPlanDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.commission.SkitCommissionRuleDO;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionPlanMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.commission.SkitCommissionRuleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;

import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.COMMISSION_PLAN_ACTIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitCommissionServiceImplTest {

    @Mock
    private SkitCommissionPlanMapper planMapper;
    @Mock
    private SkitCommissionRuleMapper ruleMapper;

    @InjectMocks
    private SkitCommissionServiceImpl commissionService;

    @Test
    void shouldPublishArbitraryAncestorLevelAboveOneHundred() {
        SkitCommissionPlanDO persistedPlan = SkitCommissionPlanDO.builder()
                .id(88L).version(1).status(COMMISSION_PLAN_ACTIVE)
                .publishedTime(LocalDateTime.of(2026, 7, 14, 12, 0)).build();
        when(planMapper.selectActive()).thenReturn(null, persistedPlan);
        when(planMapper.selectLatest()).thenReturn(null);
        doAnswer(invocation -> {
            SkitCommissionPlanDO row = invocation.getArgument(0);
            row.setId(88L);
            return 1;
        }).when(planMapper).insert(any(SkitCommissionPlanDO.class));
        when(ruleMapper.selectListByPlanId(88L)).thenReturn(Arrays.asList(
                SkitCommissionRuleDO.builder().planId(88L).levelNo(0).rateBps(5_000).build(),
                SkitCommissionRuleDO.builder().planId(88L).levelNo(101).rateBps(1_000).build()));

        SkitCommissionService.PlanView plan = assertDoesNotThrow(() -> commissionService.replaceRules(Arrays.asList(
                new SkitCommissionService.RuleView(0, new BigDecimal("50")),
                new SkitCommissionService.RuleView(101, new BigDecimal("10")))));

        assertEquals(1, plan.getVersion());
        assertEquals(Arrays.asList(0, 101), Arrays.asList(
                plan.getRules().get(0).getLevel(), plan.getRules().get(1).getLevel()));
    }

}
