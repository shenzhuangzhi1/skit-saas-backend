package cn.iocoder.yudao.module.skit.service.ad;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitTenantAdCapabilityDO;

public interface SkitTenantAdReadinessEvidenceReader {

    SkitTenantAdReadinessEvidence read(Long tenantId, SkitTenantAdCapabilityDO capability);

}
