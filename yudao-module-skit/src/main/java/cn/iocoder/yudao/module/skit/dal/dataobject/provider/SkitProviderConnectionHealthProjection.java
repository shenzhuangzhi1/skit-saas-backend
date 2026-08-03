package cn.iocoder.yudao.module.skit.dal.dataobject.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import java.time.LocalDateTime;
import lombok.Data;

/** One-row, aggregate-only database projection for provider capture health. */
@TenantIgnore
@Data
public class SkitProviderConnectionHealthProjection {

  private LocalDateTime firstReceivedAt;
  private LocalDateTime lastReceivedAt;
  private Long acceptedAttempts;
  private Long duplicates;
  private Long conflicts;
  private Long fallback;
  private Long quarantined;
  private Long dbFailures;
  private LocalDateTime dbFailureAt;
}
