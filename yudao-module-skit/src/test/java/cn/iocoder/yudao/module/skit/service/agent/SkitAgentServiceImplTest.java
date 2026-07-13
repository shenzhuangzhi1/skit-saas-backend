package cn.iocoder.yudao.module.skit.service.agent;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.common.util.validation.ValidationUtils;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentCreateReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentMobileUpdateReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentPasswordResetReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentUpdateReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentPageReqVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.member.SkitMemberMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitPlatformAdminGuard;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdAccountService;
import cn.iocoder.yudao.module.skit.service.app.SkitAppReleaseService;
import cn.iocoder.yudao.module.skit.service.commission.SkitCommissionService;
import cn.iocoder.yudao.module.system.controller.admin.tenant.vo.tenant.TenantSaveReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantPackageDO;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.service.tenant.TenantPackageService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.time.LocalDateTime;
import javax.validation.ConstraintViolationException;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AGENT_ARCHIVED;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AGENT_NOT_ARCHIVED;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.PLATFORM_ADMIN_REQUIRED;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.AGENT_NOT_EXISTS;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.USER_MOBILE_EXISTS;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.USER_USERNAME_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkitAgentServiceImplTest {

    @InjectMocks
    private SkitAgentServiceImpl agentService;

    @Mock
    private SkitPlatformAdminGuard platformAdminGuard;
    @Mock
    private SkitAgentMapper agentMapper;
    @Mock
    private SkitMemberMapper memberMapper;
    @Mock
    private TenantService tenantService;
    @Mock
    private TenantPackageService tenantPackageService;
    @Mock
    private AdminUserService adminUserService;
    @Mock
    private SkitAdAccountService adAccountService;
    @Mock
    private SkitCommissionService commissionService;
    @Mock
    private SkitAppReleaseService appReleaseService;

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void createAgentRequiresPlatformAdministrator() {
        doThrow(exception(PLATFORM_ADMIN_REQUIRED)).when(platformAdminGuard).check();

        assertServiceException(() -> agentService.createAgent(createRequest()), PLATFORM_ADMIN_REQUIRED);

        verifyNoInteractions(tenantService, agentMapper, memberMapper, adAccountService, commissionService);
    }

    @Test
    void createAgentCreatesTenantRegistryAndTenantDefaults() {
        when(tenantPackageService.getTenantPackageByCode("SKIT_AGENT_STANDARD"))
                .thenReturn(new TenantPackageDO().setId(7L));
        when(tenantService.createTenant(any(TenantSaveReqVO.class))).thenReturn(42L);
        when(agentMapper.selectByTenantCode(anyString())).thenReturn(null);
        when(agentMapper.selectByRootInviteCode(anyString())).thenReturn(null);
        when(memberMapper.selectByInviteCode(anyString())).thenReturn(null);
        when(agentMapper.insert(any(SkitAgentDO.class))).thenReturn(1);

        Long tenantId = agentService.createAgent(createRequest());

        assertEquals(42L, tenantId);
        ArgumentCaptor<TenantSaveReqVO> tenantCaptor = ArgumentCaptor.forClass(TenantSaveReqVO.class);
        verify(tenantService).createTenant(tenantCaptor.capture());
        TenantSaveReqVO tenant = tenantCaptor.getValue();
        assertEquals("Agent 42", tenant.getContactName());
        assertEquals("13800000000", tenant.getContactMobile());
        assertEquals("13800000000", tenant.getUsername());
        assertEquals(7L, tenant.getPackageId());
        assertEquals(1, tenant.getAccountCount());
        ArgumentCaptor<SkitAgentDO> agentCaptor = ArgumentCaptor.forClass(SkitAgentDO.class);
        verify(agentMapper).insert(agentCaptor.capture());
        SkitAgentDO agent = agentCaptor.getValue();
        assertEquals(42L, agent.getTenantId());
        assertEquals("AG42", agent.getTenantCode());
        assertEquals(CommonStatusEnum.ENABLE.getStatus(), agent.getStatus());
        assertTrue(agent.getRootInviteCode().startsWith("A"));
        assertNull(TenantContextHolder.getTenantId(), "创建结束后必须恢复平台租户上下文");
        verify(adAccountService).ensureDefaultAccounts();
        verify(adAccountService).saveSettings(any(SkitAdAccountService.Settings.class));
        verify(commissionService).ensureDefaultPlan();
    }

    @Test
    void createAgentRejectsAdministratorUsernameAlreadyBoundToAnotherTenant() {
        when(adminUserService.getUserListByUsernameIgnoreTenant("13800000000"))
                .thenReturn(Collections.singletonList(new AdminUserDO().setId(999L)));

        assertServiceException(() -> agentService.createAgent(createRequest()), USER_USERNAME_EXISTS);

        verifyNoInteractions(tenantService, agentMapper, memberMapper, adAccountService, commissionService);
    }

    @Test
    void createContractRejectsInvalidMobile() {
        SkitAgentCreateReqVO request = createRequest();
        request.setMobile("12345");

        assertThrows(ConstraintViolationException.class, () -> ValidationUtils.validate(request));
    }

    @Test
    void createContractRejectsInvalidStatus() {
        SkitAgentCreateReqVO request = createRequest();
        request.setStatus(9);

        assertThrows(ConstraintViolationException.class, () -> ValidationUtils.validate(request));
    }

    @Test
    void createContractRejectsExpiredTenant() {
        SkitAgentCreateReqVO request = createRequest();
        request.setExpireTime(LocalDateTime.now().minusSeconds(1));

        assertThrows(ConstraintViolationException.class, () -> ValidationUtils.validate(request));
    }

    @Test
    void updateAgentCannotChangeIdentityOrPassword() {
        assertThrows(NoSuchFieldException.class, () -> SkitAgentUpdateReqVO.class.getDeclaredField("mobile"));
        assertThrows(NoSuchFieldException.class, () -> SkitAgentUpdateReqVO.class.getDeclaredField("password"));
        assertThrows(NoSuchFieldException.class, () -> SkitAgentUpdateReqVO.class.getDeclaredField("packageId"));
    }

    @Test
    void updateAgentPreservesIdentityPasswordAndMissingSecrets() {
        mockExistingAgent();
        SkitAdAccountService.Settings existing = new SkitAdAccountService.Settings();
        existing.setPangleUsername("pangle-user");
        existing.setPangleAppId("pangle-app");
        existing.setPanglePlacementId("pangle-slot");
        existing.setPangleEnabled(true);
        existing.setTakuUsername("taku-user");
        existing.setTakuAppId("taku-app");
        existing.setTakuPlacementId("taku-slot");
        existing.setTakuEnabled(true);
        when(adAccountService.getSettings()).thenReturn(existing);
        SkitAgentUpdateReqVO request = updateRequest();

        agentService.updateAgent(request);

        ArgumentCaptor<SkitAdAccountService.Settings> captor =
                ArgumentCaptor.forClass(SkitAdAccountService.Settings.class);
        verify(adAccountService).saveSettings(captor.capture());
        assertEquals("pangle-user", captor.getValue().getPangleUsername());
        assertEquals("taku-app", captor.getValue().getTakuAppId());
        assertNull(captor.getValue().getPangleAppSecret());
        assertNull(captor.getValue().getTakuAppKey());
        assertNull(captor.getValue().getTakuAppSecret());
        verify(adminUserService, never()).updateUserPassword(anyLong(), anyString());
        verify(adminUserService, never()).updateUserIdentity(anyLong(), anyString(), anyString());
    }

    @Test
    void updateAgentDisablesBoundAdministratorWithTenant() {
        mockExistingAgent();
        when(adAccountService.getSettings()).thenReturn(new SkitAdAccountService.Settings());
        SkitAgentUpdateReqVO request = updateRequest();
        request.setStatus(CommonStatusEnum.DISABLE.getStatus());

        agentService.updateAgent(request);

        verify(adminUserService).updateUserStatus(420L, CommonStatusEnum.DISABLE.getStatus());
    }

    @Test
    void updateAgentEnablesBoundAdministratorWithTenant() {
        when(agentMapper.selectByTenantId(42L)).thenReturn(SkitAgentDO.builder().id(4L).tenantId(42L)
                .tenantCode("AG42").rootInviteCode("AOLDINVITE01").build());
        when(tenantService.getTenant(42L)).thenReturn(new TenantDO().setId(42L).setName("Agent 42")
                .setContactName("Agent 42").setContactMobile("13800000000")
                .setContactUserId(420L).setStatus(CommonStatusEnum.DISABLE.getStatus())
                .setPackageId(7L).setExpireTime(LocalDateTime.now().plusDays(30)).setAccountCount(1));
        when(adAccountService.getSettings()).thenReturn(new SkitAdAccountService.Settings());

        agentService.updateAgent(updateRequest());

        verify(adminUserService).updateUserStatus(420L, CommonStatusEnum.ENABLE.getStatus());
    }

    @Test
    void updateAgentCannotEnableArchivedAgent() {
        when(agentMapper.selectByTenantId(42L)).thenReturn(SkitAgentDO.builder().id(4L).tenantId(42L)
                .tenantCode("AG42").archivedTime(LocalDateTime.now()).build());

        assertServiceException(() -> agentService.updateAgent(updateRequest()), AGENT_ARCHIVED);

        verify(tenantService, never()).updateTenant(any());
        verify(adminUserService, never()).updateUserStatus(anyLong(), anyInt());
    }

    @Test
    void mobileRebindRejectsGlobalMobileDuplicate() {
        mockExistingAgent();
        when(adminUserService.getUserListByMobileIgnoreTenant("13900000000"))
                .thenReturn(Collections.singletonList(new AdminUserDO().setId(999L)));
        SkitAgentMobileUpdateReqVO request = new SkitAgentMobileUpdateReqVO();
        request.setTenantId(42L);
        request.setMobile("13900000000");

        assertServiceException(() -> agentService.updateAgentMobile(request), USER_MOBILE_EXISTS);

        verify(tenantService, never()).updateTenant(any());
        verify(adminUserService, never()).updateUserIdentity(anyLong(), anyString(), anyString());
    }

    @Test
    void mobileRebindUpdatesTenantAndBoundAdministrator() {
        mockExistingAgent();
        SkitAgentMobileUpdateReqVO request = new SkitAgentMobileUpdateReqVO();
        request.setTenantId(42L);
        request.setMobile(" +86 13900000000 ");

        agentService.updateAgentMobile(request);

        ArgumentCaptor<TenantSaveReqVO> tenantCaptor = ArgumentCaptor.forClass(TenantSaveReqVO.class);
        verify(tenantService).updateTenant(tenantCaptor.capture());
        assertEquals("13900000000", tenantCaptor.getValue().getContactMobile());
        verify(adminUserService).updateUserIdentity(420L, "13900000000", "13900000000");
    }

    @Test
    void passwordResetUsesDedicatedCommand() {
        mockExistingAgent();
        SkitAgentPasswordResetReqVO request = new SkitAgentPasswordResetReqVO();
        request.setTenantId(42L);
        request.setPassword("new-secret");

        agentService.resetAgentPassword(request);

        verify(adminUserService).updateUserPassword(420L, "new-secret");
    }

    @Test
    void archiveAndRestoreKeepRegistryRowAndToggleBoundIdentity() {
        SkitAgentDO activeAgent = SkitAgentDO.builder().id(4L).tenantId(42L)
                .tenantCode("AG42").rootInviteCode("AOLDINVITE01").build();
        SkitAgentDO archivedAgent = SkitAgentDO.builder().id(4L).tenantId(42L)
                .tenantCode("AG42").rootInviteCode("AOLDINVITE01").archivedTime(LocalDateTime.now()).build();
        when(agentMapper.selectByTenantId(42L)).thenReturn(activeAgent, archivedAgent);
        when(tenantService.getTenant(42L)).thenReturn(new TenantDO().setId(42L).setContactUserId(420L));

        agentService.archiveAgent(42L);
        verify(agentMapper).updateArchiveState(eq(42L), any(LocalDateTime.class), any());
        verify(tenantService).updateTenantStatus(42L, CommonStatusEnum.DISABLE.getStatus());
        verify(adminUserService).updateUserStatus(420L, CommonStatusEnum.DISABLE.getStatus());
        verify(agentMapper, never()).deleteById(any());

        agentService.restoreAgent(42L);
        verify(agentMapper).clearArchiveState(42L);
        verify(tenantService).updateTenantStatus(42L, CommonStatusEnum.ENABLE.getStatus());
        verify(adminUserService).updateUserStatus(420L, CommonStatusEnum.ENABLE.getStatus());
    }

    @Test
    void archiveAlreadyArchivedReassertsDisabledStateWithoutReplacingMetadata() {
        LocalDateTime archivedTime = LocalDateTime.now().minusDays(1);
        when(agentMapper.selectByTenantId(42L)).thenReturn(SkitAgentDO.builder().id(4L).tenantId(42L)
                .tenantCode("AG42").archivedTime(archivedTime).archivedBy(88L).build());
        when(tenantService.getTenant(42L)).thenReturn(new TenantDO().setId(42L).setContactUserId(420L));

        agentService.archiveAgent(42L);

        verify(agentMapper, never()).updateArchiveState(anyLong(), any(), any());
        verify(tenantService).updateTenantStatus(42L, CommonStatusEnum.DISABLE.getStatus());
        verify(adminUserService).updateUserStatus(420L, CommonStatusEnum.DISABLE.getStatus());
    }

    @Test
    void restoreRejectsAgentThatIsNotArchived() {
        when(agentMapper.selectByTenantId(42L)).thenReturn(SkitAgentDO.builder().id(4L).tenantId(42L)
                .tenantCode("AG42").build());

        assertServiceException(() -> agentService.restoreAgent(42L), AGENT_NOT_ARCHIVED);

        verify(agentMapper, never()).clearArchiveState(anyLong());
        verify(tenantService, never()).updateTenantStatus(anyLong(), anyInt());
    }

    @Test
    void rootInviteRotationUsesCollisionProtectedGenerator() {
        when(agentMapper.selectByTenantId(42L)).thenReturn(SkitAgentDO.builder().id(4L).tenantId(42L)
                .tenantCode("AG42").rootInviteCode("AOLDINVITE01").build());
        when(agentMapper.selectByRootInviteCode(anyString())).thenReturn(null);
        when(memberMapper.selectByInviteCode(anyString())).thenReturn(null);

        String inviteCode = agentService.rotateRootInviteCode(42L);

        assertTrue(inviteCode.startsWith("A"));
        verify(agentMapper).updateRootInviteCode(42L, inviteCode);
    }

    @Test
    void rootInviteRotationRetriesAfterAgentCollision() {
        when(agentMapper.selectByTenantId(42L)).thenReturn(SkitAgentDO.builder().id(4L).tenantId(42L)
                .tenantCode("AG42").rootInviteCode("AOLDINVITE01").build());
        when(agentMapper.selectByRootInviteCode(anyString()))
                .thenReturn(SkitAgentDO.builder().id(99L).tenantId(99L).build(), (SkitAgentDO) null);
        when(memberMapper.selectByInviteCode(anyString())).thenReturn(null);

        String inviteCode = agentService.rotateRootInviteCode(42L);

        verify(agentMapper, times(2)).selectByRootInviteCode(anyString());
        verify(agentMapper).updateRootInviteCode(42L, inviteCode);
    }

    @Test
    void rootInviteRotationFailsAfterCollisionRetryBudgetExhausted() {
        when(agentMapper.selectByTenantId(42L)).thenReturn(SkitAgentDO.builder().id(4L).tenantId(42L)
                .tenantCode("AG42").rootInviteCode("AOLDINVITE01").build());
        when(agentMapper.selectByRootInviteCode(anyString()))
                .thenReturn(SkitAgentDO.builder().id(99L).tenantId(99L).build());
        when(memberMapper.selectByInviteCode(anyString())).thenReturn(null);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> agentService.rotateRootInviteCode(42L));

        assertEquals("生成代理商邀请码失败", exception.getMessage());
        verify(agentMapper, times(10)).selectByRootInviteCode(anyString());
        verify(agentMapper, never()).updateRootInviteCode(anyLong(), anyString());
    }

    @Test
    void lifecycleCommandRejectsUnknownAgent() {
        when(agentMapper.selectByTenantId(404L)).thenReturn(null);

        assertServiceException(() -> agentService.archiveAgent(404L), AGENT_NOT_EXISTS);

        verify(tenantService, never()).updateTenantStatus(anyLong(), anyInt());
    }

    @Test
    void pageUsesDatabasePaginationAndOnlyEnrichesReturnedRows() {
        SkitAgentPageReqVO request = new SkitAgentPageReqVO();
        request.setPageNo(3);
        request.setPageSize(1);
        SkitAgentDO pageAgent = SkitAgentDO.builder().id(4L).tenantId(42L).tenantCode("AG42")
                .rootInviteCode("AINVITE00001").build();
        when(agentMapper.selectPage(request)).thenReturn(new PageResult<>(Collections.singletonList(pageAgent), 99L));
        when(tenantService.getTenant(42L)).thenReturn(new TenantDO().setId(42L).setName("Agent 42")
                .setContactUserId(420L).setStatus(CommonStatusEnum.ENABLE.getStatus()).setPackageId(7L)
                .setExpireTime(LocalDateTime.now().plusDays(30)).setAccountCount(1));
        when(tenantPackageService.getTenantPackage(7L)).thenReturn(new TenantPackageDO().setId(7L).setName("标准套餐"));
        when(adminUserService.getUserIgnoreTenant(420L))
                .thenReturn(new AdminUserDO().setId(420L).setUsername("13800000000"));
        when(adAccountService.getSettings()).thenReturn(new SkitAdAccountService.Settings());

        PageResult<?> result = agentService.getAgentPage(request);

        assertEquals(99L, result.getTotal());
        assertEquals(1, result.getList().size());
        verify(agentMapper).selectPage(request);
        verify(agentMapper, never()).selectList();
        verify(tenantService, times(1)).getTenant(42L);
    }

    private void mockExistingAgent() {
        when(agentMapper.selectByTenantId(42L)).thenReturn(SkitAgentDO.builder().id(4L).tenantId(42L)
                .tenantCode("AG42").rootInviteCode("AOLDINVITE01").build());
        when(tenantService.getTenant(42L)).thenReturn(new TenantDO().setId(42L).setName("Agent 42")
                .setContactName("Agent 42").setContactMobile("13800000000")
                .setContactUserId(420L).setStatus(CommonStatusEnum.ENABLE.getStatus())
                .setPackageId(7L).setExpireTime(LocalDateTime.now().plusDays(30)).setAccountCount(1));
    }

    private SkitAgentUpdateReqVO updateRequest() {
        SkitAgentUpdateReqVO request = new SkitAgentUpdateReqVO();
        request.setTenantId(42L);
        request.setName("Updated Agent");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        request.setExpireTime(LocalDateTime.now().plusDays(60));
        return request;
    }

    private SkitAgentCreateReqVO createRequest() {
        SkitAgentCreateReqVO request = new SkitAgentCreateReqVO();
        request.setName("Agent 42");
        request.setMobile(" 13800000000 ");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        request.setPassword("secret123");
        request.setExpireTime(LocalDateTime.now().plusDays(30));
        return request;
    }
}
