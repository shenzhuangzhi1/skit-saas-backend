package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitStablePageRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPlanRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionPublishReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.commission.SkitCommissionRuleVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.ledger.SkitCommissionLedgerPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.ledger.SkitCommissionLedgerRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.member.SkitMemberChildrenRespVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScopeGuard;
import cn.iocoder.yudao.module.skit.framework.security.SkitPlatformAdminGuard;
import cn.iocoder.yudao.module.skit.service.commission.SkitCommissionManagementService;
import cn.iocoder.yudao.module.skit.service.management.SkitCommissionLedgerQueryService;
import cn.iocoder.yudao.module.skit.service.member.SkitMemberTreeQueryService;
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
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_TARGET_TENANT_REQUIRED;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_TENANT_SCOPE_FORBIDDEN;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitAdvancedManagementControllerTest {

    @Mock private SkitPlatformAdminGuard platformAdminGuard;
    @Mock private PermissionService permissionService;
    @Mock private TenantService tenantService;
    @Mock private SkitAgentMapper agentMapper;
    @Mock private SkitCommissionManagementService commissionService;
    @Mock private SkitCommissionLedgerQueryService ledgerService;
    @Mock private SkitMemberTreeQueryService memberTreeService;

    private SkitCommissionPlanController commissionController;
    private SkitCommissionLedgerController ledgerController;
    private SkitMemberTreeController memberTreeController;

    @BeforeEach
    void setUp() {
        SkitAdminTenantScopeGuard guard = new SkitAdminTenantScopeGuard(platformAdminGuard,
                permissionService, tenantService, agentMapper);
        commissionController = new SkitCommissionPlanController(guard, commissionService);
        ledgerController = new SkitCommissionLedgerController(guard, ledgerService);
        memberTreeController = new SkitMemberTreeController(guard, memberTreeService);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    void tenantAdminCurrentPlanUsesOriginalLoginTenant() {
        authenticate(99L, 42L);
        when(permissionService.hasAnyRoles(99L, "tenant_admin")).thenReturn(true);
        when(agentMapper.selectByTenantId(42L)).thenReturn(agent(42L));
        SkitCommissionPlanRespVO expected = new SkitCommissionPlanRespVO();
        when(commissionService.getCurrent(42L, "UTC+8")).thenReturn(expected);

        assertSame(expected, commissionController.getCurrent(null, "UTC+8").getData());

        verify(tenantService).validTenant(42L);
        verify(commissionService).getCurrent(42L, "UTC+8");
    }

    @Test
    void crossTenantLedgerSelectorIsRejectedBeforeQuery() {
        authenticate(99L, 42L);
        when(permissionService.hasAnyRoles(99L, "tenant_admin")).thenReturn(true);
        SkitCommissionLedgerPageReqVO request = new SkitCommissionLedgerPageReqVO()
                .setTenantId(43L).setCurrency("CNY");

        assertServiceException(() -> ledgerController.getPage(request),
                MANAGEMENT_TENANT_SCOPE_FORBIDDEN);

        verifyNoInteractions(tenantService, agentMapper, ledgerService);
    }

    @Test
    void platformAdminMustExplicitlySelectTenantForLedger() {
        authenticate(7L, 1L);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(true);

        assertServiceException(() -> ledgerController.getPage(
                        new SkitCommissionLedgerPageReqVO().setCurrency("CNY")),
                MANAGEMENT_TARGET_TENANT_REQUIRED);

        verifyNoInteractions(tenantService, agentMapper, ledgerService);
    }

    @Test
    void memberChildrenCannotEscapeOriginalTenant() {
        authenticate(99L, 42L);
        when(permissionService.hasAnyRoles(99L, "tenant_admin")).thenReturn(true);

        assertServiceException(() -> memberTreeController.getChildren(
                        700L, 43L, null, 20, "UTC+8"),
                MANAGEMENT_TENANT_SCOPE_FORBIDDEN);

        verifyNoInteractions(tenantService, agentMapper, memberTreeService);
    }

    @Test
    void publishReceivesExactlyTheWriteScopeBoundByGuard() {
        authenticate(99L, 42L);
        when(permissionService.hasAnyRoles(99L, "tenant_admin")).thenReturn(true);
        when(agentMapper.selectByTenantId(42L)).thenReturn(agent(42L));
        SkitCommissionPublishReqVO request = new SkitCommissionPublishReqVO()
                .setExpectedVersion(0).setReason("publish first verified commission plan")
                .setRules(Collections.singletonList(
                        new SkitCommissionRuleVO().setLevelNo(0).setRateBps(5000)));
        SkitCommissionPlanRespVO expected = new SkitCommissionPlanRespVO();
        when(commissionService.publish(argThat(scope -> scope.getTargetTenantId() == 42L
                        && scope.getAuthorizedCommandType() != null), eq(request)))
                .thenReturn(expected);

        assertSame(expected, commissionController.publish(request).getData());

        verify(commissionService).publish(argThat(scope -> scope.getTargetTenantId() == 42L
                && scope.getOriginalTenantId() == 42L && !scope.isDelegated()), eq(request));
        verify(platformAdminGuard).isPlatformAdmin();
        verify(platformAdminGuard, never()).check();
    }

    private void authenticate(long userId, long tenantId) {
        LoginUser loginUser = new LoginUser().setId(userId).setTenantId(tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }

    private SkitAgentDO agent(long tenantId) {
        return SkitAgentDO.builder().tenantId(tenantId).tenantCode("AGENT" + tenantId).build();
    }
}
