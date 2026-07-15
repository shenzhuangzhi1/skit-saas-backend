package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitStablePageRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.ledger.SkitCommissionLedgerPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.ledger.SkitCommissionLedgerRespVO;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScopeGuard;
import cn.iocoder.yudao.module.skit.service.management.SkitCommissionLedgerQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 只读分成流水")
@RestController
@RequestMapping("/skit/tenant/commission-ledger")
@Validated
@PreAuthorize("@ss.hasAnyRoles('tenant_admin','super_admin')")
public class SkitCommissionLedgerController {

    private final SkitAdminTenantScopeGuard tenantScopeGuard;
    private final SkitCommissionLedgerQueryService ledgerQueryService;

    public SkitCommissionLedgerController(SkitAdminTenantScopeGuard tenantScopeGuard,
                                          SkitCommissionLedgerQueryService ledgerQueryService) {
        this.tenantScopeGuard = Objects.requireNonNull(tenantScopeGuard, "tenantScopeGuard");
        this.ledgerQueryService = Objects.requireNonNull(ledgerQueryService, "ledgerQueryService");
    }

    @GetMapping("/page")
    @Operation(summary = "按币种和稳定快照分页获得不可变分成流水")
    public CommonResult<SkitStablePageRespVO<SkitCommissionLedgerRespVO>> getPage(
            @Valid SkitCommissionLedgerPageReqVO query) {
        return success(tenantScopeGuard.readTenant(query.getTenantId(), true,
                scope -> ledgerQueryService.getPage(scope.getTargetTenantId(), query)));
    }
}
