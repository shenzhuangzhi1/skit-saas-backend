package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member.SkitMemberAncestorsRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member.SkitMemberChildrenRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member.SkitMemberSubtreeSummaryReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member.SkitMemberSubtreeSummaryRespVO;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScopeGuard;
import cn.iocoder.yudao.module.skit.service.member.SkitMemberTreeQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_DEFAULT;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_PATTERN;

@Tag(name = "管理后台 - 会员师徒树")
@RestController
@RequestMapping("/skit/tenant/member")
@Validated
@PreAuthorize("@ss.hasAnyRoles('tenant_admin','super_admin')")
public class SkitMemberTreeController {

    private final SkitAdminTenantScopeGuard tenantScopeGuard;
    private final SkitMemberTreeQueryService memberTreeQueryService;

    public SkitMemberTreeController(SkitAdminTenantScopeGuard tenantScopeGuard,
                                    SkitMemberTreeQueryService memberTreeQueryService) {
        this.tenantScopeGuard = Objects.requireNonNull(tenantScopeGuard, "tenantScopeGuard");
        this.memberTreeQueryService = Objects.requireNonNull(memberTreeQueryService,
                "memberTreeQueryService");
    }

    @GetMapping("/{id}/children")
    @Operation(summary = "按稳定游标懒加载直属徒弟")
    public CommonResult<SkitMemberChildrenRespVO> getChildren(
            @PathVariable("id") @Positive(message = "会员编号必须大于 0") Long memberId,
            @RequestParam(value = "tenantId", required = false)
            @Positive(message = "租户编号必须大于 0") Long tenantId,
            @RequestParam(value = "cursor", required = false)
            @Size(max = 1024, message = "成员树游标过长") String cursor,
            @RequestParam(value = "pageSize", defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") Integer pageSize,
            @RequestParam(value = "timezone", defaultValue = MANAGEMENT_TIMEZONE_DEFAULT)
            @Pattern(regexp = MANAGEMENT_TIMEZONE_PATTERN,
                    message = "时区仅支持 UTC-8、UTC+8 或 UTC+0") String timezone) {
        return success(tenantScopeGuard.readTenant(tenantId, true,
                scope -> memberTreeQueryService.getChildren(scope.getTargetTenantId(), memberId,
                        cursor, pageSize, timezone)));
    }

    @GetMapping("/{id}/ancestors")
    @Operation(summary = "获得从根邀请人到当前会员的有界面包屑")
    public CommonResult<SkitMemberAncestorsRespVO> getAncestors(
            @PathVariable("id") @Positive(message = "会员编号必须大于 0") Long memberId,
            @RequestParam(value = "tenantId", required = false)
            @Positive(message = "租户编号必须大于 0") Long tenantId,
            @RequestParam(value = "timezone", defaultValue = MANAGEMENT_TIMEZONE_DEFAULT)
            @Pattern(regexp = MANAGEMENT_TIMEZONE_PATTERN,
                    message = "时区仅支持 UTC-8、UTC+8 或 UTC+0") String timezone) {
        return success(tenantScopeGuard.readTenant(tenantId, true,
                scope -> memberTreeQueryService.getAncestors(scope.getTargetTenantId(),
                        memberId, timezone)));
    }

    @GetMapping("/{id}/subtree-summary")
    @Operation(summary = "按对账账本统计会员及其徒子徒孙贡献")
    public CommonResult<SkitMemberSubtreeSummaryRespVO> getSubtreeSummary(
            @PathVariable("id") @Positive(message = "会员编号必须大于 0") Long memberId,
            @Valid SkitMemberSubtreeSummaryReqVO query) {
        return success(tenantScopeGuard.readTenant(query.getTenantId(), true,
                scope -> memberTreeQueryService.getSubtreeSummary(scope.getTargetTenantId(),
                        memberId, query)));
    }
}
