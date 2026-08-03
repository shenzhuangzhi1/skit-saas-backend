package cn.iocoder.yudao.module.skit.service.ad.callback;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitProviderConnectionCapacityGuardTest {

    @Test
    void concurrentAdmissionIsBoundedAndReleased() throws Exception {
        FakeTicker ticker = new FakeTicker();
        SkitProviderConnectionCapacityGuard guard = guard(8, 2, 1000, 1000, ticker);
        ExecutorService workers = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(20);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 20; index++) {
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    SkitProviderConnectionCapacityGuard.Permit permit = guard.tryAcquire(41L);
                    if (permit != null) {
                        admitted.incrementAndGet();
                    }
                    attempted.countDown();
                    if (permit != null) {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        permit.close();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertEquals(2, admitted.get());
            release.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
        assertNotNull(guard.tryAcquire(41L));
    }

    @Test
    void burstExhaustionRefillsAtConfiguredPeak() {
        FakeTicker ticker = new FakeTicker();
        SkitProviderConnectionCapacityGuard guard = guard(8, 4, 2, 2, ticker);

        close(guard.tryAcquire(41L));
        close(guard.tryAcquire(41L));
        assertNull(guard.tryAcquire(41L));

        ticker.advance(Duration.ofMillis(499));
        assertNull(guard.tryAcquire(41L));
        ticker.advance(Duration.ofMillis(1));
        close(guard.tryAcquire(41L));
        assertNull(guard.tryAcquire(41L));
    }

    @Test
    void doubleCloseNeverOverReleasesTheFairSemaphore() {
        SkitProviderConnectionCapacityGuard guard = guard(8, 1, 100, 100,
                new FakeTicker());
        SkitProviderConnectionCapacityGuard.Permit first = guard.tryAcquire(51L);
        assertNotNull(first);
        first.close();
        first.close();

        SkitProviderConnectionCapacityGuard.Permit second = guard.tryAcquire(51L);
        assertNotNull(second);
        assertNull(guard.tryAcquire(51L));
        second.close();
    }

    @Test
    void connectionsHaveIndependentConcurrencyAndTokenBuckets() {
        SkitProviderConnectionCapacityGuard guard = guard(8, 1, 1, 1,
                new FakeTicker());
        SkitProviderConnectionCapacityGuard.Permit first = guard.tryAcquire(61L);
        SkitProviderConnectionCapacityGuard.Permit second = guard.tryAcquire(62L);

        assertNotNull(first);
        assertNotNull(second);
        assertNull(guard.tryAcquire(61L));
        assertNull(guard.tryAcquire(62L));
        first.close();
        second.close();
    }

    @Test
    void trackedConnectionStateIsStrictlyBoundedAndInvalidIdsCannotCreateIt() {
        SkitProviderConnectionCapacityGuard guard = guard(2, 1, 10, 10,
                new FakeTicker());
        assertThrows(IllegalArgumentException.class, () -> guard.tryAcquire(0));
        assertThrows(IllegalArgumentException.class, () -> guard.tryAcquire(-1));
        close(guard.tryAcquire(71L));
        close(guard.tryAcquire(72L));

        assertNull(guard.tryAcquire(73L));
        assertNotNull(guard.tryAcquire(71L));
    }

    @Test
    void redisOutageEmergencyAdmissionIsOneFixedGlobalBucket() {
        SkitProviderConnectionCapacityGuard guard = guard(1, 2, 1000, 1000,
                new FakeTicker());
        SkitProviderConnectionCapacityGuard.Permit first = guard.tryAcquireEmergency();
        SkitProviderConnectionCapacityGuard.Permit second = guard.tryAcquireEmergency();

        assertNotNull(first);
        assertNotNull(second);
        assertNull(guard.tryAcquireEmergency());
        close(guard.tryAcquire(71L));
        assertNull(guard.tryAcquire(72L));

        first.close();
        assertNotNull(guard.tryAcquireEmergency());
        second.close();
    }

    @Test
    void invalidConfigurationFailsBeforeAnyConnectionCanBeTracked() {
        FakeTicker ticker = new FakeTicker();
        assertThrows(IllegalArgumentException.class,
                () -> guard(0, 1, 1, 1, ticker));
        assertThrows(IllegalArgumentException.class,
                () -> guard(1, 0, 1, 1, ticker));
        assertThrows(IllegalArgumentException.class,
                () -> guard(1, 1, 0, 1, ticker));
        assertThrows(IllegalArgumentException.class,
                () -> guard(1, 1, 1, 0, ticker));
    }

    private static SkitProviderConnectionCapacityGuard guard(
            int tracked, int concurrent, int peakPerSecond, int burst, FakeTicker ticker) {
        return new SkitProviderConnectionCapacityGuard(
                tracked, concurrent, peakPerSecond, burst, ticker::read);
    }

    private static void close(SkitProviderConnectionCapacityGuard.Permit permit) {
        assertNotNull(permit);
        permit.close();
    }

    private static final class FakeTicker {
        private final AtomicLong nanos = new AtomicLong();

        private long read() {
            return nanos.get();
        }

        private void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
