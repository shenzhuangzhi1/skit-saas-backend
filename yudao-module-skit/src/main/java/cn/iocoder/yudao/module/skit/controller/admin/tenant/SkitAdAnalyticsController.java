package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsOverviewRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsQueryReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsTimeseriesReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsTimeseriesRespVO;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScopeGuard;
import cn.iocoder.yudao.module.skit.service.analytics.SkitAdAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 广告监控分析")
@RestController
@RequestMapping("/skit/tenant/ad-analytics")
@Validated
@PreAuthorize("@ss.hasAnyRoles('tenant_admin','super_admin')")
public class SkitAdAnalyticsController {

    private final SkitAdminTenantScopeGuard tenantScopeGuard;
    private final SkitAdAnalyticsService analyticsService;

    public SkitAdAnalyticsController(SkitAdminTenantScopeGuard tenantScopeGuard,
                                     SkitAdAnalyticsService analyticsService) {
        this.tenantScopeGuard = tenantScopeGuard;
        this.analyticsService = analyticsService;
    }

    @GetMapping("/overview")
    @Operation(summary = "获得租户广告漏斗、收益和平台健康总览")
    public CommonResult<SkitAdAnalyticsOverviewRespVO> getOverview(
            @Valid SkitAdAnalyticsQueryReqVO reqVO) {
        return success(tenantScopeGuard.readTenantOrGlobal(reqVO.getTenantId(), true,
                scope -> analyticsService.getOverview(scope.getTargetTenantId(), reqVO),
                () -> analyticsService.getGlobalOverview(reqVO)));
    }

    @GetMapping("/timeseries")
    @Operation(summary = "获得租户广告漏斗和收益趋势")
    public CommonResult<SkitAdAnalyticsTimeseriesRespVO> getTimeseries(
            @Valid SkitAdAnalyticsTimeseriesReqVO reqVO) {
        return success(tenantScopeGuard.readTenantOrGlobal(reqVO.getTenantId(), true,
                scope -> analyticsService.getTimeseries(scope.getTargetTenantId(), reqVO),
                () -> analyticsService.getGlobalTimeseries(reqVO)));
    }

}
