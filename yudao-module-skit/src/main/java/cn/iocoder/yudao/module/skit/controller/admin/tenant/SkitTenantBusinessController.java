package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.skit.framework.security.SkitPlatformAdminGuard;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdAccountService;
import cn.iocoder.yudao.module.skit.service.commission.SkitCommissionService;
import cn.iocoder.yudao.module.skit.service.member.SkitMemberService;
import cn.iocoder.yudao.module.skit.service.revenue.SkitRevenueService;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;

import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Objects;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 短剧租户业务")
@RestController
@RequestMapping("/skit/tenant")
@Validated
@PreAuthorize("@ss.hasAnyRoles('tenant_admin','super_admin')")
public class SkitTenantBusinessController {

    @Resource
    private SkitAdAccountService adAccountService;
    @Resource
    private SkitCommissionService commissionService;
    @Resource
    private SkitMemberService memberService;
    @Resource
    private SkitRevenueService revenueService;
    @Resource
    private SkitPlatformAdminGuard platformAdminGuard;
    @Resource
    private SkitAgentMapper agentMapper;
    @Resource
    private TenantService tenantService;

    @GetMapping("/invitation")
    @Operation(summary = "获得代理商根邀请码")
    public CommonResult<AgentInvitationRespVO> getAgentInvitation(
            @RequestParam(value = "tenantId", required = false) Long requestedTenantId) {
        Long tenantId = resolveTargetTenant(requestedTenantId);
        SkitAgentDO agent = agentMapper.selectByTenantId(tenantId);
        TenantDO tenant = tenantService.getTenant(tenantId);
        if (agent == null || tenant == null) {
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AGENT_NOT_EXISTS);
        }
        AgentInvitationRespVO result = new AgentInvitationRespVO();
        result.setTenantId(tenantId);
        result.setTenantCode(agent.getTenantCode());
        result.setRootInviteCode(agent.getRootInviteCode());
        result.setTenantName(tenant.getName());
        return success(result);
    }

    @GetMapping("/ad-account")
    @Operation(summary = "获得穿山甲与 Taku 广告账号配置")
    public CommonResult<SkitAdAccountService.Settings> getAdAccount(
            @RequestParam(value = "tenantId", required = false) Long tenantId) {
        return success(inTargetTenant(tenantId, adAccountService::getSettings));
    }

    @PutMapping("/ad-account")
    @ApiAccessLog(sanitizeKeys = {"pangleAppSecret", "takuAppKey", "takuAppSecret"})
    @Operation(summary = "保存穿山甲与 Taku 广告账号配置")
    public CommonResult<SkitAdAccountService.Settings> saveAdAccount(@Valid @RequestBody AdAccountSaveReqVO reqVO) {
        SkitAdAccountService.Settings settings = new SkitAdAccountService.Settings();
        settings.setPangleUsername(reqVO.getPangleUsername());
        settings.setPangleAppId(reqVO.getPangleAppId());
        settings.setPangleAppSecret(reqVO.getPangleAppSecret());
        settings.setPanglePlacementId(reqVO.getPanglePlacementId());
        settings.setPangleEnabled(reqVO.getPangleEnabled());
        settings.setTakuUsername(reqVO.getTakuUsername());
        settings.setTakuAppId(reqVO.getTakuAppId());
        settings.setTakuAppKey(reqVO.getTakuAppKey());
        settings.setTakuAppSecret(reqVO.getTakuAppSecret());
        settings.setTakuPlacementId(reqVO.getTakuPlacementId());
        settings.setTakuEnabled(reqVO.getTakuEnabled());
        return success(inTargetTenant(reqVO.getTenantId(), () -> adAccountService.saveSettings(settings)));
    }

    @GetMapping("/commission-rules")
    @Operation(summary = "获得当前生效分成规则")
    public CommonResult<List<SkitCommissionService.RuleView>> getCommissionRules(
            @RequestParam(value = "tenantId", required = false) Long tenantId) {
        return success(inTargetTenant(tenantId, () -> commissionService.getActivePlan().getRules()));
    }

    @PutMapping("/commission-rules")
    @Operation(summary = "发布新的分成规则版本")
    public CommonResult<SkitCommissionService.PlanView> saveCommissionRules(
            @Valid @RequestBody CommissionRulesSaveReqVO reqVO) {
        return success(inTargetTenant(reqVO.getTenantId(), () -> commissionService.replaceRules(reqVO.getRules())));
    }

    @GetMapping("/member/page")
    @Operation(summary = "分页查询当前代理商会员")
    public CommonResult<PageResult<SkitMemberService.MemberView>> getMemberPage(@Valid MemberPageReqVO reqVO) {
        return success(inTargetTenant(reqVO.getTenantId(),
                () -> memberService.getMemberPage(reqVO, reqVO.getKeyword(), reqVO.getStatus())));
    }

    @GetMapping("/ledger/page")
    @Operation(summary = "分页查询广告预估分成账本")
    public CommonResult<PageResult<SkitRevenueService.LedgerView>> getLedgerPage(@Valid LedgerPageReqVO reqVO) {
        Long beneficiaryUserId = reqVO.getBeneficiaryUserId() != null
                ? reqVO.getBeneficiaryUserId() : reqVO.getMemberId();
        return success(inTargetTenant(reqVO.getTenantId(), () -> revenueService.getLedgerPage(
                reqVO, beneficiaryUserId, reqVO.getBeneficiaryType(), reqVO.getCreateTime())));
    }

    private Long resolveTargetTenant(Long requestedTenantId) {
        Long effectiveTenantId = requestedTenantId == null
                ? TenantContextHolder.getRequiredTenantId() : requestedTenantId;
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        Long originalTenantId = loginUser == null ? null : loginUser.getTenantId();
        if (!Objects.equals(effectiveTenantId, originalTenantId)) {
            platformAdminGuard.check();
        }
        if (agentMapper.selectByTenantId(effectiveTenantId) == null || tenantService.getTenant(effectiveTenantId) == null) {
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AGENT_NOT_EXISTS);
        }
        return effectiveTenantId;
    }

    private <T> T inTargetTenant(Long requestedTenantId, Supplier<T> supplier) {
        Long targetTenantId = resolveTargetTenant(requestedTenantId);
        AtomicReference<T> result = new AtomicReference<>();
        TenantUtils.execute(targetTenantId, () -> result.set(supplier.get()));
        return result.get();
    }

    @Data
    public static class AdAccountSaveReqVO {
        private Long tenantId;
        private String pangleUsername;
        private String pangleAppId;
        private String pangleAppSecret;
        private String panglePlacementId;
        private Boolean pangleEnabled;
        private String takuUsername;
        private String takuAppId;
        private String takuAppKey;
        private String takuAppSecret;
        private String takuPlacementId;
        private Boolean takuEnabled;
    }

    @Data
    public static class CommissionRulesSaveReqVO {
        private Long tenantId;
        @NotEmpty(message = "分成规则不能为空")
        @Valid
        private List<SkitCommissionService.RuleView> rules;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class MemberPageReqVO extends PageParam {
        private Long tenantId;
        private String keyword;
        private Integer status;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class LedgerPageReqVO extends PageParam {
        private Long tenantId;
        private Long beneficiaryUserId;
        private Long memberId;
        private Integer beneficiaryType;
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime[] createTime;
    }

    @Data
    public static class AgentInvitationRespVO {
        private Long tenantId;
        private String tenantCode;
        private String rootInviteCode;
        private String tenantName;
    }
}
