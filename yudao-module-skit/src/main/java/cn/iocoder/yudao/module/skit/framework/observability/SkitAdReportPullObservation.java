package cn.iocoder.yudao.module.skit.framework.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Low-cardinality report-pull metrics. Tenant/account/credential values are deliberately absent. */
public final class SkitAdReportPullObservation {

    public static final String COUNTER_NAME = "skit.ad.report.pull";
    public static final String TIMER_NAME = "skit.ad.report.pull.duration";
    public static final String ALERT_NAME = "skit.ad.report.pull.alert";

    private static final String PROVIDER = "TAKU";
    private static final Set<String> FAILURE_REASONS = new HashSet<>(Arrays.asList(
            "UPSTREAM_FAILURE", "CREDENTIAL_FAILURE", "PERSISTENCE_FAILURE",
            "CONFIGURATION_FAILURE", "INTERNAL_FAILURE"));

    private final MeterRegistry registry;
    private final Counter alert;

    public SkitAdReportPullObservation() {
        this(Metrics.globalRegistry);
    }

    public SkitAdReportPullObservation(MeterRegistry registry) {
        this.registry = registry;
        this.alert = Counter.builder(ALERT_NAME).tag("provider", PROVIDER)
                .tag("reason", "consecutive_failures")
                .description("Taku report accounts crossing the consecutive failure threshold")
                .register(registry);
    }

    public void recordSuccess(Duration duration) {
        record("success", "none", duration);
    }

    public void recordFailure(Duration duration, String reason) {
        record("failure", canonicalFailureReason(reason), duration);
    }

    public void recordRepeatedFailureAlert() {
        alert.increment();
    }

    private void record(String outcome, String reason, Duration duration) {
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("Report pull duration must be non-negative");
        }
        Counter.builder(COUNTER_NAME).tag("provider", PROVIDER).tag("outcome", outcome)
                .tag("reason", reason).description("Taku report pull outcomes")
                .register(registry).increment();
        Timer.builder(TIMER_NAME).tag("provider", PROVIDER).tag("outcome", outcome)
                .tag("reason", reason).description("Taku report pull end-to-end duration")
                .register(registry).record(duration);
    }

    private String canonicalFailureReason(String reason) {
        return FAILURE_REASONS.contains(reason) ? reason : "INTERNAL_FAILURE";
    }
}
