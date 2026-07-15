package cn.iocoder.yudao.module.skit.controller.app.member;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.module.skit.framework.observability.SkitLegacyAdRevenueObservation;
import cn.iocoder.yudao.module.skit.framework.security.SkitMemberRateLimiterKeyResolver;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "用户 APP - 广告预估收益")
@RestController
@RequestMapping("/skit/member/ad-revenue")
@Validated
public class SkitMemberAdRevenueController {

    private final SkitLegacyAdRevenueObservation observation;

    public SkitMemberAdRevenueController(SkitLegacyAdRevenueObservation observation) {
        this.observation = observation;
    }

    @PostMapping("/report")
    @PreAuthorize("@ss.hasScope('skit_member') and @skitMemberSecurityGuard.isSkitMember()")
    @RateLimiter(time = 60, count = 60, keyResolver = SkitMemberRateLimiterKeyResolver.class)
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    @Operation(summary = "兼容旧版客户端广告收益上报", description = "接口已废弃且不会产生任何财务影响，请迁移至广告会话接口")
    public CommonResult<LegacyReportRespVO> report(@Valid @RequestBody ReportReqVO reqVO) {
        observation.recordAcknowledged();
        return success(new LegacyReportRespVO(true, "LEGACY_UNVERIFIED", false, "/skit/member/ad-sessions"));
    }

    @Data
    @AllArgsConstructor
    public static class LegacyReportRespVO {
        private Boolean deprecated;
        private String status;
        private Boolean financialEffect;
        private String replacement;
    }

    @Data
    public static class ReportReqVO {
        @NotBlank
        private String provider;
        @NotBlank
        private String externalEventId;
        @NotBlank
        private String placementId;
        @NotNull
        @DecimalMin(value = "0", inclusive = true)
        private BigDecimal grossAmount;
        @NotNull
        private OffsetDateTime occurredTime;
        @NotNull
        private Boolean completed;
        private Boolean mock;
        private JsonNode rawData;
    }
}
