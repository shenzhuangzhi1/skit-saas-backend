package cn.iocoder.yudao.module.skit.service.ad.callback;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Bounded local admission for registry-verified provider connections.
 *
 * <p>The dispatcher must call this only after resolving a trusted connection id. Even if that
 * contract is violated, the state map has a hard maximum and therefore cannot grow without
 * bound from attacker-selected ids.</p>
 */
@Component
public final class SkitProviderConnectionCapacityGuard {

    private static final double NANOS_PER_SECOND = 1_000_000_000D;

    private final int maximumTrackedConnections;
    private final int maximumConcurrentPerConnection;
    private final int peakPermitsPerSecond;
    private final int burstPermits;
    private final LongSupplier ticker;
    private final ConnectionBucket emergencyBucket;
    private final Object bucketsMonitor = new Object();
    private final Map<Long, ConnectionBucket> buckets = new LinkedHashMap<>();

    @Autowired
    public SkitProviderConnectionCapacityGuard(
            SkitProviderConnectionCapacityProperties properties) {
        this(Objects.requireNonNull(properties, "properties").getMaximumTrackedConnections(),
                properties.getMaximumConcurrentPerConnection(),
                properties.getPeakPermitsPerSecond(), properties.getBurstPermits(),
                System::nanoTime);
    }

    SkitProviderConnectionCapacityGuard(int maximumTrackedConnections,
                                        int maximumConcurrentPerConnection,
                                        int peakPermitsPerSecond, int burstPermits,
                                        LongSupplier ticker) {
        if (maximumTrackedConnections <= 0 || maximumConcurrentPerConnection <= 0
                || peakPermitsPerSecond <= 0 || burstPermits <= 0) {
            throw new IllegalArgumentException("Provider callback capacity configuration is invalid");
        }
        this.maximumTrackedConnections = maximumTrackedConnections;
        this.maximumConcurrentPerConnection = maximumConcurrentPerConnection;
        this.peakPermitsPerSecond = peakPermitsPerSecond;
        this.burstPermits = burstPermits;
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.emergencyBucket = new ConnectionBucket(maximumConcurrentPerConnection,
                peakPermitsPerSecond, burstPermits, this.ticker.getAsLong());
    }

    /**
     * Bounds all pre-registry work during Redis degradation in one fixed, attacker-independent
     * bucket. Verified provider impressions still acquire their connection bucket afterwards.
     */
    public Permit tryAcquireEmergency() {
        return emergencyBucket.tryAcquire(ticker.getAsLong());
    }

    /** Returns {@code null} immediately when concurrency, peak, burst, or map capacity is spent. */
    public Permit tryAcquire(long providerConnectionId) {
        if (providerConnectionId <= 0) {
            throw new IllegalArgumentException("Verified provider connection id is invalid");
        }
        ConnectionBucket bucket = bucket(providerConnectionId);
        return bucket == null ? null : bucket.tryAcquire(ticker.getAsLong());
    }

    private ConnectionBucket bucket(long providerConnectionId) {
        synchronized (bucketsMonitor) {
            ConnectionBucket existing = buckets.get(providerConnectionId);
            if (existing != null) {
                return existing;
            }
            if (buckets.size() >= maximumTrackedConnections) {
                return null;
            }
            ConnectionBucket created = new ConnectionBucket(maximumConcurrentPerConnection,
                    peakPermitsPerSecond, burstPermits, ticker.getAsLong());
            buckets.put(providerConnectionId, created);
            return created;
        }
    }

    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    private static final class ConnectionBucket {

        private final Semaphore concurrent;
        private final int peakPermitsPerSecond;
        private final int burstPermits;
        private double availableTokens;
        private long lastRefillNanos;

        private ConnectionBucket(int maximumConcurrent, int peakPermitsPerSecond,
                                 int burstPermits, long nowNanos) {
            this.concurrent = new Semaphore(maximumConcurrent, true);
            this.peakPermitsPerSecond = peakPermitsPerSecond;
            this.burstPermits = burstPermits;
            this.availableTokens = burstPermits;
            this.lastRefillNanos = nowNanos;
        }

        private Permit tryAcquire(long nowNanos) {
            boolean concurrencyAcquired;
            try {
                // Timed acquisition, including zero timeout, observes fair semaphore ordering.
                concurrencyAcquired = concurrent.tryAcquire(0, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (!concurrencyAcquired) {
                return null;
            }
            if (!consumeToken(nowNanos)) {
                concurrent.release();
                return null;
            }
            return new IdempotentPermit(concurrent);
        }

        private synchronized boolean consumeToken(long nowNanos) {
            long elapsed = nowNanos - lastRefillNanos;
            if (elapsed > 0) {
                double refilled = elapsed * (double) peakPermitsPerSecond / NANOS_PER_SECOND;
                availableTokens = Math.min(burstPermits, availableTokens + refilled);
                lastRefillNanos = nowNanos;
            }
            if (availableTokens + 1e-9D < 1D) {
                return false;
            }
            availableTokens = Math.max(0D, availableTokens - 1D);
            return true;
        }
    }

    private static final class IdempotentPermit implements Permit {

        private final Semaphore concurrent;
        private final AtomicBoolean closed = new AtomicBoolean();

        private IdempotentPermit(Semaphore concurrent) {
            this.concurrent = concurrent;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                concurrent.release();
            }
        }
    }
}
