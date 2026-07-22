package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import cn.iocoder.yudao.module.skit.service.ad.SkitTenantAdCapabilityService;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Data
public class SkitAdNetworkCapabilityRespVO {

    private Integer networkFirmId;
    private String rewardAuthority;
    private boolean enabled;
    private boolean verified;
    private LocalDateTime verifiedAt;
    private boolean supportsUserId;
    private boolean supportsCustomData;
    private boolean supportsStableTransaction;
    private boolean supportsImpressionRevenue;
    private boolean supportsReporting;
    private boolean selectable;
    private List<String> blockers = Collections.emptyList();

    public static SkitAdNetworkCapabilityRespVO from(
            SkitTenantAdCapabilityService.NetworkCapabilityView source) {
        SkitAdNetworkCapabilityRespVO result = new SkitAdNetworkCapabilityRespVO();
        result.setNetworkFirmId(source.getNetworkFirmId());
        result.setRewardAuthority(source.getRewardAuthority());
        result.setEnabled(source.isEnabled());
        result.setVerified(source.isVerified());
        result.setVerifiedAt(source.getVerifiedAt());
        result.setSupportsUserId(source.isSupportsUserId());
        result.setSupportsCustomData(source.isSupportsCustomData());
        result.setSupportsStableTransaction(source.isSupportsStableTransaction());
        result.setSupportsImpressionRevenue(source.isSupportsImpressionRevenue());
        result.setSupportsReporting(source.isSupportsReporting());
        result.setSelectable(source.isSelectable());
        result.setBlockers(source.getBlockers() == null ? Collections.emptyList() : source.getBlockers());
        return result;
    }

}
