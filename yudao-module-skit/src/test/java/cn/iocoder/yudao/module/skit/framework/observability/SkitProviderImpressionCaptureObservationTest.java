package cn.iocoder.yudao.module.skit.framework.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.iocoder.yudao.module.skit.framework.observability.SkitProviderImpressionCaptureObservation.CallbackKind;
import cn.iocoder.yudao.module.skit.framework.observability.SkitProviderImpressionCaptureObservation.FormatBucket;
import cn.iocoder.yudao.module.skit.framework.observability.SkitProviderImpressionCaptureObservation.PersistenceFailure;
import cn.iocoder.yudao.module.skit.framework.observability.SkitProviderImpressionCaptureObservation.RouteKind;
import cn.iocoder.yudao.module.skit.framework.observability.SkitProviderImpressionCaptureObservation.TransactionOutcome;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionWireParser;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SkitProviderImpressionCaptureObservationTest {

  private static final LocalDateTime ACCEPTED_AT =
      LocalDateTime.of(2026, 8, 3, 8, 9, 10);
  private static final Set<String> TAG_KEYS =
      setOf("provider", "route_type", "callback_type", "decision", "format");
  private static final Set<String> ROUTES = setOf("unknown", "provider", "tenant");
  private static final Set<String> CALLBACKS = setOf("unknown", "impression", "reward");
  private static final Set<String> FORMATS =
      setOf("0", "1", "2", "3", "4", "unknown", "missing");

  @Test
  void everyRequiredDecisionUsesOnlyConstantAllowlistedTags() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SkitProviderImpressionCaptureObservation observation =
        new SkitProviderImpressionCaptureObservation(registry);

    for (RouteKind route : RouteKind.values()) {
      for (CallbackKind callback : CallbackKind.values()) {
        observation.recordRequest(route, callback);
        observation.recordRejected602(route, callback, FormatBucket.UNKNOWN);
        observation.recordFailure503(route, callback, FormatBucket.MISSING);
        observation.recordRedisDegradation(route, callback);
      }
    }
    for (FormatBucket format : FormatBucket.values()) {
      observation.recordAccepted200AfterCommit(format, ACCEPTED_AT);
      observation.recordTransactionDuration(
          Duration.ofMillis(17), TransactionOutcome.COMMITTED, format);
      observation.recordTransactionDuration(
          Duration.ofMillis(19), TransactionOutcome.FAILED, format);
      for (PersistenceFailure failure : PersistenceFailure.values()) {
        observation.recordPersistenceFailure(failure, format);
      }
      observation.recordDuplicate(format);
      observation.recordConflict(format);
      observation.recordFallback(format);
      observation.recordQuarantined(format);
      observation.recordCapacityReject(format);
    }

    assertFalse(registry.getMeters().isEmpty());
    Set<String> decisions =
        registry.getMeters().stream()
            .flatMap(meter -> meter.getId().getTags().stream())
            .filter(tag -> "decision".equals(tag.getKey()))
            .map(Tag::getValue)
            .collect(Collectors.toSet());
    assertTrue(
        decisions.containsAll(
            setOf(
                "request",
                "200",
                "602",
                "503",
                "transaction_committed",
                "transaction_failed",
                "persistence_crypto",
                "persistence_database",
                "persistence_transaction",
                "persistence_internal",
                "duplicate",
                "conflict",
                "fallback",
                "quarantined",
                "capacity_reject",
                "redis_degraded")));
    for (Meter meter : registry.getMeters()) {
      Set<String> actualKeys =
          meter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet());
      assertEquals(TAG_KEYS, actualKeys, meter.getId().toString());
      assertEquals("TAKU", meter.getId().getTag("provider"));
      assertTrue(ROUTES.contains(meter.getId().getTag("route_type")));
      assertTrue(CALLBACKS.contains(meter.getId().getTag("callback_type")));
      assertTrue(FORMATS.contains(meter.getId().getTag("format")));
      assertTrue(
          SkitProviderImpressionCaptureObservation.allowedDecisionTagValues()
              .contains(meter.getId().getTag("decision")));
    }
  }

  private static Set<String> setOf(String... values) {
    return new HashSet<>(Arrays.asList(values));
  }

  @Test
  void publicRecordingApiCannotAcceptDynamicStringsExceptionsOrIdentifiers() {
    for (Method method : SkitProviderImpressionCaptureObservation.class.getDeclaredMethods()) {
      if (!Modifier.isPublic(method.getModifiers()) || !method.getName().startsWith("record")) {
        continue;
      }
      for (Class<?> parameter : method.getParameterTypes()) {
        assertFalse(parameter == String.class, method.toString());
        assertFalse(Throwable.class.isAssignableFrom(parameter), method.toString());
        assertFalse(parameter == Long.TYPE || parameter == Long.class, method.toString());
        assertFalse(parameter == byte[].class || parameter == char[].class, method.toString());
      }
    }
  }

  @Test
  void formatClassifierEmitsOnlyBoundedBucketsAndNeverRetainsWireValues() {
    String sentinelPackage = "sentinel.package.do.not.export";
    String sentinelPlacement = "sentinel-placement-do-not-export";
    String sentinelRequest = "sentinel-request-do-not-export";
    String base =
        "req_id="
            + sentinelRequest
            + "&adsource_id=1&package_name="
            + sentinelPackage
            + "&placement_id="
            + sentinelPlacement;
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SkitProviderImpressionCaptureObservation observation =
        new SkitProviderImpressionCaptureObservation(registry);

    assertEquals(FormatBucket.MISSING, bucket(base));
    assertEquals(FormatBucket.ZERO, bucket(base + "&adformat=000"));
    assertEquals(FormatBucket.ONE, bucket(base + "&adformat=1"));
    assertEquals(FormatBucket.TWO, bucket(base + "&adformat=02"));
    assertEquals(FormatBucket.THREE, bucket(base + "&adformat=3"));
    assertEquals(FormatBucket.FOUR, bucket(base + "&adformat=4"));
    assertEquals(FormatBucket.UNKNOWN, bucket(base + "&adformat=5"));
    assertEquals(FormatBucket.UNKNOWN, bucket(base + "&adformat=1&adformat=2"));
    assertEquals(FormatBucket.UNKNOWN, bucket(base + "&adformat=sentinel-device-format"));

    observation.recordAccepted200AfterCommit(FormatBucket.ONE, ACCEPTED_AT);
    String meters = registry.getMeters().toString();
    String rendered = observation.toString();
    for (String sentinel :
        Arrays.asList(
            sentinelPackage,
            sentinelPlacement,
            sentinelRequest,
            "sentinel-device-format",
            "203.0.113.77",
            "acct_sentinel_callback_key")) {
      assertFalse(meters.contains(sentinel), sentinel);
      assertFalse(rendered.contains(sentinel), sentinel);
    }
  }

  @Test
  void acceptedCounterAndTimestampChangeOnlyThroughExplicitPostCommitMethod() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SkitProviderImpressionCaptureObservation observation =
        new SkitProviderImpressionCaptureObservation(registry);

    observation.recordRequest(RouteKind.PROVIDER, CallbackKind.IMPRESSION);
    observation.recordPersistenceFailure(PersistenceFailure.TRANSACTION, FormatBucket.ONE);
    observation.recordFailure503(
        RouteKind.PROVIDER, CallbackKind.IMPRESSION, FormatBucket.ONE);

    assertEquals(0D, acceptedCount(registry), 0.001D);
    assertEquals(0D, lastAccepted(registry), 0.001D);

    observation.recordAccepted200AfterCommit(FormatBucket.ONE, ACCEPTED_AT);

    assertEquals(1D, acceptedCount(registry), 0.001D);
    assertEquals(ACCEPTED_AT.toEpochSecond(ZoneOffset.UTC), lastAccepted(registry), 0.001D);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            observation.recordAccepted200AfterCommit(
                FormatBucket.ONE, ACCEPTED_AT.withNano(1)));
  }

  @Test
  void rejectsNullEnumsAndInvalidDurationsBeforeRegisteringMeters() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SkitProviderImpressionCaptureObservation observation =
        new SkitProviderImpressionCaptureObservation(registry);

    assertThrows(
        NullPointerException.class,
        () -> observation.recordRequest(null, CallbackKind.IMPRESSION));
    assertThrows(
        NullPointerException.class,
        () ->
            observation.recordRejected602(
                RouteKind.PROVIDER, CallbackKind.IMPRESSION, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            observation.recordTransactionDuration(
                Duration.ofNanos(-1), TransactionOutcome.FAILED, FormatBucket.UNKNOWN));
    assertThrows(
        NullPointerException.class,
        () -> observation.recordPersistenceFailure(null, FormatBucket.UNKNOWN));

    assertTrue(registry.getMeters().isEmpty());
  }

  private static FormatBucket bucket(String query) {
    try (SkitProviderImpressionWireParser.WirePayload payload =
        new SkitProviderImpressionWireParser().parseBounded(query)) {
      return FormatBucket.fromWirePayload(payload);
    }
  }

  private static double acceptedCount(SimpleMeterRegistry registry) {
    Counter counter = registry
        .find(SkitProviderImpressionCaptureObservation.RESPONSE_COUNTER_NAME)
        .tag("provider", "TAKU")
        .tag("route_type", "provider")
        .tag("callback_type", "impression")
        .tag("decision", "200")
        .tag("format", "1")
        .counter();
    return counter == null ? 0D : counter.count();
  }

  private static double lastAccepted(SimpleMeterRegistry registry) {
    Gauge gauge = registry
        .find(SkitProviderImpressionCaptureObservation.LAST_ACCEPTED_GAUGE_NAME)
        .tag("provider", "TAKU")
        .tag("route_type", "provider")
        .tag("callback_type", "impression")
        .tag("decision", "200")
        .tag("format", "1")
        .gauge();
    return gauge == null ? 0D : gauge.value();
  }
}
