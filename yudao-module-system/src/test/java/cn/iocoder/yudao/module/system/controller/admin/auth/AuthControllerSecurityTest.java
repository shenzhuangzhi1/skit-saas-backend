package cn.iocoder.yudao.module.system.controller.admin.auth;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AuthControllerSecurityTest {

    @Test
    void shouldNotExposeGenericAdministratorRegistration() {
        boolean registerExposed = Arrays.stream(AuthController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(mapping -> mapping != null)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .anyMatch("/register"::equals);

        assertFalse(registerExposed);
    }

    @Test
    void shouldResolvePasswordLoginAndRefreshWithoutTenantHeader() throws Exception {
        assertTenantIgnored("login");
        assertTenantIgnored("refreshToken");
    }

    @Test
    void shouldNotExposeUnusedSmsOrSocialAuthentication() {
        assertFalse(hasPostMapping("/sms-login"));
        assertFalse(hasPostMapping("/send-sms-code"));
        assertFalse(hasPostMapping("/reset-password"));
        assertFalse(hasPostMapping("/social-login"));
        assertFalse(hasGetMapping("/social-auth-redirect"));
    }

    private static void assertTenantIgnored(String methodName) throws Exception {
        Method method = Arrays.stream(AuthController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow(() -> new NoSuchMethodException(methodName));
        assertNotNull(method.getAnnotation(TenantIgnore.class), methodName + " must not require a tenant header");
    }

    private static boolean hasPostMapping(String path) {
        return Arrays.stream(AuthController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(mapping -> mapping != null)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .anyMatch(path::equals);
    }

    private static boolean hasGetMapping(String path) {
        return Arrays.stream(AuthController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(GetMapping.class))
                .filter(mapping -> mapping != null)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .anyMatch(path::equals);
    }
}
