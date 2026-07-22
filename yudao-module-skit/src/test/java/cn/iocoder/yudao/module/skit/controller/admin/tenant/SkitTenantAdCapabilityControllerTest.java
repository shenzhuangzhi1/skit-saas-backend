package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdCallbackKeyRotateReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdCallbackKeyRotateRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdRewardSecretRotateReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdRewardSecretRotateRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdNetworkCapabilityRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdNetworkCapabilityVerifyReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitTenantAdCapabilityConfigReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitTenantAdReadinessRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitTenantAdRolloutReqVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScopeGuard;
import cn.iocoder.yudao.module.skit.framework.security.SkitManagementCommandType;
import cn.iocoder.yudao.module.skit.framework.security.SkitPlatformAdminGuard;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionService;
import cn.iocoder.yudao.module.skit.service.ad.SkitCallbackPublicUrlService;
import cn.iocoder.yudao.module.skit.service.ad.SkitTenantAdCapabilityService;
import cn.iocoder.yudao.module.skit.service.management.SkitManagementCommandExecutor;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_TARGET_TENANT_REQUIRED;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_TENANT_SCOPE_FORBIDDEN;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_COMMAND_FORBIDDEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitTenantAdCapabilityControllerTest {

    @Mock
    private SkitTenantAdCapabilityService capabilityService;
    @Mock
    private SkitAdCredentialVersionService credentialService;
    @Mock
    private SkitCallbackPublicUrlService callbackPublicUrlService;
    @Mock
    private SkitPlatformAdminGuard platformAdminGuard;
    @Mock
    private PermissionService permissionService;
    @Mock
    private TenantService tenantService;
    @Mock
    private SkitAgentMapper agentMapper;
    @Mock
    private SkitManagementCommandExecutor commandExecutor;

    private SkitTenantAdCapabilityController controller;

    @BeforeEach
    void setUp() {
        SkitAdminTenantScopeGuard guard = new SkitAdminTenantScopeGuard(
                platformAdminGuard, permissionService, tenantService, agentMapper);
        controller = new SkitTenantAdCapabilityController(capabilityService, credentialService,
                callbackPublicUrlService, guard, commandExecutor);
    }

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    void tenantAdminReadsOnlyItsOriginalTenantAndGetsLosslessConfiguration() {
        authenticate(42L);
        TenantContextHolder.setTenantId(99L);
        mockTenantAdmin(42L);
        SkitTenantAdCapabilityService.ReadinessView readiness = readiness(42L);
        when(capabilityService.getReadiness()).thenAnswer(invocation -> {
            assertEquals(42L, TenantContextHolder.getRequiredTenantId());
            return readiness;
        });

        CommonResult<SkitTenantAdReadinessRespVO> response = controller.getReadiness(null);

        assertEquals(42L, response.getData().getTenantId());
        assertEquals(4201L, response.getData().getAdAccountId());
        assertEquals("unlock-placement-42", response.getData().getDedicatedUnlockPlacementId());
        assertEquals(response.getData().getReadinessVersion(),
                response.getData().getExpectedReadinessVersion());
        assertEquals(1, response.getData().getNetworkReadiness().size());
        assertEquals(Collections.singletonList("012345abcdef"),
                response.getData().getNetworkReadiness().get(0).getSourceRefs());
        assertTrue(response.getData().getMissingSignedRewardNetworkFirmIds().isEmpty());
        assertTrue(response.getData().getMissingImpressionNetworkFirmIds().isEmpty());
        assertEquals(99L, TenantContextHolder.getRequiredTenantId());
    }

    @Test
    void tenantAdminCannotSelfCertifyANetworkCapability() {
        authenticate(42L);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(false);
        when(permissionService.hasAnyRoles(99L, "tenant_admin")).thenReturn(true);

        assertServiceException(() -> controller.verifyNetworkCapability(
                networkCapabilityRequest(42L, 46, true)), MANAGEMENT_COMMAND_FORBIDDEN);

        verifyNoInteractions(capabilityService, commandExecutor);
    }

    @Test
    void superAdminCapabilityVerificationUsesExactTenantAccountNetworkAuditScope() {
        authenticate(1L);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(true);
        when(agentMapper.selectByTenantId(42L)).thenReturn(agent(42L));
        when(capabilityService.listNetworkCapabilities(4201L)).thenReturn(Collections.emptyList());
        SkitTenantAdCapabilityService.NetworkCapabilityView saved = networkCapability(46);
        when(commandExecutor.execute(any(), eq(SkitManagementCommandType.AD_NETWORK_CAPABILITY_VERIFY),
                anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(saved);

        CommonResult<SkitAdNetworkCapabilityRespVO> response = controller.verifyNetworkCapability(
                networkCapabilityRequest(42L, 46, true));

        assertEquals(46, response.getData().getNetworkFirmId());
        assertEquals("SIGNED_REWARD", response.getData().getRewardAuthority());
        verify(commandExecutor).execute(any(),
                eq(SkitManagementCommandType.AD_NETWORK_CAPABILITY_VERIFY),
                eq("AD_NETWORK_CAPABILITY"), eq("4201:46"),
                eq("verify signed callback capability"), anyString(), anyString(), any());
    }

    @Test
    void tenantAdminCrossTenantTargetFailsBeforeLookupOrService() {
        authenticate(42L);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(false);
        when(permissionService.hasAnyRoles(99L, "tenant_admin")).thenReturn(true);

        assertServiceException(() -> controller.getReadiness(43L),
                MANAGEMENT_TENANT_SCOPE_FORBIDDEN);

        verifyNoInteractions(agentMapper, tenantService, capabilityService);
    }

    @Test
    void superAdminMustSupplyExplicitReadTarget() {
        authenticate(1L);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(true);

        assertServiceException(() -> controller.getReadiness(null),
                MANAGEMENT_TARGET_TENANT_REQUIRED);

        verifyNoInteractions(agentMapper, tenantService, capabilityService);
    }

    @Test
    void superAdminCanInspectArchivedTenantWithoutActivatingIt() {
        authenticate(1L);
        TenantContextHolder.setTenantId(1L);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(true);
        when(tenantService.getTenant(42L)).thenReturn(new TenantDO().setId(42L).setStatus(1));
        when(agentMapper.selectByTenantId(42L)).thenReturn(agent(42L));
        when(capabilityService.getReadiness()).thenReturn(readiness(42L).setTenantActive(false));

        CommonResult<SkitTenantAdReadinessRespVO> response = controller.getReadiness(42L);

        assertFalse(response.getData().getTenantActive());
        verify(tenantService, never()).validTenant(42L);
        assertEquals(1L, TenantContextHolder.getRequiredTenantId());
    }

    @Test
    void configurationAndRolloutUseExactAuditedCommandTypes() {
        authenticate(42L);
        mockTenantAdmin(42L);
        when(capabilityService.getReadiness()).thenReturn(readiness(42L));
        SkitTenantAdCapabilityService.CapabilityView saved = capability(42L);
        when(commandExecutor.execute(any(), eq(SkitManagementCommandType.AD_READINESS_CONFIGURATION),
                anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(saved);
        when(commandExecutor.execute(any(), eq(SkitManagementCommandType.AD_ROLLOUT_TRANSITION),
                anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(saved);

        controller.configure(configuration(42L));
        controller.transition(transition(42L));

        verify(commandExecutor).execute(any(),
                eq(SkitManagementCommandType.AD_READINESS_CONFIGURATION),
                eq("TENANT_AD_READINESS"), eq("42"), eq("configure tenant advertising safely"),
                anyString(), anyString(), any());
        verify(commandExecutor).execute(any(), eq(SkitManagementCommandType.AD_ROLLOUT_TRANSITION),
                eq("TENANT_AD_ROLLOUT"), eq("42"), eq("start the approved shadow rollout"),
                anyString(), anyString(), any());
    }

    @Test
    void callbackKeyIsOneTimeResponseOnlyAndNeverCacheable() {
        authenticate(42L);
        mockTenantAdmin(42L);
        SkitAdCallbackKeyRotateRespVO issued = new SkitAdCallbackKeyRotateRespVO();
        issued.setTenantId(42L);
        issued.setAdAccountId(4201L);
        issued.setVersion(2);
        issued.setConfigured(true);
        issued.setCallbackKey("raw-key-must-not-appear-in-to-string");
        issued.setRewardCallbackUrl("https://ads.example.com/reward/raw-key");
        issued.setImpressionCallbackUrl("https://ads.example.com/impression/raw-key");
        when(commandExecutor.execute(any(), eq(SkitManagementCommandType.AD_CREDENTIAL_ROTATE_NORMAL),
                eq("AD_CALLBACK_KEY"), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(issued);

        ResponseEntity<CommonResult<SkitAdCallbackKeyRotateRespVO>> response =
                controller.rotateCallbackKey(callbackRequest(42L));

        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("no-cache", response.getHeaders().getFirst("Pragma"));
        assertFalse(response.getBody().getData().toString().contains("raw-key"));
        assertEquals("raw-key-must-not-appear-in-to-string",
                response.getBody().getData().getCallbackKey());
    }

    @Test
    void rewardSecretIsWriteOnlyZeroedAndAbsentFromResponseAndAuditInput() throws Exception {
        authenticate(42L);
        mockTenantAdmin(42L);
        SkitAdRewardSecretRotateRespVO issued = new SkitAdRewardSecretRotateRespVO();
        issued.setTenantId(42L);
        issued.setAdAccountId(4201L);
        issued.setVersion(3);
        issued.setConfigured(true);
        when(commandExecutor.execute(any(), eq(SkitManagementCommandType.AD_CREDENTIAL_ROTATE_NORMAL),
                eq("AD_REWARD_SECRET"), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(issued);
        SkitAdRewardSecretRotateReqVO request = rewardRequest(42L);
        String serialized = new ObjectMapper().writeValueAsString(request);

        ResponseEntity<CommonResult<SkitAdRewardSecretRotateRespVO>> response =
                controller.rotateRewardSecret(request);

        assertFalse(serialized.contains("provider-super-secret"));
        assertFalse(request.toString().contains("provider-super-secret"));
        assertTrue(Arrays.equals(new char[request.getRewardSecret().length], request.getRewardSecret()));
        assertFalse(new ObjectMapper().writeValueAsString(response.getBody())
                .contains("provider-super-secret"));
    }

    private void mockTenantAdmin(Long tenantId) {
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(false);
        when(permissionService.hasAnyRoles(99L, "tenant_admin")).thenReturn(true);
        when(agentMapper.selectByTenantId(tenantId)).thenReturn(agent(tenantId));
    }

    private SkitAgentDO agent(Long tenantId) {
        return SkitAgentDO.builder().tenantId(tenantId).tenantCode("AGENT" + tenantId).build();
    }

    private SkitTenantAdCapabilityService.ReadinessView readiness(Long tenantId) {
        SkitTenantAdCapabilityService.ReadinessView result =
                new SkitTenantAdCapabilityService.ReadinessView();
        result.setTenantId(tenantId);
        result.setAdAccountId(4201L);
        result.setRolloutState("OFF");
        result.setReadinessVersion(7);
        result.setExpectedReadinessVersion(7);
        result.setDedicatedUnlockPlacementId("unlock-placement-42");
        result.setDedicatedPlacementVerified(true);
        result.setUnlockNetworkFirmIds(new LinkedHashSet<>(Arrays.asList(35, 66, 67)));
        result.setShadowTestMemberIds(new LinkedHashSet<>(Collections.singletonList(101L)));
        result.setMinNativeVersion("2.4.0");
        result.setMinProtocolVersion(1);
        result.setCallbackKeyVersion(2);
        result.setCallbackKeyIssuedAt(LocalDateTime.of(2026, 7, 15, 4, 0));
        SkitTenantAdCapabilityService.NetworkReadinessView network =
                new SkitTenantAdCapabilityService.NetworkReadinessView();
        network.setNetworkFirmId(66);
        network.setSourceRefs(Collections.singletonList("012345abcdef"));
        network.setSignedRewardSourceRefs(Collections.singletonList("012345abcdef"));
        network.setImpressionSourceRefs(Collections.singletonList("012345abcdef"));
        result.setNetworkReadiness(Collections.singletonList(network));
        return result;
    }

    private SkitTenantAdCapabilityService.NetworkCapabilityView networkCapability(int networkFirmId) {
        SkitTenantAdCapabilityService.NetworkCapabilityView result =
                new SkitTenantAdCapabilityService.NetworkCapabilityView();
        result.setNetworkFirmId(networkFirmId);
        result.setRewardAuthority("SIGNED_REWARD");
        result.setEnabled(true);
        result.setVerified(true);
        result.setVerifiedAt(LocalDateTime.of(2026, 7, 15, 5, 0));
        result.setSupportsUserId(true);
        result.setSupportsCustomData(true);
        result.setSupportsStableTransaction(true);
        result.setSupportsImpressionRevenue(true);
        result.setSupportsReporting(true);
        result.setSelectable(true);
        return result;
    }

    private SkitTenantAdCapabilityService.CapabilityView capability(Long tenantId) {
        SkitTenantAdCapabilityService.CapabilityView result =
                new SkitTenantAdCapabilityService.CapabilityView();
        result.setTenantId(tenantId);
        result.setAdAccountId(4201L);
        result.setRolloutState("OFF");
        result.setReadinessVersion(8);
        return result;
    }

    private SkitTenantAdCapabilityConfigReqVO configuration(Long tenantId) {
        SkitTenantAdCapabilityConfigReqVO request = new SkitTenantAdCapabilityConfigReqVO();
        request.setTenantId(tenantId);
        request.setAdAccountId(4201L);
        request.setDedicatedUnlockPlacementId("unlock-placement-42");
        request.setDedicatedPlacementVerified(true);
        request.setRewardCallbackTemplateVerified(true);
        request.setImpressionCallbackTemplateVerified(true);
        request.setUnlockNetworkFirmIds(new LinkedHashSet<>(Arrays.asList(35, 66, 67)));
        request.setShadowTestMemberIds(new LinkedHashSet<>(Collections.singletonList(101L)));
        request.setMinNativeVersion("2.4.0");
        request.setMinProtocolVersion(1);
        request.setExpectedReadinessVersion(7);
        request.setReason("configure tenant advertising safely");
        return request;
    }

    private SkitTenantAdRolloutReqVO transition(Long tenantId) {
        SkitTenantAdRolloutReqVO request = new SkitTenantAdRolloutReqVO();
        request.setTenantId(tenantId);
        request.setTargetState("SHADOW_TEST_USERS");
        request.setMinNativeVersion("2.4.0");
        request.setMinProtocolVersion(1);
        request.setExpectedReadinessVersion(7);
        request.setReason("start the approved shadow rollout");
        return request;
    }

    private SkitAdCallbackKeyRotateReqVO callbackRequest(Long tenantId) {
        SkitAdCallbackKeyRotateReqVO request = new SkitAdCallbackKeyRotateReqVO();
        request.setTenantId(tenantId);
        request.setAdAccountId(4201L);
        request.setExpectedReadinessVersion(7);
        request.setPriorAcceptanceMinutes(0);
        request.setReason("rotate tenant callback key safely");
        return request;
    }

    private SkitAdRewardSecretRotateReqVO rewardRequest(Long tenantId) {
        SkitAdRewardSecretRotateReqVO request = new SkitAdRewardSecretRotateReqVO();
        request.setTenantId(tenantId);
        request.setAdAccountId(4201L);
        request.setExpectedReadinessVersion(7);
        request.setPriorAcceptanceMinutes(15);
        request.setRewardSecret("provider-super-secret".toCharArray());
        request.setReason("rotate tenant reward secret safely");
        return request;
    }

    private SkitAdNetworkCapabilityVerifyReqVO networkCapabilityRequest(
            Long tenantId, int networkFirmId, boolean enabled) {
        SkitAdNetworkCapabilityVerifyReqVO request = new SkitAdNetworkCapabilityVerifyReqVO();
        request.setTenantId(tenantId);
        request.setAdAccountId(4201L);
        request.setNetworkFirmId(networkFirmId);
        request.setRewardAuthority("SIGNED_REWARD");
        request.setEnabled(enabled);
        request.setSupportsUserId(true);
        request.setSupportsCustomData(true);
        request.setSupportsStableTransaction(true);
        request.setSupportsImpressionRevenue(true);
        request.setSupportsReporting(true);
        request.setExpectedReadinessVersion(7);
        request.setReason("verify signed callback capability");
        return request;
    }

    private void authenticate(Long tenantId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(99L);
        loginUser.setTenantId(tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }

}
