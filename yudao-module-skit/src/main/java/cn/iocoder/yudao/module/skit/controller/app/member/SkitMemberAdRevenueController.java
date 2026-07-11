package cn.iocoder.yudao.module.skit.controller.app.member;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.skit.service.revenue.SkitRevenueService;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.module.skit.framework.security.SkitMemberRateLimiterKeyResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "用户 APP - 广告预估收益")
@RestController
@RequestMapping("/skit/member/ad-revenue")
@Validated
public class SkitMemberAdRevenueController {

    @Resource
    private SkitRevenueService revenueService;
    @Resource
    private ObjectMapper objectMapper;

    @PostMapping("/report")
    @PreAuthorize("@ss.hasScope('skit_member') and @skitMemberSecurityGuard.isSkitMember()")
    @RateLimiter(time = 60, count = 60, keyResolver = SkitMemberRateLimiterKeyResolver.class)
    @Operation(summary = "上报客户端广告预估收益", description = "仅形成 ESTIMATED 账本，待 S2S/报表对账后才可结算")
    public CommonResult<SkitRevenueService.ReportResult> report(@Valid @RequestBody ReportReqVO reqVO) {
        SkitRevenueService.ReportCommand command = new SkitRevenueService.ReportCommand();
        command.setProvider(reqVO.getProvider());
        command.setExternalEventId(reqVO.getExternalEventId());
        command.setPlacementId(reqVO.getPlacementId());
        command.setGrossAmount(reqVO.getGrossAmount());
        command.setOccurredTime(reqVO.getOccurredTime());
        command.setCompleted(reqVO.getCompleted());
        command.setMock(Boolean.TRUE.equals(reqVO.getMock()));
        command.setRawData(reqVO.getRawData() == null ? null : writeJson(reqVO.getRawData()));
        return success(revenueService.report(getLoginUserId(), command));
    }

    private String writeJson(JsonNode rawData) {
        try {
            return objectMapper.writeValueAsString(rawData);
        } catch (Exception ex) {
            throw new IllegalArgumentException("rawData 不是合法 JSON", ex);
        }
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
