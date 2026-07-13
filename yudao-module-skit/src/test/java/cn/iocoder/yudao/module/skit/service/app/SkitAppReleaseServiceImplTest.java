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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_DISABLE;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_EXPIRE;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_NOT_EXISTS;
import static org.mockito.Mockito.*;

import java.util.Arrays;

@ExtendWith(MockitoExtension.class)
class SkitAppReleaseServiceImplTest {

    @InjectMocks
    private SkitAppReleaseServiceImpl releaseService;
    @Mock
    private SkitAppReleaseProfileMapper profileMapper;
    @Mock
    private SkitAgentMapper agentMapper;
    @Mock
    private TenantService tenantService;

    @Test
    void currentManifestReturnsCompatibleStableBundle() {
        when(profileMapper.selectByProfileCode("AGENT42")).thenReturn(enabledProfile());
        when(agentMapper.selectByTenantId(42L)).thenReturn(enabledAgent());

        SkitAppReleaseService.Manifest manifest = releaseService.current("agent42", "2.2.0");

        assertTrue(manifest.isUpdateAvailable());
        assertEquals("2.3.0", manifest.getHotVersion());
        assertEquals("https://updates.example.com/agent42/2.3.0.wgt", manifest.getBundleUrl());
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", manifest.getSha256());
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

    private SkitAppReleaseProfileDO enabledProfile() {
        return SkitAppReleaseProfileDO.builder().tenantId(42L).profileCode("AGENT42").channel("production")
                .minNativeVersion("2.1.0").hotVersion("2.3.0")
                .hotBundleUrl("https://updates.example.com/agent42/2.3.0.wgt")
                .hotBundleSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
    }

    private SkitAgentDO enabledAgent() {
        return SkitAgentDO.builder().tenantId(42L).tenantCode("AGENT42")
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
    }

}
