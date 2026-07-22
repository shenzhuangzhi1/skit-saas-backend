package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdConsumptionDetailRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdConsumptionPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdConsumptionRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdConsumptionSummaryRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitStablePageRespVO;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScopeGuard;
import cn.iocoder.yudao.module.skit.service.analytics.SkitAdConsumptionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_PATTERN;

@Tag(name = "管理后台 - 广告消费明细")
@RestController
@RequestMapping("/skit/tenant/ad-consumptions")
@Validated
@PreAuthorize("@ss.hasAnyRoles('tenant_admin','super_admin')")
public class SkitAdConsumptionController {

    private final SkitAdminTenantScopeGuard tenantScopeGuard;
    private final SkitAdConsumptionQueryService consumptionQueryService;

    public SkitAdConsumptionController(SkitAdminTenantScopeGuard tenantScopeGuard,
                                       SkitAdConsumptionQueryService consumptionQueryService) {
        this.tenantScopeGuard = tenantScopeGuard;
        this.consumptionQueryService = consumptionQueryService;
    }

    @GetMapping("/summary")
    @Operation(summary = "获得真实广告消费概览")
    public CommonResult<SkitAdConsumptionSummaryRespVO> getSummary(
            @Valid SkitAdConsumptionPageReqVO reqVO) {
        return success(tenantScopeGuard.readTenantOrGlobal(reqVO.getTenantId(), true,
                scope -> consumptionQueryService.getSummary(scope.getTargetTenantId(), reqVO),
                () -> consumptionQueryService.getGlobalSummary(reqVO)));
    }

    @GetMapping("/page")
    @Operation(summary = "分页获得每次广告消费记录")
    public CommonResult<SkitStablePageRespVO<SkitAdConsumptionRespVO>> getPage(
            @Valid SkitAdConsumptionPageReqVO reqVO) {
        return success(tenantScopeGuard.readTenantOrGlobal(reqVO.getTenantId(), true,
                scope -> consumptionQueryService.getPage(scope.getTargetTenantId(), reqVO),
                () -> consumptionQueryService.getGlobalPage(reqVO)));
    }

    @GetMapping("/get")
    @Operation(summary = "获得广告消费全链路时间线")
    public CommonResult<SkitAdConsumptionDetailRespVO> get(
            @RequestParam("id") @Positive Long id,
            @RequestParam(value = "tenantId", required = false) @Positive Long tenantId,
            @RequestParam(value = "timezone", required = false)
            @Pattern(regexp = MANAGEMENT_TIMEZONE_PATTERN,
                    message = "时区仅支持 UTC-8、UTC+8 或 UTC+0") String timezone) {
        return success(tenantScopeGuard.readTenantOrGlobal(tenantId, true,
                scope -> consumptionQueryService.get(scope.getTargetTenantId(), id, timezone),
                () -> consumptionQueryService.getGlobal(id, timezone)));
    }

}
