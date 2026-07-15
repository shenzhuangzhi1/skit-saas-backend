package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import cn.iocoder.yudao.module.skit.service.ad.SkitTenantAdCapabilityService;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

@Data
public class SkitTenantAdCapabilityRespVO {

    private Long tenantId;
    private Long adAccountId;
    private String rolloutState;
    private String dedicatedUnlockPlacementId;
    private Set<Integer> unlockNetworkFirmIds;
    private Set<Long> shadowTestMemberIds;
    private String minNativeVersion;
    private Integer minProtocolVersion;
    private Integer readinessVersion;
    private LocalDateTime enforcedAt;

    public static SkitTenantAdCapabilityRespVO from(SkitTenantAdCapabilityService.CapabilityView source) {
        SkitTenantAdCapabilityRespVO result = new SkitTenantAdCapabilityRespVO();
        result.setTenantId(source.getTenantId());
        result.setAdAccountId(source.getAdAccountId());
        result.setRolloutState(source.getRolloutState());
        result.setDedicatedUnlockPlacementId(source.getDedicatedUnlockPlacementId());
        result.setUnlockNetworkFirmIds(source.getUnlockNetworkFirmIds());
        result.setShadowTestMemberIds(source.getShadowTestMemberIds());
        result.setMinNativeVersion(source.getMinNativeVersion());
        result.setMinProtocolVersion(source.getMinProtocolVersion());
        result.setReadinessVersion(source.getReadinessVersion());
        result.setEnforcedAt(source.getEnforcedAt());
        return result;
    }

}
