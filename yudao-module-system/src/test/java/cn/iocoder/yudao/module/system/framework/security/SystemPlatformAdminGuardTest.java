package cn.iocoder.yudao.module.system.framework.security;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemPlatformAdminGuardTest {

    @InjectMocks
    private SystemPlatformAdminGuard guard;
    @Mock
    private TenantProperties tenantProperties;
    @Mock
    private TenantService tenantService;
    @Mock
    private PermissionService permissionService;

    @Test
    void shouldAcceptOnlySuperAdminFromConfiguredSystemTenant() {
        LoginUser loginUser = new LoginUser().setId(7L).setTenantId(1L).setVisitTenantId(42L);
        when(tenantProperties.getPlatformTenantId()).thenReturn(1L);
        when(tenantService.getTenant(1L)).thenReturn(new TenantDO().setId(1L)
                .setPackageId(TenantDO.PACKAGE_ID_SYSTEM));
        when(permissionService.hasAnyRoles(7L, "super_admin")).thenReturn(true);

        try (MockedStatic<SecurityFrameworkUtils> security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUser).thenReturn(loginUser);

            assertTrue(guard.isPlatformAdmin());
        }
    }

    @Test
    void shouldRejectSuperAdminRoleFromAgentTenant() {
        LoginUser loginUser = new LoginUser().setId(7L).setTenantId(42L);
        when(tenantProperties.getPlatformTenantId()).thenReturn(1L);

        try (MockedStatic<SecurityFrameworkUtils> security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUser).thenReturn(loginUser);

            assertFalse(guard.isPlatformAdmin());
        }
        verify(tenantService, never()).getTenant(42L);
        verify(permissionService, never()).hasAnyRoles(7L, "super_admin");
    }

    @Test
    void shouldRejectConfiguredTenantWithoutSystemPackage() {
        LoginUser loginUser = new LoginUser().setId(7L).setTenantId(1L);
        when(tenantProperties.getPlatformTenantId()).thenReturn(1L);
        when(tenantService.getTenant(1L)).thenReturn(new TenantDO().setId(1L).setPackageId(9L));

        try (MockedStatic<SecurityFrameworkUtils> security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUser).thenReturn(loginUser);

            assertFalse(guard.isPlatformAdmin());
        }
        verify(permissionService, never()).hasAnyRoles(7L, "super_admin");
    }

    @Test
    void shouldRejectAnonymousRequest() {
        try (MockedStatic<SecurityFrameworkUtils> security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUser).thenReturn(null);

            assertFalse(guard.isPlatformAdmin());
        }
        verify(tenantService, never()).getTenant(org.mockito.ArgumentMatchers.anyLong());
    }
}
