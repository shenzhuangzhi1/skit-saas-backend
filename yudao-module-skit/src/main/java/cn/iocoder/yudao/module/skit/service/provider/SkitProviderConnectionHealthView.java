package cn.iocoder.yudao.module.skit.service.provider;

import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderConnectionHealthProjection;
import java.time.LocalDateTime;
import java.util.Objects;

/** Explicit safe DTO. It contains no provider callback row, identifier, hash, or payload field. */
public final class SkitProviderConnectionHealthView {

  private final LocalDateTime firstReceivedAt;
  private final LocalDateTime lastReceivedAt;
  private final long acceptedAttempts;
  private final long duplicates;
  private final long conflicts;
  private final long fallback;
  private final long quarantined;
  private final Long dbFailures;
  private final LocalDateTime dbFailureAt;

  private SkitProviderConnectionHealthView(
      LocalDateTime firstReceivedAt,
      LocalDateTime lastReceivedAt,
      long acceptedAttempts,
      long duplicates,
      long conflicts,
      long fallback,
      long quarantined,
      Long dbFailures,
      LocalDateTime dbFailureAt) {
    if (firstReceivedAt != null
        && lastReceivedAt != null
        && lastReceivedAt.isBefore(firstReceivedAt)) {
      throw new IllegalStateException("Provider health timestamp range is invalid");
    }
    this.firstReceivedAt = firstReceivedAt;
    this.lastReceivedAt = lastReceivedAt;
    this.acceptedAttempts = nonNegative(acceptedAttempts, "acceptedAttempts");
    this.duplicates = nonNegative(duplicates, "duplicates");
    this.conflicts = nonNegative(conflicts, "conflicts");
    this.fallback = nonNegative(fallback, "fallback");
    this.quarantined = nonNegative(quarantined, "quarantined");
    this.dbFailures = nullableNonNegative(dbFailures, "dbFailures");
    this.dbFailureAt = dbFailureAt;
  }

  public static SkitProviderConnectionHealthView from(
      SkitProviderConnectionHealthProjection projection) {
    SkitProviderConnectionHealthProjection safe =
        Objects.requireNonNull(projection, "projection");
    return new SkitProviderConnectionHealthView(
        safe.getFirstReceivedAt(),
        safe.getLastReceivedAt(),
        value(safe.getAcceptedAttempts()),
        value(safe.getDuplicates()),
        value(safe.getConflicts()),
        value(safe.getFallback()),
        value(safe.getQuarantined()),
        safe.getDbFailures(),
        safe.getDbFailureAt());
  }

  public static SkitProviderConnectionHealthView empty() {
    return new SkitProviderConnectionHealthView(null, null, 0, 0, 0, 0, 0, null, null);
  }

  public LocalDateTime getFirstReceivedAt() {
    return firstReceivedAt;
  }

  public LocalDateTime getLastReceivedAt() {
    return lastReceivedAt;
  }

  public long getAcceptedAttempts() {
    return acceptedAttempts;
  }

  public long getDuplicates() {
    return duplicates;
  }

  public long getConflicts() {
    return conflicts;
  }

  public long getFallback() {
    return fallback;
  }

  public long getQuarantined() {
    return quarantined;
  }

  public Long getDbFailures() {
    return dbFailures;
  }

  public LocalDateTime getDbFailureAt() {
    return dbFailureAt;
  }

  @Override
  public String toString() {
    return "SkitProviderConnectionHealthView{firstReceivedAt="
        + firstReceivedAt
        + ", lastReceivedAt="
        + lastReceivedAt
        + ", acceptedAttempts="
        + acceptedAttempts
        + ", duplicates="
        + duplicates
        + ", conflicts="
        + conflicts
        + ", fallback="
        + fallback
        + ", quarantined="
        + quarantined
        + ", dbFailures="
        + dbFailures
        + ", dbFailureAt="
        + dbFailureAt
        + '}';
  }

  private static long value(Long value) {
    return value == null ? 0 : value;
  }

  private static long nonNegative(long value, String field) {
    if (value < 0) {
      throw new IllegalStateException("Provider health " + field + " is invalid");
    }
    return value;
  }

  private static Long nullableNonNegative(Long value, String field) {
    if (value != null && value < 0) {
      throw new IllegalStateException("Provider health " + field + " is invalid");
    }
    return value;
  }
}
