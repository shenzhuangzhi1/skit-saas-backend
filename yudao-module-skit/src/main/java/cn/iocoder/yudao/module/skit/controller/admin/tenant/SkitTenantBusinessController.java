package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.validation.InEnum;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScopeGuard;
import cn.iocoder.yudao.module.skit.framework.security.SkitManagementCommandType;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdAccountService;
import cn.iocoder.yudao.module.skit.service.app.SkitAppReleaseService;
import cn.iocoder.yudao.module.skit.service.app.SkitAppBuildMaterialService;
import cn.iocoder.yudao.module.skit.service.member.SkitMemberService;
import cn.iocoder.yudao.module.skit.service.management.SkitManagementCommandExecutor;
import cn.iocoder.yudao.module.skit.service.reconciliation.SkitReportingConfigurationService;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;
import javax.validation.constraints.Min;
import javax.validation.constraints.Max;
import javax.validation.constraints.Size;
import org.hibernate.validator.constraints.Length;

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
    private SkitAppBuildMaterialService appBuildMaterialService;
    @Resource
    private SkitMemberService memberService;
    @Resource
    private SkitReportingConfigurationService reportingConfigurationService;
    @Resource
    private SkitAdminTenantScopeGuard adminTenantScopeGuard;
    @Resource
    private SkitManagementCommandExecutor managementCommandExecutor;
    @Resource
    private SkitAgentMapper agentMapper;
    @Resource
    private TenantService tenantService;

    @GetMapping("/invitation")
    @Operation(summary = "获得代理商根邀请码")
    public CommonResult<AgentInvitationRespVO> getAgentInvitation(
            @RequestParam(value = "tenantId", required = false) Long requestedTenantId) {
        return success(adminTenantScopeGuard.readTenant(requestedTenantId, false, scope -> {
            Long tenantId = scope.getTargetTenantId();
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
            return result;
        }));
    }

    @GetMapping("/ad-account")
    @Operation(summary = "获得穿山甲与 Taku 广告账号配置")
    public CommonResult<SkitAdAccountService.Settings> getAdAccount(
            @RequestParam(value = "tenantId", required = false) Long tenantId) {
        return success(adminTenantScopeGuard.readTenant(tenantId, false,
                scope -> adAccountService.getSettings()));
    }

    @PutMapping("/ad-account")
    @ApiAccessLog(sanitizeKeys = {"pangleAppSecret", "takuAppKey", "takuAppSecret"})
    @Operation(summary = "保存穿山甲与 Taku 广告账号配置")
    public CommonResult<SkitAdAccountService.Settings> saveAdAccount(@Valid @RequestBody AdAccountSaveReqVO reqVO) {
        return success(adminTenantScopeGuard.writeTenant(reqVO.getTenantId(),
                SkitManagementCommandType.AD_ACCOUNT_UPDATE, reqVO.getReason(), scope -> {
                    SkitAdAccountService.Settings before = adAccountService.getSettings();
                    SkitAdAccountService.Settings settings = toSettings(reqVO);
                    return managementCommandExecutor.execute(scope,
                            SkitManagementCommandType.AD_ACCOUNT_UPDATE, "AD_ACCOUNT_SETTINGS",
                            scope.getTargetTenantId().toString(), reqVO.getReason(),
                            canonical(before), canonical(reqVO), () -> {
                                SkitAdAccountService.Settings after =
                                        adAccountService.saveSettings(settings);
                                return new SkitManagementCommandExecutor.CommandResult<>(
                                        after, canonical(after));
                            });
                }));
    }

    @PutMapping("/ad-account/clear-credentials")
    @Operation(summary = "显式清除广告平台凭证并停用平台")
    public CommonResult<Boolean> clearAdAccountCredentials(@Valid @RequestBody AdCredentialClearReqVO reqVO) {
        return success(adminTenantScopeGuard.writeTenant(reqVO.getTenantId(),
                SkitManagementCommandType.AD_CREDENTIAL_CLEAR, reqVO.getReason(), scope -> {
                    SkitAdAccountService.Settings before = adAccountService.getSettings();
                    return managementCommandExecutor.execute(scope,
                            SkitManagementCommandType.AD_CREDENTIAL_CLEAR, "AD_ACCOUNT_CREDENTIAL",
                            reqVO.getProvider().toUpperCase(), reqVO.getReason(), canonical(before),
                            "provider=" + reqVO.getProvider().toUpperCase(), () -> {
                                adAccountService.clearCredentials(reqVO.getProvider());
                                SkitAdAccountService.Settings after = adAccountService.getSettings();
                                return new SkitManagementCommandExecutor.CommandResult<>(
                                        true, canonical(after));
                            });
                }));
    }

    @GetMapping("/ad-account/reporting-configuration")
    @Operation(summary = "获得 Taku 报表配置与凭证版本元数据")
    public CommonResult<SkitReportingConfigurationService.View> getReportingConfiguration(
            @RequestParam(value = "tenantId", required = false) Long tenantId) {
        return success(adminTenantScopeGuard.readTenant(tenantId, true,
                scope -> reportingConfigurationService.getConfiguration()));
    }

    @PutMapping("/ad-account/reporting-configuration")
    @ApiAccessLog(sanitizeKeys = {"publisherKey"})
    @Operation(summary = "保存 Taku 报表配置并可选轮换 Publisher Key")
    public CommonResult<SkitReportingConfigurationService.View> saveReportingConfiguration(
            @Valid @RequestBody ReportingConfigurationSaveReqVO reqVO) {
        SkitReportingConfigurationService.View result = adminTenantScopeGuard.writeTenant(
                reqVO.getTenantId(), SkitManagementCommandType.REPORTING_CONFIGURATION,
                reqVO.getReason(), scope -> {
                    SkitReportingConfigurationService.View before =
                            reportingConfigurationService.getConfiguration();
                    SkitReportingConfigurationService.Command command =
                            new SkitReportingConfigurationService.Command()
                                    .setCredentialVersion(reqVO.getCredentialVersion())
                                    .setPublisherKey(reqVO.getPublisherKey())
                                    .setReportTimezone(reqVO.getReportTimezone())
                                    .setCurrency(reqVO.getCurrency())
                                    .setAmountScale(reqVO.getAmountScale())
                                    .setAdFormat(reqVO.getAdFormat());
                    return managementCommandExecutor.execute(scope,
                            SkitManagementCommandType.REPORTING_CONFIGURATION,
                            "TAKU_REPORTING_CONFIGURATION",
                            String.valueOf(before.getAdAccountId()), reqVO.getReason(),
                            before.auditCanonical(), command.auditCanonical(), () -> {
                                SkitReportingConfigurationService.View after =
                                        reportingConfigurationService.configure(command);
                                return new SkitManagementCommandExecutor.CommandResult<>(
                                        after, after.auditCanonical());
                            });
                });
        return success(result);
    }

    @GetMapping("/app-release")
    @PreAuthorize("@ss.hasRole('super_admin')")
    @Operation(summary = "获得代理商 App 发布档案")
    public CommonResult<SkitAppReleaseService.ProfileView> getAppRelease(
            @RequestParam("tenantId") Long tenantId) {
        return success(adminTenantScopeGuard.readTenant(tenantId, true,
                scope -> appReleaseService.getProfile(scope.getTargetTenantId())));
    }

    @PutMapping("/app-release")
    @PreAuthorize("@ss.hasRole('super_admin')")
    @ApiAccessLog(sanitizeKeys = {"hotManifestSignature"})
    @Operation(summary = "保存代理商 App 发布档案")
    public CommonResult<SkitAppReleaseService.ProfileView> saveAppRelease(
            @Valid @RequestBody AppReleaseSaveReqVO reqVO) {
        SkitAppReleaseService.ProfileView profile = new SkitAppReleaseService.ProfileView();
        profile.setTenantId(reqVO.getTenantId());
        profile.setChannel(reqVO.getChannel());
        profile.setMinNativeVersion(reqVO.getMinNativeVersion());
        profile.setHotVersion(reqVO.getHotVersion());
        profile.setHotBundleUrl(reqVO.getHotBundleUrl());
        profile.setHotBundleSha256(reqVO.getHotBundleSha256());
        profile.setHotReleaseNo(reqVO.getHotReleaseNo());
        profile.setHotManifestSignature(reqVO.getHotManifestSignature());
        profile.setNativeVersion(reqVO.getNativeVersion());
        profile.setNativePackage(reqVO.getNativePackage());
        profile.setNativeProtocolVersion(reqVO.getNativeProtocolVersion());
        profile.setRuntimeUpdatePublicKey(reqVO.getRuntimeUpdatePublicKey());
        profile.setStatus(reqVO.getStatus());
        return success(adminTenantScopeGuard.writeTenant(reqVO.getTenantId(),
                SkitManagementCommandType.APP_RELEASE_UPDATE, reqVO.getReason(), scope -> {
                    SkitAppReleaseService.ProfileView before =
                            appReleaseService.getProfile(scope.getTargetTenantId());
                    return managementCommandExecutor.execute(scope,
                            SkitManagementCommandType.APP_RELEASE_UPDATE, "APP_RELEASE_PROFILE",
                            scope.getTargetTenantId().toString(), reqVO.getReason(),
                            canonical(before), canonical(reqVO), () -> {
                                SkitAppReleaseService.ProfileView after =
                                        appReleaseService.saveProfile(profile);
                                return new SkitManagementCommandExecutor.CommandResult<>(
                                        after, canonical(after));
                            });
                }));
    }

    @GetMapping("/app-build/material")
    @PreAuthorize("@ss.hasRole('super_admin')")
    @Operation(summary = "获得代理商 App 原生构建资料状态")
    public CommonResult<SkitAppBuildMaterialService.MaterialView> getAppBuildMaterial(
            @RequestParam("tenantId") Long tenantId) {
        return success(adminTenantScopeGuard.readTenant(tenantId, true,
                scope -> appBuildMaterialService.getMaterial(scope.getTargetTenantId())));
    }

    @PutMapping("/app-build/material")
    @PreAuthorize("@ss.hasRole('super_admin')")
    @ApiAccessLog(sanitizeKeys = {"pangleSettingsJson", "releaseKeystoreBase64", "storePassword", "keyAlias",
            "keyPassword"})
    @Operation(summary = "保存代理商 App 原生构建资料")
    public CommonResult<SkitAppBuildMaterialService.MaterialView> saveAppBuildMaterial(
            @Valid @RequestBody AppBuildMaterialSaveReqVO reqVO) {
        SkitAppBuildMaterialService.MaterialCommand command = new SkitAppBuildMaterialService.MaterialCommand()
                .setTenantId(reqVO.getTenantId())
                .setApiBaseUrl(reqVO.getApiBaseUrl())
                .setAppName(reqVO.getAppName())
                .setNativeVersionCode(reqVO.getNativeVersionCode())
                .setNativeVersionName(reqVO.getNativeVersionName())
                .setRuntimeReleaseNo(reqVO.getRuntimeReleaseNo())
                .setPangleSettingsJson(reqVO.getPangleSettingsJson())
                .setReleaseKeystoreBase64(reqVO.getReleaseKeystoreBase64())
                .setStorePassword(reqVO.getStorePassword())
                .setKeyAlias(reqVO.getKeyAlias())
                .setKeyPassword(reqVO.getKeyPassword())
                .setReason(reqVO.getReason());
        return success(adminTenantScopeGuard.writeTenant(reqVO.getTenantId(),
                SkitManagementCommandType.APP_BUILD_MATERIAL_UPDATE, reqVO.getReason(), scope -> {
                    SkitAppBuildMaterialService.MaterialView before =
                            appBuildMaterialService.getMaterial(scope.getTargetTenantId());
                    return managementCommandExecutor.execute(scope,
                            SkitManagementCommandType.APP_BUILD_MATERIAL_UPDATE, "APP_BUILD_MATERIAL",
                            scope.getTargetTenantId().toString(), reqVO.getReason(), before.auditCanonical(),
                            canonical(reqVO), () -> {
                                SkitAppBuildMaterialService.MaterialView after =
                                        appBuildMaterialService.saveMaterial(command);
                                return new SkitManagementCommandExecutor.CommandResult<>(
                                        after, after.auditCanonical());
                            });
                }));
    }

    @GetMapping("/member/page")
    @Operation(summary = "分页查询当前代理商会员")
    public CommonResult<PageResult<SkitMemberService.MemberView>> getMemberPage(@Valid MemberPageReqVO reqVO) {
        return success(adminTenantScopeGuard.readTenantOrGlobal(reqVO.getTenantId(), true,
                scope -> memberService.getMemberPage(reqVO, reqVO.getKeyword(), reqVO.getStatus()),
                () -> memberService.getGlobalMemberPage(reqVO, reqVO.getKeyword(), reqVO.getStatus())));
    }

    @GetMapping("/member/get")
    @Operation(summary = "获得会员详情")
    public CommonResult<SkitMemberService.MemberView> getMember(
            @RequestParam("id") @NotNull Long id,
            @RequestParam(value = "tenantId", required = false) Long tenantId) {
        return success(adminTenantScopeGuard.readTenant(tenantId, true,
                scope -> memberService.getMember(id)));
    }

    @PutMapping("/member/update-status")
    @Operation(summary = "启用或停用会员")
    public CommonResult<Boolean> updateMemberStatus(@Valid @RequestBody MemberStatusUpdateReqVO reqVO) {
        return success(adminTenantScopeGuard.writeTenant(reqVO.getTenantId(),
                SkitManagementCommandType.MEMBER_STATUS_CHANGE, reqVO.getReason(), scope -> {
                    SkitMemberService.MemberView before = memberService.getMember(reqVO.getId());
                    return managementCommandExecutor.execute(scope,
                            SkitManagementCommandType.MEMBER_STATUS_CHANGE, "MEMBER",
                            reqVO.getId().toString(), reqVO.getReason(), canonical(before),
                            "memberId=" + reqVO.getId() + ";status=" + reqVO.getStatus(), () -> {
                                memberService.updateMemberStatus(reqVO.getId(), reqVO.getStatus());
                                SkitMemberService.MemberView after = memberService.getMember(reqVO.getId());
                                return new SkitManagementCommandExecutor.CommandResult<>(
                                        true, canonical(after));
                            });
                }));
    }

    @PutMapping("/member/reset-password")
    @ApiAccessLog(sanitizeKeys = {"password"})
    @Operation(summary = "重置会员密码")
    public CommonResult<Boolean> resetMemberPassword(@Valid @RequestBody MemberPasswordResetReqVO reqVO) {
        return success(adminTenantScopeGuard.writeTenant(reqVO.getTenantId(),
                SkitManagementCommandType.MEMBER_PASSWORD_RESET, reqVO.getReason(), scope -> {
                    SkitMemberService.MemberView before = memberService.getMember(reqVO.getId());
                    return managementCommandExecutor.execute(scope,
                            SkitManagementCommandType.MEMBER_PASSWORD_RESET, "MEMBER_CREDENTIAL",
                            reqVO.getId().toString(), reqVO.getReason(), canonical(before),
                            "memberId=" + reqVO.getId() + ";passwordProvided=true", () -> {
                                memberService.resetMemberPassword(reqVO.getId(), reqVO.getPassword());
                                SkitMemberService.MemberView after = memberService.getMember(reqVO.getId());
                                return new SkitManagementCommandExecutor.CommandResult<>(
                                        true, canonical(after));
                            });
                }));
    }

    private SkitAdAccountService.Settings toSettings(AdAccountSaveReqVO request) {
        SkitAdAccountService.Settings settings = new SkitAdAccountService.Settings();
        settings.setPangleUsername(request.getPangleUsername());
        settings.setPangleAppId(request.getPangleAppId());
        settings.setPangleAppSecret(request.getPangleAppSecret());
        settings.setPanglePlacementId(request.getPanglePlacementId());
        settings.setPangleEnabled(request.getPangleEnabled());
        settings.setTakuUsername(request.getTakuUsername());
        settings.setTakuAppId(request.getTakuAppId());
        settings.setTakuAppKey(request.getTakuAppKey());
        settings.setTakuAppSecret(request.getTakuAppSecret());
        settings.setTakuPlacementId(request.getTakuPlacementId());
        settings.setTakuEnabled(request.getTakuEnabled());
        return settings;
    }

    private static String canonical(SkitAdAccountService.Settings value) {
        if (value == null) return "<none>";
        return "pangleUsername=" + value.getPangleUsername() + ";pangleAppId=" + value.getPangleAppId()
                + ";panglePlacement=" + value.getPanglePlacementId()
                + ";pangleEnabled=" + value.getPangleEnabled()
                + ";pangleSecretConfigured=" + value.getPangleSecretConfigured()
                + ";takuUsername=" + value.getTakuUsername() + ";takuAppId=" + value.getTakuAppId()
                + ";takuPlacement=" + value.getTakuPlacementId()
                + ";takuEnabled=" + value.getTakuEnabled()
                + ";takuAppKeyConfigured=" + value.getTakuAppKeyConfigured()
                + ";takuSecretConfigured=" + value.getTakuSecretConfigured();
    }

    private static String canonical(AdAccountSaveReqVO value) {
        return "pangleUsername=" + value.getPangleUsername() + ";pangleAppId=" + value.getPangleAppId()
                + ";panglePlacement=" + value.getPanglePlacementId()
                + ";pangleEnabled=" + value.getPangleEnabled()
                + ";pangleSecretProvided=" + (value.getPangleAppSecret() != null)
                + ";takuUsername=" + value.getTakuUsername() + ";takuAppId=" + value.getTakuAppId()
                + ";takuPlacement=" + value.getTakuPlacementId()
                + ";takuEnabled=" + value.getTakuEnabled()
                + ";takuAppKeyProvided=" + (value.getTakuAppKey() != null)
                + ";takuSecretProvided=" + (value.getTakuAppSecret() != null);
    }

    private static String canonical(SkitAppReleaseService.ProfileView value) {
        if (value == null) return "<none>";
        return "tenant=" + value.getTenantId() + ";profile=" + value.getProfileCode()
                + ";channel=" + value.getChannel() + ";minNative=" + value.getMinNativeVersion()
                + ";hotVersion=" + value.getHotVersion() + ";bundleUrl=" + value.getHotBundleUrl()
                + ";bundleSha256=" + value.getHotBundleSha256()
                + ";releaseNo=" + value.getHotReleaseNo() + ";nativeVersion=" + value.getNativeVersion()
                + ";nativePackage=" + value.getNativePackage()
                + ";protocol=" + value.getNativeProtocolVersion()
                + ";keyFingerprint=" + value.getRuntimeUpdateKeyFingerprint()
                + ";status=" + value.getStatus();
    }

    private static String canonical(AppReleaseSaveReqVO value) {
        return "tenant=" + value.getTenantId() + ";channel=" + value.getChannel()
                + ";minNative=" + value.getMinNativeVersion() + ";hotVersion=" + value.getHotVersion()
                + ";bundleUrl=" + value.getHotBundleUrl() + ";bundleSha256=" + value.getHotBundleSha256()
                + ";releaseNo=" + value.getHotReleaseNo() + ";manifestSignatureProvided="
                + (value.getHotManifestSignature() != null) + ";nativeVersion=" + value.getNativeVersion()
                + ";nativePackage=" + value.getNativePackage()
                + ";protocol=" + value.getNativeProtocolVersion()
                + ";runtimeUpdatePublicKeyProvided=" + (value.getRuntimeUpdatePublicKey() != null)
                + ";status=" + value.getStatus();
    }

    private static String canonical(AppBuildMaterialSaveReqVO value) {
        return "tenant=" + value.getTenantId() + ";apiBaseUrl=" + value.getApiBaseUrl()
                + ";appName=" + value.getAppName() + ";nativeVersionCode=" + value.getNativeVersionCode()
                + ";nativeVersionName=" + value.getNativeVersionName() + ";runtimeReleaseNo="
                + value.getRuntimeReleaseNo() + ";pangleSettingsProvided="
                + (value.getPangleSettingsJson() != null && !value.getPangleSettingsJson().trim().isEmpty())
                + ";keystoreProvided="
                + (value.getReleaseKeystoreBase64() != null && !value.getReleaseKeystoreBase64().trim().isEmpty())
                + ";storePasswordProvided="
                + (value.getStorePassword() != null && !value.getStorePassword().isEmpty())
                + ";keyAliasProvided="
                + (value.getKeyAlias() != null && !value.getKeyAlias().trim().isEmpty())
                + ";keyPasswordProvided="
                + (value.getKeyPassword() != null && !value.getKeyPassword().isEmpty());
    }

    private static String canonical(SkitMemberService.MemberView value) {
        return value == null ? "<none>" : "memberId=" + value.getId() + ";status=" + value.getStatus();
    }

    @Data
    public static class AdAccountSaveReqVO {
        private Long tenantId;
        @Size(max = 128, message = "穿山甲账号长度不能超过 128 个字符")
        private String pangleUsername;
        @Size(max = 128, message = "穿山甲 App ID 长度不能超过 128 个字符")
        private String pangleAppId;
        @Size(max = 2048, message = "穿山甲密钥长度不能超过 2048 个字符")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @ToString.Exclude
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
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @ToString.Exclude
        private String takuAppKey;
        @Size(max = 2048, message = "Taku 服务端密钥长度不能超过 2048 个字符")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @ToString.Exclude
        private String takuAppSecret;
        @Size(max = 128, message = "Taku 广告位长度不能超过 128 个字符")
        private String takuPlacementId;
        @NotNull(message = "Taku 启用状态不能为空")
        private Boolean takuEnabled;
        @NotBlank(message = "管理操作原因不能为空")
        @Length(min = 10, max = 500, message = "管理操作原因长度必须为 10 到 500 个字符")
        private String reason;
    }

    @Data
    public static class AdCredentialClearReqVO {
        private Long tenantId;
        @NotBlank(message = "广告平台不能为空")
        @Pattern(regexp = "(?i)PANGLE|TAKU", message = "广告平台仅支持 PANGLE 或 TAKU")
        private String provider;
        @NotBlank(message = "管理操作原因不能为空")
        @Length(min = 10, max = 500, message = "管理操作原因长度必须为 10 到 500 个字符")
        private String reason;
    }

    @Data
    public static class ReportingConfigurationSaveReqVO {
        private Long tenantId;
        @NotNull(message = "当前报表凭证版本不能为空")
        @Min(value = 0, message = "当前报表凭证版本不能小于 0")
        private Integer credentialVersion;
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @ToString.Exclude
        @Size(min = 1, max = 4096, message = "Publisher Key 长度必须为 1 到 4096 个字符")
        private String publisherKey;
        @NotBlank(message = "报表时区不能为空")
        @Pattern(regexp = "UTC-8|UTC\\+8|UTC\\+0",
                message = "报表时区仅支持 UTC-8、UTC+8 或 UTC+0")
        private String reportTimezone;
        @NotBlank(message = "报表币种不能为空")
        @Pattern(regexp = "[A-Z]{3}", message = "报表币种必须为三位大写 ISO 代码")
        private String currency;
        @NotNull(message = "报表金额精度不能为空")
        @Min(value = 0, message = "报表金额精度不能小于 0")
        @Max(value = 18, message = "报表金额精度不能大于 18")
        private Integer amountScale;
        @NotBlank(message = "广告格式不能为空")
        @Pattern(regexp = "rewarded_video", message = "分成广告仅支持 rewarded_video")
        private String adFormat;
        @NotBlank(message = "管理操作原因不能为空")
        @Length(min = 10, max = 500, message = "管理操作原因长度必须为 10 到 500 个字符")
        private String reason;
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
        @PositiveOrZero(message = "热更新发布序号不能为负数")
        private Long hotReleaseNo;
        @Length(max = 1024, message = "热更新清单签名不能超过 1024 个字符")
        private String hotManifestSignature;
        private String nativeVersion;
        private String nativePackage;
        @Positive(message = "原生广告协议版本必须大于 0")
        private Integer nativeProtocolVersion;
        @Length(max = 4096, message = "租户热更新公钥不能超过 4096 个字符")
        private String runtimeUpdatePublicKey;
        @NotNull
        private Integer status;
        @NotBlank(message = "管理操作原因不能为空")
        @Length(min = 10, max = 500, message = "管理操作原因长度必须为 10 到 500 个字符")
        private String reason;
    }

    @Data
    public static class AppBuildMaterialSaveReqVO {
        @NotNull(message = "租户编号不能为空")
        private Long tenantId;
        @Size(max = 2048, message = "API 地址长度不能超过 2048 个字符")
        private String apiBaseUrl;
        @Size(max = 128, message = "应用名称长度不能超过 128 个字符")
        private String appName;
        @Positive(message = "原生 versionCode 必须大于 0")
        private Long nativeVersionCode;
        @Size(max = 64, message = "原生版本名称长度不能超过 64 个字符")
        private String nativeVersionName;
        @Positive(message = "运行时发布序号必须大于 0")
        private Long runtimeReleaseNo;
        @Size(max = 64 * 1024, message = "穿山甲设置文件不能超过 64KB")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @ToString.Exclude
        private String pangleSettingsJson;
        @Size(max = 16 * 1024 * 1024, message = "发布 keystore 不能超过 16MB")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @ToString.Exclude
        private String releaseKeystoreBase64;
        @Size(max = 512, message = "store password 长度不能超过 512 个字符")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @ToString.Exclude
        private String storePassword;
        @Size(max = 256, message = "key alias 长度不能超过 256 个字符")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @ToString.Exclude
        private String keyAlias;
        @Size(max = 512, message = "key password 长度不能超过 512 个字符")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @ToString.Exclude
        private String keyPassword;
        @NotBlank(message = "管理操作原因不能为空")
        @Length(min = 10, max = 500, message = "管理操作原因长度必须为 10 到 500 个字符")
        private String reason;
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
        @NotBlank(message = "管理操作原因不能为空")
        @Length(min = 10, max = 500, message = "管理操作原因长度必须为 10 到 500 个字符")
        private String reason;
    }

    @Data
    public static class MemberPasswordResetReqVO {
        private Long tenantId;
        @NotNull(message = "会员编号不能为空")
        private Long id;
        @NotBlank(message = "新密码不能为空")
        @Length(min = 6, max = 32, message = "会员密码长度为 6 到 32 位")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @ToString.Exclude
        private String password;
        @NotBlank(message = "管理操作原因不能为空")
        @Length(min = 10, max = 500, message = "管理操作原因长度必须为 10 到 500 个字符")
        private String reason;
    }

    @Data
    public static class AgentInvitationRespVO {
        private Long tenantId;
        private String tenantCode;
        private String rootInviteCode;
        private String tenantName;
    }
}
