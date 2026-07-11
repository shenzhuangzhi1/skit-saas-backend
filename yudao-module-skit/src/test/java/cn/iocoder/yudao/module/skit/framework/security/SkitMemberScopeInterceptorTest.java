package cn.iocoder.yudao.module.skit.framework.security;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitMemberScopeInterceptorTest {

    private final SkitMemberScopeInterceptor interceptor = new SkitMemberScopeInterceptor(new WebProperties());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldAllowSkitMemberApi() {
        authenticateSkitMember();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app-api/skit/member/user/profile");
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void shouldRejectLegacyMemberApiForSkitToken() {
        authenticateSkitMember();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app-api/member/user/get");
        assertThrows(ServiceException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    private void authenticateSkitMember() {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(1L);
        loginUser.setTenantId(2L);
        loginUser.setUserType(2);
        loginUser.setScopes(Collections.singletonList("skit_member"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }
}
