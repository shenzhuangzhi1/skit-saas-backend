package cn.iocoder.yudao.module.skit.service.provider;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.PROVIDER_CURRENT_PASSWORD_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class SkitCurrentAdminPasswordReauthServiceTest {

  private static final long USER_ID = 7L;
  private static final long ORIGINAL_TENANT_ID = 1L;

  @Mock private AdminUserService adminUserService;
  @Mock private PasswordEncoder passwordEncoder;

  private SkitCurrentAdminPasswordReauthService service;

  @BeforeEach
  void setUp() {
    service = new SkitCurrentAdminPasswordReauthServiceImpl(adminUserService, passwordEncoder);
    authenticate(ORIGINAL_TENANT_ID, 42L);
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void matchesCurrentPasswordThroughCharBufferAndClearsCallerArray() {
    char[] supplied = "correct-current-password".toCharArray();
    when(adminUserService.getUserIgnoreTenant(USER_ID)).thenReturn(activeUser());
    when(passwordEncoder.matches(
            argThat(
                raw -> raw instanceof CharBuffer && "correct-current-password".contentEquals(raw)),
            eq("encoded-password")))
        .thenReturn(true);

    service.verifyCurrentUserPassword(supplied);

    assertCleared(supplied);
    verify(adminUserService).getUserIgnoreTenant(USER_ID);
  }

  @Test
  void wrongPasswordUsesStableDenialAndStillClearsCallerArray() {
    char[] supplied = "wrong-current-password".toCharArray();
    when(adminUserService.getUserIgnoreTenant(USER_ID)).thenReturn(activeUser());
    when(passwordEncoder.matches(argThat(raw -> raw instanceof CharBuffer), eq("encoded-password")))
        .thenReturn(false);

    assertServiceException(
        () -> service.verifyCurrentUserPassword(supplied), PROVIDER_CURRENT_PASSWORD_INVALID);

    assertCleared(supplied);
  }

  @Test
  void blankAndOversizedPasswordsDenyBeforeUserLookupAndAreCleared() {
    char[] blank = "   ".toCharArray();
    char[] oversized = new char[129];
    Arrays.fill(oversized, 'x');

    assertServiceException(
        () -> service.verifyCurrentUserPassword(blank), PROVIDER_CURRENT_PASSWORD_INVALID);
    assertServiceException(
        () -> service.verifyCurrentUserPassword(oversized), PROVIDER_CURRENT_PASSWORD_INVALID);

    assertCleared(blank);
    assertCleared(oversized);
    verify(adminUserService, never()).getUserIgnoreTenant(USER_ID);
  }

  @Test
  void fetchedAdministratorMustBelongToImmutableOriginalLoginTenant() {
    char[] supplied = "correct-current-password".toCharArray();
    AdminUserDO wrongTenant = activeUser();
    wrongTenant.setTenantId(42L);
    when(adminUserService.getUserIgnoreTenant(USER_ID)).thenReturn(wrongTenant);

    assertServiceException(
        () -> service.verifyCurrentUserPassword(supplied), PROVIDER_CURRENT_PASSWORD_INVALID);

    assertCleared(supplied);
    verify(passwordEncoder, never())
        .matches(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void serviceProducesNoReusableReauthenticationArtifact() {
    assertEquals(1, SkitCurrentAdminPasswordReauthService.class.getDeclaredMethods().length);
    assertEquals(
        Void.TYPE,
        SkitCurrentAdminPasswordReauthService.class.getDeclaredMethods()[0].getReturnType());
  }

  private AdminUserDO activeUser() {
    AdminUserDO user = new AdminUserDO();
    user.setId(USER_ID);
    user.setTenantId(ORIGINAL_TENANT_ID);
    user.setStatus(CommonStatusEnum.ENABLE.getStatus());
    user.setPassword("encoded-password");
    return user;
  }

  private void authenticate(long tenantId, Long visitTenantId) {
    LoginUser loginUser =
        new LoginUser().setId(USER_ID).setTenantId(tenantId).setVisitTenantId(visitTenantId);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
  }

  private static void assertCleared(char[] value) {
    assertTrue(value != null && value.length > 0);
    for (char character : value) {
      assertEquals('\0', character);
    }
  }
}
