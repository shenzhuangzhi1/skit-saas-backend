package cn.iocoder.yudao.module.skit.service.ad;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitTenantAdCapabilityDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdNetworkCapabilityDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.app.SkitAppReleaseProfileDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdNetworkCapabilityMapper;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitTenantAdCapabilityServiceImplTest {

    private static final Long TENANT_ID = 42L;
    private static final Long ACCOUNT_ID = 4201L;

    @Mock
    private SkitTenantAdCapabilityMapper capabilityMapper;
    @Mock
    private SkitAdAccountMapper adAccountMapper;
    @Mock
    private SkitAdNetworkCapabilityMapper networkCapabilityMapper;
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
        lenient().when(adAccountMapper.selectEnabledTakuPlacementId(TENANT_ID, ACCOUNT_ID))
                .thenReturn("unlock-placement-42");
        service = new SkitTenantAdCapabilityServiceImpl(
                capabilityMapper, adAccountMapper, networkCapabilityMapper, evidenceReader, releaseProfileMapper,
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
        SkitTenantAdReadinessEvidence evidence = readyEvidence();
        evidence.setAvailableNetworkCapabilities(Collections.singletonList(networkEvidence(66)));
        evidence.setNetworkReadiness(Collections.singletonList(networkEvidence(66)));
        when(evidenceReader.read(eq(TENANT_ID), any(SkitTenantAdCapabilityDO.class)))
                .thenReturn(evidence);

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
        assertEquals(Collections.singleton(66), view.getUnlockNetworkFirmIds());
        assertEquals(new LinkedHashSet<>(Arrays.asList(101L, 102L)), view.getShadowTestMemberIds());
        assertEquals("2.4.0", view.getMinNativeVersion());
        assertEquals(1, view.getMinProtocolVersion());
        assertEquals(view.getReadinessVersion(), view.getExpectedReadinessVersion());
        assertEquals(4, view.getCallbackKeyVersion());
        assertEquals(5, view.getRewardSecretVersion());
        assertTrue(view.getCallbackPublicUrlHttps());
        assertEquals(1, view.getAvailableNetworkCapabilities().size());
        assertEquals(66, view.getAvailableNetworkCapabilities().get(0).getNetworkFirmId());
        assertTrue(view.getAvailableNetworkCapabilities().get(0).isVerified());
        assertTrue(view.getAvailableNetworkCapabilities().get(0).isSelectable());
        assertEquals(1, view.getNetworkReadiness().size());
        assertTrue(view.getNetworkReadiness().get(0).isAuthoritative());
        assertTrue(view.getNetworkReadiness().get(0).isSignedRewardObserved());
        assertTrue(view.getNetworkReadiness().get(0).isImpressionObserved());
        assertEquals(Collections.singletonList("012345abcdef"),
                view.getNetworkReadiness().get(0).getSourceRefs());
        assertTrue(view.getMissingSignedRewardNetworkFirmIds().isEmpty());
        assertTrue(view.getMissingImpressionNetworkFirmIds().isEmpty());
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
        assertTrue(view.getUnlockNetworkFirmIds().isEmpty(),
                "a new tenant must not silently inherit a rewarded network");
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
                evidence -> evidence.setPairedSourceEvidenceObserved(false),
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
    void accountingEvidenceBlocksProductionButDoesNotBlockControlledShadowTesting() {
        SkitTenantAdCapabilityDO capability = shadowCapability();
        when(capabilityMapper.selectByTenantForShare(TENANT_ID)).thenReturn(capability);
        SkitTenantAdReadinessEvidence evidence = readyEvidence()
                .setImpressionCallbackTemplateVerified(false)
                .setReportingCredentialConfigured(false)
                .setReportingPermissionVerified(false);
        when(evidenceReader.read(TENANT_ID, capability)).thenReturn(evidence);

        SkitTenantAdCapabilityService.ReadinessView view = service.getReadiness();

        assertTrue(view.isShadowReady());
        assertFalse(view.isProductionReady());
        assertTrue(view.getBlockers().contains("IMPRESSION_CALLBACK_TEMPLATE_UNVERIFIED"));
        assertTrue(view.getBlockers().contains("REPORTING_CREDENTIAL_MISSING"));
        assertTrue(view.getBlockers().contains("REPORTING_PERMISSION_UNVERIFIED"));
    }

    @Test
    void unpairedSourceEvidenceBlocksProductionEvenWhenNetworkLevelFactsExist() {
        SkitTenantAdCapabilityDO capability = shadowCapability();
        when(capabilityMapper.selectByTenantForShare(TENANT_ID)).thenReturn(capability);
        SkitTenantAdReadinessEvidence evidence = readyEvidence()
                .setPairedSourceEvidenceObserved(false)
                .setMissingPairedSourceNetworkFirmIds(Collections.singleton(66));
        when(evidenceReader.read(TENANT_ID, capability)).thenReturn(evidence);

        SkitTenantAdCapabilityService.ReadinessView view = service.getReadiness();

        assertTrue(view.isShadowReady());
        assertFalse(view.isProductionReady());
        assertTrue(view.getBlockers().contains("PAIRED_SOURCE_EVIDENCE_MISSING"));
        assertEquals(Collections.singleton(66), view.getMissingPairedSourceNetworkFirmIds());
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
    void configurationPersistsRequestedDynamicNetworksSortedWithoutSelfCertification() {
        SkitTenantAdCapabilityDO capability = offCapability();
        when(capabilityMapper.selectByTenantForUpdate(TENANT_ID)).thenReturn(capability);
        when(evidenceReader.read(eq(TENANT_ID), any(SkitTenantAdCapabilityDO.class)))
                .thenReturn(readyEvidence());
        when(networkCapabilityMapper.selectAllForShare(TENANT_ID, ACCOUNT_ID)).thenReturn(
                Arrays.asList(networkCapability(22, true), networkCapability(46, true),
                        networkCapability(66, true)));
        when(capabilityMapper.updateConfigurationCas(TENANT_ID, capability.getId(), 3,
                ACCOUNT_ID, "unlock-placement-42", true, true, true, "[22,46,66]",
                "[101,102]", "2.4.0", 1)).thenReturn(1);
        when(capabilityMapper.selectByTenantForShare(TENANT_ID))
                .thenReturn(shadowCapability().setUnlockNetworkFirmIdsJson("[22,46,66]")
                        .setReadinessVersion(4));

        SkitTenantAdCapabilityService.ConfigurationCommand command = configuration(3);
        command.setDedicatedUnlockPlacementId("user-supplied-spoofed-placement");
        command.setUnlockNetworkFirmIds(new LinkedHashSet<>(Arrays.asList(46, 66, 22)));
        command.setMinProtocolVersion(null);

        SkitTenantAdCapabilityService.CapabilityView saved = service.configure(command);

        assertEquals(new LinkedHashSet<>(Arrays.asList(22, 46, 66)),
                saved.getUnlockNetworkFirmIds());
        verify(adAccountMapper).selectEnabledTakuPlacementId(TENANT_ID, ACCOUNT_ID);
        verify(networkCapabilityMapper).selectAllForShare(TENANT_ID, ACCOUNT_ID);
        verify(capabilityMapper).updateConfigurationCas(TENANT_ID, capability.getId(), 3,
                ACCOUNT_ID, "unlock-placement-42", true, true, true, "[22,46,66]",
                "[101,102]", "2.4.0", 1);
    }

    @Test
    void offConfigurationRejectsMissingSelectedNetworkCapability() {
        SkitTenantAdCapabilityDO capability = offCapability();
        when(capabilityMapper.selectByTenantForUpdate(TENANT_ID)).thenReturn(capability);
        when(evidenceReader.read(eq(TENANT_ID), any(SkitTenantAdCapabilityDO.class)))
                .thenReturn(readyEvidence());
        when(networkCapabilityMapper.selectAllForShare(TENANT_ID, ACCOUNT_ID)).thenReturn(
                Arrays.asList(networkCapability(35, true), networkCapability(66, true)));

        assertServiceException(() -> service.configure(configuration(3)), AD_ROLLOUT_NOT_READY,
                "UNLOCK_NETWORK_CAPABILITY_NOT_AUTHORITATIVE");

        verify(capabilityMapper, never()).updateConfigurationCas(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void offConfigurationRejectsDisabledUnverifiedOrWrongAuthorityCapability() {
        SkitTenantAdCapabilityDO capability = offCapability();
        SkitAdNetworkCapabilityDO unverified = networkCapability(67, true).setVerifiedAt(null);
        SkitAdNetworkCapabilityDO disabled = networkCapability(67, false);
        SkitAdNetworkCapabilityDO wrongAuthority = networkCapability(67, true)
                .setRewardAuthority("NONE");
        when(capabilityMapper.selectByTenantForUpdate(TENANT_ID)).thenReturn(capability);
        when(evidenceReader.read(eq(TENANT_ID), any(SkitTenantAdCapabilityDO.class)))
                .thenReturn(readyEvidence());
        for (SkitAdNetworkCapabilityDO rejected : Arrays.asList(
                disabled, unverified, wrongAuthority)) {
            when(networkCapabilityMapper.selectAllForShare(TENANT_ID, ACCOUNT_ID)).thenReturn(
                    Arrays.asList(networkCapability(35, true), networkCapability(66, true), rejected));
            assertServiceException(() -> service.configure(configuration(3)), AD_ROLLOUT_NOT_READY,
                    "UNLOCK_NETWORK_CAPABILITY_NOT_AUTHORITATIVE");
        }

        verify(capabilityMapper, never()).updateConfigurationCas(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void offConfigurationRejectsCrossAccountSelectedNetworkCapability() {
        SkitTenantAdCapabilityDO capability = offCapability();
        SkitAdNetworkCapabilityDO crossAccount = networkCapability(67, true).setAdAccountId(9999L);
        when(capabilityMapper.selectByTenantForUpdate(TENANT_ID)).thenReturn(capability);
        when(evidenceReader.read(eq(TENANT_ID), any(SkitTenantAdCapabilityDO.class)))
                .thenReturn(readyEvidence());
        when(networkCapabilityMapper.selectAllForShare(TENANT_ID, ACCOUNT_ID)).thenReturn(
                Arrays.asList(networkCapability(35, true), networkCapability(66, true), crossAccount));

        assertServiceException(() -> service.configure(configuration(3)), AD_ROLLOUT_NOT_READY,
                "UNLOCK_NETWORK_CAPABILITY_NOT_AUTHORITATIVE");

        verify(capabilityMapper, never()).updateConfigurationCas(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void productionUnlockConfigurationRejectsInvalidOrOversizedNetworkSets() {
        SkitTenantAdCapabilityService.ConfigurationCommand invalid = configuration(3);
        invalid.setUnlockNetworkFirmIds(new LinkedHashSet<>(Arrays.asList(66, -7)));
        assertServiceException(() -> service.configure(invalid), AD_ROLLOUT_CONFIG_INVALID,
                "UNLOCK_NETWORKS");

        LinkedHashSet<Integer> oversized = new LinkedHashSet<>();
        for (int network = 1; network <= 17; network++) {
            oversized.add(network);
        }
        SkitTenantAdCapabilityService.ConfigurationCommand tooMany = configuration(3);
        tooMany.setUnlockNetworkFirmIds(oversized);
        assertServiceException(() -> service.configure(tooMany), AD_ROLLOUT_CONFIG_INVALID,
                "UNLOCK_NETWORKS");

        verifyNoInteractions(capabilityMapper, evidenceReader, networkCapabilityMapper);
    }

    @Test
    void offConfigurationMayExplicitlyKeepTheNetworkSelectionEmpty() {
        SkitTenantAdCapabilityDO capability = offCapability();
        when(capabilityMapper.selectByTenantForUpdate(TENANT_ID)).thenReturn(capability);
        when(evidenceReader.read(eq(TENANT_ID), any(SkitTenantAdCapabilityDO.class)))
                .thenReturn(readyEvidence());
        when(capabilityMapper.updateConfigurationCas(TENANT_ID, capability.getId(), 3,
                ACCOUNT_ID, "unlock-placement-42", true, true, true, "[]",
                "[101,102]", "2.4.0", 1)).thenReturn(1);
        when(capabilityMapper.selectByTenantForShare(TENANT_ID))
                .thenReturn(offCapability().setUnlockNetworkFirmIdsJson("[]").setReadinessVersion(4));
        SkitTenantAdCapabilityService.ConfigurationCommand command = configuration(3);
        command.setUnlockNetworkFirmIds(Collections.emptySet());

        SkitTenantAdCapabilityService.CapabilityView saved = service.configure(command);

        assertTrue(saved.getUnlockNetworkFirmIds().isEmpty());
        verify(capabilityMapper).updateConfigurationCas(TENANT_ID, capability.getId(), 3,
                ACCOUNT_ID, "unlock-placement-42", true, true, true, "[]",
                "[101,102]", "2.4.0", 1);
    }

    @Test
    void activeShadowConfigurationCannotRemoveEveryUnlockNetwork() {
        when(capabilityMapper.selectByTenantForUpdate(TENANT_ID)).thenReturn(shadowCapability());
        SkitTenantAdCapabilityService.ConfigurationCommand command = configuration(8);
        command.setUnlockNetworkFirmIds(Collections.emptySet());

        assertServiceException(() -> service.configure(command), AD_ROLLOUT_CONFIG_INVALID,
                "UNLOCK_NETWORKS");

        verify(capabilityMapper, never()).updateConfigurationCas(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void superAdminCapabilityMutationSupportsArbitraryNetworkIdsAndLogicalDisable() {
        SkitAdNetworkCapabilityDO network46 = networkCapability(46, true);
        when(capabilityMapper.selectByTenantForUpdate(TENANT_ID))
                .thenReturn(offCapability(), offCapability().setReadinessVersion(4));
        when(networkCapabilityMapper.selectForUpdate(TENANT_ID, ACCOUNT_ID, 46))
                .thenReturn(null, network46);
        when(networkCapabilityMapper.upsertVerified(TENANT_ID, ACCOUNT_ID, 46,
                "SIGNED_REWARD", true, true, true, true, true)).thenReturn(1);
        when(networkCapabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 46))
                .thenReturn(network46, networkCapability(46, false));
        when(networkCapabilityMapper.disable(TENANT_ID, ACCOUNT_ID, 46)).thenReturn(1);
        when(capabilityMapper.bumpNetworkCapabilityVersionCas(TENANT_ID, 700L, ACCOUNT_ID, 3))
                .thenReturn(1);
        when(capabilityMapper.bumpNetworkCapabilityVersionCas(TENANT_ID, 700L, ACCOUNT_ID, 4))
                .thenReturn(1);

        SkitTenantAdCapabilityService.NetworkCapabilityView enabled =
                service.verifyNetworkCapability(networkCommand(46, true));
        SkitTenantAdCapabilityService.NetworkCapabilityCommand disable = networkCommand(46, false);
        disable.setExpectedReadinessVersion(4);
        SkitTenantAdCapabilityService.NetworkCapabilityView disabled =
                service.verifyNetworkCapability(disable);

        assertEquals(46, enabled.getNetworkFirmId());
        assertTrue(enabled.isEnabled());
        assertFalse(disabled.isEnabled());
        assertTrue(disabled.isVerified(), "logical disable must preserve verification metadata");
        verify(networkCapabilityMapper).upsertVerified(TENANT_ID, ACCOUNT_ID, 46,
                "SIGNED_REWARD", true, true, true, true, true);
        verify(networkCapabilityMapper).disable(TENANT_ID, ACCOUNT_ID, 46);
        verify(capabilityMapper).bumpNetworkCapabilityVersionCas(TENANT_ID, 700L, ACCOUNT_ID, 3);
        verify(capabilityMapper).bumpNetworkCapabilityVersionCas(TENANT_ID, 700L, ACCOUNT_ID, 4);
    }

    @Test
    void idempotentNetworkCapabilityUpsertStillAdvancesVersionAndReturnsPersistedState() {
        SkitAdNetworkCapabilityDO network8 = networkCapability(8, true);
        when(capabilityMapper.selectByTenantForUpdate(TENANT_ID)).thenReturn(offCapability());
        when(networkCapabilityMapper.selectForUpdate(TENANT_ID, ACCOUNT_ID, 8)).thenReturn(network8);
        when(networkCapabilityMapper.upsertVerified(TENANT_ID, ACCOUNT_ID, 8,
                "SIGNED_REWARD", true, true, true, true, true)).thenReturn(0);
        when(capabilityMapper.bumpNetworkCapabilityVersionCas(TENANT_ID, 700L, ACCOUNT_ID, 3))
                .thenReturn(1);
        when(networkCapabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 8)).thenReturn(network8);

        SkitTenantAdCapabilityService.NetworkCapabilityView saved =
                service.verifyNetworkCapability(networkCommand(8, true));

        assertEquals(8, saved.getNetworkFirmId());
        assertTrue(saved.isEnabled());
        verify(capabilityMapper).bumpNetworkCapabilityVersionCas(TENANT_ID, 700L, ACCOUNT_ID, 3);
        verify(networkCapabilityMapper).selectForShare(TENANT_ID, ACCOUNT_ID, 8);
    }

    @Test
    void capabilityMutationRejectsCrossTenantAccountAndUnsignedOrUnstableClaims() {
        when(adAccountMapper.selectEnabledTakuPlacementId(TENANT_ID, 9999L)).thenReturn(null);
        SkitTenantAdCapabilityService.NetworkCapabilityCommand crossTenant = networkCommand(22, true);
        crossTenant.setAdAccountId(9999L);
        assertServiceException(() -> service.verifyNetworkCapability(crossTenant),
                AD_ROLLOUT_CONFIG_INVALID, "AD_ACCOUNT_TARGET_MISMATCH");

        SkitTenantAdCapabilityService.NetworkCapabilityCommand unstable = networkCommand(22, true);
        unstable.setSupportsStableTransaction(false);
        assertServiceException(() -> service.verifyNetworkCapability(unstable),
                AD_ROLLOUT_CONFIG_INVALID, "SIGNED_REWARD_CAPABILITY_INVALID");
        verifyNoInteractions(networkCapabilityMapper);
    }

    @Test
    void explicitNoneAuthorityIsPersistedButNeverSelectable() {
        SkitAdNetworkCapabilityDO none = networkCapability(22, true)
                .setRewardAuthority("NONE")
                .setSupportsUserId(false)
                .setSupportsCustomData(false)
                .setSupportsStableTransaction(false);
        when(capabilityMapper.selectByTenantForUpdate(TENANT_ID)).thenReturn(offCapability());
        when(networkCapabilityMapper.selectForUpdate(TENANT_ID, ACCOUNT_ID, 22)).thenReturn(null);
        when(networkCapabilityMapper.upsertVerified(TENANT_ID, ACCOUNT_ID, 22,
                "NONE", false, false, false, true, true)).thenReturn(1);
        when(capabilityMapper.bumpNetworkCapabilityVersionCas(TENANT_ID, 700L, ACCOUNT_ID, 3))
                .thenReturn(1);
        when(networkCapabilityMapper.selectForShare(TENANT_ID, ACCOUNT_ID, 22)).thenReturn(none);
        SkitTenantAdCapabilityService.NetworkCapabilityCommand command = networkCommand(22, true);
        command.setRewardAuthority("NONE");
        command.setSupportsUserId(false);
        command.setSupportsCustomData(false);
        command.setSupportsStableTransaction(false);

        SkitTenantAdCapabilityService.NetworkCapabilityView saved =
                service.verifyNetworkCapability(command);

        assertEquals("NONE", saved.getRewardAuthority());
        assertTrue(saved.isVerified());
        assertFalse(saved.isSelectable());
        assertTrue(saved.getBlockers().contains("SIGNED_REWARD_AUTHORITY_MISSING"));
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
                "SHADOW_MEMBER_TENANT_MISMATCH");
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

    @Test
    void offGateAllowsOnlyRuntimeApprovedPlayerGrant() {
        when(capabilityMapper.selectByTenantForShare(TENANT_ID)).thenReturn(offCapability());
        SkitTenantAdCapabilityService.ClientRuntime accepted =
                new SkitTenantAdCapabilityService.ClientRuntime("2.4.0", 1);

        service.checkClientAccess(101L, accepted, SkitTenantAdCapabilityService.AccessOperation.PLAYER_GRANT);
        assertServiceException(() -> service.checkClientAccess(101L, accepted,
                        SkitTenantAdCapabilityService.AccessOperation.AD_SESSION), AD_ROLLOUT_NOT_READY,
                "ROLLOUT_OFF");
        assertServiceException(() -> service.checkClientAccess(101L, accepted,
                        SkitTenantAdCapabilityService.AccessOperation.PROTECTED_CONTENT), AD_ROLLOUT_NOT_READY,
                "ROLLOUT_OFF");
        assertServiceException(() -> service.checkClientAccess(101L,
                        new SkitTenantAdCapabilityService.ClientRuntime("2.3.9", 1),
                        SkitTenantAdCapabilityService.AccessOperation.PLAYER_GRANT), AD_ROLLOUT_NOT_READY,
                "CLIENT_VERSION_REVOKED");
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

    private SkitTenantAdCapabilityService.NetworkCapabilityCommand networkCommand(
            int networkFirmId, boolean enabled) {
        SkitTenantAdCapabilityService.NetworkCapabilityCommand command =
                new SkitTenantAdCapabilityService.NetworkCapabilityCommand();
        command.setAdAccountId(ACCOUNT_ID);
        command.setNetworkFirmId(networkFirmId);
        command.setRewardAuthority("SIGNED_REWARD");
        command.setEnabled(enabled);
        command.setSupportsUserId(true);
        command.setSupportsCustomData(true);
        command.setSupportsStableTransaction(true);
        command.setSupportsImpressionRevenue(true);
        command.setSupportsReporting(true);
        command.setExpectedReadinessVersion(3);
        return command;
    }

    private SkitAdNetworkCapabilityDO networkCapability(int networkFirmId, boolean enabled) {
        SkitAdNetworkCapabilityDO result = new SkitAdNetworkCapabilityDO();
        result.setId(900L + networkFirmId);
        result.setTenantId(TENANT_ID);
        result.setAdAccountId(ACCOUNT_ID);
        result.setNetworkFirmId(networkFirmId);
        result.setRewardAuthority("SIGNED_REWARD");
        result.setSupportsUserId(true);
        result.setSupportsCustomData(true);
        result.setSupportsStableTransaction(true);
        result.setSupportsImpressionRevenue(true);
        result.setSupportsReporting(true);
        result.setEnabled(enabled);
        result.setVerifiedAt(java.time.LocalDateTime.of(2026, 7, 14, 1, 5));
        return result;
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
                .setUnlockNetworkFirmIdsJson("[66]")
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
                .setImpressionCallbackObserved(true).setPairedSourceEvidenceObserved(true)
                .setNativeReleaseReady(true)
                .setProtocolReady(true).setShadowMembersBelongToTenant(true)
                .setShadowMembersValid(true).setCallbackPublicUrlHttps(true)
                .setCallbackKeyVersion(4).setRewardSecretVersion(5)
                .setCallbackKeyIssuedAt(java.time.LocalDateTime.of(2026, 7, 14, 1, 3))
                .setRewardSecretIssuedAt(java.time.LocalDateTime.of(2026, 7, 14, 1, 4));
    }

    private SkitTenantAdReadinessEvidence.NetworkEvidence networkEvidence(int networkFirmId) {
        return new SkitTenantAdReadinessEvidence.NetworkEvidence()
                .setNetworkFirmId(networkFirmId).setRewardAuthority("SIGNED_REWARD")
                .setEnabled(true).setVerified(true)
                .setVerifiedAt(java.time.LocalDateTime.of(2026, 7, 14, 1, 5))
                .setSupportsUserId(true).setSupportsCustomData(true)
                .setSupportsStableTransaction(true).setSupportsImpressionRevenue(true)
                .setSupportsReporting(true).setAuthoritative(true).setSelectable(true)
                .setSignedRewardObserved(true).setImpressionObserved(true)
                .setPairedSourceObserved(true)
                .setLastSignedRewardCallbackAt(java.time.LocalDateTime.of(2026, 7, 14, 2, 0))
                .setLastImpressionCallbackAt(java.time.LocalDateTime.of(2026, 7, 14, 2, 1))
                .setSourceRefs(Collections.singletonList("012345abcdef"))
                .setSignedRewardSourceRefs(Collections.singletonList("012345abcdef"))
                .setImpressionSourceRefs(Collections.singletonList("012345abcdef"))
                .setPairedSourceRefs(Collections.singletonList("012345abcdef"));
    }

}
