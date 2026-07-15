package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitReconciliationDetailRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitReconciliationPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitReconciliationRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitStablePageRespVO;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScopeGuard;
import cn.iocoder.yudao.module.skit.service.reconciliation.SkitReconciliationQueryService;
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

@Tag(name = "管理后台 - 广告对账")
@RestController
@RequestMapping("/skit/tenant/reconciliation")
@Validated
@PreAuthorize("@ss.hasAnyRoles('tenant_admin','super_admin')")
public class SkitReconciliationController {

    private final SkitAdminTenantScopeGuard tenantScopeGuard;
    private final SkitReconciliationQueryService reconciliationQueryService;

    public SkitReconciliationController(SkitAdminTenantScopeGuard tenantScopeGuard,
                                        SkitReconciliationQueryService reconciliationQueryService) {
        this.tenantScopeGuard = tenantScopeGuard;
        this.reconciliationQueryService = reconciliationQueryService;
    }

    @GetMapping("/page")
    @Operation(summary = "分页获得报表对账桶和最新修订")
    public CommonResult<SkitStablePageRespVO<SkitReconciliationRespVO>> getPage(
            @Valid SkitReconciliationPageReqVO reqVO) {
        return success(tenantScopeGuard.readTenantOrGlobal(reqVO.getTenantId(), true,
                scope -> reconciliationQueryService.getPage(scope.getTargetTenantId(), reqVO),
                () -> reconciliationQueryService.getGlobalPage(reqVO)));
    }

    @GetMapping("/get")
    @Operation(summary = "获得报表拉取、未匹配事件和追加式修订详情")
    public CommonResult<SkitReconciliationDetailRespVO> get(
            @RequestParam("id") @Positive Long id,
            @RequestParam(value = "tenantId", required = false) @Positive Long tenantId,
            @RequestParam(value = "timezone", required = false)
            @Pattern(regexp = MANAGEMENT_TIMEZONE_PATTERN,
                    message = "时区仅支持 UTC-8、UTC+8 或 UTC+0")
            String timezone) {
        return success(tenantScopeGuard.readTenantOrGlobal(tenantId, true,
                scope -> reconciliationQueryService.get(scope.getTargetTenantId(), id, timezone),
                () -> reconciliationQueryService.getGlobal(id, timezone)));
    }

}
