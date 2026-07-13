package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.validation.InEnum;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.skit.framework.security.SkitPlatformAdminGuard;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdAccountService;
import cn.iocoder.yudao.module.skit.service.app.SkitAppReleaseService;
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
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import org.hibernate.validator.constraints.Length;
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
    private SkitAppReleaseService appReleaseService;
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

    @PutMapping("/ad-account/clear-credentials")
    @Operation(summary = "显式清除广告平台凭证并停用平台")
    public CommonResult<Boolean> clearAdAccountCredentials(@Valid @RequestBody AdCredentialClearReqVO reqVO) {
        return success(inTargetTenant(reqVO.getTenantId(), () -> {
            adAccountService.clearCredentials(reqVO.getProvider());
            return true;
        }));
    }

    @GetMapping("/app-release")
    @PreAuthorize("@ss.hasRole('super_admin')")
    @Operation(summary = "获得代理商 App 发布档案")
    public CommonResult<SkitAppReleaseService.ProfileView> getAppRelease(
            @RequestParam("tenantId") Long tenantId) {
        platformAdminGuard.check();
        return success(inAuditTenant(tenantId, () -> appReleaseService.getProfile(tenantId)));
    }

    @PutMapping("/app-release")
    @PreAuthorize("@ss.hasRole('super_admin')")
    @Operation(summary = "保存代理商 App 发布档案")
    public CommonResult<SkitAppReleaseService.ProfileView> saveAppRelease(
            @Valid @RequestBody AppReleaseSaveReqVO reqVO) {
        platformAdminGuard.check();
        SkitAppReleaseService.ProfileView profile = new SkitAppReleaseService.ProfileView();
        profile.setTenantId(reqVO.getTenantId());
        profile.setChannel(reqVO.getChannel());
        profile.setMinNativeVersion(reqVO.getMinNativeVersion());
        profile.setHotVersion(reqVO.getHotVersion());
        profile.setHotBundleUrl(reqVO.getHotBundleUrl());
        profile.setHotBundleSha256(reqVO.getHotBundleSha256());
        profile.setNativeVersion(reqVO.getNativeVersion());
        profile.setNativePackage(reqVO.getNativePackage());
        profile.setStatus(reqVO.getStatus());
        return success(inTargetTenant(reqVO.getTenantId(), () -> appReleaseService.saveProfile(profile)));
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
        return success(inAuditTenant(reqVO.getTenantId(),
                () -> memberService.getMemberPage(reqVO, reqVO.getKeyword(), reqVO.getStatus())));
    }

    @GetMapping("/member/get")
    @Operation(summary = "获得会员详情")
    public CommonResult<SkitMemberService.MemberView> getMember(
            @RequestParam("id") @NotNull Long id,
            @RequestParam(value = "tenantId", required = false) Long tenantId) {
        return success(inAuditTenant(tenantId, () -> memberService.getMember(id)));
    }

    @PutMapping("/member/update-status")
    @Operation(summary = "启用或停用会员")
    public CommonResult<Boolean> updateMemberStatus(@Valid @RequestBody MemberStatusUpdateReqVO reqVO) {
        return success(inTargetTenant(reqVO.getTenantId(), () -> {
            memberService.updateMemberStatus(reqVO.getId(), reqVO.getStatus());
            return true;
        }));
    }

    @PutMapping("/member/reset-password")
    @ApiAccessLog(sanitizeKeys = {"password"})
    @Operation(summary = "重置会员密码")
    public CommonResult<Boolean> resetMemberPassword(@Valid @RequestBody MemberPasswordResetReqVO reqVO) {
        return success(inTargetTenant(reqVO.getTenantId(), () -> {
            memberService.resetMemberPassword(reqVO.getId(), reqVO.getPassword());
            return true;
        }));
    }

    @GetMapping("/ledger/page")
    @Operation(summary = "分页查询广告预估分成账本")
    public CommonResult<PageResult<SkitRevenueService.LedgerView>> getLedgerPage(@Valid LedgerPageReqVO reqVO) {
        Long beneficiaryUserId = reqVO.getBeneficiaryUserId() != null
                ? reqVO.getBeneficiaryUserId() : reqVO.getMemberId();
        return success(inAuditTenant(reqVO.getTenantId(), () -> revenueService.getLedgerPage(
                reqVO, beneficiaryUserId, reqVO.getBeneficiaryType(), reqVO.getCreateTime())));
    }

    private Long resolveTargetTenant(Long requestedTenantId) {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        Long originalTenantId = loginUser == null ? null : loginUser.getTenantId();
        if (originalTenantId == null) {
            platformAdminGuard.check();
        }
        // Never derive an agent request from TenantContextHolder: visit-tenant headers can change it.
        Long effectiveTenantId = requestedTenantId == null ? originalTenantId : requestedTenantId;
        if (!Objects.equals(effectiveTenantId, originalTenantId)) {
            platformAdminGuard.check();
        }
        tenantService.validTenant(effectiveTenantId);
        if (agentMapper.selectByTenantId(effectiveTenantId) == null) {
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AGENT_NOT_EXISTS);
        }
        return effectiveTenantId;
    }

    private <T> T inTargetTenant(Long requestedTenantId, Supplier<T> supplier) {
        Long targetTenantId = resolveTargetTenant(requestedTenantId);
        return executeInTenant(targetTenantId, supplier);
    }

    private Long resolveAuditTenant(Long requestedTenantId) {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        Long originalTenantId = loginUser == null ? null : loginUser.getTenantId();
        Long effectiveTenantId = requestedTenantId == null ? originalTenantId : requestedTenantId;
        if (Objects.equals(effectiveTenantId, originalTenantId)) {
            return resolveTargetTenant(requestedTenantId);
        }
        // Only an original platform administrator can cross tenants for immutable/read-only audit views.
        platformAdminGuard.check();
        if (tenantService.getTenant(effectiveTenantId) == null
                || agentMapper.selectByTenantId(effectiveTenantId) == null) {
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AGENT_NOT_EXISTS);
        }
        return effectiveTenantId;
    }

    private <T> T inAuditTenant(Long requestedTenantId, Supplier<T> supplier) {
        return executeInTenant(resolveAuditTenant(requestedTenantId), supplier);
    }

    private <T> T executeInTenant(Long targetTenantId, Supplier<T> supplier) {
        AtomicReference<T> result = new AtomicReference<>();
        TenantUtils.execute(targetTenantId, () -> result.set(supplier.get()));
        return result.get();
    }

    @Data
    public static class AdAccountSaveReqVO {
        private Long tenantId;
        @Size(max = 128, message = "穿山甲账号长度不能超过 128 个字符")
        private String pangleUsername;
        @Size(max = 128, message = "穿山甲 App ID 长度不能超过 128 个字符")
        private String pangleAppId;
        @Size(max = 2048, message = "穿山甲密钥长度不能超过 2048 个字符")
        private String pangleAppSecret;
        @Size(max = 128, message = "穿山甲广告位长度不能超过 128 个字符")
        private String panglePlacementId;
        @NotNull(message = "穿山甲启用状态不能为空")
        private Boolean pangleEnabled;
        @Size(max = 128, message = "Taku 账号长度不能超过 128 个字符")
        private String takuUsername;
        @Size(max = 128, message = "Taku App ID 长度不能超过 128 个字符")
        private String takuAppId;
        @Size(max = 255, message = "Taku App Key 长度不能超过 255 个字符")
        private String takuAppKey;
        @Size(max = 2048, message = "Taku 服务端密钥长度不能超过 2048 个字符")
        private String takuAppSecret;
        @Size(max = 128, message = "Taku 广告位长度不能超过 128 个字符")
        private String takuPlacementId;
        @NotNull(message = "Taku 启用状态不能为空")
        private Boolean takuEnabled;
    }

    @Data
    public static class AdCredentialClearReqVO {
        private Long tenantId;
        @NotBlank(message = "广告平台不能为空")
        @Pattern(regexp = "(?i)PANGLE|TAKU", message = "广告平台仅支持 PANGLE 或 TAKU")
        private String provider;
    }

    @Data
    public static class CommissionRulesSaveReqVO {
        private Long tenantId;
        @NotEmpty(message = "分成规则不能为空")
        @Valid
        private List<SkitCommissionService.RuleView> rules;
    }

    @Data
    public static class AppReleaseSaveReqVO {
        @NotNull
        private Long tenantId;
        private String channel;
        private String minNativeVersion;
        private String hotVersion;
        private String hotBundleUrl;
        private String hotBundleSha256;
        private String nativeVersion;
        private String nativePackage;
        @NotNull
        private Integer status;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class MemberPageReqVO extends PageParam {
        private Long tenantId;
        @Size(max = 64, message = "会员搜索关键字长度不能超过 64 个字符")
        private String keyword;
        @InEnum(value = CommonStatusEnum.class, message = "会员状态必须是 {value}")
        private Integer status;
    }

    @Data
    public static class MemberStatusUpdateReqVO {
        private Long tenantId;
        @NotNull(message = "会员编号不能为空")
        private Long id;
        @NotNull(message = "会员状态不能为空")
        @InEnum(value = CommonStatusEnum.class, message = "会员状态必须是 {value}")
        private Integer status;
    }

    @Data
    public static class MemberPasswordResetReqVO {
        private Long tenantId;
        @NotNull(message = "会员编号不能为空")
        private Long id;
        @NotBlank(message = "新密码不能为空")
        @Length(min = 6, max = 32, message = "会员密码长度为 6 到 32 位")
        private String password;
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
