package cn.iocoder.yudao.module.skit.service.app;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.module.skit.dal.dataobject.app.SkitAppReleaseProfileDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.app.SkitAppReleaseProfileMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.APP_RELEASE_PROFILE_INVALID;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_DISABLE;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_EXPIRE;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_NOT_EXISTS;
import static org.mockito.Mockito.*;

import java.util.Arrays;

@ExtendWith(MockitoExtension.class)
class SkitAppReleaseServiceImplTest {

    private static final String TENANT_PUBLIC_KEY_A = "tenant-public-key-a";
    private static final String TENANT_PUBLIC_KEY_B = "tenant-public-key-b";
    private static final String TENANT_KEY_FINGERPRINT_A = repeat('1', 64);
    private static final String TENANT_KEY_FINGERPRINT_B = repeat('2', 64);

    @InjectMocks
    private SkitAppReleaseServiceImpl releaseService;
    @Mock
    private SkitAppReleaseProfileMapper profileMapper;
    @Mock
    private SkitAgentMapper agentMapper;
    @Mock
    private TenantService tenantService;
    @Mock
    private SkitRuntimeUpdateManifestVerifier manifestVerifier;

    @Test
    void currentManifestReturnsCompatibleStableBundle() {
        stubTenantATrustRoot();
        when(profileMapper.selectByProfileCode("AGENT42")).thenReturn(enabledProfile());
        when(agentMapper.selectByTenantId(42L)).thenReturn(enabledAgent());

        SkitAppReleaseService.Manifest manifest = releaseService.current("agent42", "2.2.0");

        assertTrue(manifest.isUpdateAvailable());
        assertEquals("2.3.0", manifest.getHotVersion());
        assertEquals("AGENT42", manifest.getTenantId());
        assertEquals("top.neoshen.agent42", manifest.getApplicationId());
        assertEquals("https://updates.example.com/agent42/2.3.0.wgt", manifest.getBundleUrl());
        assertEquals(repeat('a', 64), manifest.getBundleSha256());
        assertEquals(1, manifest.getProtocolVersion());
        assertEquals(42L, manifest.getReleaseNo());
        assertEquals(repeat('A', 344), manifest.getSignature());
        verify(manifestVerifier).verify(TENANT_PUBLIC_KEY_A,
                "AGENT42", "top.neoshen.agent42",
                repeat('a', 64), 1, 42L, repeat('A', 344));
    }

    @Test
    void currentManifestRejectsNativeVersionBelowMinimum() {
        when(profileMapper.selectByProfileCode("AGENT42")).thenReturn(enabledProfile());
        when(agentMapper.selectByTenantId(42L)).thenReturn(enabledAgent());

        SkitAppReleaseService.Manifest manifest = releaseService.current("AGENT42", "2.0.9");

        assertFalse(manifest.isUpdateAvailable());
    }

    @Test
    void currentManifestUsesCanonicalTenantAndIgnoresLegacyAgentStatus() {
        stubTenantATrustRoot();
        when(profileMapper.selectByProfileCode("AGENT42")).thenReturn(enabledProfile());
        when(agentMapper.selectByTenantId(42L)).thenReturn(enabledAgent()
                .setStatus(CommonStatusEnum.DISABLE.getStatus()));

        SkitAppReleaseService.Manifest manifest = releaseService.current("AGENT42", "2.2.0");

        assertTrue(manifest.isUpdateAvailable());
        verify(tenantService).validTenant(42L);
    }

    @Test
    void currentManifestRejectsDisabledExpiredAndDeletedTenantBeforeAgentLookup() {
        when(profileMapper.selectByProfileCode("AGENT42")).thenReturn(enabledProfile());
        doThrow(exception(TENANT_DISABLE, "agent"),
                exception(TENANT_EXPIRE, "agent"),
                exception(TENANT_NOT_EXISTS)).when(tenantService).validTenant(42L);

        for (ErrorCode errorCode : Arrays.asList(TENANT_DISABLE, TENANT_EXPIRE, TENANT_NOT_EXISTS)) {
            if (errorCode == TENANT_NOT_EXISTS) {
                assertServiceException(() -> releaseService.current("AGENT42", "2.2.0"), errorCode);
            } else {
                assertServiceException(() -> releaseService.current("AGENT42", "2.2.0"), errorCode, "agent");
            }
        }

        verifyNoInteractions(agentMapper);
    }

    @Test
    void disabledProfileStillValidatesTenantBeforeReturningEmptyManifest() {
        SkitAppReleaseProfileDO profile = enabledProfile().setStatus(CommonStatusEnum.DISABLE.getStatus());
        when(profileMapper.selectByProfileCode("AGENT42")).thenReturn(profile);
        doThrow(exception(TENANT_DISABLE, "disabled-agent")).when(tenantService).validTenant(42L);

        assertServiceException(() -> releaseService.current("AGENT42", "2.2.0"), TENANT_DISABLE,
                "disabled-agent");
    }

    @Test
    void getProfileIsPureReadWhenProfileDoesNotExist() {
        when(profileMapper.selectByTenantId(42L)).thenReturn(null);

        assertNull(releaseService.getProfile(42L));

        verify(profileMapper).selectByTenantId(42L);
        verifyNoInteractions(agentMapper, tenantService);
        verify(profileMapper, never()).insert(any(SkitAppReleaseProfileDO.class));
    }

    @Test
    void getProfileReturnsTheTenantPublicKeyAndServerDerivedFingerprint() {
        when(profileMapper.selectByTenantId(42L)).thenReturn(enabledProfile());

        SkitAppReleaseService.ProfileView profile = releaseService.getProfile(42L);

        assertEquals(TENANT_PUBLIC_KEY_A, profile.getRuntimeUpdatePublicKey());
        assertEquals(TENANT_KEY_FINGERPRINT_A, profile.getRuntimeUpdateKeyFingerprint());
        verifyNoInteractions(manifestVerifier);
    }

    @Test
    void saveProfileRejectsReleaseRollbackAndSameReleaseScopeMutation() {
        SkitAppReleaseProfileDO existing = enabledProfile().setId(7L);
        when(agentMapper.selectByTenantId(42L)).thenReturn(enabledAgent());
        when(profileMapper.selectByTenantId(42L)).thenReturn(existing);
        when(profileMapper.selectByTenantIdForUpdate(42L)).thenReturn(existing);

        SkitAppReleaseService.ProfileView rollback = profileView(existing);
        rollback.setHotReleaseNo(41L);
        assertServiceException(() -> releaseService.saveProfile(rollback),
                APP_RELEASE_PROFILE_INVALID, "热更新发布序号不能回退");

        SkitAppReleaseService.ProfileView sameReleaseMutation = profileView(existing);
        sameReleaseMutation.setHotBundleSha256(repeat('b', 64));
        assertServiceException(() -> releaseService.saveProfile(sameReleaseMutation),
                APP_RELEASE_PROFILE_INVALID, "签名字段变更必须提升热更新发布序号");

        SkitAppReleaseService.ProfileView sameReleaseKeyRotation = profileView(existing);
        sameReleaseKeyRotation.setRuntimeUpdatePublicKey(TENANT_PUBLIC_KEY_B);
        assertServiceException(() -> releaseService.saveProfile(sameReleaseKeyRotation),
                APP_RELEASE_PROFILE_INVALID, "签名字段变更必须提升热更新发布序号");

        verify(profileMapper, never()).updateById(any(SkitAppReleaseProfileDO.class));
    }

    @Test
    void saveProfileAcceptsOnlyAHigherReleaseVerifiedByTheTenantTrustRoot() {
        when(manifestVerifier.validateAndFingerprint(TENANT_PUBLIC_KEY_B))
                .thenReturn(TENANT_KEY_FINGERPRINT_B);
        SkitAppReleaseProfileDO existing = enabledProfile().setId(7L);
        when(agentMapper.selectByTenantId(42L)).thenReturn(enabledAgent());
        when(profileMapper.selectByTenantId(42L)).thenReturn(existing);
        when(profileMapper.selectByTenantIdForUpdate(42L)).thenReturn(existing);
        SkitAppReleaseService.ProfileView next = profileView(existing);
        next.setHotVersion("2.3.1");
        next.setHotBundleUrl("https://updates.example.com/agent42/2.3.1.zip");
        next.setHotBundleSha256(repeat('b', 64));
        next.setHotReleaseNo(43L);
        next.setHotManifestSignature(repeat('B', 344));
        next.setRuntimeUpdatePublicKey(TENANT_PUBLIC_KEY_B);
        next.setRuntimeUpdateKeyFingerprint("caller-controlled-fingerprint");

        SkitAppReleaseService.ProfileView saved = releaseService.saveProfile(next);

        assertEquals(43L, saved.getHotReleaseNo());
        assertEquals(repeat('B', 344), saved.getHotManifestSignature());
        assertEquals(TENANT_PUBLIC_KEY_B, saved.getRuntimeUpdatePublicKey());
        assertEquals(TENANT_KEY_FINGERPRINT_B, saved.getRuntimeUpdateKeyFingerprint());
        verify(manifestVerifier).verify(TENANT_PUBLIC_KEY_B,
                "AGENT42", "top.neoshen.agent42",
                repeat('b', 64), 1, 43L, repeat('B', 344));
        verify(profileMapper).updateById(existing);
    }

    @Test
    void saveProfileRejectsASignedManifestWithoutATenantTrustRoot() {
        SkitAppReleaseProfileDO existing = enabledProfile().setId(7L)
                .setRuntimeUpdatePublicKey("").setRuntimeUpdateKeyFingerprint("");
        when(agentMapper.selectByTenantId(42L)).thenReturn(enabledAgent());
        when(profileMapper.selectByTenantId(42L)).thenReturn(existing);
        when(profileMapper.selectByTenantIdForUpdate(42L)).thenReturn(existing);
        SkitAppReleaseService.ProfileView next = profileView(existing);

        assertServiceException(() -> releaseService.saveProfile(next),
                APP_RELEASE_PROFILE_INVALID, "签名热更新清单必须配置该租户的 RSA 公钥");
        verifyNoInteractions(manifestVerifier);
        verify(profileMapper, never()).updateById(any(SkitAppReleaseProfileDO.class));
    }

    private SkitAppReleaseProfileDO enabledProfile() {
        return SkitAppReleaseProfileDO.builder().tenantId(42L).profileCode("AGENT42").channel("production")
                .minNativeVersion("2.1.0").hotVersion("2.3.0")
                .hotBundleUrl("https://updates.example.com/agent42/2.3.0.wgt")
                .hotBundleSha256(repeat('a', 64)).hotReleaseNo(42L)
                .hotManifestSignature(repeat('A', 344))
                .nativeVersion("2.2.0").nativePackage("top.neoshen.agent42")
                .nativeProtocolVersion(1)
                .runtimeUpdatePublicKey(TENANT_PUBLIC_KEY_A)
                .runtimeUpdateKeyFingerprint(TENANT_KEY_FINGERPRINT_A)
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
    }

    private SkitAgentDO enabledAgent() {
        return SkitAgentDO.builder().tenantId(42L).tenantCode("AGENT42")
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
    }

    private SkitAppReleaseService.ProfileView profileView(SkitAppReleaseProfileDO profile) {
        SkitAppReleaseService.ProfileView view = new SkitAppReleaseService.ProfileView();
        view.setTenantId(profile.getTenantId());
        view.setChannel(profile.getChannel());
        view.setMinNativeVersion(profile.getMinNativeVersion());
        view.setHotVersion(profile.getHotVersion());
        view.setHotBundleUrl(profile.getHotBundleUrl());
        view.setHotBundleSha256(profile.getHotBundleSha256());
        view.setHotReleaseNo(profile.getHotReleaseNo());
        view.setHotManifestSignature(profile.getHotManifestSignature());
        view.setNativeVersion(profile.getNativeVersion());
        view.setNativePackage(profile.getNativePackage());
        view.setNativeProtocolVersion(profile.getNativeProtocolVersion());
        view.setRuntimeUpdatePublicKey(profile.getRuntimeUpdatePublicKey());
        view.setRuntimeUpdateKeyFingerprint(profile.getRuntimeUpdateKeyFingerprint());
        view.setStatus(profile.getStatus());
        return view;
    }

    private void stubTenantATrustRoot() {
        when(manifestVerifier.validateAndFingerprint(TENANT_PUBLIC_KEY_A))
                .thenReturn(TENANT_KEY_FINGERPRINT_A);
    }

    private static String repeat(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }

}
