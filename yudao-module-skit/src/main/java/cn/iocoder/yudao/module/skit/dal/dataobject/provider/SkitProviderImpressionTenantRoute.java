package cn.iocoder.yudao.module.skit.dal.dataobject.provider;

import lombok.Data;

/** Server-owned tenant route derived from an immutable ad session envelope. */
@Data
public class SkitProviderImpressionTenantRoute {

    private Long tenantId;
    private Long adAccountId;
    private Long adSessionId;
    private Integer callbackKeyVersion;
}
