package cn.iocoder.yudao.module.skit.controller.admin.tenant;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.util.validation.ValidationUtils;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScope;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScopeGuard;
import cn.iocoder.yudao.module.skit.framework.security.SkitManagementAccessMode;
import cn.iocoder.yudao.module.skit.framework.security.SkitManagementCommandType;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdAccountService;
import cn.iocoder.yudao.module.skit.service.app.SkitAppReleaseService;
import cn.iocoder.yudao.module.skit.service.management.SkitManagementCommandExecutor;
import cn.iocoder.yudao.module.skit.service.member.SkitMemberService;
import cn.iocoder.yudao.module.skit.service.reconciliation.SkitReportingConfigurationService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.validation.ConstraintViolationException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitTenantBusinessControllerTest {

    @InjectMocks
    private SkitTenantBusinessController controller;
    @Mock
    private SkitAdAccountService adAccountService;
    @Mock
    private SkitAppReleaseService appReleaseService;
    @Mock
    private SkitMemberService memberService;
    @Mock
    private SkitReportingConfigurationService reportingConfigurationService;
    @Mock
    private SkitAdminTenantScopeGuard adminTenantScopeGuard;
    @Mock
    private SkitManagementCommandExecutor managementCommandExecutor;
    @Mock
    private SkitAgentMapper agentMapper;
    @Mock
    private TenantService tenantService;

    @Test
    void everyLegacyReadUsesTheSharedTenantGuard() throws Exception {
        SkitAdminTenantScope scope = scope(SkitManagementAccessMode.ACTIVE_READ, null, false);
        when(adminTenantScopeGuard.readTenant(eq(20L), eq(false), any())).thenAnswer(invocation ->
                apply(invocation.getArgument(2), scope));
        when(adAccountService.getSettings()).thenReturn(new SkitAdAccountService.Settings());

        controller.getAdAccount(20L);

        verify(adminTenantScopeGuard).readTenant(eq(20L), eq(false), any());
    }

    @Test
    void adAccountSaveAndClearUseExactAuditedCommandsWithoutCredentialMaterial() throws Exception {
        SkitTenantBusinessController.AdAccountSaveReqVO save = validAdAccountSave();
        executeWrite(SkitManagementCommandType.AD_ACCOUNT_UPDATE, save.getReason());
        SkitAdAccountService.Settings current = new SkitAdAccountService.Settings();
        when(adAccountService.getSettings()).thenReturn(current);
        when(managementCommandExecutor.execute(any(), eq(SkitManagementCommandType.AD_ACCOUNT_UPDATE),
                eq("AD_ACCOUNT_SETTINGS"), eq("20"), eq(save.getReason()),
                anyString(), anyString(), any())).thenReturn(current);

        controller.saveAdAccount(save);

        verify(adminTenantScopeGuard).writeTenant(eq(20L),
                eq(SkitManagementCommandType.AD_ACCOUNT_UPDATE), eq(save.getReason()), any());
        verify(managementCommandExecutor).execute(any(),
                eq(SkitManagementCommandType.AD_ACCOUNT_UPDATE), eq("AD_ACCOUNT_SETTINGS"),
                eq("20"), eq(save.getReason()), anyString(), anyString(), any());

        SkitTenantBusinessController.AdCredentialClearReqVO clear =
                new SkitTenantBusinessController.AdCredentialClearReqVO();
        clear.setTenantId(20L);
        clear.setProvider("TAKU");
        clear.setReason("clear compromised Taku credentials");
        executeWrite(SkitManagementCommandType.AD_CREDENTIAL_CLEAR, clear.getReason());
        when(managementCommandExecutor.execute(any(), eq(SkitManagementCommandType.AD_CREDENTIAL_CLEAR),
                eq("AD_ACCOUNT_CREDENTIAL"), eq("TAKU"), eq(clear.getReason()),
                anyString(), anyString(), any())).thenReturn(true);

        controller.clearAdAccountCredentials(clear);

        verify(adminTenantScopeGuard).writeTenant(eq(20L),
                eq(SkitManagementCommandType.AD_CREDENTIAL_CLEAR), eq(clear.getReason()), any());
    }

    @Test
    void appReleaseMemberStatusAndPasswordResetUseDurableExactCommands() throws Exception {
        SkitTenantBusinessController.AppReleaseSaveReqVO app = new SkitTenantBusinessController.AppReleaseSaveReqVO();
        app.setTenantId(20L);
        app.setStatus(CommonStatusEnum.ENABLE.getStatus());
        app.setReason("publish the approved tenant application");
        executeWrite(SkitManagementCommandType.APP_RELEASE_UPDATE, app.getReason());
        SkitAppReleaseService.ProfileView profile = new SkitAppReleaseService.ProfileView();
        profile.setTenantId(20L);
        when(appReleaseService.getProfile(20L)).thenReturn(profile);
        when(managementCommandExecutor.execute(any(), eq(SkitManagementCommandType.APP_RELEASE_UPDATE),
                eq("APP_RELEASE_PROFILE"), eq("20"), eq(app.getReason()),
                anyString(), anyString(), any())).thenReturn(profile);
        controller.saveAppRelease(app);

        SkitTenantBusinessController.MemberStatusUpdateReqVO status =
                new SkitTenantBusinessController.MemberStatusUpdateReqVO();
        status.setTenantId(20L);
        status.setId(8L);
        status.setStatus(CommonStatusEnum.DISABLE.getStatus());
        status.setReason("disable member after verified abuse");
        executeWrite(SkitManagementCommandType.MEMBER_STATUS_CHANGE, status.getReason());
        when(memberService.getMember(8L)).thenReturn(member(8L));
        when(managementCommandExecutor.execute(any(), eq(SkitManagementCommandType.MEMBER_STATUS_CHANGE),
                eq("MEMBER"), eq("8"), eq(status.getReason()),
                anyString(), anyString(), any())).thenReturn(true);
        controller.updateMemberStatus(status);

        SkitTenantBusinessController.MemberPasswordResetReqVO password =
                new SkitTenantBusinessController.MemberPasswordResetReqVO();
        password.setTenantId(20L);
        password.setId(8L);
        password.setPassword("new-password-123");
        password.setReason("reset password after owner verification");
        executeWrite(SkitManagementCommandType.MEMBER_PASSWORD_RESET, password.getReason());
        when(managementCommandExecutor.execute(any(), eq(SkitManagementCommandType.MEMBER_PASSWORD_RESET),
                eq("MEMBER_CREDENTIAL"), eq("8"), eq(password.getReason()),
                anyString(), anyString(), any())).thenReturn(true);
        controller.resetMemberPassword(password);

        verify(adminTenantScopeGuard).writeTenant(eq(20L),
                eq(SkitManagementCommandType.APP_RELEASE_UPDATE), eq(app.getReason()), any());
        verify(adminTenantScopeGuard).writeTenant(eq(20L),
                eq(SkitManagementCommandType.MEMBER_STATUS_CHANGE), eq(status.getReason()), any());
        verify(adminTenantScopeGuard).writeTenant(eq(20L),
                eq(SkitManagementCommandType.MEMBER_PASSWORD_RESET), eq(password.getReason()), any());
    }

    @Test
    void controllerHasNoCompetingTenantAuthorityHelpers() {
        Set<String> methods = Arrays.stream(SkitTenantBusinessController.class.getDeclaredMethods())
                .map(Method::getName).collect(Collectors.toSet());
        assertFalse(methods.contains("resolveTargetTenant"));
        assertFalse(methods.contains("resolveAuditTenant"));
        assertFalse(methods.contains("inTargetTenant"));
        assertFalse(methods.contains("inAuditTenant"));
        assertFalse(methods.contains("executeInTenant"));
    }

    @Test
    void requestContractsRequireReasonAndKeepPasswordOutOfAccessLogs() throws Exception {
        SkitTenantBusinessController.MemberStatusUpdateReqVO status =
                new SkitTenantBusinessController.MemberStatusUpdateReqVO();
        status.setTenantId(20L);
        status.setId(8L);
        status.setStatus(CommonStatusEnum.ENABLE.getStatus());
        assertThrows(ConstraintViolationException.class, () -> ValidationUtils.validate(status));

        SkitTenantBusinessController.MemberPasswordResetReqVO password =
                new SkitTenantBusinessController.MemberPasswordResetReqVO();
        password.setTenantId(20L);
        password.setId(8L);
        password.setPassword("new-password-123");
        password.setReason("reset password after owner verification");
        Method reset = SkitTenantBusinessController.class.getDeclaredMethod(
                "resetMemberPassword", SkitTenantBusinessController.MemberPasswordResetReqVO.class);
        assertTrue(Arrays.asList(reset.getAnnotation(ApiAccessLog.class).sanitizeKeys())
                .contains("password"));
    }

    @Test
    void reportingConfigurationRemainsGuardedAuditedAndWriteOnly() throws Exception {
        SkitTenantBusinessController.ReportingConfigurationSaveReqVO request =
                new SkitTenantBusinessController.ReportingConfigurationSaveReqVO();
        request.setTenantId(20L);
        request.setCredentialVersion(4);
        request.setPublisherKey("write-only-publisher");
        request.setReportTimezone("UTC+8");
        request.setCurrency("USD");
        request.setAmountScale(6);
        request.setAdFormat("rewarded_video");
        request.setReason("rotate reporting credential after approval");
        executeWrite(SkitManagementCommandType.REPORTING_CONFIGURATION, request.getReason());
        SkitReportingConfigurationService.View before =
                new SkitReportingConfigurationService.View().setTenantId(20L)
                        .setAdAccountId(2002L).setCredentialVersion(4);
        when(reportingConfigurationService.getConfiguration()).thenReturn(before);
        when(managementCommandExecutor.execute(any(),
                eq(SkitManagementCommandType.REPORTING_CONFIGURATION),
                eq("TAKU_REPORTING_CONFIGURATION"), eq("2002"), eq(request.getReason()),
                anyString(), anyString(), any())).thenReturn(before);

        controller.saveReportingConfiguration(request);

        assertFalse(request.toString().contains("write-only-publisher"));
        verify(adminTenantScopeGuard).writeTenant(eq(20L),
                eq(SkitManagementCommandType.REPORTING_CONFIGURATION),
                eq(request.getReason()), any());
    }

    @Test
    void appReleaseMapsTheTenantPublicTrustRootIntoTheLockedProfileCommand() throws Exception {
        SkitTenantBusinessController.AppReleaseSaveReqVO request =
                new SkitTenantBusinessController.AppReleaseSaveReqVO();
        request.setTenantId(20L);
        request.setRuntimeUpdatePublicKey("tenant-public-key");
        request.setStatus(CommonStatusEnum.DISABLE.getStatus());
        request.setReason("configure tenant runtime update trust root");
        executeWrite(SkitManagementCommandType.APP_RELEASE_UPDATE, request.getReason());
        SkitAppReleaseService.ProfileView before = new SkitAppReleaseService.ProfileView();
        before.setTenantId(20L);
        SkitAppReleaseService.ProfileView saved = new SkitAppReleaseService.ProfileView();
        saved.setTenantId(20L);
        saved.setRuntimeUpdatePublicKey("tenant-public-key");
        saved.setRuntimeUpdateKeyFingerprint(repeat('a', 64));
        when(appReleaseService.getProfile(20L)).thenReturn(before);
        when(appReleaseService.saveProfile(any())).thenReturn(saved);
        when(managementCommandExecutor.execute(any(), eq(SkitManagementCommandType.APP_RELEASE_UPDATE),
                eq("APP_RELEASE_PROFILE"), eq("20"), eq(request.getReason()),
                anyString(), anyString(), any())).thenAnswer(invocation -> {
            Supplier<?> mutation = invocation.getArgument(7);
            mutation.get();
            return saved;
        });

        controller.saveAppRelease(request);

        ArgumentCaptor<SkitAppReleaseService.ProfileView> profileCaptor =
                ArgumentCaptor.forClass(SkitAppReleaseService.ProfileView.class);
        verify(appReleaseService).saveProfile(profileCaptor.capture());
        assertTrue("tenant-public-key".equals(
                profileCaptor.getValue().getRuntimeUpdatePublicKey()));
    }

    private SkitTenantBusinessController.AdAccountSaveReqVO validAdAccountSave() {
        SkitTenantBusinessController.AdAccountSaveReqVO request =
                new SkitTenantBusinessController.AdAccountSaveReqVO();
        request.setTenantId(20L);
        request.setPangleEnabled(false);
        request.setTakuEnabled(true);
        request.setTakuAppSecret("server-only-secret");
        request.setReason("update tenant advertising account safely");
        return request;
    }

    private SkitMemberService.MemberView member(Long id) {
        SkitMemberService.MemberView result = new SkitMemberService.MemberView();
        result.setId(id);
        result.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return result;
    }

    private static String repeat(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }

    private void executeWrite(SkitManagementCommandType type, String reason) throws Exception {
        SkitAdminTenantScope scope = scope(SkitManagementAccessMode.OPERATIONAL_WRITE, type, false);
        when(adminTenantScopeGuard.writeTenant(eq(20L), eq(type), eq(reason), any()))
                .thenAnswer(invocation -> apply(invocation.getArgument(3), scope));
    }

    @SuppressWarnings("unchecked")
    private <T> T apply(Object function, SkitAdminTenantScope scope) {
        return ((Function<SkitAdminTenantScope, T>) function).apply(scope);
    }

    private SkitAdminTenantScope scope(SkitManagementAccessMode mode,
                                       SkitManagementCommandType command,
                                       boolean platform) throws Exception {
        Constructor<SkitAdminTenantScope> constructor = SkitAdminTenantScope.class
                .getDeclaredConstructor(Long.class, Long.class, Long.class, boolean.class,
                        boolean.class, SkitManagementAccessMode.class,
                        SkitManagementCommandType.class);
        constructor.setAccessible(true);
        return constructor.newInstance(99L, 20L, 20L, platform, false, mode, command);
    }

}
