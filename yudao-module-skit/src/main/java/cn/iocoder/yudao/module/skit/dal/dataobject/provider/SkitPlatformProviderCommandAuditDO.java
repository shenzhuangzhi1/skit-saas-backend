package cn.iocoder.yudao.module.skit.dal.dataobject.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.ToString;

/** Allowlisted, append-only evidence for one successful platform provider command. */
@TenantIgnore
@TableName("skit_platform_provider_command_audit")
@Data
public class SkitPlatformProviderCommandAuditDO {

  @TableId private Long id;
  private Long actorUserId;
  private Long originalLoginTenantId;
  private String action;
  private Long providerConnectionId;
  private Long providerCallbackRouteId;
  private Long callbackRouteRegistryId;
  private String reason;
  private LocalDateTime reauthenticatedAt;
  @JsonIgnore @ToString.Exclude private byte[] requestFingerprint;
  @JsonIgnore @ToString.Exclude private byte[] beforeStateHash;
  @JsonIgnore @ToString.Exclude private byte[] afterStateHash;
  private String traceId;
  private String resultStatus;
  private String resultCode;
  private LocalDateTime occurredAt;
}
