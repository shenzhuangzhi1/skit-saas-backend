package cn.iocoder.yudao.framework.tenant.core.web;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.biz.system.tenant.TenantCommonApi;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.FORBIDDEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TenantVisitContextInterceptorTest {

    @Mock
    private SecurityFrameworkService securityFrameworkService;
    @Mock
    private TenantCommonApi tenantApi;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    void tenantAdministratorCannotVisitAnotherTenantEvenWithLegacyPermission() {
        authenticate(42L);
        TenantContextHolder.setTenantId(42L);
        when(securityFrameworkService.hasRole("super_admin")).thenReturn(false);
        MockHttpServletRequest request = visitRequest(43L);
        TenantVisitContextInterceptor interceptor = interceptor();

        ServiceException exception = assertThrows(ServiceException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(FORBIDDEN.getCode(), exception.getCode());
        assertEquals(42L, TenantContextHolder.getRequiredTenantId());
    }

    @Test
    void systemSuperAdministratorCanVisitAnotherTenant() throws Exception {
        LoginUser loginUser = authenticate(1L);
        TenantContextHolder.setTenantId(1L);
        when(securityFrameworkService.hasRole("super_admin")).thenReturn(true);
        TenantVisitContextInterceptor interceptor = interceptor();

        boolean allowed = interceptor.preHandle(visitRequest(42L), new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
        assertEquals(42L, TenantContextHolder.getRequiredTenantId());
        assertEquals(42L, loginUser.getVisitTenantId());
        verify(tenantApi).validateTenant(42L);
    }

    @Test
    void systemSuperAdministratorCannotVisitInactiveTenant() {
        authenticate(1L);
        TenantContextHolder.setTenantId(1L);
        when(securityFrameworkService.hasRole("super_admin")).thenReturn(true);
        ServiceException inactive = new ServiceException(FORBIDDEN.getCode(), "tenant inactive");
        doThrow(inactive).when(tenantApi).validateTenant(42L);

        ServiceException actual = assertThrows(ServiceException.class,
                () -> interceptor().preHandle(visitRequest(42L), new MockHttpServletResponse(), new Object()));

        assertEquals(inactive, actual);
        assertEquals(1L, TenantContextHolder.getRequiredTenantId());
    }

    private TenantVisitContextInterceptor interceptor() {
        TenantProperties properties = new TenantProperties();
        properties.setPlatformTenantId(1L);
        return new TenantVisitContextInterceptor(properties, securityFrameworkService, tenantApi);
    }

    private LoginUser authenticate(Long tenantId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(1L);
        loginUser.setTenantId(tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
        return loginUser;
    }

    private MockHttpServletRequest visitRequest(Long tenantId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("visit-tenant-id", tenantId);
        return request;
    }

}
