package cn.iocoder.yudao.module.skit.framework.security;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
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
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_COMMAND_FORBIDDEN;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_DELEGATION_REASON_REQUIRED;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_TARGET_TENANT_REQUIRED;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_TENANT_SCOPE_FORBIDDEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitAdminTenantScopeGuardTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long TENANT_A = 41L;
    private static final long TENANT_B = 42L;
    private static final long USER_ID = 81L;

    @Mock private SkitPlatformAdminGuard platformAdminGuard;
    @Mock private PermissionService permissionService;
    @Mock private TenantService tenantService;
    @Mock private SkitAgentMapper agentMapper;

    private SkitAdminTenantScopeGuard guard;

    @BeforeEach
    void setUp() {
        guard = new SkitAdminTenantScopeGuard(
                platformAdminGuard, permissionService, tenantService, agentMapper);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    void tenantAdminUsesOriginalLoginTenantAndIgnoresVisitedContext() {
        authenticate(USER_ID, TENANT_A, TENANT_B);
        TenantContextHolder.setTenantId(TENANT_B);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(false);
        when(permissionService.hasAnyRoles(USER_ID, "tenant_admin")).thenReturn(true);
        when(agentMapper.selectByTenantId(TENANT_A)).thenReturn(agent(TENANT_A));

        SkitAdminTenantScope scope = guard.readTenant(null, false, current -> {
            assertEquals(TENANT_A, TenantContextHolder.getRequiredTenantId());
            return current;
        });

        assertEquals(USER_ID, scope.getOperatorUserId());
        assertEquals(TENANT_A, scope.getOriginalTenantId());
        assertEquals(TENANT_A, scope.getTargetTenantId());
        assertFalse(scope.isPlatformAdmin());
        assertFalse(scope.isDelegated());
        assertEquals(SkitManagementAccessMode.ACTIVE_READ, scope.getAccessMode());
        assertNull(scope.getAuthorizedCommandType());
        assertEquals(TENANT_B, TenantContextHolder.getRequiredTenantId(), "prior context must be restored");
        verify(tenantService).validTenant(TENANT_A);
    }

    @Test
    void tenantAdminCannotSelectAnotherTenantBeforeAnyTenantOrAgentLookup() {
        authenticate(USER_ID, TENANT_A, TENANT_B);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(false);
        when(permissionService.hasAnyRoles(USER_ID, "tenant_admin")).thenReturn(true);

        assertServiceException(() -> guard.readTenant(TENANT_B, false, scope -> scope),
                MANAGEMENT_TENANT_SCOPE_FORBIDDEN);

        verifyNoInteractions(tenantService, agentMapper);
    }

    @Test
    void platformAdminCanUseExplicitGlobalReadWithoutSelectingOrActivatingATenant() {
        authenticate(USER_ID, 1L, 1L);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(true);

        String result = guard.readTenantOrGlobal(null, true,
                scope -> "tenant", () -> "global");

        assertEquals("global", result);
        verifyNoInteractions(tenantService, agentMapper);
    }

    @Test
    void tenantAdminOmittedTargetStillUsesOriginalTenantAndNeverGlobalBranch() {
        authenticate(USER_ID, TENANT_A, TENANT_B);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(false);
        when(permissionService.hasAnyRoles(USER_ID, "tenant_admin")).thenReturn(true);
        when(agentMapper.selectByTenantId(TENANT_A)).thenReturn(agent(TENANT_A));

        String result = guard.readTenantOrGlobal(null, true,
                scope -> "tenant-" + scope.getTargetTenantId(), () -> "global");

        assertEquals("tenant-" + TENANT_A, result);
        verify(tenantService).validTenant(TENANT_A);
    }

    @Test
    void nonTenantAdminRoleCannotUseTheGuard() {
        authenticate(USER_ID, TENANT_A, null);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(false);
        when(permissionService.hasAnyRoles(USER_ID, "tenant_admin")).thenReturn(false);

        assertServiceException(() -> guard.readTenant(null, false, scope -> scope),
                MANAGEMENT_TENANT_SCOPE_FORBIDDEN);
        verifyNoInteractions(tenantService, agentMapper);
    }

    @Test
    void anonymousRequestIsRejectedBeforeAnyAuthorityOrTenantLookup() {
        assertServiceException(() -> guard.readTenant(TENANT_A, false, scope -> scope),
                MANAGEMENT_TENANT_SCOPE_FORBIDDEN);

        verifyNoInteractions(platformAdminGuard, permissionService, tenantService, agentMapper);
    }

    @Test
    void platformAdminMustExplicitlySelectTenantForTenantScopedCalls() {
        authenticate(USER_ID, PLATFORM_TENANT_ID, TENANT_B);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(true);

        assertServiceException(() -> guard.readTenant(null, false, scope -> scope),
                MANAGEMENT_TARGET_TENANT_REQUIRED);

        verifyNoInteractions(permissionService, tenantService, agentMapper);
    }

    @Test
    void platformArchivedAuditReadUsesExplicitTargetAndRestoresContext() {
        authenticate(USER_ID, PLATFORM_TENANT_ID, TENANT_A);
        TenantContextHolder.setTenantId(PLATFORM_TENANT_ID);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(true);
        when(tenantService.getTenant(TENANT_B)).thenReturn(new TenantDO().setId(TENANT_B));
        when(agentMapper.selectByTenantId(TENANT_B)).thenReturn(agent(TENANT_B));

        SkitAdminTenantScope scope = guard.readTenant(TENANT_B, true, current -> {
            assertEquals(TENANT_B, TenantContextHolder.getRequiredTenantId());
            return current;
        });

        assertTrue(scope.isPlatformAdmin());
        assertTrue(scope.isDelegated());
        assertEquals(SkitManagementAccessMode.ARCHIVED_AUDIT_READ, scope.getAccessMode());
        assertEquals(PLATFORM_TENANT_ID, TenantContextHolder.getRequiredTenantId());
        verify(tenantService, never()).validTenant(TENANT_B);
    }

    @Test
    void everyWriteRequiresReasonBeforeTargetLookup() {
        authenticate(USER_ID, TENANT_A, null);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(false);
        when(permissionService.hasAnyRoles(USER_ID, "tenant_admin")).thenReturn(true);

        assertServiceException(() -> guard.writeTenant(TENANT_A,
                        SkitManagementCommandType.COMMISSION_PLAN_PUBLISH, "short", scope -> scope),
                MANAGEMENT_DELEGATION_REASON_REQUIRED);

        verifyNoInteractions(tenantService, agentMapper);
    }

    @Test
    void tenantAdminCannotRunPlatformOnlyCommand() {
        authenticate(USER_ID, TENANT_A, null);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(false);
        when(permissionService.hasAnyRoles(USER_ID, "tenant_admin")).thenReturn(true);

        assertServiceException(() -> guard.writeTenant(TENANT_A,
                        SkitManagementCommandType.CALLBACK_DEAD_LETTER_REPLAY,
                        "security replay after verified incident", scope -> scope),
                MANAGEMENT_COMMAND_FORBIDDEN);

        verifyNoInteractions(tenantService, agentMapper);
    }

    @Test
    void delegatedPlatformWriteUsesActiveTargetAndClosedCommand() {
        authenticate(USER_ID, PLATFORM_TENANT_ID, TENANT_A);
        TenantContextHolder.setTenantId(PLATFORM_TENANT_ID);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(true);
        when(agentMapper.selectByTenantId(TENANT_B)).thenReturn(agent(TENANT_B));

        SkitAdminTenantScope scope = guard.writeTenant(TENANT_B,
                SkitManagementCommandType.REPORT_PULL_RETRY_SINGLE,
                "retry one transient provider report failure", current -> {
                    assertEquals(TENANT_B, TenantContextHolder.getRequiredTenantId());
                    return current;
                });

        assertTrue(scope.isDelegated());
        assertEquals(SkitManagementAccessMode.OPERATIONAL_WRITE, scope.getAccessMode());
        assertEquals(SkitManagementCommandType.REPORT_PULL_RETRY_SINGLE,
                scope.getAuthorizedCommandType());
        verify(tenantService).validTenant(TENANT_B);
        assertEquals(PLATFORM_TENANT_ID, TenantContextHolder.getRequiredTenantId());
    }

    @Test
    void tenantAdminCanConfigureReportingOnlyInsideItsOwnTenant() {
        authenticate(USER_ID, TENANT_A, TENANT_B);
        TenantContextHolder.setTenantId(TENANT_B);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(false);
        when(permissionService.hasAnyRoles(USER_ID, "tenant_admin")).thenReturn(true);
        when(agentMapper.selectByTenantId(TENANT_A)).thenReturn(agent(TENANT_A));

        SkitAdminTenantScope scope = guard.writeTenant(null,
                SkitManagementCommandType.REPORTING_CONFIGURATION,
                "configure approved Taku reporting publisher key", current -> {
                    assertEquals(TENANT_A, TenantContextHolder.getRequiredTenantId());
                    return current;
                });

        assertEquals(TENANT_A, scope.getTargetTenantId());
        assertFalse(scope.isDelegated());
        assertEquals(SkitManagementCommandType.REPORTING_CONFIGURATION,
                scope.getAuthorizedCommandType());
        assertEquals(TENANT_B, TenantContextHolder.getRequiredTenantId());
        verify(tenantService).validTenant(TENANT_A);
    }

    @Test
    void platformAdminMustExplicitlySelectReportingConfigurationTenant() {
        authenticate(USER_ID, PLATFORM_TENANT_ID, TENANT_A);
        TenantContextHolder.setTenantId(PLATFORM_TENANT_ID);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(true);
        when(agentMapper.selectByTenantId(TENANT_B)).thenReturn(agent(TENANT_B));

        SkitAdminTenantScope scope = guard.writeTenant(TENANT_B,
                SkitManagementCommandType.REPORTING_CONFIGURATION,
                "rotate approved Taku reporting publisher key", current -> {
                    assertEquals(TENANT_B, TenantContextHolder.getRequiredTenantId());
                    return current;
                });

        assertEquals(TENANT_B, scope.getTargetTenantId());
        assertTrue(scope.isPlatformAdmin());
        assertTrue(scope.isDelegated());
        assertEquals(SkitManagementCommandType.REPORTING_CONFIGURATION,
                scope.getAuthorizedCommandType());
        assertEquals(PLATFORM_TENANT_ID, TenantContextHolder.getRequiredTenantId());
        verify(tenantService).validTenant(TENANT_B);
    }

    @Test
    void globalReadRequiresPlatformAdminAndDoesNotChangeTenantContext() {
        authenticate(USER_ID, PLATFORM_TENANT_ID, null);
        TenantContextHolder.setTenantId(PLATFORM_TENANT_ID);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(true);
        AtomicLong seenTenant = new AtomicLong();

        String value = guard.globalRead(() -> {
            seenTenant.set(TenantContextHolder.getRequiredTenantId());
            return "ok";
        });

        assertEquals("ok", value);
        assertEquals(PLATFORM_TENANT_ID, seenTenant.get());
        assertEquals(PLATFORM_TENANT_ID, TenantContextHolder.getRequiredTenantId());
    }

    @Test
    void tenantAdminCannotUseGlobalRead() {
        authenticate(USER_ID, TENANT_A, null);
        when(platformAdminGuard.isPlatformAdmin()).thenReturn(false);

        assertServiceException(() -> guard.globalRead(() -> "leak"),
                MANAGEMENT_TENANT_SCOPE_FORBIDDEN);

        verifyNoInteractions(permissionService, tenantService, agentMapper);
    }

    private void authenticate(long userId, long tenantId, Long visitTenantId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(userId);
        loginUser.setTenantId(tenantId);
        loginUser.setVisitTenantId(visitTenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }

    private SkitAgentDO agent(long tenantId) {
        return SkitAgentDO.builder().id(tenantId * 10).tenantId(tenantId).build();
    }
}
