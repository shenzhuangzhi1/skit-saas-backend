package cn.iocoder.yudao.module.skit.service.provider;

/** Supplies the aggregate-only health object used by the platform provider GET. */
public interface SkitProviderConnectionHealthService {

  SkitProviderConnectionHealthView getSafeHealth(long providerConnectionId);
}
