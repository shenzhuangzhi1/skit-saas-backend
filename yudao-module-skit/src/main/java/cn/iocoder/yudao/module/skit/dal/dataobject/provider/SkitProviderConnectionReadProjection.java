package cn.iocoder.yudao.module.skit.dal.dataobject.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import java.time.LocalDateTime;
import lombok.Data;

/** Explicit database projection for the provider lifecycle GET allowlist. */
@TenantIgnore
@Data
public class SkitProviderConnectionReadProjection {

  private Long connectionId;
  private String provider;
  private String accountMode;
  private String connectionState;
  private Long activeCallbackRouteId;
  private LocalDateTime connectionCreatedAt;
  private LocalDateTime connectionUpdatedAt;
  private LocalDateTime connectionBlockedAt;
  private Long routeId;
  private Integer routeVersion;
  private String purpose;
  private String routeState;
  private String routeSlot;
  private String canonicalOrigin;
  private Integer callbackPathVersion;
  private Integer callbackTemplateVersion;
  private String callbackKeyFingerprint;
  private LocalDateTime issuedAt;
  private LocalDateTime submittedAt;
  private LocalDateTime abandonedAt;
  private LocalDateTime routeUpdatedAt;
}
