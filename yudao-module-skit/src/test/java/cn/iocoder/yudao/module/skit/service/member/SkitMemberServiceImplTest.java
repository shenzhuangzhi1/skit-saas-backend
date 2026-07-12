package cn.iocoder.yudao.module.skit.service.member;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCheckRespDTO;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCreateReqDTO;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenRespDTO;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberClosureDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberClosureMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.system.enums.oauth2.OAuth2ClientConstants;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_APP_CONTEXT_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_TOKEN_SCOPE_INVALID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkitMemberServiceImplTest {

    private static final long TENANT_ID = 42L;
    private static final long INVITER_ID = 7L;
    private static final long MEMBER_ID = 8L;

    @InjectMocks
    private SkitMemberServiceImpl memberService;

    @Mock
    private SkitMemberMapper memberMapper;
    @Mock
    private SkitMemberClosureMapper closureMapper;
    @Mock
    private SkitAgentMapper agentMapper;
    @Mock
    private TenantService tenantService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private OAuth2TokenCommonApi oauth2TokenApi;

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void registerWithMemberInviteBuildsCompleteAncestorClosureInInviteTenant() {
        SkitMemberDO inviter = enabledMember(INVITER_ID, TENANT_ID, 2, "PARENT88");
        SkitAgentDO agent = SkitAgentDO.builder().tenantId(TENANT_ID).tenantCode("AGENT42")
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
        when(agentMapper.selectByRootInviteCode("MEMBER01")).thenReturn(null);
        when(memberMapper.selectByInviteCode(anyString())).thenAnswer(invocation -> {
            assertTrue(TenantContextHolder.isIgnore(), "邀请码全局解析必须显式忽略租户过滤");
            return "MEMBER01".equals(invocation.getArgument(0)) ? inviter : null;
        });
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(agent);
        when(memberMapper.selectByMobile("13800000001")).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            assertFalse(TenantContextHolder.isIgnore());
            return null;
        });
        when(memberMapper.selectById(INVITER_ID)).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            return inviter;
        });
        when(passwordEncoder.encode("secret123")).thenReturn("encoded-password");
        when(memberMapper.insert(any(SkitMemberDO.class))).thenAnswer(invocation -> {
            SkitMemberDO inserted = invocation.getArgument(0);
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            inserted.setId(MEMBER_ID);
            return 1;
        });
        when(closureMapper.selectAncestors(INVITER_ID)).thenReturn(Arrays.asList(
                SkitMemberClosureDO.builder().ancestorId(INVITER_ID).descendantId(INVITER_ID).distance(0).build(),
                SkitMemberClosureDO.builder().ancestorId(5L).descendantId(INVITER_ID).distance(1).build()));
        when(closureMapper.insert(any(SkitMemberClosureDO.class))).thenReturn(1);
        when(oauth2TokenApi.createAccessToken(any(OAuth2AccessTokenCreateReqDTO.class))).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            return token(MEMBER_ID);
        });

        SkitMemberService.RegisterCommand command = new SkitMemberService.RegisterCommand();
        command.setTenantId(TENANT_ID);
        command.setMobile("13800000001");
        command.setPassword("secret123");
        command.setNickname("new member");
        command.setInviteCode(" member01 ");
        command.setRegisterIp("127.0.0.1");
        SkitMemberService.AuthResult result = memberService.register(command);

        assertEquals(MEMBER_ID, result.getUserId());
        assertEquals(TENANT_ID, result.getTenantId());
        assertNotNull(result.getInviteCode());
        assertEquals(8, result.getInviteCode().length());
        assertNull(TenantContextHolder.getTenantId(), "服务调用结束后必须恢复原租户上下文");

        ArgumentCaptor<SkitMemberDO> memberCaptor = ArgumentCaptor.forClass(SkitMemberDO.class);
        verify(memberMapper).insert(memberCaptor.capture());
        SkitMemberDO insertedMember = memberCaptor.getValue();
        assertEquals(INVITER_ID, insertedMember.getInviterId());
        assertEquals(3, insertedMember.getDepth());
        assertEquals("encoded-password", insertedMember.getPassword());

        ArgumentCaptor<SkitMemberClosureDO> closureCaptor = ArgumentCaptor.forClass(SkitMemberClosureDO.class);
        verify(closureMapper, times(3)).insert(closureCaptor.capture());
        List<SkitMemberClosureDO> insertedClosures = closureCaptor.getAllValues();
        assertClosure(insertedClosures.get(0), MEMBER_ID, MEMBER_ID, 0);
        assertClosure(insertedClosures.get(1), INVITER_ID, MEMBER_ID, 1);
        assertClosure(insertedClosures.get(2), 5L, MEMBER_ID, 2);

        ArgumentCaptor<OAuth2AccessTokenCreateReqDTO> tokenCaptor =
                ArgumentCaptor.forClass(OAuth2AccessTokenCreateReqDTO.class);
        verify(oauth2TokenApi).createAccessToken(tokenCaptor.capture());
        assertEquals(MEMBER_ID, tokenCaptor.getValue().getUserId());
        assertEquals(UserTypeEnum.MEMBER.getValue(), tokenCaptor.getValue().getUserType());
        assertEquals(OAuth2ClientConstants.CLIENT_ID_DEFAULT, tokenCaptor.getValue().getClientId());
        assertEquals(Collections.singletonList("skit_member"), tokenCaptor.getValue().getScopes());
        // 邀请码解析和签发 Token 都要独立确认租户仍有效。
        verify(tenantService, times(2)).validTenant(TENANT_ID);
    }

    @Test
    void loginUsesTheTenantFromVerifiedAppContext() {
        SkitMemberDO member = enabledMember(MEMBER_ID, TENANT_ID, 0, "INVITE88");
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(enabledAgent());
        when(memberMapper.selectByMobile("13800000002")).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            assertFalse(TenantContextHolder.isIgnore());
            return member;
        });
        when(passwordEncoder.matches("secret123", member.getPassword())).thenReturn(true);
        when(memberMapper.updateById(any(SkitMemberDO.class))).thenReturn(1);
        when(oauth2TokenApi.createAccessToken(any(OAuth2AccessTokenCreateReqDTO.class))).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            return token(MEMBER_ID);
        });
        SkitMemberService.LoginCommand command = new SkitMemberService.LoginCommand();
        command.setTenantId(TENANT_ID);
        command.setMobile("13800000002");
        command.setPassword("secret123");
        command.setLoginIp("127.0.0.2");

        SkitMemberService.AuthResult result = memberService.login(command);

        assertEquals(TENANT_ID, result.getTenantId());
        assertEquals("INVITE88", result.getInviteCode());
        assertNull(TenantContextHolder.getTenantId());
    }

    @Test
    void loginDoesNotQueryAnotherTenantForTheSameMobile() {
        SkitMemberDO member = enabledMember(2L, 20L, 0, "INVITE20");
        when(agentMapper.selectByTenantId(20L)).thenReturn(SkitAgentDO.builder().tenantId(20L)
                .tenantCode("AGENT20").status(CommonStatusEnum.ENABLE.getStatus()).build());
        when(memberMapper.selectByMobile("13800000003")).thenAnswer(invocation -> {
            assertEquals(20L, TenantContextHolder.getTenantId());
            return member;
        });
        when(passwordEncoder.matches("secret123", member.getPassword())).thenReturn(true);
        when(memberMapper.updateById(any(SkitMemberDO.class))).thenReturn(1);
        when(oauth2TokenApi.createAccessToken(any(OAuth2AccessTokenCreateReqDTO.class))).thenReturn(token(2L));
        SkitMemberService.LoginCommand command = new SkitMemberService.LoginCommand();
        command.setTenantId(20L);
        command.setMobile("13800000003");
        command.setPassword("secret123");

        SkitMemberService.AuthResult result = memberService.login(command);

        assertEquals(20L, result.getTenantId());
        verify(memberMapper, never()).selectListByMobile(anyString());
    }

    @Test
    void registerAllowsMobileAlreadyUsedByAnotherTenant() {
        SkitAgentDO agent = SkitAgentDO.builder().tenantId(TENANT_ID).tenantCode("AGENT42")
                .rootInviteCode("ROOT42").status(CommonStatusEnum.ENABLE.getStatus()).build();
        SkitMemberDO existingMember = enabledMember(99L, 43L, 0, "OTHER99");
        when(agentMapper.selectByRootInviteCode("ROOT42")).thenReturn(agent);
        when(memberMapper.selectListByMobile("13800000004")).thenAnswer(invocation -> {
            assertTrue(TenantContextHolder.isIgnore());
            return Collections.singletonList(existingMember);
        });
        when(memberMapper.selectByMobile("13800000004")).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            return null;
        });
        when(passwordEncoder.encode("secret123")).thenReturn("encoded-password");
        when(memberMapper.insert(any(SkitMemberDO.class))).thenAnswer(invocation -> {
            invocation.<SkitMemberDO>getArgument(0).setId(MEMBER_ID);
            return 1;
        });
        when(closureMapper.insert(any(SkitMemberClosureDO.class))).thenReturn(1);
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(agent);
        when(oauth2TokenApi.createAccessToken(any(OAuth2AccessTokenCreateReqDTO.class))).thenReturn(token(MEMBER_ID));
        SkitMemberService.RegisterCommand command = new SkitMemberService.RegisterCommand();
        command.setTenantId(TENANT_ID);
        command.setMobile("13800000004");
        command.setPassword("secret123");
        command.setNickname("new member");
        command.setInviteCode("ROOT42");

        SkitMemberService.AuthResult result = memberService.register(command);

        assertEquals(TENANT_ID, result.getTenantId());
        assertEquals(MEMBER_ID, result.getUserId());
        verify(memberMapper).insert(any(SkitMemberDO.class));
        verify(memberMapper, never()).selectListByMobile(anyString());
    }

    @Test
    void registerRejectsInviteFromAnotherAgentContextBeforeMemberLookup() {
        SkitAgentDO agent = SkitAgentDO.builder().tenantId(TENANT_ID).tenantCode("AGENT42")
                .rootInviteCode("ROOT42").status(CommonStatusEnum.ENABLE.getStatus()).build();
        when(agentMapper.selectByRootInviteCode("ROOT42")).thenReturn(agent);
        SkitMemberService.RegisterCommand command = new SkitMemberService.RegisterCommand();
        command.setTenantId(43L);
        command.setMobile("13800000005");
        command.setPassword("secret123");
        command.setNickname("new member");
        command.setInviteCode("ROOT42");

        assertServiceException(() -> memberService.register(command), MEMBER_APP_CONTEXT_INVALID);

        verify(memberMapper, never()).selectByMobile(anyString());
        verify(memberMapper, never()).insert(any(SkitMemberDO.class));
    }

    @Test
    void loginRejectsMissingAppContextBeforeMemberLookup() {
        SkitMemberService.LoginCommand command = new SkitMemberService.LoginCommand();
        command.setMobile("13800000006");
        command.setPassword("secret123");

        assertServiceException(() -> memberService.login(command), MEMBER_APP_CONTEXT_INVALID);

        verifyNoInteractions(memberMapper, passwordEncoder, oauth2TokenApi);
    }

    @Test
    void refreshTokenRequiresSkitMemberScopeAndRevokesInvalidAccessToken() {
        OAuth2AccessTokenRespDTO refreshed = token(MEMBER_ID);
        OAuth2AccessTokenCheckRespDTO checked = new OAuth2AccessTokenCheckRespDTO()
                .setUserType(UserTypeEnum.MEMBER.getValue()).setTenantId(TENANT_ID)
                .setScopes(Collections.singletonList("profile"));
        when(oauth2TokenApi.refreshAccessToken("refresh-token", OAuth2ClientConstants.CLIENT_ID_DEFAULT))
                .thenReturn(refreshed);
        when(oauth2TokenApi.checkAccessToken("access-token")).thenReturn(checked);

        assertServiceException(() -> memberService.refreshToken("refresh-token"), MEMBER_TOKEN_SCOPE_INVALID);

        verify(oauth2TokenApi).removeAccessToken("access-token");
        verifyNoInteractions(memberMapper);
    }

    @Test
    void refreshTokenLoadsEnabledMemberInsideTokenTenant() {
        SkitMemberDO member = enabledMember(MEMBER_ID, TENANT_ID, 0, "INVITE88");
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(enabledAgent());
        OAuth2AccessTokenRespDTO refreshed = token(MEMBER_ID);
        OAuth2AccessTokenCheckRespDTO checked = new OAuth2AccessTokenCheckRespDTO()
                .setUserType(UserTypeEnum.MEMBER.getValue()).setTenantId(TENANT_ID)
                .setScopes(Collections.singletonList("skit_member"));
        when(oauth2TokenApi.refreshAccessToken("refresh-token", OAuth2ClientConstants.CLIENT_ID_DEFAULT))
                .thenReturn(refreshed);
        when(oauth2TokenApi.checkAccessToken("access-token")).thenReturn(checked);
        when(memberMapper.selectById(MEMBER_ID)).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            assertFalse(TenantContextHolder.isIgnore());
            return member;
        });

        SkitMemberService.AuthResult result = memberService.refreshToken("refresh-token");

        assertEquals(TENANT_ID, result.getTenantId());
        assertEquals(MEMBER_ID, result.getUserId());
        assertEquals("INVITE88", result.getInviteCode());
        assertNull(TenantContextHolder.getTenantId());
        verify(oauth2TokenApi, never()).removeAccessToken(anyString());
    }

    private SkitMemberDO enabledMember(Long id, Long tenantId, Integer depth, String inviteCode) {
        SkitMemberDO member = SkitMemberDO.builder().id(id).mobile("13800000000").password("encoded-password")
                .nickname("member-" + id).inviteCode(inviteCode).depth(depth)
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
        member.setTenantId(tenantId);
        return member;
    }

    private SkitAgentDO enabledAgent() {
        return SkitAgentDO.builder().tenantId(TENANT_ID).tenantCode("AGENT42")
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
    }

    private OAuth2AccessTokenRespDTO token(Long memberId) {
        return new OAuth2AccessTokenRespDTO().setAccessToken("access-token").setRefreshToken("refresh-token-2")
                .setUserId(memberId).setUserType(UserTypeEnum.MEMBER.getValue())
                .setExpiresTime(LocalDateTime.of(2026, 7, 12, 0, 0));
    }

    private void assertClosure(SkitMemberClosureDO closure, Long ancestorId, Long descendantId, int distance) {
        assertEquals(ancestorId, closure.getAncestorId());
        assertEquals(descendantId, closure.getDescendantId());
        assertEquals(distance, closure.getDistance());
    }
}
