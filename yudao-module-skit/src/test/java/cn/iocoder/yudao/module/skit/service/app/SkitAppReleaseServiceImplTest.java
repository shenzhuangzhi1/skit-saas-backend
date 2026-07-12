package cn.iocoder.yudao.module.skit.service.app;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.module.skit.dal.dataobject.app.SkitAppReleaseProfileDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.app.SkitAppReleaseProfileMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitAppReleaseServiceImplTest {

    @InjectMocks
    private SkitAppReleaseServiceImpl releaseService;
    @Mock
    private SkitAppReleaseProfileMapper profileMapper;
    @Mock
    private SkitAgentMapper agentMapper;

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
