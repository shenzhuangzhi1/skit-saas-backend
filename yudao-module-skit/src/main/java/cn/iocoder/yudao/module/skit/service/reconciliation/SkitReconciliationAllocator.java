package cn.iocoder.yudao.module.skit.service.reconciliation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Deterministic, integer-only allocator for one authoritative report bucket.
 *
 * <p>A bucket is deliberately single-currency and carries every dimension used to isolate the
 * dedicated drama-unlock traffic. Missing, mixed, or impossible evidence fails closed to suspense;
 * no member or agent settlement is inferred from an unsafe bucket.</p>
 */
@Component
public class SkitReconciliationAllocator {

    private static final String RECONCILED = "RECONCILED";
    private static final String PARTIAL = "PARTIAL";
    private static final String SUSPENSE = "SUSPENSE";

    public Result allocate(Bucket bucket) {
        Objects.requireNonNull(bucket, "bucket");
        validateBucketMoney(bucket);
        List<EventEstimate> events = sortedEvents(bucket.getEvents());
        if (!bucket.isDimensionsComplete() || !safeDimensions(bucket, events)) {
            return suspense(bucket);
        }

        long matchedImpressions = events.size();
        Long reportImpressions = bucket.getReportImpressions();
        if (reportImpressions == null || reportImpressions < 0
                || matchedImpressions > reportImpressions) {
            return suspense(bucket);
        }
        if (matchedImpressions == 0L && reportImpressions > 0L) {
            return suspense(bucket);
        }

        final long attributable;
        if (reportImpressions == matchedImpressions) {
            attributable = bucket.getReportActualUnits();
        } else if (matchedImpressions > 0L && reportImpressions > matchedImpressions) {
            attributable = floorProductDivide(bucket.getReportActualUnits(), matchedImpressions,
                    reportImpressions);
        } else {
            attributable = 0L;
        }

        long estimateTotal = sumEstimates(events);
        if (attributable > 0L && estimateTotal == 0L) {
            return suspense(bucket);
        }

        List<EventAllocation> allocations = allocateEvents(events, attributable, estimateTotal);
        long suspenseUnits = subtractExact(bucket.getReportActualUnits(), attributable,
                "report suspense underflow");
        String status = suspenseUnits == 0L ? RECONCILED : PARTIAL;
        return new Result(status, bucket.getCurrency(), bucket.getAmountScale(),
                attributable, suspenseUnits, allocations);
    }

    private static List<EventAllocation> allocateEvents(List<EventEstimate> events,
                                                         long attributable, long estimateTotal) {
        if (events.isEmpty()) {
            return Collections.emptyList();
        }
        List<EventAllocation> result = new ArrayList<>(events.size());
        List<RemainderCandidate> remainderCandidates = new ArrayList<>(events.size());
        long allocated = 0L;
        for (int index = 0; index < events.size(); index++) {
            EventEstimate event = events.get(index);
            long amount = attributable == 0L ? 0L
                    : floorProductDivide(attributable, event.getEstimateUnits(), estimateTotal);
            allocated = addExact(allocated, amount, "event allocation total overflow");
            result.add(new EventAllocation(event.getEventId(), amount, event.isRewarded()));
            if (event.getEstimateUnits() > 0L && attributable > 0L) {
                BigInteger residue = BigInteger.valueOf(attributable)
                        .multiply(BigInteger.valueOf(event.getEstimateUnits()))
                        .remainder(BigInteger.valueOf(estimateTotal));
                remainderCandidates.add(new RemainderCandidate(index, event.getEventId(), residue));
            }
        }
        long remainder = subtractExact(attributable, allocated, "event allocation remainder underflow");
        // Hamilton/largest-remainder apportionment: fractional residue wins, then event ID is the
        // deterministic tie-break. Zero-estimate events are ineligible for remainder units.
        Collections.sort(remainderCandidates, Comparator
                .comparing(RemainderCandidate::getResidue).reversed()
                .thenComparingLong(RemainderCandidate::getEventId));
        if (remainder > remainderCandidates.size()) {
            throw new IllegalStateException("Event allocation remainder exceeds eligible events");
        }
        for (int index = 0; index < remainder; index++) {
            int allocationIndex = remainderCandidates.get(index).getIndex();
            EventAllocation prior = result.get(allocationIndex);
            result.set(allocationIndex, new EventAllocation(prior.getEventId(),
                    addExact(prior.getActualUnits(), 1L, "event allocation overflow"),
                    prior.isRewarded()));
        }
        long conserved = 0L;
        for (EventAllocation allocation : result) {
            conserved = addExact(conserved, allocation.getActualUnits(),
                    "event allocation conservation overflow");
        }
        if (conserved != attributable) {
            throw new IllegalStateException("Event allocation does not conserve attributable revenue");
        }
        return Collections.unmodifiableList(result);
    }

    private static long sumEstimates(List<EventEstimate> events) {
        long result = 0L;
        for (EventEstimate event : events) {
            result = addExact(result, event.getEstimateUnits(), "estimate total overflow");
        }
        return result;
    }

    private static List<EventEstimate> sortedEvents(List<EventEstimate> supplied) {
        List<EventEstimate> result = new ArrayList<>(
                supplied == null ? Collections.<EventEstimate>emptyList() : supplied);
        Collections.sort(result, Comparator.comparingLong(EventEstimate::getEventId));
        return result;
    }

    private static boolean safeDimensions(Bucket bucket, List<EventEstimate> events) {
        if (!canonicalCurrency(bucket.getCurrency()) || !canonicalText(bucket.getAppId())
                || !canonicalText(bucket.getReportTimezone()) || !canonicalText(bucket.getPlacementId())
                || !"rewarded_video".equals(bucket.getAdFormat()) || bucket.getNetworkFirmId() <= 0
                || !canonicalText(bucket.getNetworkAccountId()) || !canonicalText(bucket.getAdsourceId())) {
            return false;
        }
        Set<Long> eventIds = new HashSet<>();
        for (EventEstimate event : events) {
            if (event == null || event.getEventId() <= 0L || event.getEstimateUnits() < 0L
                    || !eventIds.add(event.getEventId())
                    || !bucket.getCurrency().equals(event.getCurrency())
                    || bucket.getAmountScale() != event.getAmountScale()
                    || !bucket.getAppId().equals(event.getAppId())
                    || !bucket.getReportTimezone().equals(event.getReportTimezone())
                    || !bucket.getPlacementId().equals(event.getPlacementId())
                    || !bucket.getAdFormat().equals(event.getAdFormat())
                    || bucket.getNetworkFirmId() != event.getNetworkFirmId()
                    || !bucket.getNetworkAccountId().equals(event.getNetworkAccountId())
                    || !bucket.getAdsourceId().equals(event.getAdsourceId())) {
                return false;
            }
        }
        return true;
    }

    private static void validateBucketMoney(Bucket bucket) {
        if (bucket.getReportActualUnits() < 0L) {
            throw new IllegalArgumentException("reportActualUnits must be non-negative");
        }
        if (bucket.getAmountScale() < 0 || bucket.getAmountScale() > 18) {
            throw new IllegalArgumentException("amountScale must be between 0 and 18");
        }
    }

    private static Result suspense(Bucket bucket) {
        return new Result(SUSPENSE, bucket.getCurrency(), bucket.getAmountScale(),
                0L, bucket.getReportActualUnits(), Collections.<EventAllocation>emptyList());
    }

    private static long floorProductDivide(long left, long right, long divisor) {
        if (left < 0L || right < 0L || divisor <= 0L) {
            throw new IllegalArgumentException("allocation operands are invalid");
        }
        return BigInteger.valueOf(left).multiply(BigInteger.valueOf(right))
                .divide(BigInteger.valueOf(divisor)).longValueExact();
    }

    private static long addExact(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }

    private static long subtractExact(long left, long right, String message) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }

    private static boolean canonicalCurrency(String value) {
        return value != null && value.matches("[A-Z]{3}");
    }

    private static boolean canonicalText(String value) {
        return value != null && !value.isEmpty() && value.equals(value.trim());
    }

    private static final class RemainderCandidate {
        private final int index;
        private final long eventId;
        private final BigInteger residue;

        private RemainderCandidate(int index, long eventId, BigInteger residue) {
            this.index = index;
            this.eventId = eventId;
            this.residue = residue;
        }

        private int getIndex() { return index; }
        private long getEventId() { return eventId; }
        private BigInteger getResidue() { return residue; }
    }

    public static final class Bucket {
        private final long reportActualUnits;
        private final Long reportImpressions;
        private final boolean dimensionsComplete;
        private final String currency;
        private final int amountScale;
        private final String appId;
        private final String reportTimezone;
        private final String placementId;
        private final String adFormat;
        private final int networkFirmId;
        private final String networkAccountId;
        private final String adsourceId;
        private final List<EventEstimate> events;

        public Bucket(long reportActualUnits, Long reportImpressions, boolean dimensionsComplete,
                      String currency, int amountScale, String appId, String reportTimezone,
                      String placementId, String adFormat, int networkFirmId,
                      String networkAccountId, String adsourceId, List<EventEstimate> events) {
            this.reportActualUnits = reportActualUnits;
            this.reportImpressions = reportImpressions;
            this.dimensionsComplete = dimensionsComplete;
            this.currency = currency;
            this.amountScale = amountScale;
            this.appId = appId;
            this.reportTimezone = reportTimezone;
            this.placementId = placementId;
            this.adFormat = adFormat;
            this.networkFirmId = networkFirmId;
            this.networkAccountId = networkAccountId;
            this.adsourceId = adsourceId;
            this.events = Collections.unmodifiableList(new ArrayList<>(
                    events == null ? Collections.<EventEstimate>emptyList() : events));
        }

        public long getReportActualUnits() { return reportActualUnits; }
        public Long getReportImpressions() { return reportImpressions; }
        public boolean isDimensionsComplete() { return dimensionsComplete; }
        public String getCurrency() { return currency; }
        public int getAmountScale() { return amountScale; }
        public String getAppId() { return appId; }
        public String getReportTimezone() { return reportTimezone; }
        public String getPlacementId() { return placementId; }
        public String getAdFormat() { return adFormat; }
        public int getNetworkFirmId() { return networkFirmId; }
        public String getNetworkAccountId() { return networkAccountId; }
        public String getAdsourceId() { return adsourceId; }
        public List<EventEstimate> getEvents() { return events; }
    }

    public static final class EventEstimate {
        private final long eventId;
        private final long estimateUnits;
        private final boolean rewarded;
        private final String currency;
        private final int amountScale;
        private final String appId;
        private final String reportTimezone;
        private final String placementId;
        private final String adFormat;
        private final int networkFirmId;
        private final String networkAccountId;
        private final String adsourceId;

        public EventEstimate(long eventId, long estimateUnits, boolean rewarded,
                             String currency, int amountScale, String appId, String reportTimezone,
                             String placementId, String adFormat, int networkFirmId,
                             String networkAccountId, String adsourceId) {
            this.eventId = eventId;
            this.estimateUnits = estimateUnits;
            this.rewarded = rewarded;
            this.currency = currency;
            this.amountScale = amountScale;
            this.appId = appId;
            this.reportTimezone = reportTimezone;
            this.placementId = placementId;
            this.adFormat = adFormat;
            this.networkFirmId = networkFirmId;
            this.networkAccountId = networkAccountId;
            this.adsourceId = adsourceId;
        }

        public EventEstimate withCurrency(String value) {
            return copy(value, amountScale, placementId);
        }

        public EventEstimate withAmountScale(int value) {
            return copy(currency, value, placementId);
        }

        public EventEstimate withPlacementId(String value) {
            return copy(currency, amountScale, value);
        }

        private EventEstimate copy(String currencyValue, int scaleValue, String placementValue) {
            return new EventEstimate(eventId, estimateUnits, rewarded, currencyValue, scaleValue,
                    appId, reportTimezone, placementValue, adFormat, networkFirmId,
                    networkAccountId, adsourceId);
        }

        public long getEventId() { return eventId; }
        public long getEstimateUnits() { return estimateUnits; }
        public boolean isRewarded() { return rewarded; }
        public String getCurrency() { return currency; }
        public int getAmountScale() { return amountScale; }
        public String getAppId() { return appId; }
        public String getReportTimezone() { return reportTimezone; }
        public String getPlacementId() { return placementId; }
        public String getAdFormat() { return adFormat; }
        public int getNetworkFirmId() { return networkFirmId; }
        public String getNetworkAccountId() { return networkAccountId; }
        public String getAdsourceId() { return adsourceId; }
    }

    public static final class EventAllocation {
        private final long eventId;
        private final long actualUnits;
        private final boolean rewarded;

        private EventAllocation(long eventId, long actualUnits, boolean rewarded) {
            this.eventId = eventId;
            this.actualUnits = actualUnits;
            this.rewarded = rewarded;
        }

        public long getEventId() { return eventId; }
        public long getActualUnits() { return actualUnits; }
        public boolean isRewarded() { return rewarded; }
    }

    public static final class Result {
        private final String status;
        private final String currency;
        private final int amountScale;
        private final long attributableActualUnits;
        private final long suspenseUnits;
        private final List<EventAllocation> eventAllocations;

        private Result(String status, String currency, int amountScale,
                       long attributableActualUnits, long suspenseUnits,
                       List<EventAllocation> eventAllocations) {
            this.status = status;
            this.currency = currency;
            this.amountScale = amountScale;
            this.attributableActualUnits = attributableActualUnits;
            this.suspenseUnits = suspenseUnits;
            this.eventAllocations = Collections.unmodifiableList(new ArrayList<>(eventAllocations));
        }

        public String getStatus() { return status; }
        public String getCurrency() { return currency; }
        public int getAmountScale() { return amountScale; }
        public long getAttributableActualUnits() { return attributableActualUnits; }
        public long getSuspenseUnits() { return suspenseUnits; }
        public List<EventAllocation> getEventAllocations() { return eventAllocations; }
    }
}
