package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsOverviewRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdAnalyticsQueryReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdEventPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAdEventRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitReconciliationDetailRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitReconciliationPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitReconciliationRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitStablePageRespVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScopeGuard;
import cn.iocoder.yudao.module.skit.framework.security.SkitPlatformAdminGuard;
import cn.iocoder.yudao.module.skit.service.analytics.SkitAdAnalyticsService;
import cn.iocoder.yudao.module.skit.service.analytics.SkitAdEventQueryService;
import cn.iocoder.yudao.module.skit.service.reconciliation.SkitReconciliationQueryService;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_TENANT_SCOPE_FORBIDDEN;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitAdOperationsReadControllerTest {

    @Mock
    private SkitPlatformAdminGuard platformAdminGuard;
    @Mock
    private PermissionService permissionService;
    @Mock
    private TenantService tenantService;
    @Mock
    private SkitAgentMapper agentMapper;
    @Mock
    private SkitAdAnalyticsService analyticsService;
    @Mock
    private SkitAdEventQueryService eventQueryService;
    @Mock
    private SkitReconciliationQueryService reconciliationQueryService;

    private SkitAdAnalyticsController analyticsController;
    private SkitAdEventController eventController;
    private SkitReconciliationController reconciliationController;

    @BeforeEach
    void setUp() {
        SkitAdminTenantScopeGuard guard = new SkitAdminTenantScopeGuard(platformAdminGuard,
                permissionService, tenantService, agentMapper);
        analyticsController = new SkitAdAnalyticsController(guard, analyticsService);
        eventController = new SkitAdEventController(guard, eventQueryService);
        reconciliationController = new SkitReconciliationController(guard,
                reconciliationQueryService);
    }

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    void tenantAdminAnalyticsScopeComesOnlyFromOriginalLoginTenant() {
        authenticate(99L, 42L);
        when(permissionService.hasAnyRoles(99L, "tenant_admin")).thenReturn(true);
        when(agentMapper.selectByTenantId(42L)).thenReturn(agent(42L));
        SkitAdAnalyticsOverviewRespVO expected = new SkitAdAnalyticsOverviewRespVO();
        expected.setTenantId(42L);
        SkitAdAnalyticsQueryReqVO request = new SkitAdAnalyticsQueryReqVO();
        when(analyticsService.getOverview(42L, request)).thenReturn(expected);

        assertSame(expected, analyticsController.getOverview(request).getData());

        verify(tenantService).validTenant(42L);
        verify(analyticsService).getOverview(42L, request);
        verify(platformAdminGuard).isPlatformAdmin();
    }

    @Test
    void eventQueryRejectsCrossTenantSelectorBeforeAnyFactLookup() {
        authenticate(99L, 42L);
        when(permissionService.hasAnyRoles(99L, "tenant_admin")).thenReturn(true);
        SkitAdEventPageReqVO request = new SkitAdEventPageReqVO();
        request.setTenantId(43L);

        assertServiceException(() -> eventController.getPage(request),
                MANAGEMENT_TENANT_SCOPE_FORBIDDEN);

        verifyNoInteractions(tenantService, agentMapper, eventQueryService);
    }

    @Test
    void platformAdminCanReadExplicitArchivedTenantWithoutActivatingIt() {
        authenticate(7L, 1L);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(true);
        when(tenantService.getTenant(42L)).thenReturn(new TenantDO().setId(42L).setStatus(1));
        when(agentMapper.selectByTenantId(42L)).thenReturn(agent(42L));
        SkitReconciliationPageReqVO request = new SkitReconciliationPageReqVO();
        request.setTenantId(42L);
        SkitStablePageRespVO<SkitReconciliationRespVO> expected = new SkitStablePageRespVO<>();
        expected.setTenantId(42L);
        when(reconciliationQueryService.getPage(42L, request)).thenReturn(expected);

        assertSame(expected, reconciliationController.getPage(request).getData());

        verify(tenantService, never()).validTenant(42L);
        verify(reconciliationQueryService).getPage(42L, request);
    }

    @Test
    void platformAdminOmittedTenantUsesOneServerSideGlobalAnalyticsQuery() {
        authenticate(7L, 1L);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(true);
        SkitAdAnalyticsQueryReqVO request = new SkitAdAnalyticsQueryReqVO();
        SkitAdAnalyticsOverviewRespVO expected = new SkitAdAnalyticsOverviewRespVO();
        when(analyticsService.getGlobalOverview(request)).thenReturn(expected);

        assertSame(expected, analyticsController.getOverview(request).getData());

        verify(analyticsService).getGlobalOverview(request);
        verify(analyticsService, never()).getOverview(anyLong(), same(request));
        verifyNoInteractions(tenantService, agentMapper);
    }

    @Test
    void platformAdminOmittedTenantUsesOneServerSideGlobalEventPageQuery() {
        authenticate(7L, 1L);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(true);
        SkitAdEventPageReqVO request = new SkitAdEventPageReqVO();
        SkitStablePageRespVO<SkitAdEventRespVO> expected = new SkitStablePageRespVO<>();
        when(eventQueryService.getGlobalPage(request)).thenReturn(expected);

        assertSame(expected, eventController.getPage(request).getData());

        verify(eventQueryService).getGlobalPage(request);
        verify(eventQueryService, never()).getPage(anyLong(), same(request));
        verifyNoInteractions(tenantService, agentMapper);
    }

    @Test
    void platformAdminOmittedTenantUsesGlobalReconciliationDetailQuery() {
        authenticate(7L, 1L);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(true);
        SkitReconciliationDetailRespVO expected = new SkitReconciliationDetailRespVO();
        when(reconciliationQueryService.getGlobal(88L, "Asia/Shanghai")).thenReturn(expected);

        assertSame(expected, reconciliationController.get(88L, null, "Asia/Shanghai").getData());

        verify(reconciliationQueryService).getGlobal(88L, "Asia/Shanghai");
        verify(reconciliationQueryService, never()).get(anyLong(), anyLong(), same("Asia/Shanghai"));
        verifyNoInteractions(tenantService, agentMapper);
    }

    private void authenticate(long userId, long tenantId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(userId);
        loginUser.setTenantId(tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }

    private SkitAgentDO agent(long tenantId) {
        return SkitAgentDO.builder().tenantId(tenantId).tenantCode("AGENT" + tenantId).build();
    }

}
