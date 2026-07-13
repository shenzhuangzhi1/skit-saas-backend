package cn.iocoder.yudao.module.system.controller.admin.user;

import cn.iocoder.yudao.module.system.controller.admin.oauth2.vo.user.OAuth2UserUpdateReqVO;
import cn.iocoder.yudao.module.system.controller.admin.permission.PermissionController;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.profile.UserProfileUpdateReqVO;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserControllerSecurityTest {

    @Test
    void shouldNotExposeGenericAdministratorMutationEndpoints() {
        assertFalse(hasMapping(PostMapping.class, "/create"));
        assertFalse(hasMapping(PostMapping.class, "/import"));
        assertFalse(hasMapping(PutMapping.class, "update"));
        assertFalse(hasMapping(PutMapping.class, "/update"));
        assertFalse(hasMapping(PutMapping.class, "/update-password"));
        assertFalse(hasMapping(PutMapping.class, "/update-status"));
        assertFalse(hasMapping(DeleteMapping.class, "/delete"));
        assertFalse(hasMapping(DeleteMapping.class, "/delete-list"));
    }

    @Test
    void shouldKeepLoginMobileOutOfSelfServiceProfileCommands() {
        assertThrows(NoSuchFieldException.class, () -> UserProfileUpdateReqVO.class.getDeclaredField("mobile"));
        assertThrows(NoSuchFieldException.class, () -> OAuth2UserUpdateReqVO.class.getDeclaredField("mobile"));
    }

    @Test
    void shouldNotExposeGenericPermissionMutationEndpoints() {
        assertFalse(hasPostMapping(PermissionController.class, "/assign-role-menu"));
        assertFalse(hasPostMapping(PermissionController.class, "/assign-role-data-scope"));
        assertFalse(hasPostMapping(PermissionController.class, "/assign-user-role"));
    }

    private static boolean hasMapping(Class<? extends Annotation> annotationType, String path) {
        return Arrays.stream(UserController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(annotationType))
                .flatMap(method -> Arrays.stream(mappingValues(method, annotationType)))
                .anyMatch(path::equals);
    }

    private static String[] mappingValues(Method method, Class<? extends Annotation> annotationType) {
        if (annotationType == PostMapping.class) {
            return method.getAnnotation(PostMapping.class).value();
        }
        if (annotationType == PutMapping.class) {
            return method.getAnnotation(PutMapping.class).value();
        }
        return method.getAnnotation(DeleteMapping.class).value();
    }

    private static boolean hasPostMapping(Class<?> controller, String path) {
        return Arrays.stream(controller.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(mapping -> mapping != null)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .anyMatch(path::equals);
    }
}
