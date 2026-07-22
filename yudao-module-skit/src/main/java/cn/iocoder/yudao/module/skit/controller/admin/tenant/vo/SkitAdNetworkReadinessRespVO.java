package cn.iocoder.yudao.module.skit.controller.admin.tenant.vo;

import cn.iocoder.yudao.module.skit.service.ad.SkitTenantAdCapabilityService;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Data
public class SkitAdNetworkReadinessRespVO {

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
    private boolean authoritative;
    private boolean signedRewardObserved;
    private boolean impressionObserved;
    private boolean pairedSourceObserved;
    private LocalDateTime lastSignedRewardCallbackAt;
    private LocalDateTime lastImpressionCallbackAt;
    private List<String> sourceRefs = Collections.emptyList();
    private List<String> signedRewardSourceRefs = Collections.emptyList();
    private List<String> impressionSourceRefs = Collections.emptyList();
    private List<String> pairedSourceRefs = Collections.emptyList();
    private List<String> blockers = Collections.emptyList();

    public static SkitAdNetworkReadinessRespVO from(
            SkitTenantAdCapabilityService.NetworkReadinessView source) {
        SkitAdNetworkReadinessRespVO result = new SkitAdNetworkReadinessRespVO();
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
        result.setAuthoritative(source.isAuthoritative());
        result.setSignedRewardObserved(source.isSignedRewardObserved());
        result.setImpressionObserved(source.isImpressionObserved());
        result.setPairedSourceObserved(source.isPairedSourceObserved());
        result.setLastSignedRewardCallbackAt(source.getLastSignedRewardCallbackAt());
        result.setLastImpressionCallbackAt(source.getLastImpressionCallbackAt());
        result.setSourceRefs(nonNull(source.getSourceRefs()));
        result.setSignedRewardSourceRefs(nonNull(source.getSignedRewardSourceRefs()));
        result.setImpressionSourceRefs(nonNull(source.getImpressionSourceRefs()));
        result.setPairedSourceRefs(nonNull(source.getPairedSourceRefs()));
        result.setBlockers(nonNull(source.getBlockers()));
        return result;
    }

    private static List<String> nonNull(List<String> values) {
        return values == null ? Collections.emptyList() : values;
    }

}
