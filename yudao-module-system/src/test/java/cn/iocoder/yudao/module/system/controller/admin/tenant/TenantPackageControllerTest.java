package cn.iocoder.yudao.module.system.controller.admin.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TenantPackageControllerTest {

    @Test
    void shouldNotExposeLegacyTenantDiscoveryEndpoints() {
        assertFalse(hasGetMapping(TenantController.class, "/get-id-by-name"));
        assertFalse(hasGetMapping(TenantController.class, "/get-by-website"));
        assertFalse(hasGetMapping(TenantController.class, "simple-list"));
        assertFalse(hasGetMapping(TenantController.class, "/simple-list"));
        assertNoTenantMethod("createTenant");
        assertNoTenantMethod("updateTenant");
        assertNoTenantMethod("deleteTenant");
        assertNoTenantMethod("deleteTenantList");
    }

    @Test
    void shouldRequireOriginalPlatformAdministratorForGenericTenantAdministration() throws Exception {
        assertTenantPermission("getTenant", "system:tenant:query");
        assertTenantPermission("getTenantPage", "system:tenant:query");
        assertTenantPermission("exportTenantExcel", "system:tenant:export");
    }

    @Test
    void shouldRequireSuperAdminAndPreserveEndpointPermissionChecks() throws Exception {
        assertPermission("createTenantPackage",
                "@systemPlatformAdminGuard.isPlatformAdmin() and @ss.hasPermission('system:tenant-package:create')");
        assertPermission("updateTenantPackage",
                "@systemPlatformAdminGuard.isPlatformAdmin() and @ss.hasPermission('system:tenant-package:update')");
        assertPermission("deleteTenantPackage",
                "@systemPlatformAdminGuard.isPlatformAdmin() and @ss.hasPermission('system:tenant-package:delete')");
        assertPermission("deleteTenantPackageList",
                "@systemPlatformAdminGuard.isPlatformAdmin() and @ss.hasPermission('system:tenant-package:delete')");
        assertPermission("getTenantPackage",
                "@systemPlatformAdminGuard.isPlatformAdmin() and @ss.hasPermission('system:tenant-package:query')");
        assertPermission("getTenantPackagePage",
                "@systemPlatformAdminGuard.isPlatformAdmin() and @ss.hasPermission('system:tenant-package:query')");
        assertPermission("getTenantPackageList", "@systemPlatformAdminGuard.isPlatformAdmin()");
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

    private static void assertTenantPermission(String methodName, String permission) {
        Method method = Arrays.stream(TenantController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow(() -> new AssertionError("Missing method " + methodName));
        PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);
        assertNotNull(authorization, methodName + " should require the platform guard");
        assertEquals("@systemPlatformAdminGuard.isPlatformAdmin() and @ss.hasPermission('"
                + permission + "')", authorization.value());
    }

    private static boolean hasGetMapping(Class<?> controller, String path) {
        return Arrays.stream(controller.getDeclaredMethods())
                .map(method -> method.getAnnotation(GetMapping.class))
                .filter(mapping -> mapping != null)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .anyMatch(path::equals);
    }

    private static void assertNoTenantMethod(String methodName) {
        assertFalse(Arrays.stream(TenantController.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals(methodName)), methodName + " must not be exposed");
    }

}
