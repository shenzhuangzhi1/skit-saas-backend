package cn.iocoder.yudao.module.system.service.auth;

import cn.hutool.core.util.ReflectUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.AuthLoginReqVO;
import cn.iocoder.yudao.module.system.controller.admin.auth.vo.AuthLoginRespVO;
import cn.iocoder.yudao.module.system.dal.dataobject.oauth2.OAuth2AccessTokenDO;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.enums.logger.LoginLogTypeEnum;
import cn.iocoder.yudao.module.system.enums.logger.LoginResultEnum;
import cn.iocoder.yudao.module.system.service.logger.LoginLogService;
import cn.iocoder.yudao.module.system.service.member.MemberService;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import com.anji.captcha.model.common.ResponseModel;
import com.anji.captcha.service.CaptchaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import javax.annotation.Resource;
import javax.validation.Validation;
import javax.validation.Validator;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertPojoEquals;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomString;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.AUTH_LOGIN_BAD_CREDENTIALS;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.AUTH_LOGIN_CAPTCHA_CODE_ERROR;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.AUTH_LOGIN_USER_DISABLED;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.TENANT_DISABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Import(AdminAuthServiceImpl.class)
class AdminAuthServiceImplTest extends BaseDbUnitTest {

    @Resource
    private AdminAuthServiceImpl authService;

    @MockBean
    private AdminUserService userService;
    @MockBean
    private TenantService tenantService;
    @MockBean
    private CaptchaService captchaService;
    @MockBean
    private LoginLogService loginLogService;
    @MockBean
    private OAuth2TokenService oauth2TokenService;
    @MockBean
    private MemberService memberService;
    @MockBean
    private Validator validator;

    @BeforeEach
    void setUp() {
        authService.setCaptchaEnable(true);
        ReflectUtil.setFieldValue(authService, "validator",
                Validation.buildDefaultValidatorFactory().getValidator());
    }

    @Test
    void authenticateSuccess() {
        String username = randomString();
        String password = randomString();
        AdminUserDO user = randomPojo(AdminUserDO.class, value -> value.setUsername(username)
                .setPassword(password).setStatus(CommonStatusEnum.ENABLE.getStatus()));
        when(userService.getUserByUsername(username)).thenReturn(user);
        when(userService.isPasswordMatch(password, user.getPassword())).thenReturn(true);

        assertPojoEquals(user, authService.authenticate(username, password));
    }

    @Test
    void authenticateRejectsMissingUser() {
        String username = randomString();

        assertServiceException(() -> authService.authenticate(username, randomString()),
                AUTH_LOGIN_BAD_CREDENTIALS);

        verify(loginLogService).createLoginLog(argThat(log -> log.getUserId() == null
                && LoginResultEnum.BAD_CREDENTIALS.getResult().equals(log.getResult())));
    }

    @Test
    void authenticateRejectsBadPassword() {
        String username = randomString();
        AdminUserDO user = randomPojo(AdminUserDO.class, value -> value.setUsername(username)
                .setStatus(CommonStatusEnum.ENABLE.getStatus()));
        when(userService.getUserByUsername(username)).thenReturn(user);

        assertServiceException(() -> authService.authenticate(username, randomString()),
                AUTH_LOGIN_BAD_CREDENTIALS);
    }

    @Test
    void authenticateRejectsDisabledUser() {
        String username = randomString();
        String password = randomString();
        AdminUserDO user = randomPojo(AdminUserDO.class, value -> value.setUsername(username)
                .setPassword(password).setStatus(CommonStatusEnum.DISABLE.getStatus()));
        when(userService.getUserByUsername(username)).thenReturn(user);
        when(userService.isPasswordMatch(password, user.getPassword())).thenReturn(true);

        assertServiceException(() -> authService.authenticate(username, password), AUTH_LOGIN_USER_DISABLED);
    }

    @Test
    void loginResolvesTenantFromUniqueUsername() {
        authService.setCaptchaEnable(false);
        AuthLoginReqVO request = new AuthLoginReqVO();
        request.setUsername("test_username");
        request.setPassword("test_password");
        AdminUserDO user = randomPojo(AdminUserDO.class, value -> value.setId(1L)
                .setUsername(request.getUsername()).setPassword(request.getPassword())
                .setStatus(CommonStatusEnum.ENABLE.getStatus()).setTenantId(42L));
        when(userService.getUserListByUsernameIgnoreTenant(request.getUsername()))
                .thenReturn(Collections.singletonList(user));
        when(userService.getUserByUsername(request.getUsername())).thenReturn(user);
        when(userService.isPasswordMatch(request.getPassword(), user.getPassword())).thenReturn(true);
        OAuth2AccessTokenDO token = randomPojo(OAuth2AccessTokenDO.class, value -> value.setUserId(1L)
                .setUserType(UserTypeEnum.ADMIN.getValue()).setTenantId(42L));
        AtomicReference<Long> tokenTenant = new AtomicReference<>();
        when(oauth2TokenService.createAccessToken(eq(1L), eq(UserTypeEnum.ADMIN.getValue()),
                eq("default"), isNull())).thenAnswer(invocation -> {
                    tokenTenant.set(TenantContextHolder.getTenantId());
                    return token;
                });

        AuthLoginRespVO response = authService.login(request);

        assertPojoEquals(token, response);
        assertEquals(42L, tokenTenant.get());
        assertNull(TenantContextHolder.getTenantId());
        verify(tenantService, atLeastOnce()).validTenant(42L);
        verify(loginLogService).createLoginLog(argThat(log -> log.getUserId().equals(user.getId())
                && LoginLogTypeEnum.LOGIN_USERNAME.getType().equals(log.getLogType())
                && LoginResultEnum.SUCCESS.getResult().equals(log.getResult())));
    }

    @Test
    void loginRejectsInactiveTenantBeforeTokenCreation() {
        authService.setCaptchaEnable(false);
        AuthLoginReqVO request = new AuthLoginReqVO();
        request.setUsername("test_username");
        request.setPassword("test_password");
        AdminUserDO user = randomPojo(AdminUserDO.class, value -> value.setId(1L)
                .setUsername(request.getUsername()).setPassword(request.getPassword())
                .setStatus(CommonStatusEnum.ENABLE.getStatus()).setTenantId(42L));
        when(userService.getUserListByUsernameIgnoreTenant(request.getUsername()))
                .thenReturn(Collections.singletonList(user));
        when(userService.getUserByUsername(request.getUsername())).thenReturn(user);
        when(userService.isPasswordMatch(request.getPassword(), user.getPassword())).thenReturn(true);
        doThrow(exception(TENANT_DISABLE, "agent")).when(tenantService).validTenant(42L);

        assertServiceException(() -> authService.login(request), TENANT_DISABLE, "agent");

        verifyNoInteractions(oauth2TokenService);
    }

    @Test
    void loginRejectsUsernameBoundToMultipleTenants() {
        authService.setCaptchaEnable(false);
        AuthLoginReqVO request = new AuthLoginReqVO();
        request.setUsername("shared-admin");
        request.setPassword("secret123");
        when(userService.getUserListByUsernameIgnoreTenant(request.getUsername())).thenReturn(Arrays.asList(
                randomPojo(AdminUserDO.class, value -> value.setTenantId(42L)),
                randomPojo(AdminUserDO.class, value -> value.setTenantId(43L))));

        assertServiceException(() -> authService.login(request), AUTH_LOGIN_BAD_CREDENTIALS);

        verify(userService, never()).getUserByUsername(anyString());
        verifyNoInteractions(oauth2TokenService);
    }

    @Test
    void validateCaptchaWhenEnabled() {
        AuthLoginReqVO request = randomPojo(AuthLoginReqVO.class);
        when(captchaService.verification(argThat(captcha ->
                request.getCaptchaVerification().equals(captcha.getCaptchaVerification()))))
                .thenReturn(ResponseModel.success());

        authService.validateCaptcha(request);
    }

    @Test
    void validateCaptchaWhenDisabled() {
        authService.setCaptchaEnable(false);
        authService.validateCaptcha(randomPojo(AuthLoginReqVO.class));
        verifyNoInteractions(captchaService);
    }

    @Test
    void validateCaptchaRejectsFailure() {
        AuthLoginReqVO request = randomPojo(AuthLoginReqVO.class);
        when(captchaService.verification(argThat(captcha ->
                request.getCaptchaVerification().equals(captcha.getCaptchaVerification()))))
                .thenReturn(ResponseModel.errorMsg("invalid"));

        assertServiceException(() -> authService.validateCaptcha(request), AUTH_LOGIN_CAPTCHA_CODE_ERROR,
                "invalid");
    }
}
