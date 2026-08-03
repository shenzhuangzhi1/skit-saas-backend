package cn.iocoder.yudao.module.skit.service.provider;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitProviderConnectionHealthProjection;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitProviderConnectionHealthMapper;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Reads one bounded global aggregate without changing the caller's tenant context. */
@Service
@TenantIgnore
public class SkitProviderConnectionHealthServiceImpl
    implements SkitProviderConnectionHealthService {

  private final SkitProviderConnectionHealthMapper mapper;

  public SkitProviderConnectionHealthServiceImpl(SkitProviderConnectionHealthMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  @TenantIgnore
  public SkitProviderConnectionHealthView getSafeHealth(long providerConnectionId) {
    if (providerConnectionId <= 0) {
      throw new IllegalArgumentException("Provider connection id must be positive");
    }
    SkitProviderConnectionHealthProjection projection =
        mapper.selectSafeAggregateByConnectionId(providerConnectionId);
    return projection == null
        ? SkitProviderConnectionHealthView.empty()
        : SkitProviderConnectionHealthView.from(projection);
  }
}
