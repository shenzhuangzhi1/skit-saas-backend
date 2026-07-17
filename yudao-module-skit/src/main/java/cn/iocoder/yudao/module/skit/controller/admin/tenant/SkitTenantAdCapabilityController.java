package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdCallbackKeyRotateReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdCallbackKeyRotateRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdRewardSecretRotateReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdRewardSecretRotateRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitTenantAdCapabilityConfigReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitTenantAdCapabilityRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitTenantAdReadinessRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitTenantAdRolloutReqVO;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScope;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScopeGuard;
import cn.iocoder.yudao.module.skit.framework.security.SkitManagementCommandType;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionService;
import cn.iocoder.yudao.module.skit.service.ad.SkitCallbackPublicUrlService;
import cn.iocoder.yudao.module.skit.service.ad.SkitTenantAdCapabilityService;
import cn.iocoder.yudao.module.skit.service.management.SkitManagementCommandExecutor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 租户广告接入就绪")
@RestController
@RequestMapping("/skit/tenant/ad-readiness")
@Validated
@PreAuthorize("@ss.hasAnyRoles('tenant_admin','super_admin')")
@Slf4j
public class SkitTenantAdCapabilityController {

    private final SkitTenantAdCapabilityService capabilityService;
    private final SkitAdCredentialVersionService credentialService;
    private final SkitCallbackPublicUrlService callbackPublicUrlService;
    private final SkitAdminTenantScopeGuard tenantScopeGuard;
    private final SkitManagementCommandExecutor commandExecutor;

    public SkitTenantAdCapabilityController(
            SkitTenantAdCapabilityService capabilityService,
            SkitAdCredentialVersionService credentialService,
            SkitCallbackPublicUrlService callbackPublicUrlService,
            SkitAdminTenantScopeGuard tenantScopeGuard,
            SkitManagementCommandExecutor commandExecutor) {
        this.capabilityService = capabilityService;
        this.credentialService = credentialService;
        this.callbackPublicUrlService = callbackPublicUrlService;
        this.tenantScopeGuard = tenantScopeGuard;
        this.commandExecutor = commandExecutor;
    }

    @GetMapping
    @Operation(summary = "获得代理商广告接入就绪状态和可编辑配置")
    public CommonResult<SkitTenantAdReadinessRespVO> getReadiness(
            @RequestParam(value = "tenantId", required = false) Long requestedTenantId) {
        return success(tenantScopeGuard.readTenant(requestedTenantId, true,
                scope -> SkitTenantAdReadinessRespVO.from(capabilityService.getReadiness())));
    }

    @PutMapping("/configuration")
    @Operation(summary = "保存广告接入就绪配置")
    public CommonResult<SkitTenantAdCapabilityRespVO> configure(
            @Valid @RequestBody SkitTenantAdCapabilityConfigReqVO request) {
        SkitTenantAdCapabilityService.CapabilityView saved = tenantScopeGuard.writeTenant(
                request.getTenantId(), SkitManagementCommandType.AD_READINESS_CONFIGURATION,
                request.getReason(), scope -> {
                    SkitTenantAdCapabilityService.ReadinessView before = capabilityService.getReadiness();
                    return commandExecutor.execute(scope,
                            SkitManagementCommandType.AD_READINESS_CONFIGURATION,
                            "TENANT_AD_READINESS", scope.getTargetTenantId().toString(),
                            request.getReason(), canonical(before), canonical(request), () -> {
                                SkitTenantAdCapabilityService.CapabilityView result =
                                        capabilityService.configure(toCommand(request));
                                return new SkitManagementCommandExecutor.CommandResult<>(
                                        result, canonical(result));
                            });
                });
        return success(SkitTenantAdCapabilityRespVO.from(saved));
    }

    @PutMapping("/rollout")
    @Operation(summary = "原子切换广告发布状态和最低客户端版本")
    public CommonResult<SkitTenantAdCapabilityRespVO> transition(
            @Valid @RequestBody SkitTenantAdRolloutReqVO request) {
        SkitTenantAdCapabilityService.CapabilityView saved = tenantScopeGuard.writeTenant(
                request.getTenantId(), SkitManagementCommandType.AD_ROLLOUT_TRANSITION,
                request.getReason(), scope -> {
                    SkitTenantAdCapabilityService.ReadinessView before = capabilityService.getReadiness();
                    return commandExecutor.execute(scope, SkitManagementCommandType.AD_ROLLOUT_TRANSITION,
                            "TENANT_AD_ROLLOUT", scope.getTargetTenantId().toString(),
                            request.getReason(), canonical(before), canonical(request), () -> {
                                SkitTenantAdCapabilityService.CapabilityView result =
                                        capabilityService.transition(toCommand(request));
                                return new SkitManagementCommandExecutor.CommandResult<>(
                                        result, canonical(result));
                            });
                });
        return success(SkitTenantAdCapabilityRespVO.from(saved));
    }

    @PostMapping("/callback-key/rotate")
    @Operation(summary = "签发或轮换回调密钥（原文和回调地址只返回一次）")
    @ApiAccessLog(responseEnable = false,
            sanitizeKeys = {"callbackKey", "rewardCallbackUrl", "impressionCallbackUrl"})
    @Transactional(rollbackFor = Exception.class)
    public ResponseEntity<CommonResult<SkitAdCallbackKeyRotateRespVO>> rotateCallbackKey(
            @Valid @RequestBody SkitAdCallbackKeyRotateReqVO request) {
        try {
            SkitAdCallbackKeyRotateRespVO response = tenantScopeGuard.writeTenant(
                    request.getTenantId(), SkitManagementCommandType.AD_CREDENTIAL_ROTATE_NORMAL,
                    request.getReason(), scope -> rotateCallbackKey(scope, request));
            return noStore(success(response));
        } catch (RuntimeException failure) {
            SQLException sqlFailure = findSqlFailure(failure);
            if (sqlFailure != null) {
                log.error("[rotateCallbackKey][tenantId({}) adAccountId({}) sqlState({}) errorCode({})]",
                        request.getTenantId(), request.getAdAccountId(), sqlFailure.getSQLState(),
                        sqlFailure.getErrorCode());
            }
            throw failure;
        }
    }

    @PostMapping("/reward-secret/rotate")
    @Operation(summary = "签发或轮换版本化奖励验签密钥（密钥不回显）")
    @ApiAccessLog(responseEnable = false, sanitizeKeys = {"rewardSecret"})
    @Transactional(rollbackFor = Exception.class)
    public ResponseEntity<CommonResult<SkitAdRewardSecretRotateRespVO>> rotateRewardSecret(
            @Valid @RequestBody SkitAdRewardSecretRotateReqVO request) {
        try {
            SkitAdRewardSecretRotateRespVO response = tenantScopeGuard.writeTenant(
                    request.getTenantId(), SkitManagementCommandType.AD_CREDENTIAL_ROTATE_NORMAL,
                    request.getReason(), scope -> rotateRewardSecret(scope, request));
            return noStore(success(response));
        } finally {
            if (request.getRewardSecret() != null) {
                Arrays.fill(request.getRewardSecret(), '\0');
            }
        }
    }

    private SkitAdCallbackKeyRotateRespVO rotateCallbackKey(
            SkitAdminTenantScope scope, SkitAdCallbackKeyRotateReqVO request) {
        capabilityService.lockCredentialMutationTarget(
                request.getAdAccountId(), request.getExpectedReadinessVersion());
        SkitAdCredentialVersionService.CredentialMetadata before =
                credentialService.getActiveCallbackKeyVersion(
                        scope.getTargetTenantId(), request.getAdAccountId());
        return commandExecutor.execute(scope, SkitManagementCommandType.AD_CREDENTIAL_ROTATE_NORMAL,
                "AD_CALLBACK_KEY", request.getAdAccountId().toString(), request.getReason(),
                canonical(before), canonical(request), () -> {
                    Duration grace = Duration.ofMinutes(request.getPriorAcceptanceMinutes());
                    SkitAdCredentialVersionService.CallbackKeyIssue issued =
                            credentialService.rotateCallbackKey(scope.getTargetTenantId(),
                                    request.getAdAccountId(), grace);
                    String rawKey = issued.consumeCallbackKey();
                    SkitAdCallbackKeyRotateRespVO result = new SkitAdCallbackKeyRotateRespVO();
                    result.setTenantId(scope.getTargetTenantId());
                    result.setAdAccountId(request.getAdAccountId());
                    result.setVersion(issued.getVersion());
                    result.setConfigured(true);
                    result.setActivatedAt(issued.getActivatedAt());
                    result.setPriorVersionAcceptUntil(before == null ? null
                            : issued.getActivatedAt().plus(grace));
                    result.setCallbackKey(rawKey);
                    result.setRewardCallbackUrl(callbackPublicUrlService.rewardCallbackUrl(rawKey));
                    result.setImpressionCallbackUrl(callbackPublicUrlService.impressionCallbackUrl(rawKey));
                    return new SkitManagementCommandExecutor.CommandResult<>(
                            result, canonical(result));
                });
    }

    private SkitAdRewardSecretRotateRespVO rotateRewardSecret(
            SkitAdminTenantScope scope, SkitAdRewardSecretRotateReqVO request) {
        capabilityService.lockCredentialMutationTarget(
                request.getAdAccountId(), request.getExpectedReadinessVersion());
        SkitAdCredentialVersionService.CredentialMetadata before =
                credentialService.getActiveRewardSecretVersion(
                        scope.getTargetTenantId(), request.getAdAccountId());
        return commandExecutor.execute(scope, SkitManagementCommandType.AD_CREDENTIAL_ROTATE_NORMAL,
                "AD_REWARD_SECRET", request.getAdAccountId().toString(), request.getReason(),
                canonical(before), canonicalRewardRequest(request), () -> {
                    byte[] secret = utf8(request.getRewardSecret());
                    try {
                        Duration grace = Duration.ofMinutes(request.getPriorAcceptanceMinutes());
                        SkitAdCredentialVersionService.CredentialMetadata issued =
                                credentialService.rotateRewardSecret(scope.getTargetTenantId(),
                                        request.getAdAccountId(), secret, grace);
                        SkitAdRewardSecretRotateRespVO result = new SkitAdRewardSecretRotateRespVO();
                        result.setTenantId(scope.getTargetTenantId());
                        result.setAdAccountId(request.getAdAccountId());
                        result.setVersion(issued.getVersion());
                        result.setConfigured(true);
                        result.setActivatedAt(issued.getActivatedAt());
                        result.setPriorVersionAcceptUntil(before == null ? null
                                : issued.getActivatedAt().plus(grace));
                        return new SkitManagementCommandExecutor.CommandResult<>(
                                result, canonical(result));
                    } finally {
                        Arrays.fill(secret, (byte) 0);
                    }
                });
    }

    private SkitTenantAdCapabilityService.ConfigurationCommand toCommand(
            SkitTenantAdCapabilityConfigReqVO request) {
        SkitTenantAdCapabilityService.ConfigurationCommand command =
                new SkitTenantAdCapabilityService.ConfigurationCommand();
        command.setAdAccountId(request.getAdAccountId());
        command.setDedicatedUnlockPlacementId(request.getDedicatedUnlockPlacementId());
        command.setDedicatedPlacementVerified(request.getDedicatedPlacementVerified());
        command.setRewardCallbackTemplateVerified(request.getRewardCallbackTemplateVerified());
        command.setImpressionCallbackTemplateVerified(request.getImpressionCallbackTemplateVerified());
        command.setUnlockNetworkFirmIds(request.getUnlockNetworkFirmIds());
        command.setShadowTestMemberIds(request.getShadowTestMemberIds());
        command.setMinNativeVersion(request.getMinNativeVersion());
        command.setMinProtocolVersion(request.getMinProtocolVersion());
        command.setExpectedReadinessVersion(request.getExpectedReadinessVersion());
        return command;
    }

    private SkitTenantAdCapabilityService.TransitionCommand toCommand(
            SkitTenantAdRolloutReqVO request) {
        SkitTenantAdCapabilityService.TransitionCommand command =
                new SkitTenantAdCapabilityService.TransitionCommand();
        command.setTargetState(request.getTargetState());
        command.setMinNativeVersion(request.getMinNativeVersion());
        command.setMinProtocolVersion(request.getMinProtocolVersion());
        command.setExpectedReadinessVersion(request.getExpectedReadinessVersion());
        return command;
    }

    private static SQLException findSqlFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException) {
                return (SQLException) current;
            }
            current = current.getCause();
        }
        return null;
    }

    private static <T> ResponseEntity<CommonResult<T>> noStore(CommonResult<T> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache").body(body);
    }

    private static byte[] utf8(char[] chars) {
        ByteBuffer buffer = null;
        try {
            buffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars));
            byte[] result = new byte[buffer.remaining()];
            buffer.get(result);
            return result;
        } catch (CharacterCodingException invalidSecret) {
            throw new IllegalArgumentException("Reward secret contains invalid characters");
        } finally {
            if (buffer != null && buffer.hasArray()) {
                Arrays.fill(buffer.array(), (byte) 0);
            }
        }
    }

    private static String canonical(SkitTenantAdCapabilityService.ReadinessView value) {
        if (value == null) return "<none>";
        return "tenant=" + value.getTenantId() + ";account=" + value.getAdAccountId()
                + ";state=" + value.getRolloutState() + ";version=" + value.getReadinessVersion()
                + ";placement=" + value.getDedicatedUnlockPlacementId()
                + ";networks=" + value.getUnlockNetworkFirmIds()
                + ";members=" + value.getShadowTestMemberIds()
                + ";native=" + value.getMinNativeVersion()
                + ";protocol=" + value.getMinProtocolVersion();
    }

    private static String canonical(SkitTenantAdCapabilityService.CapabilityView value) {
        if (value == null) return "<none>";
        return "tenant=" + value.getTenantId() + ";account=" + value.getAdAccountId()
                + ";state=" + value.getRolloutState() + ";version=" + value.getReadinessVersion()
                + ";placement=" + value.getDedicatedUnlockPlacementId()
                + ";networks=" + value.getUnlockNetworkFirmIds()
                + ";members=" + value.getShadowTestMemberIds()
                + ";native=" + value.getMinNativeVersion()
                + ";protocol=" + value.getMinProtocolVersion();
    }

    private static String canonical(SkitTenantAdCapabilityConfigReqVO value) {
        return "account=" + value.getAdAccountId() + ";expected=" + value.getExpectedReadinessVersion()
                + ";placement=" + value.getDedicatedUnlockPlacementId()
                + ";dedicatedVerified=" + value.getDedicatedPlacementVerified()
                + ";rewardTemplateVerified=" + value.getRewardCallbackTemplateVerified()
                + ";impressionTemplateVerified=" + value.getImpressionCallbackTemplateVerified()
                + ";networks=" + value.getUnlockNetworkFirmIds()
                + ";members=" + value.getShadowTestMemberIds()
                + ";native=" + value.getMinNativeVersion()
                + ";protocol=" + value.getMinProtocolVersion();
    }

    private static String canonical(SkitTenantAdRolloutReqVO value) {
        return "state=" + value.getTargetState() + ";expected=" + value.getExpectedReadinessVersion()
                + ";native=" + value.getMinNativeVersion()
                + ";protocol=" + value.getMinProtocolVersion();
    }

    private static String canonical(SkitAdCallbackKeyRotateReqVO value) {
        return "account=" + value.getAdAccountId() + ";expected=" + value.getExpectedReadinessVersion()
                + ";graceMinutes=" + value.getPriorAcceptanceMinutes();
    }

    private static String canonicalRewardRequest(SkitAdRewardSecretRotateReqVO value) {
        return "account=" + value.getAdAccountId() + ";expected=" + value.getExpectedReadinessVersion()
                + ";graceMinutes=" + value.getPriorAcceptanceMinutes() + ";secretProvided=true";
    }

    private static String canonical(SkitAdCredentialVersionService.CredentialMetadata value) {
        if (value == null) return "configured=false";
        return "account=" + value.getAdAccountId() + ";version=" + value.getVersion()
                + ";active=" + value.isActive() + ";activatedAt=" + value.getActivatedAt()
                + ";acceptUntil=" + value.getAcceptUntil();
    }

    private static String canonical(SkitAdCallbackKeyRotateRespVO value) {
        return "account=" + value.getAdAccountId() + ";version=" + value.getVersion()
                + ";configured=" + value.getConfigured() + ";activatedAt=" + value.getActivatedAt()
                + ";priorAcceptUntil=" + value.getPriorVersionAcceptUntil();
    }

    private static String canonical(SkitAdRewardSecretRotateRespVO value) {
        return "account=" + value.getAdAccountId() + ";version=" + value.getVersion()
                + ";configured=" + value.getConfigured() + ";activatedAt=" + value.getActivatedAt()
                + ";priorAcceptUntil=" + value.getPriorVersionAcceptUntil();
    }

}
