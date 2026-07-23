package cn.iocoder.yudao.module.skit.controller.app.member;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.annotation.security.PermitAll;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.PROVIDER_PANGLE;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.PROVIDER_TAKU;

@Tag(name = "用户 APP - 代理商广告配置")
@RestController
@RequestMapping("/skit/member/ad-config")
public class SkitMemberAdConfigController {

    @Resource
    private SkitAdAccountService adAccountService;

    @GetMapping
    @PermitAll
    @Operation(summary = "获得当前代理商公开广告配置")
    public CommonResult<AdConfigRespVO> getAdConfig() {
        AdConfigRespVO result = new AdConfigRespVO();
        List<SkitAdAccountService.PublicConfig> configs = adAccountService.getEnabledPublicConfigs();
        for (SkitAdAccountService.PublicConfig config : configs) {
            if (PROVIDER_PANGLE.equals(config.getProvider())) {
                result.setPangle(config);
                result.setPanglePlacementId(config.getPlacementId());
            } else if (PROVIDER_TAKU.equals(config.getProvider())) {
                result.setTaku(config);
                result.setTakuPlacementId(config.getPlacementId());
                result.setCheckInEntryInterstitialPlacementId(
                        config.getCheckInEntryInterstitialPlacementId());
                result.setPostCheckInDramaInterstitialPlacementId(
                        config.getPostCheckInDramaInterstitialPlacementId());
                result.setHomeBannerPlacementId(config.getHomeBannerPlacementId());
            }
        }
        if (result.getPangle() != null && result.getTaku() != null) {
            result.setProvider("MULTI");
        } else if (result.getPangle() != null) {
            result.setProvider(PROVIDER_PANGLE);
        } else if (result.getTaku() != null) {
            result.setProvider(PROVIDER_TAKU);
        } else {
            result.setProvider("NONE");
        }
        return success(result);
    }

    @Data
    public static class AdConfigRespVO {
        private String provider;
        private String panglePlacementId;
        private String takuPlacementId;
        private String checkInEntryInterstitialPlacementId;
        private String postCheckInDramaInterstitialPlacementId;
        private String homeBannerPlacementId;
        private SkitAdAccountService.PublicConfig pangle;
        private SkitAdAccountService.PublicConfig taku;
    }
}
