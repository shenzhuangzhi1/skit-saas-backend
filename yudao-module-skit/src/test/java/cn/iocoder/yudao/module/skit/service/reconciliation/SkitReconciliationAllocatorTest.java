package cn.iocoder.yudao.module.skit.service.reconciliation;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitReconciliationAllocatorTest {

    private final SkitReconciliationAllocator allocator = new SkitReconciliationAllocator();

    @Test
    void exactImpressionMatchAttributesEveryUnitWithStableEventRemainder() {
        SkitReconciliationAllocator.Result result = allocator.allocate(bucket(
                101L, 3L, true, event(30L, 30L), event(10L, 10L), event(20L, 20L)));

        assertEquals("RECONCILED", result.getStatus());
        assertEquals(101L, result.getAttributableActualUnits());
        assertEquals(0L, result.getSuspenseUnits());
        assertEventAmounts(result, 10L, 17L, 20L, 34L, 30L, 50L);
    }

    @Test
    void partialLocalCoverageFloorsAttributedShareAndKeepsResidualInSuspense() {
        SkitReconciliationAllocator.Result result = allocator.allocate(bucket(
                1_001L, 10L, true, event(2L, 1L), event(1L, 1L), event(3L, 1L)));

        assertEquals("PARTIAL", result.getStatus());
        assertEquals(300L, result.getAttributableActualUnits());
        assertEquals(701L, result.getSuspenseUnits());
        assertEventAmounts(result, 1L, 100L, 2L, 100L, 3L, 100L);
    }

    @Test
    void impossibleOrInsufficientReportDimensionsKeepTheWholeBucketInSuspense() {
        List<SkitReconciliationAllocator.Bucket> unsafe = Arrays.asList(
                bucket(500L, 1L, true, event(1L, 1L), event(2L, 1L)),
                bucket(500L, null, true, event(1L, 1L)),
                bucket(500L, 1L, false, event(1L, 1L)),
                bucketWithEvent(500L, 1L, true,
                        event(1L, 1L).withPlacementId("foreign-placement")));

        for (SkitReconciliationAllocator.Bucket bucket : unsafe) {
            SkitReconciliationAllocator.Result result = allocator.allocate(bucket);
            assertEquals("SUSPENSE", result.getStatus());
            assertEquals(0L, result.getAttributableActualUnits());
            assertEquals(500L, result.getSuspenseUnits());
            assertTrue(result.getEventAllocations().isEmpty());
        }
    }

    @Test
    void positiveActualWithZeroEstimateIsNeverGuessed() {
        SkitReconciliationAllocator.Result result = allocator.allocate(bucket(
                77L, 2L, true, event(1L, 0L), event(2L, 0L)));

        assertEquals("SUSPENSE", result.getStatus());
        assertEquals(0L, result.getAttributableActualUnits());
        assertEquals(77L, result.getSuspenseUnits());
        assertTrue(result.getEventAllocations().isEmpty());
    }

    @Test
    void reportWithNoMatchedLocalEventsKeepsWholeBucketInSuspense() {
        SkitReconciliationAllocator.Result result = allocator.allocate(bucket(
                77L, 2L, true));

        assertEquals("SUSPENSE", result.getStatus());
        assertEquals(0L, result.getAttributableActualUnits());
        assertEquals(77L, result.getSuspenseUnits());
        assertTrue(result.getEventAllocations().isEmpty());
    }

    @Test
    void largestFractionalRemainderNeverAwardsAZeroEstimateEvent() {
        SkitReconciliationAllocator.Result result = allocator.allocate(bucket(
                1L, 3L, true, event(1L, 0L), event(2L, 1L), event(3L, 1L)));

        assertEquals("RECONCILED", result.getStatus());
        assertEventAmounts(result, 1L, 0L, 2L, 1L, 3L, 0L);
    }

    @Test
    void zeroIncomeReconcilesWithoutInventingMoney() {
        SkitReconciliationAllocator.Result result = allocator.allocate(bucket(
                0L, 2L, true, event(9L, 10L), event(8L, 20L)));

        assertEquals("RECONCILED", result.getStatus());
        assertEquals(0L, result.getAttributableActualUnits());
        assertEquals(0L, result.getSuspenseUnits());
        assertEventAmounts(result, 8L, 0L, 9L, 0L);
    }

    @Test
    void oneBucketNeverMixesCurrencyOrPrecision() {
        SkitReconciliationAllocator.EventEstimate wrongCurrency = event(1L, 1L)
                .withCurrency("CNY");
        SkitReconciliationAllocator.EventEstimate wrongScale = event(1L, 1L)
                .withAmountScale(6);

        assertEquals("SUSPENSE", allocator.allocate(
                bucketWithEvent(10L, 1L, true, wrongCurrency)).getStatus());
        assertEquals("SUSPENSE", allocator.allocate(
                bucketWithEvent(10L, 1L, true, wrongScale)).getStatus());

        SkitReconciliationAllocator.Result usd = allocator.allocate(bucket(
                10L, 1L, true, event(1L, 1L)));
        SkitReconciliationAllocator.Result cny = allocator.allocate(new SkitReconciliationAllocator.Bucket(
                10L, 1L, true, "CNY", 8, "app", "UTC+8", "placement",
                "rewarded_video", 7, "network", "adsource",
                Collections.singletonList(event(2L, 1L).withCurrency("CNY"))));
        assertEquals("USD", usd.getCurrency());
        assertEquals("CNY", cny.getCurrency());
    }

    @Test
    void integerProductsUseOverflowSafeArithmetic() {
        SkitReconciliationAllocator.Result result = allocator.allocate(bucket(
                Long.MAX_VALUE - 7, 2L, true,
                event(1L, Long.MAX_VALUE - 10), event(2L, 10L)));

        assertEquals(Long.MAX_VALUE - 7, result.getAttributableActualUnits());
        assertEquals(Long.MAX_VALUE - 7,
                result.getEventAllocations().stream().mapToLong(
                        SkitReconciliationAllocator.EventAllocation::getActualUnits).sum());
    }

    private SkitReconciliationAllocator.Bucket bucket(long actual, Long impressions,
                                                       boolean dimensionsComplete,
                                                       SkitReconciliationAllocator.EventEstimate... events) {
        return new SkitReconciliationAllocator.Bucket(actual, impressions, dimensionsComplete,
                "USD", 8, "app", "UTC+8", "placement", "rewarded_video",
                7, "network", "adsource", Arrays.asList(events));
    }

    private SkitReconciliationAllocator.Bucket bucketWithEvent(long actual, Long impressions,
                                                                boolean dimensionsComplete,
                                                                SkitReconciliationAllocator.EventEstimate event) {
        return bucket(actual, impressions, dimensionsComplete, event);
    }

    private SkitReconciliationAllocator.EventEstimate event(long id, long estimate) {
        return new SkitReconciliationAllocator.EventEstimate(id, estimate, true,
                "USD", 8, "app", "UTC+8", "placement", "rewarded_video",
                7, "network", "adsource");
    }

    private void assertEventAmounts(SkitReconciliationAllocator.Result result, long... pairs) {
        assertEquals(0, pairs.length % 2);
        assertEquals(pairs.length / 2, result.getEventAllocations().size());
        for (int index = 0; index < pairs.length; index += 2) {
            long eventId = pairs[index];
            long amount = pairs[index + 1];
            SkitReconciliationAllocator.EventAllocation allocation = result.getEventAllocations().stream()
                    .filter(row -> row.getEventId() == eventId).findFirst().orElseThrow(AssertionError::new);
            assertEquals(amount, allocation.getActualUnits());
        }
    }
}
