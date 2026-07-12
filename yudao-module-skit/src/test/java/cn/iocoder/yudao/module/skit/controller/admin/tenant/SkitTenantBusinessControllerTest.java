package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitPlatformAdminGuard;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdAccountService;
import cn.iocoder.yudao.module.skit.service.commission.SkitCommissionService;
import cn.iocoder.yudao.module.skit.service.member.SkitMemberService;
import cn.iocoder.yudao.module.skit.service.revenue.SkitRevenueService;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.PLATFORM_ADMIN_REQUIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkitTenantBusinessControllerTest {

    @InjectMocks
    private SkitTenantBusinessController controller;

    @Mock
    private SkitAdAccountService adAccountService;
    @Mock
    private SkitCommissionService commissionService;
    @Mock
    private SkitMemberService memberService;
    @Mock
    private SkitRevenueService revenueService;
    @Mock
    private SkitPlatformAdminGuard platformAdminGuard;
    @Mock
    private SkitAgentMapper agentMapper;
    @Mock
    private TenantService tenantService;

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    void tenantAdminCannotReadAnotherAgentAdAccount() {
        authenticate(10L);
        TenantContextHolder.setTenantId(10L);
        doThrow(exception(PLATFORM_ADMIN_REQUIRED)).when(platformAdminGuard).check();

        assertServiceException(() -> controller.getAdAccount(20L), PLATFORM_ADMIN_REQUIRED);

        verify(platformAdminGuard).check();
        verifyNoInteractions(adAccountService, agentMapper, tenantService);
    }

    @Test
    void tenantAdminReadsOnlyItsOwnTenantContext() {
        authenticate(10L);
        TenantContextHolder.setTenantId(10L);
        mockExistingAgent(10L);
        SkitAdAccountService.Settings settings = new SkitAdAccountService.Settings();
        settings.setPangleAppId("pangle-10");
        when(adAccountService.getSettings()).thenAnswer(invocation -> {
            assertEquals(10L, TenantContextHolder.getRequiredTenantId());
            return settings;
        });

        CommonResult<SkitAdAccountService.Settings> response = controller.getAdAccount(null);

        assertEquals("pangle-10", response.getData().getPangleAppId());
        assertEquals(10L, TenantContextHolder.getRequiredTenantId());
        verify(platformAdminGuard, never()).check();
    }

    @Test
    void tenantAdminIgnoresVisitTenantContextWhenNoTenantIsRequested() {
        authenticate(10L);
        TenantContextHolder.setTenantId(20L);
        mockExistingAgent(10L);
        SkitAdAccountService.Settings settings = new SkitAdAccountService.Settings();
        settings.setPangleAppId("pangle-10");
        when(adAccountService.getSettings()).thenAnswer(invocation -> {
            assertEquals(10L, TenantContextHolder.getRequiredTenantId());
            return settings;
        });

        CommonResult<SkitAdAccountService.Settings> response = controller.getAdAccount(null);

        assertEquals("pangle-10", response.getData().getPangleAppId());
        verify(platformAdminGuard, never()).check();
    }

    @Test
    void platformAdminCrossTenantAccessRunsInsideSelectedAgentContext() {
        authenticate(1L);
        TenantContextHolder.setTenantId(1L);
        mockExistingAgent(20L);
        SkitAdAccountService.Settings settings = new SkitAdAccountService.Settings();
        settings.setTakuAppId("taku-20");
        when(adAccountService.getSettings()).thenAnswer(invocation -> {
            assertEquals(20L, TenantContextHolder.getRequiredTenantId());
            return settings;
        });

        CommonResult<SkitAdAccountService.Settings> response = controller.getAdAccount(20L);

        assertEquals("taku-20", response.getData().getTakuAppId());
        assertEquals(1L, TenantContextHolder.getRequiredTenantId(),
                "跨租户调用结束后必须恢复平台租户上下文");
        verify(platformAdminGuard).check();
    }

    private void authenticate(Long tenantId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(99L);
        loginUser.setTenantId(tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }

    private void mockExistingAgent(Long tenantId) {
        when(agentMapper.selectByTenantId(tenantId)).thenReturn(SkitAgentDO.builder().tenantId(tenantId)
                .tenantCode("AGENT" + tenantId).status(CommonStatusEnum.ENABLE.getStatus()).build());
        when(tenantService.getTenant(tenantId)).thenReturn(new TenantDO().setId(tenantId));
    }
}
