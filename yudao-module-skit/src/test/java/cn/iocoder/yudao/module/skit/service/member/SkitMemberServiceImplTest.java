package cn.iocoder.yudao.module.skit.service.member;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCheckRespDTO;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCreateReqDTO;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenRespDTO;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberClosureDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.member.SkitMemberDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberClosureMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.service.invite.SkitInviteCodeRegistryService;
import cn.iocoder.yudao.module.skit.service.invite.SkitInviteCodeRegistryService.OwnerType;
import cn.iocoder.yudao.module.skit.service.invite.SkitInviteCodeRegistryService.ResolvedOwner;
import cn.iocoder.yudao.module.system.enums.oauth2.OAuth2ClientConstants;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.INVITE_CODE_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_APP_CONTEXT_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_PASSWORD_INVALID;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MEMBER_TOKEN_SCOPE_INVALID;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_DISABLE;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_EXPIRE;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_NOT_EXISTS;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
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
    private SkitInviteCodeRegistryService inviteCodeRegistryService;
    @Mock
    private TenantService tenantService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private OAuth2TokenCommonApi oauth2TokenApi;
    @Mock
    private OAuth2TokenService oauth2TokenService;

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void registerWithMemberInviteBuildsCompleteAncestorClosureInInviteTenant() {
        SkitMemberDO inviter = enabledMember(INVITER_ID, TENANT_ID, 2, "MEMBER01");
        SkitAgentDO agent = SkitAgentDO.builder().tenantId(TENANT_ID).tenantCode("AGENT42")
                .status(CommonStatusEnum.ENABLE.getStatus()).build();
        ResolvedOwner discovered = memberOwner(91L, TENANT_ID, INVITER_ID, "MEMBER01");
        when(inviteCodeRegistryService.resolveActive("MEMBER01")).thenReturn(discovered);
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(agent);
        when(memberMapper.selectByTenantAndIdForUpdate(TENANT_ID, INVITER_ID)).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            assertFalse(TenantContextHolder.isIgnore());
            return inviter;
        });
        when(inviteCodeRegistryService.lockActive(OwnerType.MEMBER, TENANT_ID, INVITER_ID, "MEMBER01"))
                .thenReturn(discovered);
        when(memberMapper.selectByMobile("13800000001")).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            assertFalse(TenantContextHolder.isIgnore());
            return null;
        });
        when(inviteCodeRegistryService.isClaimed(anyString())).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("encoded-password");
        when(memberMapper.insert(any(SkitMemberDO.class))).thenAnswer(invocation -> {
            SkitMemberDO inserted = invocation.getArgument(0);
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            inserted.setId(MEMBER_ID);
            return 1;
        });
        when(inviteCodeRegistryService.claimMember(eq(TENANT_ID), eq(MEMBER_ID), anyString()))
                .thenAnswer(invocation -> memberOwner(92L, TENANT_ID, MEMBER_ID, invocation.getArgument(2)));
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

        InOrder registrationOrder = inOrder(memberMapper, inviteCodeRegistryService, closureMapper);
        registrationOrder.verify(memberMapper).selectByTenantAndIdForUpdate(TENANT_ID, INVITER_ID);
        registrationOrder.verify(inviteCodeRegistryService)
                .lockActive(OwnerType.MEMBER, TENANT_ID, INVITER_ID, "MEMBER01");
        registrationOrder.verify(memberMapper).insert(any(SkitMemberDO.class));
        registrationOrder.verify(inviteCodeRegistryService)
                .claimMember(TENANT_ID, MEMBER_ID, insertedMember.getInviteCode());
        registrationOrder.verify(closureMapper).insert(any(SkitMemberClosureDO.class));

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
        verify(agentMapper, never()).selectByRootInviteCode(anyString());
        verify(memberMapper, never()).selectByInviteCode(anyString());
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
        SkitAgentDO agent = SkitAgentDO.builder().id(6L).tenantId(TENANT_ID).tenantCode("AGENT42")
                .rootInviteCode("ROOT42").status(CommonStatusEnum.ENABLE.getStatus()).build();
        ResolvedOwner discovered = agentOwner(81L, TENANT_ID, agent.getId(), "ROOT42");
        when(inviteCodeRegistryService.resolveActive("ROOT42")).thenReturn(discovered);
        when(agentMapper.selectByTenantIdForUpdate(TENANT_ID)).thenReturn(agent);
        when(inviteCodeRegistryService.lockActive(OwnerType.AGENT, TENANT_ID, agent.getId(), "ROOT42"))
                .thenReturn(discovered);
        when(memberMapper.selectByMobile("13800000004")).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            return null;
        });
        when(inviteCodeRegistryService.isClaimed(anyString())).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("encoded-password");
        when(memberMapper.insert(any(SkitMemberDO.class))).thenAnswer(invocation -> {
            invocation.<SkitMemberDO>getArgument(0).setId(MEMBER_ID);
            return 1;
        });
        when(inviteCodeRegistryService.claimMember(eq(TENANT_ID), eq(MEMBER_ID), anyString()))
                .thenAnswer(invocation -> memberOwner(82L, TENANT_ID, MEMBER_ID, invocation.getArgument(2)));
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
        InOrder lockOrder = inOrder(agentMapper, inviteCodeRegistryService, memberMapper);
        lockOrder.verify(agentMapper).selectByTenantIdForUpdate(TENANT_ID);
        lockOrder.verify(inviteCodeRegistryService)
                .lockActive(OwnerType.AGENT, TENANT_ID, agent.getId(), "ROOT42");
        lockOrder.verify(memberMapper).insert(any(SkitMemberDO.class));
        lockOrder.verify(inviteCodeRegistryService).claimMember(eq(TENANT_ID), eq(MEMBER_ID), anyString());
        verify(memberMapper).insert(any(SkitMemberDO.class));
        verify(memberMapper, never()).selectListByMobile(anyString());
        verify(agentMapper, never()).selectByRootInviteCode(anyString());
        verify(memberMapper, never()).selectByInviteCode(anyString());
    }

    @Test
    void registerRejectsInviteFromAnotherAgentContextBeforeMemberLookup() {
        when(inviteCodeRegistryService.resolveActive("ROOT42"))
                .thenReturn(agentOwner(81L, TENANT_ID, 6L, "ROOT42"));
        SkitMemberService.RegisterCommand command = new SkitMemberService.RegisterCommand();
        command.setTenantId(43L);
        command.setMobile("13800000005");
        command.setPassword("secret123");
        command.setNickname("new member");
        command.setInviteCode("ROOT42");

        assertServiceException(() -> memberService.register(command), MEMBER_APP_CONTEXT_INVALID);

        verify(inviteCodeRegistryService).resolveActive("ROOT42");
        verifyNoInteractions(agentMapper, memberMapper, closureMapper, tenantService, passwordEncoder, oauth2TokenApi);
        verifyNoMoreInteractions(inviteCodeRegistryService);
    }

    @Test
    void registerRejectsMemberWhoseLockedCurrentCodeNoLongerMatchesRegistry() {
        ResolvedOwner discovered = memberOwner(91L, TENANT_ID, INVITER_ID, "MEMBER01");
        SkitMemberDO staleOwner = enabledMember(INVITER_ID, TENANT_ID, 2, "OLD-CODE");
        when(inviteCodeRegistryService.resolveActive("MEMBER01")).thenReturn(discovered);
        when(memberMapper.selectByTenantAndIdForUpdate(TENANT_ID, INVITER_ID)).thenReturn(staleOwner);

        assertServiceException(() -> memberService.register(registerCommand(TENANT_ID, "MEMBER01")),
                INVITE_CODE_INVALID);

        verify(memberMapper).selectByTenantAndIdForUpdate(TENANT_ID, INVITER_ID);
        verify(inviteCodeRegistryService, never()).lockActive(any(), anyLong(), anyLong(), anyString());
        verify(memberMapper, never()).selectByMobile(anyString());
        verify(memberMapper, never()).insert(any(SkitMemberDO.class));
    }

    @Test
    void registerRejectsDisabledLockedMemberBeforeRegistryLock() {
        ResolvedOwner discovered = memberOwner(91L, TENANT_ID, INVITER_ID, "MEMBER01");
        SkitMemberDO disabledOwner = enabledMember(INVITER_ID, TENANT_ID, 2, "MEMBER01")
                .setStatus(CommonStatusEnum.DISABLE.getStatus());
        when(inviteCodeRegistryService.resolveActive("MEMBER01")).thenReturn(discovered);
        when(memberMapper.selectByTenantAndIdForUpdate(TENANT_ID, INVITER_ID)).thenReturn(disabledOwner);

        assertServiceException(() -> memberService.register(registerCommand(TENANT_ID, "MEMBER01")),
                INVITE_CODE_INVALID);

        verify(inviteCodeRegistryService, never()).lockActive(any(), anyLong(), anyLong(), anyString());
        verify(memberMapper, never()).selectByMobile(anyString());
        verify(memberMapper, never()).insert(any(SkitMemberDO.class));
    }

    @Test
    void registerRejectsARegistryRowThatChangesTenantWhileBeingLocked() {
        ResolvedOwner discovered = memberOwner(91L, TENANT_ID, INVITER_ID, "MEMBER01");
        SkitMemberDO inviter = enabledMember(INVITER_ID, TENANT_ID, 2, "MEMBER01");
        when(inviteCodeRegistryService.resolveActive("MEMBER01")).thenReturn(discovered);
        when(memberMapper.selectByTenantAndIdForUpdate(TENANT_ID, INVITER_ID)).thenReturn(inviter);
        when(inviteCodeRegistryService.lockActive(OwnerType.MEMBER, TENANT_ID, INVITER_ID, "MEMBER01"))
                .thenReturn(memberOwner(91L, 43L, INVITER_ID, "MEMBER01"));

        assertServiceException(() -> memberService.register(registerCommand(TENANT_ID, "MEMBER01")),
                INVITE_CODE_INVALID);

        InOrder lockOrder = inOrder(memberMapper, inviteCodeRegistryService);
        lockOrder.verify(memberMapper).selectByTenantAndIdForUpdate(TENANT_ID, INVITER_ID);
        lockOrder.verify(inviteCodeRegistryService)
                .lockActive(OwnerType.MEMBER, TENANT_ID, INVITER_ID, "MEMBER01");
        verify(memberMapper, never()).selectByMobile(anyString());
        verify(memberMapper, never()).insert(any(SkitMemberDO.class));
    }

    @Test
    void registerRejectsPolymorphicRegistryRowWithTwoOwnersBeforeTenantReads() {
        ResolvedOwner corrupt = new ResolvedOwner(81L, TENANT_ID, OwnerType.AGENT, 6L, INVITER_ID,
                "ROOT42", "ROOT42", "ACTIVE", null);
        when(inviteCodeRegistryService.resolveActive("ROOT42")).thenReturn(corrupt);

        assertServiceException(() -> memberService.register(registerCommand(TENANT_ID, "ROOT42")),
                INVITE_CODE_INVALID);

        verify(inviteCodeRegistryService).resolveActive("ROOT42");
        verifyNoMoreInteractions(inviteCodeRegistryService);
        verifyNoInteractions(agentMapper, memberMapper, closureMapper, tenantService, passwordEncoder, oauth2TokenApi);
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
    void loginRejectsDisabledExpiredAndDeletedTenantBeforeMemberOrPasswordWork() {
        SkitMemberService.LoginCommand command = new SkitMemberService.LoginCommand();
        command.setTenantId(TENANT_ID);
        command.setMobile("13800000006");
        command.setPassword("secret123");
        doThrow(exception(TENANT_DISABLE, "agent"),
                exception(TENANT_EXPIRE, "agent"),
                exception(TENANT_NOT_EXISTS)).when(tenantService).validTenant(TENANT_ID);

        for (ErrorCode errorCode : Arrays.asList(TENANT_DISABLE, TENANT_EXPIRE, TENANT_NOT_EXISTS)) {
            if (errorCode == TENANT_NOT_EXISTS) {
                assertServiceException(() -> memberService.login(command), errorCode);
            } else {
                assertServiceException(() -> memberService.login(command), errorCode, "agent");
            }
        }

        verify(tenantService, times(3)).validTenant(TENANT_ID);
        verifyNoInteractions(memberMapper, passwordEncoder, oauth2TokenApi, agentMapper);
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

    @Test
    void agentInvitationUsesCanonicalTenantAndIgnoresLegacyAgentStatus() {
        SkitAgentDO legacyDisabledAgent = SkitAgentDO.builder().id(6L).tenantId(TENANT_ID)
                .tenantCode("AGENT42").rootInviteCode("ROOT42")
                .status(CommonStatusEnum.DISABLE.getStatus()).build();
        when(inviteCodeRegistryService.resolveActive("ROOT42"))
                .thenReturn(agentOwner(81L, TENANT_ID, 6L, "ROOT42"));
        when(agentMapper.selectByTenantId(TENANT_ID)).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            assertFalse(TenantContextHolder.isIgnore());
            return legacyDisabledAgent;
        });
        when(tenantService.getTenant(TENANT_ID)).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            assertFalse(TenantContextHolder.isIgnore());
            return null;
        });

        SkitMemberService.InvitationView result = memberService.resolveInvitation("root42");

        assertTrue(result.getValid());
        assertEquals(TENANT_ID, result.getTenantId());
        assertEquals("AGENT42", result.getTenantCode());
        verify(tenantService).validTenant(TENANT_ID);
        verify(agentMapper, never()).selectByRootInviteCode(anyString());
        verify(memberMapper, never()).selectByInviteCode(anyString());
    }

    @Test
    void agentInvitationRejectsDisabledExpiredAndDeletedTenant() {
        SkitAgentDO agent = SkitAgentDO.builder().id(6L).tenantId(TENANT_ID)
                .tenantCode("AGENT42").rootInviteCode("ROOT42").build();
        when(inviteCodeRegistryService.resolveActive("ROOT42"))
                .thenReturn(agentOwner(81L, TENANT_ID, 6L, "ROOT42"));
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(agent);
        doThrow(exception(TENANT_DISABLE, "agent"),
                exception(TENANT_EXPIRE, "agent"),
                exception(TENANT_NOT_EXISTS)).when(tenantService).validTenant(TENANT_ID);

        for (ErrorCode errorCode : Arrays.asList(TENANT_DISABLE, TENANT_EXPIRE, TENANT_NOT_EXISTS)) {
            if (errorCode == TENANT_NOT_EXISTS) {
                assertServiceException(() -> memberService.resolveInvitation("root42"), errorCode);
            } else {
                assertServiceException(() -> memberService.resolveInvitation("root42"), errorCode, "agent");
            }
        }

        verify(tenantService, times(3)).validTenant(TENANT_ID);
        verify(agentMapper, never()).selectByRootInviteCode(anyString());
        verify(memberMapper, never()).selectByInviteCode(anyString());
    }

    @Test
    void publicInvitationRejectsCrossTenantOrStaleMemberRegistryOwnership() {
        when(inviteCodeRegistryService.resolveActive("MEMBER01"))
                .thenReturn(memberOwner(91L, TENANT_ID, INVITER_ID, "MEMBER01"));
        SkitMemberDO corruptOwner = enabledMember(INVITER_ID, 43L, 2, "OTHER01");
        when(memberMapper.selectById(INVITER_ID)).thenAnswer(invocation -> {
            assertEquals(TENANT_ID, TenantContextHolder.getTenantId());
            assertFalse(TenantContextHolder.isIgnore());
            return corruptOwner;
        });

        assertServiceException(() -> memberService.resolveInvitation(" member01 "), INVITE_CODE_INVALID);

        verify(agentMapper, never()).selectByRootInviteCode(anyString());
        verify(memberMapper, never()).selectByInviteCode(anyString());
        verifyNoInteractions(tenantService);
    }

    @Test
    void publicInvitationRejectsArchivedAgentEvenWhenRegistryRowIsActive() {
        when(inviteCodeRegistryService.resolveActive("ROOT42"))
                .thenReturn(agentOwner(81L, TENANT_ID, 6L, "ROOT42"));
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(SkitAgentDO.builder()
                .id(6L).tenantId(TENANT_ID).tenantCode("AGENT42").rootInviteCode("ROOT42")
                .archivedTime(LocalDateTime.now()).build());

        assertServiceException(() -> memberService.resolveInvitation("root42"), INVITE_CODE_INVALID);

        verifyNoInteractions(tenantService);
        verify(agentMapper, never()).selectByRootInviteCode(anyString());
        verify(memberMapper, never()).selectByInviteCode(anyString());
    }

    @Test
    void refreshRejectsDisabledExpiredAndDeletedTenantAndRevokesNewTokenPair() {
        OAuth2AccessTokenRespDTO refreshed = token(MEMBER_ID);
        OAuth2AccessTokenCheckRespDTO checked = new OAuth2AccessTokenCheckRespDTO()
                .setUserType(UserTypeEnum.MEMBER.getValue()).setTenantId(TENANT_ID)
                .setScopes(Collections.singletonList("skit_member"));
        when(oauth2TokenApi.refreshAccessToken("refresh-token", OAuth2ClientConstants.CLIENT_ID_DEFAULT))
                .thenReturn(refreshed);
        when(oauth2TokenApi.checkAccessToken("access-token")).thenReturn(checked);
        doThrow(exception(TENANT_DISABLE, "agent"),
                exception(TENANT_EXPIRE, "agent"),
                exception(TENANT_NOT_EXISTS)).when(tenantService).validTenant(TENANT_ID);

        for (ErrorCode errorCode : Arrays.asList(TENANT_DISABLE, TENANT_EXPIRE, TENANT_NOT_EXISTS)) {
            if (errorCode == TENANT_NOT_EXISTS) {
                assertServiceException(() -> memberService.refreshToken("refresh-token"), errorCode);
            } else {
                assertServiceException(() -> memberService.refreshToken("refresh-token"), errorCode, "agent");
            }
        }

        verify(oauth2TokenApi, times(3)).removeAccessToken("access-token");
        verifyNoInteractions(memberMapper);
    }

    @Test
    void refreshUsesCanonicalTenantAndIgnoresLegacyAgentStatus() {
        SkitMemberDO member = enabledMember(MEMBER_ID, TENANT_ID, 0, "INVITE88");
        when(agentMapper.selectByTenantId(TENANT_ID)).thenReturn(SkitAgentDO.builder().tenantId(TENANT_ID)
                .tenantCode("AGENT42").status(CommonStatusEnum.DISABLE.getStatus()).build());
        OAuth2AccessTokenRespDTO refreshed = token(MEMBER_ID);
        OAuth2AccessTokenCheckRespDTO checked = new OAuth2AccessTokenCheckRespDTO()
                .setUserType(UserTypeEnum.MEMBER.getValue()).setTenantId(TENANT_ID)
                .setScopes(Collections.singletonList("skit_member"));
        when(oauth2TokenApi.refreshAccessToken("refresh-token", OAuth2ClientConstants.CLIENT_ID_DEFAULT))
                .thenReturn(refreshed);
        when(oauth2TokenApi.checkAccessToken("access-token")).thenReturn(checked);
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(member);

        SkitMemberService.AuthResult result = memberService.refreshToken("refresh-token");

        assertEquals(MEMBER_ID, result.getUserId());
        verify(tenantService).validTenant(TENANT_ID);
        verify(oauth2TokenApi, never()).removeAccessToken(anyString());
    }

    @Test
    void adminCanReadMemberDetailInsideCurrentTenant() {
        TenantContextHolder.setTenantId(TENANT_ID);
        SkitMemberDO member = enabledMember(MEMBER_ID, TENANT_ID, 2, "INVITE88");
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(member);
        when(memberMapper.selectCountByInviterId(MEMBER_ID)).thenReturn(3L);

        SkitMemberService.MemberView result = memberService.getMember(MEMBER_ID);

        assertEquals(MEMBER_ID, result.getId());
        assertEquals("13800000000", result.getMobile());
        assertEquals(3L, result.getChildCount());
    }

    @Test
    void disablingMemberRevokesOnlyCurrentTenantDefaultClientSkitMemberTokens() {
        TenantContextHolder.setTenantId(TENANT_ID);
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(
                enabledMember(MEMBER_ID, TENANT_ID, 0, "INVITE88"));

        memberService.updateMemberStatus(MEMBER_ID, CommonStatusEnum.DISABLE.getStatus());

        ArgumentCaptor<SkitMemberDO> captor = ArgumentCaptor.forClass(SkitMemberDO.class);
        verify(memberMapper).updateById(captor.capture());
        assertEquals(MEMBER_ID, captor.getValue().getId());
        assertEquals(CommonStatusEnum.DISABLE.getStatus(), captor.getValue().getStatus());
        verify(oauth2TokenService).removeAccessToken(MEMBER_ID, UserTypeEnum.MEMBER.getValue(),
                OAuth2ClientConstants.CLIENT_ID_DEFAULT, "skit_member");
    }

    @Test
    void enablingMemberDoesNotRevokeTokens() {
        TenantContextHolder.setTenantId(TENANT_ID);
        SkitMemberDO member = enabledMember(MEMBER_ID, TENANT_ID, 0, "INVITE88")
                .setStatus(CommonStatusEnum.DISABLE.getStatus());
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(member);

        memberService.updateMemberStatus(MEMBER_ID, CommonStatusEnum.ENABLE.getStatus());

        verify(memberMapper).updateById(any(SkitMemberDO.class));
        verifyNoInteractions(oauth2TokenService);
    }

    @Test
    void resettingMemberPasswordEncodesPasswordAndRevokesOnlySkitMemberTokens() {
        TenantContextHolder.setTenantId(TENANT_ID);
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(
                enabledMember(MEMBER_ID, TENANT_ID, 0, "INVITE88"));
        when(passwordEncoder.encode("new-secret-123")).thenReturn("new-hash");

        memberService.resetMemberPassword(MEMBER_ID, "new-secret-123");

        ArgumentCaptor<SkitMemberDO> captor = ArgumentCaptor.forClass(SkitMemberDO.class);
        verify(memberMapper).updateById(captor.capture());
        assertEquals(MEMBER_ID, captor.getValue().getId());
        assertEquals("new-hash", captor.getValue().getPassword());
        verify(oauth2TokenService).removeAccessToken(MEMBER_ID, UserTypeEnum.MEMBER.getValue(),
                OAuth2ClientConstants.CLIENT_ID_DEFAULT, "skit_member");
    }

    @Test
    void resettingMemberPasswordRejectsBlankValueBeforeMemberLookup() {
        TenantContextHolder.setTenantId(TENANT_ID);

        assertServiceException(() -> memberService.resetMemberPassword(MEMBER_ID, "      "),
                MEMBER_PASSWORD_INVALID);

        verifyNoInteractions(memberMapper, passwordEncoder, oauth2TokenService);
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

    private SkitMemberService.RegisterCommand registerCommand(Long tenantId, String inviteCode) {
        SkitMemberService.RegisterCommand command = new SkitMemberService.RegisterCommand();
        command.setTenantId(tenantId);
        command.setMobile("13800000009");
        command.setPassword("secret123");
        command.setNickname("new member");
        command.setInviteCode(inviteCode);
        command.setRegisterIp("127.0.0.9");
        return command;
    }

    private ResolvedOwner agentOwner(Long registryId, Long tenantId, Long agentId, String code) {
        return new ResolvedOwner(registryId, tenantId, OwnerType.AGENT, agentId, null,
                code, code, "ACTIVE", null);
    }

    private ResolvedOwner memberOwner(Long registryId, Long tenantId, Long memberId, String code) {
        return new ResolvedOwner(registryId, tenantId, OwnerType.MEMBER, null, memberId,
                code, code, "ACTIVE", null);
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
