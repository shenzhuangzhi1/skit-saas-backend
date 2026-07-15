package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitStablePageRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPlanPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPlanRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPreviewReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPreviewRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPublishReqVO;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScopeGuard;
import cn.iocoder.yudao.module.skit.framework.security.SkitManagementCommandType;
import cn.iocoder.yudao.module.skit.service.commission.SkitCommissionManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_DEFAULT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_PATTERN;

@Tag(name = "管理后台 - 分成方案")
@RestController
@RequestMapping("/skit/tenant/commission-plans")
@Validated
@PreAuthorize("@ss.hasAnyRoles('tenant_admin','super_admin')")
public class SkitCommissionPlanController {

    private final SkitAdminTenantScopeGuard tenantScopeGuard;
    private final SkitCommissionManagementService commissionService;

    public SkitCommissionPlanController(SkitAdminTenantScopeGuard tenantScopeGuard,
                                        SkitCommissionManagementService commissionService) {
        this.tenantScopeGuard = Objects.requireNonNull(tenantScopeGuard, "tenantScopeGuard");
        this.commissionService = Objects.requireNonNull(commissionService, "commissionService");
    }

    @GetMapping("/current")
    @Operation(summary = "获得当前生效的任意层级分成方案")
    public CommonResult<SkitCommissionPlanRespVO> getCurrent(
            @RequestParam(value = "tenantId", required = false)
            @Positive(message = "租户编号必须大于 0") Long tenantId,
            @RequestParam(value = "timezone", defaultValue = MANAGEMENT_TIMEZONE_DEFAULT)
            @Pattern(regexp = MANAGEMENT_TIMEZONE_PATTERN,
                    message = "时区仅支持 UTC-8、UTC+8 或 UTC+0") String timezone) {
        return success(tenantScopeGuard.readTenant(tenantId, true,
                scope -> commissionService.getCurrent(scope.getTargetTenantId(), timezone)));
    }

    @GetMapping("/history/page")
    @Operation(summary = "分页获得不可变的分成方案版本历史")
    public CommonResult<SkitStablePageRespVO<SkitCommissionPlanRespVO>> getHistory(
            @Valid SkitCommissionPlanPageReqVO query) {
        return success(tenantScopeGuard.readTenant(query.getTenantId(), true,
                scope -> commissionService.getHistory(scope.getTargetTenantId(), query)));
    }

    @PostMapping("/preview")
    @Operation(summary = "按整数最小单位预览分成并验证守恒")
    public CommonResult<SkitCommissionPreviewRespVO> preview(
            @Valid @RequestBody SkitCommissionPreviewReqVO request) {
        return success(tenantScopeGuard.readTenant(request.getTenantId(), true,
                scope -> commissionService.preview(scope.getTargetTenantId(), request)));
    }

    @PostMapping("/publish")
    @Operation(summary = "按预期版本发布并审计新的分成方案")
    public CommonResult<SkitCommissionPlanRespVO> publish(
            @Valid @RequestBody SkitCommissionPublishReqVO request) {
        return success(tenantScopeGuard.writeTenant(request.getTenantId(),
                SkitManagementCommandType.COMMISSION_PLAN_PUBLISH, request.getReason(),
                scope -> commissionService.publish(scope, request)));
    }
}
