package cn.iocoder.yudao.module.system.controller.admin.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TenantPackageControllerTest {

    @Test
    void shouldRequireSuperAdminForEveryTenantPackageEndpoint() {
        PreAuthorize authorization = TenantPackageController.class.getAnnotation(PreAuthorize.class);

        assertNotNull(authorization);
        assertEquals("@ss.hasRole('super_admin')", authorization.value());
    }

    @Test
    void shouldRequireSuperAdminAndPreserveEndpointPermissionChecks() throws Exception {
        assertPermission("createTenantPackage",
                "@ss.hasRole('super_admin') and @ss.hasPermission('system:tenant-package:create')");
        assertPermission("updateTenantPackage",
                "@ss.hasRole('super_admin') and @ss.hasPermission('system:tenant-package:update')");
        assertPermission("deleteTenantPackage",
                "@ss.hasRole('super_admin') and @ss.hasPermission('system:tenant-package:delete')");
        assertPermission("deleteTenantPackageList",
                "@ss.hasRole('super_admin') and @ss.hasPermission('system:tenant-package:delete')");
        assertPermission("getTenantPackage",
                "@ss.hasRole('super_admin') and @ss.hasPermission('system:tenant-package:query')");
        assertPermission("getTenantPackagePage",
                "@ss.hasRole('super_admin') and @ss.hasPermission('system:tenant-package:query')");
        assertPermission("getTenantPackageList", "@ss.hasRole('super_admin')");
    }

    private static void assertPermission(String methodName, String expected) throws Exception {
        Method method = findMethod(methodName);
        PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);
        assertNotNull(authorization, methodName + " should keep its permission check");
        assertEquals(expected, authorization.value());
    }

    private static Method findMethod(String methodName) throws NoSuchMethodException {
        for (Method method : TenantPackageController.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        throw new NoSuchMethodException(methodName);
    }

}
