package cn.iocoder.yudao.module.skit.framework.security;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_DISABLE;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_EXPIRE;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkitMemberSecurityGuardTest {

    private static final long TENANT_ID = 42L;
    private static final long MEMBER_ID = 8L;

    @InjectMocks
    private SkitMemberSecurityGuard guard;
    @Mock
    private SkitMemberMapper memberMapper;
    @Mock
    private SkitAgentMapper agentMapper;
    @Mock
    private TenantService tenantService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedRouteUsesCanonicalTenantAndIgnoresLegacyAgentStatus() {
        authenticate();
        SkitMemberDO member = SkitMemberDO.builder().id(MEMBER_ID)
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
        member.setTenantId(TENANT_ID);
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(SkitAgentDO.builder().tenantId(TENANT_ID)
                .tenantCode("AGENT42").status(CommonStatusEnum.DISABLE.getStatus()).build());
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(member);

        assertTrue(guard.isSkitMember());

        verify(tenantService).validTenant(TENANT_ID);
    }

    @Test
    void authenticatedRouteRejectsDisabledExpiredAndDeletedTenantBeforeMemberLookup() {
        authenticate();
        doThrow(exception(TENANT_DISABLE, "disabled-agent"),
                exception(TENANT_EXPIRE, "expired-agent"),
                exception(TENANT_NOT_EXISTS)).when(tenantService).validTenant(TENANT_ID);

        assertFalse(guard.isSkitMember());
        assertFalse(guard.isSkitMember());
        assertFalse(guard.isSkitMember());

        verify(tenantService, times(3)).validTenant(TENANT_ID);
        verifyNoInteractions(memberMapper, agentMapper);
    }

    private void authenticate() {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(MEMBER_ID);
        loginUser.setTenantId(TENANT_ID);
        loginUser.setUserType(UserTypeEnum.MEMBER.getValue());
        loginUser.setScopes(Collections.singletonList("skit_member"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }
}
