package cn.iocoder.yudao.module.skit.framework.observability;

import cn.iocoder.yudao.module.skit.service.ad.callback.SkitProviderImpressionWireParser;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality telemetry for the account-level Taku impression capture boundary.
 *
 * <p>Every tag key and value is selected from enums in this class. Callers cannot pass callback
 * keys, connection identifiers, request material, network metadata, exception messages, or tenant
 * identifiers into a metric identity.
 */
@Component
public class SkitProviderImpressionCaptureObservation {

  public static final String REQUEST_COUNTER_NAME = "skit.provider.impression.callback.request";
  public static final String RESPONSE_COUNTER_NAME = "skit.provider.impression.callback.response";
  public static final String TRANSACTION_TIMER_NAME =
      "skit.provider.impression.callback.transaction.duration";
  public static final String EVENT_COUNTER_NAME = "skit.provider.impression.callback.event";
  public static final String LAST_ACCEPTED_GAUGE_NAME =
      "skit.provider.impression.callback.last.accepted.epoch";

  private static final String PROVIDER = "TAKU";
  private static final Set<String> ALLOWED_DECISIONS = allowedDecisions();

  private final MeterRegistry registry;
  private final ConcurrentMap<FormatBucket, AtomicLong> lastAcceptedEpochSeconds =
      new ConcurrentHashMap<>();

  public SkitProviderImpressionCaptureObservation(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  public void recordRequest(RouteKind route, CallbackKind callback) {
    increment(REQUEST_COUNTER_NAME, route, callback, Decision.REQUEST, FormatBucket.MISSING);
  }

  /** Records a transport 200 and its timestamp only after the capture transaction committed. */
  public void recordAccepted200AfterCommit(FormatBucket format, LocalDateTime acceptedAt) {
    FormatBucket safeFormat = Objects.requireNonNull(format, "format");
    LocalDateTime safeAcceptedAt = requireUtcSecond(acceptedAt);
    increment(
        RESPONSE_COUNTER_NAME,
        RouteKind.PROVIDER,
        CallbackKind.IMPRESSION,
        Decision.ACK_200,
        safeFormat);
    AtomicLong timestamp =
        lastAcceptedEpochSeconds.computeIfAbsent(
            safeFormat,
            bucket -> {
              AtomicLong state = new AtomicLong();
              Gauge.builder(LAST_ACCEPTED_GAUGE_NAME, state, AtomicLong::get)
                  .tags(tags(RouteKind.PROVIDER, CallbackKind.IMPRESSION, Decision.ACK_200, bucket))
                  .description("UTC epoch second of the last committed Taku impression capture")
                  .strongReference(true)
                  .register(registry);
              return state;
            });
    timestamp.accumulateAndGet(safeAcceptedAt.toEpochSecond(ZoneOffset.UTC), Math::max);
  }

  public void recordRejected602(
      RouteKind route, CallbackKind callback, FormatBucket format) {
    increment(RESPONSE_COUNTER_NAME, route, callback, Decision.REJECT_602, format);
  }

  public void recordFailure503(RouteKind route, CallbackKind callback, FormatBucket format) {
    increment(RESPONSE_COUNTER_NAME, route, callback, Decision.FAILURE_503, format);
  }

  public void recordTransactionDuration(
      Duration duration, TransactionOutcome outcome, FormatBucket format) {
    if (duration == null || duration.isNegative()) {
      throw new IllegalArgumentException("Provider capture transaction duration must be non-negative");
    }
    TransactionOutcome safeOutcome = Objects.requireNonNull(outcome, "outcome");
    FormatBucket safeFormat = Objects.requireNonNull(format, "format");
    Timer.builder(TRANSACTION_TIMER_NAME)
        .tags(
            tags(
                RouteKind.PROVIDER,
                CallbackKind.IMPRESSION,
                safeOutcome.decision,
                safeFormat))
        .description("Taku impression capture transaction duration")
        .register(registry)
        .record(duration);
  }

  public void recordPersistenceFailure(PersistenceFailure failure, FormatBucket format) {
    PersistenceFailure safeFailure = Objects.requireNonNull(failure, "failure");
    increment(
        EVENT_COUNTER_NAME,
        RouteKind.PROVIDER,
        CallbackKind.IMPRESSION,
        safeFailure.decision,
        format);
  }

  public void recordDuplicate(FormatBucket format) {
    recordCaptureEvent(Decision.DUPLICATE, format);
  }

  public void recordConflict(FormatBucket format) {
    recordCaptureEvent(Decision.CONFLICT, format);
  }

  public void recordFallback(FormatBucket format) {
    recordCaptureEvent(Decision.FALLBACK, format);
  }

  public void recordQuarantined(FormatBucket format) {
    recordCaptureEvent(Decision.QUARANTINED, format);
  }

  public void recordCapacityReject(FormatBucket format) {
    recordCaptureEvent(Decision.CAPACITY_REJECT, format);
  }

  public void recordRedisDegradation(RouteKind route, CallbackKind callback) {
    increment(
        EVENT_COUNTER_NAME,
        route,
        callback,
        Decision.REDIS_DEGRADED,
        FormatBucket.MISSING);
  }

  public static Set<String> allowedDecisionTagValues() {
    return ALLOWED_DECISIONS;
  }

  private void recordCaptureEvent(Decision decision, FormatBucket format) {
    increment(
        EVENT_COUNTER_NAME,
        RouteKind.PROVIDER,
        CallbackKind.IMPRESSION,
        decision,
        format);
  }

  private void increment(
      String metricName,
      RouteKind route,
      CallbackKind callback,
      Decision decision,
      FormatBucket format) {
    Counter.builder(metricName)
        .tags(tags(route, callback, decision, format))
        .description("Bounded Taku impression callback signal")
        .register(registry)
        .increment();
  }

  private static Tags tags(
      RouteKind route, CallbackKind callback, Decision decision, FormatBucket format) {
    return Tags.of(
        "provider",
        PROVIDER,
        "route_type",
        Objects.requireNonNull(route, "route").tagValue,
        "callback_type",
        Objects.requireNonNull(callback, "callback").tagValue,
        "decision",
        Objects.requireNonNull(decision, "decision").tagValue,
        "format",
        Objects.requireNonNull(format, "format").tagValue);
  }

  private static LocalDateTime requireUtcSecond(LocalDateTime value) {
    if (value == null || value.getNano() != 0) {
      throw new IllegalArgumentException("Accepted timestamp must use UTC second precision");
    }
    return value;
  }

  private static Set<String> allowedDecisions() {
    Set<String> result = new LinkedHashSet<>();
    for (Decision decision : Decision.values()) {
      result.add(decision.tagValue);
    }
    return Collections.unmodifiableSet(result);
  }

  @Override
  public String toString() {
    return "SkitProviderImpressionCaptureObservation{provider=TAKU, tags=<constant-allowlist>}";
  }

  public enum RouteKind {
    UNKNOWN("unknown"),
    PROVIDER("provider"),
    TENANT("tenant");

    private final String tagValue;

    RouteKind(String tagValue) {
      this.tagValue = tagValue;
    }
  }

  public enum CallbackKind {
    UNKNOWN("unknown"),
    IMPRESSION("impression"),
    REWARD("reward");

    private final String tagValue;

    CallbackKind(String tagValue) {
      this.tagValue = tagValue;
    }
  }

  public enum TransactionOutcome {
    COMMITTED(Decision.TRANSACTION_COMMITTED),
    FAILED(Decision.TRANSACTION_FAILED);

    private final Decision decision;

    TransactionOutcome(Decision decision) {
      this.decision = decision;
    }
  }

  public enum PersistenceFailure {
    CRYPTO(Decision.PERSISTENCE_CRYPTO),
    DATABASE(Decision.PERSISTENCE_DATABASE),
    TRANSACTION(Decision.PERSISTENCE_TRANSACTION),
    INTERNAL(Decision.PERSISTENCE_INTERNAL);

    private final Decision decision;

    PersistenceFailure(Decision decision) {
      this.decision = decision;
    }
  }

  public enum FormatBucket {
    ZERO("0"),
    ONE("1"),
    TWO("2"),
    THREE("3"),
    FOUR("4"),
    UNKNOWN("unknown"),
    MISSING("missing");

    private final String tagValue;

    FormatBucket(String tagValue) {
      this.tagValue = tagValue;
    }

    /** Reduces the parsed adformat to the finite 0..4/unknown/missing metric vocabulary. */
    public static FormatBucket fromWirePayload(
        SkitProviderImpressionWireParser.WirePayload payload) {
      Objects.requireNonNull(payload, "payload");
      byte[] candidate = null;
      int occurrences = 0;
      try {
        for (SkitProviderImpressionWireParser.WireParameter parameter : payload.getParameters()) {
          if (!"adformat".equals(parameter.getName())) {
            continue;
          }
          occurrences++;
          if (occurrences > 1 || !parameter.isDecodable()) {
            return UNKNOWN;
          }
          candidate = parameter.getDecodedValueUtf8();
        }
        if (occurrences == 0) {
          return MISSING;
        }
        return fromAsciiDigits(candidate);
      } finally {
        if (candidate != null) {
          Arrays.fill(candidate, (byte) 0);
        }
      }
    }

    private static FormatBucket fromAsciiDigits(byte[] value) {
      if (value == null || value.length == 0 || value.length > 32) {
        return UNKNOWN;
      }
      int firstNonZero = value.length - 1;
      for (int index = 0; index < value.length; index++) {
        int current = value[index] & 0xff;
        if (current < '0' || current > '9') {
          return UNKNOWN;
        }
        if (current != '0') {
          firstNonZero = index;
          break;
        }
      }
      if (value.length - firstNonZero != 1) {
        return UNKNOWN;
      }
      switch (value[firstNonZero]) {
        case '0':
          return ZERO;
        case '1':
          return ONE;
        case '2':
          return TWO;
        case '3':
          return THREE;
        case '4':
          return FOUR;
        default:
          return UNKNOWN;
      }
    }
  }

  private enum Decision {
    REQUEST("request"),
    ACK_200("200"),
    REJECT_602("602"),
    FAILURE_503("503"),
    TRANSACTION_COMMITTED("transaction_committed"),
    TRANSACTION_FAILED("transaction_failed"),
    PERSISTENCE_CRYPTO("persistence_crypto"),
    PERSISTENCE_DATABASE("persistence_database"),
    PERSISTENCE_TRANSACTION("persistence_transaction"),
    PERSISTENCE_INTERNAL("persistence_internal"),
    DUPLICATE("duplicate"),
    CONFLICT("conflict"),
    FALLBACK("fallback"),
    QUARANTINED("quarantined"),
    CAPACITY_REJECT("capacity_reject"),
    REDIS_DEGRADED("redis_degraded");

    private final String tagValue;

    Decision(String tagValue) {
      this.tagValue = tagValue;
    }
  }
}
