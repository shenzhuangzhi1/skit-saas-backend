package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SkitAgentControllerTest {

    @Test
    void allAgentEndpointsRequireSuperAdminAtControllerBoundary() {
        PreAuthorize annotation = SkitAgentController.class.getAnnotation(PreAuthorize.class);

        assertNotNull(annotation);
        assertEquals("@ss.hasRole('super_admin')", annotation.value());
    }

    @Test
    void passwordBearingEndpointsSanitizePasswordAndProviderSecrets() throws Exception {
        assertSanitized(SkitAgentController.class.getDeclaredMethod("create",
                cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentCreateReqVO.class), "password");
        assertSanitized(SkitAgentController.class.getDeclaredMethod("resetPassword",
                cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentPasswordResetReqVO.class),
                "password");
    }

    private void assertSanitized(Method method, String key) {
        ApiAccessLog annotation = method.getAnnotation(ApiAccessLog.class);
        assertNotNull(annotation);
        assertTrue(Arrays.asList(annotation.sanitizeKeys()).contains(key));
    }
}
