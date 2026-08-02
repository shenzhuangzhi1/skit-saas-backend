package cn.iocoder.yudao.module.skit.controller.admin.provider.vo;

import cn.iocoder.yudao.module.skit.service.provider.SkitPlatformProviderCommandExecutor;
import java.time.LocalDateTime;

/** Explicit HTTP allowlist for provider connection and route state. */
public final class SkitProviderConnectionRespVO {

  private final long connectionId;
  private final String provider;
  private final String accountMode;
  private final String connectionState;
  private final Long activeCallbackRouteId;
  private final LocalDateTime connectionCreatedAt;
  private final LocalDateTime connectionUpdatedAt;
  private final LocalDateTime connectionBlockedAt;
  private final Long routeId;
  private final Integer routeVersion;
  private final String purpose;
  private final String routeState;
  private final String routeSlot;
  private final String canonicalOrigin;
  private final Integer callbackPathVersion;
  private final Integer callbackTemplateVersion;
  private final String callbackKeyFingerprint;
  private final LocalDateTime issuedAt;
  private final LocalDateTime submittedAt;
  private final LocalDateTime abandonedAt;
  private final LocalDateTime routeUpdatedAt;

  private SkitProviderConnectionRespVO(SkitPlatformProviderCommandExecutor.ResourceView view) {
    this.connectionId = view.getConnectionId();
    this.provider = view.getProvider();
    this.accountMode = view.getAccountMode();
    this.connectionState = view.getConnectionState();
    this.activeCallbackRouteId = view.getActiveCallbackRouteId();
    this.connectionCreatedAt = view.getConnectionCreatedAt();
    this.connectionUpdatedAt = view.getConnectionUpdatedAt();
    this.connectionBlockedAt = view.getConnectionBlockedAt();
    this.routeId = view.getRouteId();
    this.routeVersion = view.getRouteVersion();
    this.purpose = view.getPurpose();
    this.routeState = view.getRouteState();
    this.routeSlot = view.getRouteSlot();
    this.canonicalOrigin = view.getCanonicalOrigin();
    this.callbackPathVersion = view.getCallbackPathVersion();
    this.callbackTemplateVersion = view.getCallbackTemplateVersion();
    this.callbackKeyFingerprint = view.getCallbackKeyFingerprint();
    this.issuedAt = view.getIssuedAt();
    this.submittedAt = view.getSubmittedAt();
    this.abandonedAt = view.getAbandonedAt();
    this.routeUpdatedAt = view.getRouteUpdatedAt();
  }

  public static SkitProviderConnectionRespVO from(
      SkitPlatformProviderCommandExecutor.ResourceView view) {
    return new SkitProviderConnectionRespVO(view);
  }

  public long getConnectionId() {
    return connectionId;
  }

  public String getProvider() {
    return provider;
  }

  public String getAccountMode() {
    return accountMode;
  }

  public String getConnectionState() {
    return connectionState;
  }

  public Long getActiveCallbackRouteId() {
    return activeCallbackRouteId;
  }

  public LocalDateTime getConnectionCreatedAt() {
    return connectionCreatedAt;
  }

  public LocalDateTime getConnectionUpdatedAt() {
    return connectionUpdatedAt;
  }

  public LocalDateTime getConnectionBlockedAt() {
    return connectionBlockedAt;
  }

  public Long getRouteId() {
    return routeId;
  }

  public Integer getRouteVersion() {
    return routeVersion;
  }

  public String getPurpose() {
    return purpose;
  }

  public String getRouteState() {
    return routeState;
  }

  public String getRouteSlot() {
    return routeSlot;
  }

  public String getCanonicalOrigin() {
    return canonicalOrigin;
  }

  public Integer getCallbackPathVersion() {
    return callbackPathVersion;
  }

  public Integer getCallbackTemplateVersion() {
    return callbackTemplateVersion;
  }

  public String getCallbackKeyFingerprint() {
    return callbackKeyFingerprint;
  }

  public LocalDateTime getIssuedAt() {
    return issuedAt;
  }

  public LocalDateTime getSubmittedAt() {
    return submittedAt;
  }

  public LocalDateTime getAbandonedAt() {
    return abandonedAt;
  }

  public LocalDateTime getRouteUpdatedAt() {
    return routeUpdatedAt;
  }
}
