package cn.iocoder.yudao.module.skit.service.commission;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkitMoneyAllocatorTest {

    private final SkitMoneyAllocator allocator = new SkitMoneyAllocator();

    @Test
    void shouldAllocateViewerAndArbitraryAncestorsWithIntegerFloor() {
        SkitMoneyAllocator.Result result = allocator.allocate("CNY", 8, 10_003L, Arrays.asList(
                share(7, 7L, 500, true),
                share(0, 100L, 5_000, true),
                share(1, 90L, 2_000, true),
                share(3, 70L, 1_000, true)));

        assertAllocation(result.getMemberAllocations().get(0), 0, 100L, 5_000, 5_001L);
        assertAllocation(result.getMemberAllocations().get(1), 1, 90L, 2_000, 2_000L);
        assertAllocation(result.getMemberAllocations().get(2), 3, 70L, 1_000, 1_000L);
        assertAllocation(result.getMemberAllocations().get(3), 7, 7L, 500, 500L);
        assertEquals(1_502L, result.getAgentRetentionUnits());
        assertConserved(result);
    }

    @Test
    void shouldRouteMissingDisabledAndUnconfiguredSharesToAgent() {
        SkitMoneyAllocator.Result result = allocator.allocate("USD", 6, 100_000L, Arrays.asList(
                share(0, 100L, 4_000, true),
                share(1, null, 2_000, true),
                share(2, 80L, 1_000, false),
                share(3, 70L, null, true)));

        assertEquals(1, result.getMemberAllocations().size());
        assertAllocation(result.getMemberAllocations().get(0), 0, 100L, 4_000, 40_000L);
        assertEquals(60_000L, result.getAgentRetentionUnits());
        assertConserved(result);
    }

    @Test
    void shouldGiveAllUnitsToAgentWhenNoRulesAreConfigured() {
        SkitMoneyAllocator.Result result = allocator.allocate("CNY", 8, 999L, Collections.<SkitMoneyAllocator.LevelShare>emptyList());

        assertEquals(Collections.emptyList(), result.getMemberAllocations());
        assertEquals(999L, result.getAgentRetentionUnits());
        assertConserved(result);
    }

    @Test
    void shouldConserveRoundingResidueAtAgent() {
        SkitMoneyAllocator.Result result = allocator.allocate("CNY", 8, 3L, Arrays.asList(
                share(0, 100L, 3_334, true),
                share(1, 90L, 3_333, true),
                share(2, 80L, 3_333, true)));

        assertEquals(1L, result.getMemberAllocations().get(0).getAmountUnits());
        assertEquals(0L, result.getMemberAllocations().get(1).getAmountUnits());
        assertEquals(0L, result.getMemberAllocations().get(2).getAmountUnits());
        assertEquals(2L, result.getAgentRetentionUnits());
        assertConserved(result);
    }

    @Test
    void shouldSupportZeroIncomeAndMaximumRatio() {
        SkitMoneyAllocator.Result zero = allocator.allocate("USD", 2, 0L,
                Collections.singletonList(share(0, 100L, 10_000, true)));
        SkitMoneyAllocator.Result maximum = allocator.allocate("USD", 2, 123L,
                Collections.singletonList(share(0, 100L, 10_000, true)));

        assertEquals(0L, zero.getMemberAllocations().get(0).getAmountUnits());
        assertEquals(0L, zero.getAgentRetentionUnits());
        assertEquals(123L, maximum.getMemberAllocations().get(0).getAmountUnits());
        assertEquals(0L, maximum.getAgentRetentionUnits());
        assertConserved(zero);
        assertConserved(maximum);
    }

    @Test
    void shouldKeepCurrencyAndScaleIsolatedAndConserved() {
        List<SkitMoneyAllocator.LevelShare> shares = Collections.singletonList(share(0, 100L, 2_500, true));
        SkitMoneyAllocator.Result cny = allocator.allocate("CNY", 8, 101L, shares);
        SkitMoneyAllocator.Result usd = allocator.allocate("USD", 2, 203L, shares);

        assertEquals("CNY", cny.getCurrency());
        assertEquals(8, cny.getAmountScale());
        assertEquals("USD", usd.getCurrency());
        assertEquals(2, usd.getAmountScale());
        assertConserved(cny);
        assertConserved(usd);
    }

    @Test
    void shouldRejectNegativeMoneyAndScale() {
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate("CNY", 8, -1L, Collections.<SkitMoneyAllocator.LevelShare>emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate("CNY", -1, 1L, Collections.<SkitMoneyAllocator.LevelShare>emptyList()));
    }

    @Test
    void shouldRejectInvalidCurrencyAndExcessiveScale() {
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate("", 8, 1L, Collections.<SkitMoneyAllocator.LevelShare>emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate("cny", 8, 1L, Collections.<SkitMoneyAllocator.LevelShare>emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate("CN", 8, 1L, Collections.<SkitMoneyAllocator.LevelShare>emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate("CNY", 19, 1L, Collections.<SkitMoneyAllocator.LevelShare>emptyList()));
    }

    @Test
    void shouldRejectOutOfRangeAndExcessiveRatios() {
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate("CNY", 8, 100L,
                        Collections.singletonList(share(0, 100L, -1, true))));
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate("CNY", 8, 100L,
                        Collections.singletonList(share(0, 100L, 10_001, true))));
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate("CNY", 8, 100L, Arrays.asList(
                        share(0, 100L, 6_000, true),
                        share(1, 90L, 4_001, true))));
    }

    @Test
    void shouldRejectDuplicateOrNegativeLevels() {
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate("CNY", 8, 100L,
                        Collections.singletonList(share(-1, 100L, 1_000, true))));
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate("CNY", 8, 100L, Arrays.asList(
                        share(1, 100L, 1_000, true),
                        share(1, 90L, 1_000, true))));
    }

    @Test
    void shouldRejectNullSharesNullLevelsAndInvalidMemberIds() {
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate("CNY", 8, 100L,
                        Collections.singletonList(null)));
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate("CNY", 8, 100L,
                        Collections.singletonList(share(null, 100L, 1_000, true))));
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate("CNY", 8, 100L,
                        Collections.singletonList(share(0, 0L, 1_000, true))));
        assertThrows(IllegalArgumentException.class,
                () -> allocator.allocate("CNY", 8, 100L,
                        Collections.singletonList(share(0, -1L, 1_000, true))));
    }

    @Test
    void shouldAllocateLongMaxWithoutIntermediateMultiplicationOverflow() {
        SkitMoneyAllocator.Result tinyRate = allocator.allocate("CNY", 8, Long.MAX_VALUE,
                Collections.singletonList(share(0, 100L, 2, true)));
        SkitMoneyAllocator.Result maximumRate = allocator.allocate("CNY", 8, Long.MAX_VALUE,
                Collections.singletonList(share(0, 100L, 10_000, true)));

        assertEquals(1_844_674_407_370_955L, tinyRate.getMemberAllocations().get(0).getAmountUnits());
        assertEquals(Long.MAX_VALUE, maximumRate.getMemberAllocations().get(0).getAmountUnits());
        assertEquals(0L, maximumRate.getAgentRetentionUnits());
        assertConserved(tinyRate);
        assertConserved(maximumRate);
    }

    @Test
    void shouldExposeImmutableAllocations() {
        SkitMoneyAllocator.Result result = allocator.allocate("CNY", 8, 100L,
                Collections.singletonList(share(0, 100L, 1_000, true)));

        assertThrows(UnsupportedOperationException.class,
                () -> result.getMemberAllocations().add(result.getMemberAllocations().get(0)));
    }

    @Test
    void shouldKeepRawSnapshotTrustSeamPackagePrivate() throws NoSuchMethodException {
        assertFalse(java.lang.reflect.Modifier.isPublic(SkitMoneyAllocator.class.getDeclaredMethod(
                "allocate", String.class, int.class, long.class, List.class).getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isPublic(SkitMoneyAllocator.LevelShare.class.getDeclaredConstructor(
                Integer.class, Long.class, Integer.class, boolean.class).getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isPublic(SkitPolicySnapshotService.PolicySnapshot.class
                .getDeclaredConstructors()[0].getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isPublic(SkitPolicySnapshotService.BeneficiarySlot.class
                .getDeclaredConstructors()[0].getModifiers()));
    }

    @Test
    void shouldExposeOnlyVerifiedSnapshotAdapterAcrossPackageBoundary() throws NoSuchMethodException {
        List<SkitPolicySnapshotService.BeneficiarySlot> beneficiaries = Arrays.asList(
                new SkitPolicySnapshotService.BeneficiarySlot(0, 5_000, 100L, 0, true,
                        SkitPolicySnapshotService.EligibilityReason.ELIGIBLE),
                new SkitPolicySnapshotService.BeneficiarySlot(1, 2_000, 90L, 1, false,
                        SkitPolicySnapshotService.EligibilityReason.DISABLED),
                new SkitPolicySnapshotService.BeneficiarySlot(2, 1_000, null, null, false,
                        SkitPolicySnapshotService.EligibilityReason.MISSING_ANCESTOR));
        SkitPolicySnapshotService.PolicySnapshot snapshot = new SkitPolicySnapshotService.PolicySnapshot(
                501L, 42L, 0, 11L, 100L, 7, 1, "{}", new byte[32],
                LocalDateTime.of(2026, 7, 14, 10, 11, 12),
                Collections.singletonList(new SkitPolicySnapshotService.ChainNode(0, 100L, 0, true,
                        SkitPolicySnapshotService.EligibilityReason.ELIGIBLE)),
                beneficiaries, 8_000, 5_000);

        SkitMoneyAllocator.Result result = allocator.allocate("CNY", 8, 10_003L, snapshot);

        assertEquals(1, result.getMemberAllocations().size());
        assertAllocation(result.getMemberAllocations().get(0), 0, 100L, 5_000, 5_001L);
        assertEquals(5_002L, result.getAgentRetentionUnits());
        assertConserved(result);
        assertFalse(java.lang.reflect.Modifier.isPublic(SkitMoneyAllocator.class.getDeclaredMethod(
                "allocate", String.class, int.class, long.class, List.class).getModifiers()));
    }

    private SkitMoneyAllocator.LevelShare share(Integer levelNo, Long memberId, Integer rateBps, boolean eligible) {
        return new SkitMoneyAllocator.LevelShare(levelNo, memberId, rateBps, eligible);
    }

    private void assertAllocation(SkitMoneyAllocator.MemberAllocation allocation,
                                  int levelNo, long memberId, int rateBps, long amountUnits) {
        assertEquals(levelNo, allocation.getLevelNo());
        assertEquals(memberId, allocation.getMemberId().longValue());
        assertEquals(rateBps, allocation.getRateBps());
        assertEquals(amountUnits, allocation.getAmountUnits());
    }

    private void assertConserved(SkitMoneyAllocator.Result result) {
        long memberTotal = 0L;
        for (SkitMoneyAllocator.MemberAllocation allocation : result.getMemberAllocations()) {
            memberTotal = Math.addExact(memberTotal, allocation.getAmountUnits());
        }
        assertEquals(result.getSourceAmountUnits(), Math.addExact(memberTotal, result.getAgentRetentionUnits()));
    }

}
