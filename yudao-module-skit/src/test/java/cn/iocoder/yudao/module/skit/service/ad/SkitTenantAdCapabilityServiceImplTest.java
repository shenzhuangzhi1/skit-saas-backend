package cn.iocoder.yudao.module.skit.service.ad;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitTenantAdCapabilityDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.app.SkitAppReleaseProfileDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitTenantAdCapabilityMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.app.SkitAppReleaseProfileMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitNativePlayerGrantMapper;
import cn.iocoder.yudao.module.skit.service.app.SkitRuntimeUpdateManifestVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ROLLOUT_NOT_READY;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ROLLOUT_CONFIG_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ROLLOUT_STATE_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AD_ROLLOUT_VERSION_CONFLICT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitTenantAdCapabilityServiceImplTest {

    private static final Long TENANT_ID = 42L;
    private static final Long ACCOUNT_ID = 4201L;

    @Mock
    private SkitTenantAdCapabilityMapper capabilityMapper;
    @Mock
    private SkitTenantAdReadinessEvidenceReader evidenceReader;
    @Mock
    private SkitAppReleaseProfileMapper releaseProfileMapper;
    @Mock
    private SkitNativePlayerGrantMapper nativePlayerGrantMapper;
    @Mock
    private SkitAdSessionMapper adSessionMapper;
    @Mock
    private SkitRuntimeUpdateManifestVerifier manifestVerifier;

    private SkitTenantAdCapabilityServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(TENANT_ID);
        service = new SkitTenantAdCapabilityServiceImpl(
                capabilityMapper, evidenceReader, releaseProfileMapper,
                nativePlayerGrantMapper, adSessionMapper, manifestVerifier);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void readinessEnumeratesEveryProductionPrerequisiteWithoutReturningSecrets() {
        SkitTenantAdCapabilityDO capability = shadowCapability();
        when(capabilityMapper.selectByTenantForShare(TENANT_ID)).thenReturn(capability);
        when(evidenceReader.read(TENANT_ID, capability)).thenReturn(readyEvidence());

        SkitTenantAdCapabilityService.ReadinessView view = service.getReadiness();

        assertTrue(view.isShadowReady());
        assertTrue(view.isProductionReady());
        assertTrue(view.getBlockers().isEmpty());
        assertTrue(view.isCallbackKeyConfigured());
        assertTrue(view.isRewardSecretConfigured());
        assertTrue(view.isReportingCredentialConfigured());
        assertEquals(ACCOUNT_ID, view.getAdAccountId());
        assertEquals("unlock-placement-42", view.getDedicatedUnlockPlacementId());
        assertTrue(view.getDedicatedPlacementVerified());
        assertEquals(new LinkedHashSet<>(Arrays.asList(35, 66, 67)), view.getUnlockNetworkFirmIds());
        assertEquals(new LinkedHashSet<>(Arrays.asList(101L, 102L)), view.getShadowTestMemberIds());
        assertEquals("2.4.0", view.getMinNativeVersion());
        assertEquals(1, view.getMinProtocolVersion());
        assertEquals(view.getReadinessVersion(), view.getExpectedReadinessVersion());
        assertEquals(4, view.getCallbackKeyVersion());
        assertEquals(5, view.getRewardSecretVersion());
        assertTrue(view.getCallbackPublicUrlHttps());
        assertFalse(view.toString().contains("ciphertext"));
        assertFalse(view.toString().contains("secret"));
    }

    @Test
    void readinessCanInspectAValidOffTenantBeforeItsSingletonIsCreated() {
        when(capabilityMapper.selectByTenantForShare(TENANT_ID)).thenReturn(null);
        SkitTenantAdReadinessEvidence evidence = new SkitTenantAdReadinessEvidence();
        evidence.setTenantActive(true);
        when(evidenceReader.read(eq(TENANT_ID), any(SkitTenantAdCapabilityDO.class)))
                .thenReturn(evidence);

        SkitTenantAdCapabilityService.ReadinessView view = service.getReadiness();

        assertEquals(TENANT_ID, view.getTenantId());
        assertEquals("OFF", view.getRolloutState());
        assertEquals(0, view.getReadinessVersion());
        assertFalse(view.isProductionReady());
    }

    @Test
    void productionReadinessFailsClosedForEachRequiredEvidence() {
        SkitTenantAdCapabilityDO capability = shadowCapability();
        when(capabilityMapper.selectByTenantForShare(TENANT_ID)).thenReturn(capability);
        List<Consumer<SkitTenantAdReadinessEvidence>> missingEvidence = Arrays.asList(
                evidence -> evidence.setTenantActive(false),
                evidence -> evidence.setAccountReady(false),
                evidence -> evidence.setCallbackKeyConfigured(false),
                evidence -> evidence.setRewardSecretConfigured(false),
                evidence -> evidence.setDedicatedUnlockPlacement(false),
                evidence -> evidence.setRewardCallbackTemplateVerified(false),
                evidence -> evidence.setImpressionCallbackTemplateVerified(false),
                evidence -> evidence.setUnlockNetworksAuthoritative(false),
                evidence -> evidence.setReportingCredentialConfigured(false),
                evidence -> evidence.setReportingPermissionVerified(false),
                evidence -> evidence.setReportFresh(false),
                evidence -> evidence.setSignedRewardCallbackObserved(false),
                evidence -> evidence.setImpressionCallbackObserved(false),
                evidence -> evidence.setNativeReleaseReady(false),
                evidence -> evidence.setProtocolReady(false),
                evidence -> evidence.setShadowMembersValid(false),
                evidence -> evidence.setCallbackPublicUrlHttps(false));

        for (Consumer<SkitTenantAdReadinessEvidence> mutation : missingEvidence) {
            SkitTenantAdReadinessEvidence evidence = readyEvidence();
            mutation.accept(evidence);
            when(evidenceReader.read(TENANT_ID, capability)).thenReturn(evidence);
            assertFalse(service.getReadiness().isProductionReady(),
                    "every missing readiness fact must block ENFORCED");
        }
    }

    @Test
    void httpCallbackBaseStillAllowsShadowButBlocksEnforcedReadiness() {
        SkitTenantAdCapabilityDO capability = shadowCapability();
        when(capabilityMapper.selectByTenantForShare(TENANT_ID)).thenReturn(capability);
        SkitTenantAdReadinessEvidence evidence = readyEvidence().setCallbackPublicUrlHttps(false);
        when(evidenceReader.read(TENANT_ID, capability)).thenReturn(evidence);

        SkitTenantAdCapabilityService.ReadinessView view = service.getReadiness();

        assertTrue(view.isShadowReady());
        assertFalse(view.isProductionReady());
        assertTrue(view.getBlockers().contains("CALLBACK_PUBLIC_URL_HTTPS_REQUIRED"));
    }

    @Test
    void directOffToEnforcedIsRejectedBeforeAnyWrite() {
        SkitTenantAdCapabilityDO capability = offCapability();
        when(capabilityMapper.selectByTenantForUpdate(TENANT_ID)).thenReturn(capability);

        SkitTenantAdCapabilityService.TransitionCommand command = transition(
                "ENFORCED", 3, "2.4.0", 1);

        assertServiceException(() -> service.transition(command), AD_ROLLOUT_STATE_INVALID);

        verify(capabilityMapper, never()).transitionCas(any(), any(), any(), any(), any(), any());
        verify(releaseProfileMapper, never()).updateMinNativeVersionForRollout(any(), any(), any());
    }

    @Test
    void credentialRotationLocksTheCapabilityAccountAndOptimisticVersion() {
        SkitTenantAdCapabilityDO capability = shadowCapability();
        when(capabilityMapper.selectByTenantForUpdate(TENANT_ID)).thenReturn(capability);

        service.lockCredentialMutationTarget(ACCOUNT_ID, 8);

        verify(capabilityMapper).selectByTenantForUpdate(TENANT_ID);
        assertServiceException(() -> service.lockCredentialMutationTarget(ACCOUNT_ID, 7),
                AD_ROLLOUT_VERSION_CONFLICT);
        assertServiceException(() -> service.lockCredentialMutationTarget(9999L, 8),
                AD_ROLLOUT_CONFIG_INVALID, "AD_ACCOUNT_TARGET_MISMATCH");
    }

    @Test
    void offToShadowRequiresConfiguredTestMembersAndPreflightEvidence() {
        SkitTenantAdCapabilityDO capability = offCapability();
        when(capabilityMapper.selectByTenantForUpdate(TENANT_ID)).thenReturn(capability);
        SkitTenantAdReadinessEvidence evidence = readyEvidence();
        evidence.setShadowMembersValid(false);
        when(evidenceReader.read(TENANT_ID, capability)).thenReturn(evidence);

        assertServiceException(() -> service.transition(transition(
                "SHADOW_TEST_USERS", 3, "2.4.0", 1)), AD_ROLLOUT_NOT_READY,
                "SHADOW_TEST_MEMBERS_MISSING");

        verify(capabilityMapper, never()).transitionCas(any(), any(), any(), any(), any(), any());
    }

    @Test
    void offToShadowUsesOptimisticVersionAndDatabaseCas() {
        SkitTenantAdCapabilityDO capability = offCapability();
        when(capabilityMapper.selectByTenantForUpdate(TENANT_ID)).thenReturn(capability);
        when(evidenceReader.read(TENANT_ID, capability)).thenReturn(readyEvidence());
        when(capabilityMapper.transitionCas(TENANT_ID, capability.getId(), 3,
                "SHADOW_TEST_USERS", "2.4.0", 1)).thenReturn(1);
        when(capabilityMapper.selectByTenantForShare(TENANT_ID))
                .thenReturn(offCapability().setRolloutState("SHADOW_TEST_USERS").setReadinessVersion(4));

        SkitTenantAdCapabilityService.CapabilityView result = service.transition(transition(
                "SHADOW_TEST_USERS", 3, "2.4.0", 1));

        assertEquals("SHADOW_TEST_USERS", result.getRolloutState());
        assertEquals(4, result.getReadinessVersion());
        verify(capabilityMapper).transitionCas(TENANT_ID, capability.getId(), 3,
                "SHADOW_TEST_USERS", "2.4.0", 1);
        verify(releaseProfileMapper, never()).updateMinNativeVersionForRollout(any(), any(), any());
    }

    @Test
    void shadowToEnforcedRaisesAppMinimumAndRolloutInOneTransactionalCommand() {
        SkitTenantAdCapabilityDO capability = shadowCapability();
        when(capabilityMapper.selectByTenantForUpdate(TENANT_ID)).thenReturn(capability);
        when(evidenceReader.read(TENANT_ID, capability)).thenReturn(readyEvidence());
        when(releaseProfileMapper.selectByTenantIdForUpdate(TENANT_ID)).thenReturn(signedRelease());
        when(releaseProfileMapper.updateMinNativeVersionForRollout(TENANT_ID, 77L, "2.4.0"))
                .thenReturn(1);
        when(capabilityMapper.transitionCas(TENANT_ID, capability.getId(), 8,
                "ENFORCED", "2.4.0", 1)).thenReturn(1);
        when(capabilityMapper.selectByTenantForShare(TENANT_ID))
                .thenReturn(shadowCapability().setRolloutState("ENFORCED").setReadinessVersion(9));

        SkitTenantAdCapabilityService.CapabilityView result = service.transition(transition(
                "ENFORCED", 8, "2.4.0", 1));

        assertEquals("ENFORCED", result.getRolloutState());
        verify(releaseProfileMapper).selectByTenantIdForUpdate(TENANT_ID);
        verify(releaseProfileMapper).updateMinNativeVersionForRollout(TENANT_ID, 77L, "2.4.0");
        verify(nativePlayerGrantMapper).revokeActiveForTenantRollout(TENANT_ID);
        verify(adSessionMapper).rejectPendingForTenantRollout(TENANT_ID);
        verify(capabilityMapper).transitionCas(TENANT_ID, capability.getId(), 8,
                "ENFORCED", "2.4.0", 1);
    }

    @Test
    void failedCapabilityCasRaisesConflictSoAppMinimumUpdateRollsBack() {
        SkitTenantAdCapabilityDO capability = shadowCapability();
        when(capabilityMapper.selectByTenantForUpdate(TENANT_ID)).thenReturn(capability);
        when(evidenceReader.read(TENANT_ID, capability)).thenReturn(readyEvidence());
        when(releaseProfileMapper.selectByTenantIdForUpdate(TENANT_ID)).thenReturn(signedRelease());
        when(releaseProfileMapper.updateMinNativeVersionForRollout(TENANT_ID, 77L, "2.4.0"))
                .thenReturn(1);
        when(capabilityMapper.transitionCas(TENANT_ID, capability.getId(), 8,
                "ENFORCED", "2.4.0", 1)).thenReturn(0);

        assertServiceException(() -> service.transition(transition(
                "ENFORCED", 8, "2.4.0", 1)), AD_ROLLOUT_VERSION_CONFLICT);
    }

    @Test
    void shadowToEnforcedRejectsAStoredManifestThatNoLongerVerifies() {
        SkitTenantAdCapabilityDO capability = shadowCapability();
        SkitAppReleaseProfileDO release = signedRelease();
        when(capabilityMapper.selectByTenantForUpdate(TENANT_ID)).thenReturn(capability);
        when(evidenceReader.read(TENANT_ID, capability)).thenReturn(readyEvidence());
        when(releaseProfileMapper.selectByTenantIdForUpdate(TENANT_ID)).thenReturn(release);
        doThrow(new SecurityException("invalid signature")).when(manifestVerifier).verify(
                release.getRuntimeUpdatePublicKey(), release.getProfileCode(),
                release.getNativePackage(), release.getHotBundleSha256(),
                release.getNativeProtocolVersion(), release.getHotReleaseNo(),
                release.getHotManifestSignature());

        assertServiceException(() -> service.transition(transition(
                "ENFORCED", 8, "2.4.0", 1)), AD_ROLLOUT_NOT_READY,
                "NATIVE_RELEASE_NOT_READY");

        verify(releaseProfileMapper, never()).updateMinNativeVersionForRollout(any(), any(), any());
        verify(nativePlayerGrantMapper, never()).revokeActiveForTenantRollout(any());
    }

    @Test
    void configurationRejectsCrossTenantAccountOrShadowMemberIds() {
        SkitTenantAdCapabilityDO capability = offCapability();
        when(capabilityMapper.selectByTenantForUpdate(TENANT_ID)).thenReturn(capability);
        SkitTenantAdReadinessEvidence evidence = readyEvidence();
        evidence.setAccountBelongsToTenant(false);
        when(evidenceReader.read(any(), any())).thenReturn(evidence);

        assertServiceException(() -> service.configure(configuration(3)), AD_ROLLOUT_NOT_READY,
                "CROSS_TENANT_CONFIGURATION");

        evidence.setAccountBelongsToTenant(true);
        evidence.setShadowMembersBelongToTenant(false);
        assertServiceException(() -> service.configure(configuration(3)), AD_ROLLOUT_NOT_READY,
                "CROSS_TENANT_CONFIGURATION");
        verify(capabilityMapper, never()).updateConfigurationCas(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void enforcedGateRejectsOldNativeOrWrongProtocolAndShadowGateRejectsOtherMembers() {
        SkitTenantAdCapabilityDO enforced = shadowCapability().setRolloutState("ENFORCED");
        when(capabilityMapper.selectByTenantForShare(TENANT_ID)).thenReturn(enforced);

        assertServiceException(() -> service.checkClientAccess(99L,
                new SkitTenantAdCapabilityService.ClientRuntime("2.3.9", 1),
                SkitTenantAdCapabilityService.AccessOperation.AD_SESSION), AD_ROLLOUT_NOT_READY,
                "CLIENT_VERSION_REVOKED");
        assertServiceException(() -> service.checkClientAccess(99L,
                new SkitTenantAdCapabilityService.ClientRuntime("2.4.0", 2),
                SkitTenantAdCapabilityService.AccessOperation.PROTECTED_CONTENT), AD_ROLLOUT_NOT_READY,
                "CLIENT_VERSION_REVOKED");

        SkitTenantAdCapabilityDO shadow = shadowCapability();
        when(capabilityMapper.selectByTenantForShare(TENANT_ID)).thenReturn(shadow);
        assertServiceException(() -> service.checkClientAccess(999L,
                new SkitTenantAdCapabilityService.ClientRuntime("2.4.0", 1),
                SkitTenantAdCapabilityService.AccessOperation.PLAYER_GRANT), AD_ROLLOUT_NOT_READY,
                "MEMBER_NOT_IN_SHADOW");
        service.checkClientAccess(101L,
                new SkitTenantAdCapabilityService.ClientRuntime("2.4.0", 1),
                SkitTenantAdCapabilityService.AccessOperation.PLAYER_GRANT);
    }

    private SkitTenantAdCapabilityService.ConfigurationCommand configuration(int expectedVersion) {
        SkitTenantAdCapabilityService.ConfigurationCommand command =
                new SkitTenantAdCapabilityService.ConfigurationCommand();
        command.setAdAccountId(ACCOUNT_ID);
        command.setDedicatedUnlockPlacementId("unlock-placement-42");
        command.setDedicatedPlacementVerified(true);
        command.setRewardCallbackTemplateVerified(true);
        command.setImpressionCallbackTemplateVerified(true);
        command.setUnlockNetworkFirmIds(new LinkedHashSet<>(Arrays.asList(35, 66, 67)));
        command.setShadowTestMemberIds(new LinkedHashSet<>(Arrays.asList(101L, 102L)));
        command.setMinNativeVersion("2.4.0");
        command.setMinProtocolVersion(1);
        command.setExpectedReadinessVersion(expectedVersion);
        return command;
    }

    private SkitTenantAdCapabilityService.TransitionCommand transition(
            String state, int version, String minNativeVersion, int minProtocolVersion) {
        SkitTenantAdCapabilityService.TransitionCommand command =
                new SkitTenantAdCapabilityService.TransitionCommand();
        command.setTargetState(state);
        command.setExpectedReadinessVersion(version);
        command.setMinNativeVersion(minNativeVersion);
        command.setMinProtocolVersion(minProtocolVersion);
        return command;
    }

    private SkitTenantAdCapabilityDO offCapability() {
        return capability("OFF", 3);
    }

    private SkitTenantAdCapabilityDO shadowCapability() {
        return capability("SHADOW_TEST_USERS", 8);
    }

    private SkitAppReleaseProfileDO signedRelease() {
        return SkitAppReleaseProfileDO.builder().id(77L).tenantId(TENANT_ID)
                .profileCode("AGENT_42").channel("production")
                .hotBundleSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .hotReleaseNo(9L).hotManifestSignature("signed-manifest")
                .nativeVersion("2.5.0").nativePackage("com.example.agent42")
                .nativeProtocolVersion(1)
                .runtimeUpdatePublicKey("tenant-public-key")
                .runtimeUpdateKeyFingerprint(
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
    }

    private SkitTenantAdCapabilityDO capability(String state, int version) {
        SkitTenantAdCapabilityDO result = new SkitTenantAdCapabilityDO().setId(700L);
        result.setTenantId(TENANT_ID);
        return result.setAdAccountId(ACCOUNT_ID).setRolloutState(state)
                .setDedicatedUnlockPlacementId("unlock-placement-42")
                .setDedicatedPlacementVerifiedAt(java.time.LocalDateTime.of(2026, 7, 14, 1, 0))
                .setRewardCallbackTemplateVerifiedAt(java.time.LocalDateTime.of(2026, 7, 14, 1, 1))
                .setImpressionCallbackTemplateVerifiedAt(java.time.LocalDateTime.of(2026, 7, 14, 1, 2))
                .setUnlockNetworkFirmIdsJson("[35,66,67]")
                .setShadowTestMemberIdsJson("[101,102]")
                .setMinNativeVersion("2.4.0").setMinProtocolVersion(1)
                .setReadinessVersion(version);
    }

    private SkitTenantAdReadinessEvidence readyEvidence() {
        return new SkitTenantAdReadinessEvidence()
                .setTenantActive(true).setAccountBelongsToTenant(true).setAccountReady(true)
                .setCallbackKeyConfigured(true).setRewardSecretConfigured(true)
                .setDedicatedUnlockPlacement(true).setRewardCallbackTemplateVerified(true)
                .setImpressionCallbackTemplateVerified(true).setUnlockNetworksAuthoritative(true)
                .setReportingCredentialConfigured(true).setReportingPermissionVerified(true)
                .setReportFresh(true).setSignedRewardCallbackObserved(true)
                .setImpressionCallbackObserved(true).setNativeReleaseReady(true)
                .setProtocolReady(true).setShadowMembersBelongToTenant(true)
                .setShadowMembersValid(true).setCallbackPublicUrlHttps(true)
                .setCallbackKeyVersion(4).setRewardSecretVersion(5)
                .setCallbackKeyIssuedAt(java.time.LocalDateTime.of(2026, 7, 14, 1, 3))
                .setRewardSecretIssuedAt(java.time.LocalDateTime.of(2026, 7, 14, 1, 4));
    }

}
