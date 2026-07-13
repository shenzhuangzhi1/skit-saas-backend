package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.validation.ValidationUtils;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.agent.SkitAgentDO;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitPlatformAdminGuard;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdAccountService;
import cn.iocoder.yudao.module.skit.service.commission.SkitCommissionService;
import cn.iocoder.yudao.module.skit.service.member.SkitMemberService;
import cn.iocoder.yudao.module.skit.service.revenue.SkitRevenueService;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.validation.ConstraintViolationException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.PLATFORM_ADMIN_REQUIRED;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_DISABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkitTenantBusinessControllerTest {

    @InjectMocks
    private SkitTenantBusinessController controller;

    @Mock
    private SkitAdAccountService adAccountService;
    @Mock
    private SkitCommissionService commissionService;
    @Mock
    private SkitMemberService memberService;
    @Mock
    private SkitRevenueService revenueService;
    @Mock
    private SkitPlatformAdminGuard platformAdminGuard;
    @Mock
    private SkitAgentMapper agentMapper;
    @Mock
    private TenantService tenantService;

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    void tenantAdminCannotReadAnotherAgentAdAccount() {
        authenticate(10L);
        TenantContextHolder.setTenantId(10L);
        doThrow(exception(PLATFORM_ADMIN_REQUIRED)).when(platformAdminGuard).check();

        assertServiceException(() -> controller.getAdAccount(20L), PLATFORM_ADMIN_REQUIRED);

        verify(platformAdminGuard).check();
        verifyNoInteractions(adAccountService, agentMapper, tenantService);
    }

    @Test
    void authorizedTargetIsValidatedBeforeAgentLookupAndTenantContextSwitch() {
        authenticate(10L);
        TenantContextHolder.setTenantId(10L);
        when(agentMapper.selectByTenantId(10L)).thenReturn(SkitAgentDO.builder()
                .tenantId(10L).tenantCode("AGENT10").build());
        SkitAdAccountService.Settings settings = new SkitAdAccountService.Settings();
        when(adAccountService.getSettings()).thenAnswer(invocation -> {
            assertEquals(10L, TenantContextHolder.getRequiredTenantId());
            return settings;
        });

        controller.getAdAccount(null);

        InOrder order = inOrder(tenantService, agentMapper, adAccountService);
        order.verify(tenantService).validTenant(10L);
        order.verify(agentMapper).selectByTenantId(10L);
        order.verify(adAccountService).getSettings();
    }

    @Test
    void invalidTargetTenantStopsBeforeAgentLookupAndTenantContextSwitch() {
        authenticate(10L);
        TenantContextHolder.setTenantId(10L);
        doThrow(exception(TENANT_DISABLE, "disabled-agent"))
                .when(tenantService).validTenant(10L);

        assertServiceException(() -> controller.getAdAccount(null), TENANT_DISABLE, "disabled-agent");

        verify(tenantService).validTenant(10L);
        verifyNoInteractions(agentMapper, adAccountService);
        assertEquals(10L, TenantContextHolder.getRequiredTenantId());
    }

    @Test
    void tenantAdminReadsOnlyItsOwnTenantContext() {
        authenticate(10L);
        TenantContextHolder.setTenantId(10L);
        mockExistingAgent(10L);
        SkitAdAccountService.Settings settings = new SkitAdAccountService.Settings();
        settings.setPangleAppId("pangle-10");
        when(adAccountService.getSettings()).thenAnswer(invocation -> {
            assertEquals(10L, TenantContextHolder.getRequiredTenantId());
            return settings;
        });

        CommonResult<SkitAdAccountService.Settings> response = controller.getAdAccount(null);

        assertEquals("pangle-10", response.getData().getPangleAppId());
        assertEquals(10L, TenantContextHolder.getRequiredTenantId());
        verify(platformAdminGuard, never()).check();
    }

    @Test
    void tenantAdminIgnoresVisitTenantContextWhenNoTenantIsRequested() {
        authenticate(10L);
        TenantContextHolder.setTenantId(20L);
        mockExistingAgent(10L);
        SkitAdAccountService.Settings settings = new SkitAdAccountService.Settings();
        settings.setPangleAppId("pangle-10");
        when(adAccountService.getSettings()).thenAnswer(invocation -> {
            assertEquals(10L, TenantContextHolder.getRequiredTenantId());
            return settings;
        });

        CommonResult<SkitAdAccountService.Settings> response = controller.getAdAccount(null);

        assertEquals("pangle-10", response.getData().getPangleAppId());
        verify(platformAdminGuard, never()).check();
    }

    @Test
    void platformAdminCrossTenantAccessRunsInsideSelectedAgentContext() {
        authenticate(1L);
        TenantContextHolder.setTenantId(1L);
        mockExistingAgent(20L);
        SkitAdAccountService.Settings settings = new SkitAdAccountService.Settings();
        settings.setTakuAppId("taku-20");
        when(adAccountService.getSettings()).thenAnswer(invocation -> {
            assertEquals(20L, TenantContextHolder.getRequiredTenantId());
            return settings;
        });

        CommonResult<SkitAdAccountService.Settings> response = controller.getAdAccount(20L);

        assertEquals("taku-20", response.getData().getTakuAppId());
        assertEquals(1L, TenantContextHolder.getRequiredTenantId(),
                "跨租户调用结束后必须恢复平台租户上下文");
        verify(platformAdminGuard).check();
    }

    @Test
    void platformAdminCanAuditArchivedAgentMembersWithoutCanonicalAvailabilityBypassForWrites() {
        authenticate(1L);
        TenantContextHolder.setTenantId(1L);
        when(agentMapper.selectByTenantId(20L)).thenReturn(SkitAgentDO.builder()
                .tenantId(20L).tenantCode("AGENT20").build());
        when(tenantService.getTenant(20L)).thenReturn(new TenantDO().setId(20L)
                .setStatus(CommonStatusEnum.DISABLE.getStatus()));
        when(memberService.getMemberPage(any(), isNull(), isNull())).thenAnswer(invocation -> {
            assertEquals(20L, TenantContextHolder.getRequiredTenantId());
            return new PageResult<>(Collections.emptyList(), 0L);
        });
        SkitTenantBusinessController.MemberPageReqVO reqVO =
                new SkitTenantBusinessController.MemberPageReqVO();
        reqVO.setTenantId(20L);

        controller.getMemberPage(reqVO);

        verify(platformAdminGuard).check();
        verify(tenantService, never()).validTenant(20L);
        verify(memberService).getMemberPage(reqVO, null, null);
        assertEquals(1L, TenantContextHolder.getRequiredTenantId());
    }

    @Test
    void platformAdminCanAuditArchivedAgentLedgerWithoutMutatingHistory() {
        authenticate(1L);
        TenantContextHolder.setTenantId(1L);
        when(agentMapper.selectByTenantId(20L)).thenReturn(SkitAgentDO.builder()
                .tenantId(20L).tenantCode("AGENT20").build());
        when(tenantService.getTenant(20L)).thenReturn(new TenantDO().setId(20L)
                .setStatus(CommonStatusEnum.DISABLE.getStatus()));
        when(revenueService.getLedgerPage(any(), isNull(), isNull(), isNull())).thenAnswer(invocation -> {
            assertEquals(20L, TenantContextHolder.getRequiredTenantId());
            return new PageResult<>(Collections.emptyList(), 0L);
        });
        SkitTenantBusinessController.LedgerPageReqVO reqVO =
                new SkitTenantBusinessController.LedgerPageReqVO();
        reqVO.setTenantId(20L);

        controller.getLedgerPage(reqVO);

        verify(platformAdminGuard).check();
        verify(tenantService, never()).validTenant(20L);
        verify(revenueService).getLedgerPage(reqVO, null, null, null);
    }

    @Test
    void tenantAdminCannotUseAuditReadToBypassInvalidOwnTenant() {
        authenticate(20L);
        TenantContextHolder.setTenantId(99L);
        doThrow(exception(TENANT_DISABLE, "archived-agent")).when(tenantService).validTenant(20L);
        SkitTenantBusinessController.MemberPageReqVO reqVO =
                new SkitTenantBusinessController.MemberPageReqVO();
        reqVO.setTenantId(20L);

        assertServiceException(() -> controller.getMemberPage(reqVO), TENANT_DISABLE, "archived-agent");

        verify(platformAdminGuard, never()).check();
        verifyNoInteractions(memberService, agentMapper);
    }

    @Test
    void memberAdminEndpointsExposeDetailStatusAndPasswordResetButNoCreateOrDelete() throws Exception {
        Set<String> methodNames = Arrays.stream(SkitTenantBusinessController.class.getDeclaredMethods())
                .map(Method::getName).collect(Collectors.toSet());
        assertTrue(methodNames.contains("getMember"));
        assertTrue(methodNames.contains("updateMemberStatus"));
        assertTrue(methodNames.contains("resetMemberPassword"));
        assertFalse(methodNames.contains("createMember"));
        assertFalse(methodNames.contains("deleteMember"));

        Method resetMethod = SkitTenantBusinessController.class.getDeclaredMethod("resetMemberPassword",
                SkitTenantBusinessController.MemberPasswordResetReqVO.class);
        ApiAccessLog accessLog = resetMethod.getAnnotation(ApiAccessLog.class);
        assertTrue(Arrays.asList(accessLog.sanitizeKeys()).contains("password"));
    }

    @Test
    void memberAdminRequestContractsRejectInvalidStatusAndPasswordLength() {
        SkitTenantBusinessController.MemberStatusUpdateReqVO statusReq =
                new SkitTenantBusinessController.MemberStatusUpdateReqVO();
        statusReq.setTenantId(20L);
        statusReq.setId(8L);
        statusReq.setStatus(99);
        assertThrows(ConstraintViolationException.class, () -> ValidationUtils.validate(statusReq));

        SkitTenantBusinessController.MemberPasswordResetReqVO passwordReq =
                new SkitTenantBusinessController.MemberPasswordResetReqVO();
        passwordReq.setTenantId(20L);
        passwordReq.setId(8L);
        passwordReq.setPassword("short");
        assertThrows(ConstraintViolationException.class, () -> ValidationUtils.validate(passwordReq));
    }

    @Test
    void adSaveContractRejectsOversizedMetadataAndMissingEnableFlags() {
        SkitTenantBusinessController.AdAccountSaveReqVO reqVO =
                new SkitTenantBusinessController.AdAccountSaveReqVO();
        reqVO.setTenantId(20L);
        reqVO.setPangleUsername(String.join("", Collections.nCopies(129, "x")));
        assertThrows(ConstraintViolationException.class, () -> ValidationUtils.validate(reqVO));

        reqVO.setPangleUsername("pangle-user");
        reqVO.setPangleEnabled(false);
        assertThrows(ConstraintViolationException.class, () -> ValidationUtils.validate(reqVO),
                "TAKU enabled flag must also be explicit");
    }

    @Test
    void explicitCredentialClearRunsInsideOperationalTenantContext() {
        authenticate(20L);
        TenantContextHolder.setTenantId(99L);
        mockExistingAgent(20L);
        SkitTenantBusinessController.AdCredentialClearReqVO reqVO =
                new SkitTenantBusinessController.AdCredentialClearReqVO();
        reqVO.setTenantId(20L);
        reqVO.setProvider("PANGLE");

        controller.clearAdAccountCredentials(reqVO);

        verify(tenantService).validTenant(20L);
        verify(adAccountService).clearCredentials("PANGLE");
        assertEquals(99L, TenantContextHolder.getRequiredTenantId());
    }

    private void authenticate(Long tenantId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(99L);
        loginUser.setTenantId(tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }

    private void mockExistingAgent(Long tenantId) {
        when(agentMapper.selectByTenantId(tenantId)).thenReturn(SkitAgentDO.builder().tenantId(tenantId)
                .tenantCode("AGENT" + tenantId).status(CommonStatusEnum.ENABLE.getStatus()).build());
        when(tenantService.getTenant(tenantId)).thenReturn(new TenantDO().setId(tenantId));
    }
}
