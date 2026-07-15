package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdEventDetailRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdEventPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdEventRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitStablePageRespVO;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScopeGuard;
import cn.iocoder.yudao.module.skit.service.analytics.SkitAdEventQueryService;
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

@Tag(name = "管理后台 - 广告事实事件")
@RestController
@RequestMapping("/skit/tenant/ad-events")
@Validated
@PreAuthorize("@ss.hasAnyRoles('tenant_admin','super_admin')")
public class SkitAdEventController {

    private final SkitAdminTenantScopeGuard tenantScopeGuard;
    private final SkitAdEventQueryService eventQueryService;

    public SkitAdEventController(SkitAdminTenantScopeGuard tenantScopeGuard,
                                 SkitAdEventQueryService eventQueryService) {
        this.tenantScopeGuard = tenantScopeGuard;
        this.eventQueryService = eventQueryService;
    }

    @GetMapping("/page")
    @Operation(summary = "分页获得不可变广告收益事实")
    public CommonResult<SkitStablePageRespVO<SkitAdEventRespVO>> getPage(
            @Valid SkitAdEventPageReqVO reqVO) {
        return success(tenantScopeGuard.readTenantOrGlobal(reqVO.getTenantId(), true,
                scope -> eventQueryService.getPage(scope.getTargetTenantId(), reqVO),
                () -> eventQueryService.getGlobalPage(reqVO)));
    }

    @GetMapping("/get")
    @Operation(summary = "获得广告事件、回调轨迹和分成分录详情")
    public CommonResult<SkitAdEventDetailRespVO> get(
            @RequestParam("id") @Positive Long id,
            @RequestParam(value = "tenantId", required = false) @Positive Long tenantId,
            @RequestParam(value = "timezone", required = false)
            @Pattern(regexp = MANAGEMENT_TIMEZONE_PATTERN,
                    message = "时区仅支持 UTC-8、UTC+8 或 UTC+0")
            String timezone) {
        return success(tenantScopeGuard.readTenantOrGlobal(tenantId, true,
                scope -> eventQueryService.get(scope.getTargetTenantId(), id, timezone),
                () -> eventQueryService.getGlobal(id, timezone)));
    }

}
