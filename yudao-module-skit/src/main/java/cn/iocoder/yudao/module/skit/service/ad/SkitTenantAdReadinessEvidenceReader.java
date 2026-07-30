package cn.iocoder.yudao.module.skit.service.ad;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitTenantAdCapabilityDO;

import java.util.List;

public interface SkitTenantAdReadinessEvidenceReader {

    SkitTenantAdReadinessEvidence read(Long tenantId, SkitTenantAdCapabilityDO capability);

    List<String> readNetworkPrerequisiteBlockers(Long tenantId, Integer networkFirmId);

}
