package cn.iocoder.yudao.module.skit.controller.admin.record;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordSaveReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitDashboardSummaryRespVO;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScopeGuard;
import cn.iocoder.yudao.module.skit.framework.security.SkitManagementCommandType;
import cn.iocoder.yudao.module.skit.service.record.SkitAdminRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 短剧 SaaS 通用记录")
@RestController
@RequestMapping("/skit/admin-record")
@Validated
public class SkitAdminRecordController {

    @Resource
    private SkitAdminRecordService skitAdminRecordService;
    @Resource
    private SkitAdminTenantScopeGuard adminTenantScopeGuard;

    @PostMapping("/create")
    @Operation(summary = "创建短剧后台记录")
    @PreAuthorize("@ss.hasAnyRoles('super_admin', 'tenant_admin')")
    public CommonResult<Long> createRecord(@Valid @RequestBody SkitAdminRecordSaveReqVO createReqVO) {
        if (createReqVO.getTenantId() != null) {
            return success(adminTenantScopeGuard.writeTenant(createReqVO.getTenantId(),
                    SkitManagementCommandType.ADMIN_RECORD_WRITE, createReqVO.getReason(),
                    scope -> skitAdminRecordService.createRecord(createReqVO)));
        }
        return success(skitAdminRecordService.createRecord(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新短剧后台记录")
    @PreAuthorize("@ss.hasAnyRoles('super_admin', 'tenant_admin')")
    public CommonResult<Boolean> updateRecord(@Valid @RequestBody SkitAdminRecordSaveReqVO updateReqVO) {
        if (updateReqVO.getTenantId() != null) {
            return success(adminTenantScopeGuard.writeTenant(updateReqVO.getTenantId(),
                    SkitManagementCommandType.ADMIN_RECORD_WRITE, updateReqVO.getReason(), scope -> {
                        skitAdminRecordService.updateRecord(updateReqVO);
                        return true;
                    }));
        }
        skitAdminRecordService.updateRecord(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除短剧后台记录")
    @PreAuthorize("@ss.hasAnyRoles('super_admin', 'tenant_admin')")
    public CommonResult<Boolean> deleteRecord(@RequestParam("id") Long id,
                                               @RequestParam(value = "tenantId", required = false)
                                               @Positive Long tenantId,
                                               @RequestParam(value = "reason", required = false)
                                               @Size(min = 10, max = 200) String reason) {
        if (tenantId != null) {
            return success(adminTenantScopeGuard.writeTenant(tenantId,
                    SkitManagementCommandType.ADMIN_RECORD_WRITE, reason, scope -> {
                        skitAdminRecordService.deleteRecord(id);
                        return true;
                    }));
        }
        skitAdminRecordService.deleteRecord(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除短剧后台记录")
    @PreAuthorize("@ss.hasAnyRoles('super_admin', 'tenant_admin')")
    public CommonResult<Boolean> deleteRecordList(@RequestParam("ids") List<Long> ids,
                                                   @RequestParam(value = "tenantId", required = false)
                                                   @Positive Long tenantId,
                                                   @RequestParam(value = "reason", required = false)
                                                   @Size(min = 10, max = 200) String reason) {
        if (tenantId != null) {
            return success(adminTenantScopeGuard.writeTenant(tenantId,
                    SkitManagementCommandType.ADMIN_RECORD_WRITE, reason, scope -> {
                        skitAdminRecordService.deleteRecordList(ids);
                        return true;
                    }));
        }
        skitAdminRecordService.deleteRecordList(ids);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得短剧后台记录")
    @PreAuthorize("@ss.hasAnyRoles('super_admin', 'tenant_admin')")
    public CommonResult<SkitAdminRecordRespVO> getRecord(@RequestParam("id") Long id) {
        return success(skitAdminRecordService.getRecord(id));
    }

    @GetMapping("/page")
    @Operation(summary = "获得短剧后台记录分页")
    @PreAuthorize("@ss.hasAnyRoles('super_admin', 'tenant_admin')")
    public CommonResult<PageResult<SkitAdminRecordRespVO>> getRecordPage(SkitAdminRecordPageReqVO pageReqVO) {
        if (pageReqVO.getTenantId() != null) {
            return success(adminTenantScopeGuard.readTenant(pageReqVO.getTenantId(), false,
                    scope -> skitAdminRecordService.getRecordPage(pageReqVO)));
        }
        return success(skitAdminRecordService.getRecordPage(pageReqVO));
    }

    @PostMapping("/seed")
    @Operation(summary = "初始化页面样例数据")
    @PreAuthorize("@ss.hasAnyRoles('super_admin', 'tenant_admin')")
    public CommonResult<Integer> seedPage(@RequestParam("pageKey") String pageKey) {
        return success(skitAdminRecordService.seedPage(pageKey));
    }

    @GetMapping("/dashboard-summary")
    @Operation(summary = "获得短剧看板汇总")
    @PreAuthorize("@ss.hasAnyRoles('super_admin', 'tenant_admin')")
    public CommonResult<SkitDashboardSummaryRespVO> getDashboardSummary() {
        return success(skitAdminRecordService.getDashboardSummary());
    }

}
