package cn.iocoder.yudao.module.skit.service.commission;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkitCommissionCalculatorTest {

    private final SkitCommissionCalculator calculator = new SkitCommissionCalculator();

    @Test
    void shouldAllocateConfiguredLevelsAndGiveRemainderToAgent() {
        Map<Integer, Long> membersByLevel = new LinkedHashMap<>();
        membersByLevel.put(0, 100L);
        membersByLevel.put(1, 90L);
        membersByLevel.put(2, 80L);

        SkitCommissionCalculator.Result result = calculator.calculate(
                new BigDecimal("100.00000000"), membersByLevel,
                Arrays.asList(
                        new SkitCommissionCalculator.Rule(0, 5000),
                        new SkitCommissionCalculator.Rule(1, 2000),
                        new SkitCommissionCalculator.Rule(2, 1000)));

        assertEquals(new BigDecimal("50.00000000"), result.getAllocations().get(0).getAmount());
        assertEquals(new BigDecimal("20.00000000"), result.getAllocations().get(1).getAmount());
        assertEquals(new BigDecimal("10.00000000"), result.getAllocations().get(2).getAmount());
        assertEquals(new BigDecimal("20.00000000"), result.getAgentAmount());
        assertEquals(2000, result.getAgentRateBps());
    }

    @Test
    void shouldGiveMissingAncestorShareToAgent() {
        Map<Integer, Long> membersByLevel = new LinkedHashMap<>();
        membersByLevel.put(0, 100L);
        membersByLevel.put(1, 90L);

        SkitCommissionCalculator.Result result = calculator.calculate(
                new BigDecimal("100.00000000"), membersByLevel,
                Arrays.asList(
                        new SkitCommissionCalculator.Rule(0, 5000),
                        new SkitCommissionCalculator.Rule(1, 2000),
                        new SkitCommissionCalculator.Rule(2, 1000)));

        assertEquals(2, result.getAllocations().size());
        assertEquals(new BigDecimal("30.00000000"), result.getAgentAmount());
        assertEquals(3000, result.getAgentRateBps());
    }

}
