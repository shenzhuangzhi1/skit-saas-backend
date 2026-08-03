package cn.iocoder.yudao.module.skit.service.ad.callback;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;

import java.time.LocalDateTime;

/** Global retention boundary for encrypted, accounting-only provider callback evidence. */
@TenantIgnore
public interface SkitProviderImpressionRetentionService {

    @TenantIgnore
    int purgeExpiredCiphertexts(String leaseOwner, LocalDateTime now);
}
