package cn.iocoder.yudao.module.skit.service.agent;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentSaveReqVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitPlatformAdminGuard;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdAccountService;
import cn.iocoder.yudao.module.skit.service.commission.SkitCommissionService;
import cn.iocoder.yudao.module.system.controller.admin.tenant.vo.tenant.TenantSaveReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.service.tenant.TenantPackageService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.PLATFORM_ADMIN_REQUIRED;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.USER_USERNAME_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkitAgentServiceImplTest {

    @InjectMocks
    private SkitAgentServiceImpl agentService;

    @Mock
    private SkitPlatformAdminGuard platformAdminGuard;
    @Mock
    private SkitAgentMapper agentMapper;
    @Mock
    private SkitMemberMapper memberMapper;
    @Mock
    private TenantService tenantService;
    @Mock
    private TenantPackageService tenantPackageService;
    @Mock
    private AdminUserService adminUserService;
    @Mock
    private SkitAdAccountService adAccountService;
    @Mock
    private SkitCommissionService commissionService;

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void createAgentRequiresPlatformAdministrator() {
        doThrow(exception(PLATFORM_ADMIN_REQUIRED)).when(platformAdminGuard).check();

        assertServiceException(() -> agentService.createAgent(createRequest()), PLATFORM_ADMIN_REQUIRED);

        verifyNoInteractions(tenantService, agentMapper, memberMapper, adAccountService, commissionService);
    }

    @Test
    void createAgentCreatesTenantRegistryAndTenantDefaults() {
        when(tenantService.createTenant(any(TenantSaveReqVO.class))).thenReturn(42L);
        when(agentMapper.selectByTenantCode(anyString())).thenReturn(null);
        when(agentMapper.selectByRootInviteCode(anyString())).thenReturn(null);
        when(memberMapper.selectByInviteCode(anyString())).thenReturn(null);
        when(agentMapper.insert(any(SkitAgentDO.class))).thenReturn(1);

        Long tenantId = agentService.createAgent(createRequest());

        assertEquals(42L, tenantId);
        ArgumentCaptor<SkitAgentDO> agentCaptor = ArgumentCaptor.forClass(SkitAgentDO.class);
        verify(agentMapper).insert(agentCaptor.capture());
        SkitAgentDO agent = agentCaptor.getValue();
        assertEquals(42L, agent.getTenantId());
        assertEquals("AGENT42", agent.getTenantCode());
        assertEquals(CommonStatusEnum.ENABLE.getStatus(), agent.getStatus());
        assertTrue(agent.getRootInviteCode().startsWith("A"));
        assertNull(TenantContextHolder.getTenantId(), "创建结束后必须恢复平台租户上下文");
        verify(adAccountService).ensureDefaultAccounts();
        verify(adAccountService).saveSettings(any(SkitAdAccountService.Settings.class));
        verify(commissionService).ensureDefaultPlan();
    }

    @Test
    void createAgentRejectsAdministratorUsernameAlreadyBoundToAnotherTenant() {
        when(adminUserService.getUserListByUsernameIgnoreTenant("agent-admin"))
                .thenReturn(Collections.singletonList(new AdminUserDO()));

        assertServiceException(() -> agentService.createAgent(createRequest()), USER_USERNAME_EXISTS);

        verifyNoInteractions(tenantService, agentMapper, memberMapper, adAccountService, commissionService);
    }

    private SkitAgentSaveReqVO createRequest() {
        SkitAgentSaveReqVO request = new SkitAgentSaveReqVO();
        request.setName("Agent 42");
        request.setTenantCode("agent42");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        request.setUsername("agent-admin");
        request.setPassword("secret123");
        request.setPackageId(1L);
        return request;
    }
}
