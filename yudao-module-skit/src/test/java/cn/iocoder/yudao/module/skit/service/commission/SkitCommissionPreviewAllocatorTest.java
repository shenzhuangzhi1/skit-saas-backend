package cn.iocoder.yudao.module.skit.service.commission;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionRuleVO;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.COMMISSION_RULE_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkitCommissionPreviewAllocatorTest {

    private final SkitCommissionPreviewAllocator allocator = new SkitCommissionPreviewAllocator();

    @Test
    void integerUnitsAreConservedAndRoundingRemainderStaysWithAgent() {
        SkitCommissionPreviewAllocator.Result result = allocator.allocate(101L, Arrays.asList(
                rule(0, 3333), rule(1, 3333), rule(2, 3333)));

        assertEquals(3, result.getAllocations().size());
        assertEquals(33L, result.getAllocations().get(0).getAmountUnits());
        assertEquals(33L, result.getAllocations().get(1).getAmountUnits());
        assertEquals(33L, result.getAllocations().get(2).getAmountUnits());
        assertEquals(99L, result.getMemberTotalUnits());
        assertEquals(2L, result.getAgentAmountUnits());
        assertEquals(1, result.getAgentRateBps());
        assertEquals(101L, result.getMemberTotalUnits() + result.getAgentAmountUnits());
    }

    @Test
    void supportsArbitraryConfiguredDepthAndLongMaxWithoutMultiplicationOverflow() {
        SkitCommissionPreviewAllocator.Result result = allocator.allocate(Long.MAX_VALUE,
                Arrays.asList(rule(0, 1), rule(25, 9999)));

        assertEquals(25, result.getAllocations().get(1).getLevelNo());
        assertEquals(Long.MAX_VALUE,
                result.getMemberTotalUnits() + result.getAgentAmountUnits());
        assertEquals(0, result.getAgentRateBps());
    }

    @Test
    void rejectsMissingViewerDuplicateLevelAndRateOverflow() {
        assertInvalid(Collections.singletonList(rule(2, 1000)));
        assertInvalid(Arrays.asList(rule(0, 1000), rule(0, 2000)));
        assertInvalid(Arrays.asList(rule(0, 6000), rule(1, 5000)));
    }

    private void assertInvalid(java.util.List<SkitCommissionRuleVO> rules) {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> allocator.allocate(100L, rules));
        assertEquals(COMMISSION_RULE_INVALID.getCode(), exception.getCode());
    }

    private SkitCommissionRuleVO rule(int levelNo, int rateBps) {
        return new SkitCommissionRuleVO().setLevelNo(levelNo).setRateBps(rateBps);
    }
}
