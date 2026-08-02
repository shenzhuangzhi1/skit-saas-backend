package cn.iocoder.yudao.module.skit.service.provider;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.PROVIDER_CURRENT_PASSWORD_INVALID;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class SkitCurrentAdminPasswordReauthServiceImpl
    implements SkitCurrentAdminPasswordReauthService {

  static final int MAX_PASSWORD_CHARACTERS = 128;

  private final AdminUserService adminUserService;
  private final PasswordEncoder passwordEncoder;

  public SkitCurrentAdminPasswordReauthServiceImpl(
      AdminUserService adminUserService, PasswordEncoder passwordEncoder) {
    this.adminUserService = Objects.requireNonNull(adminUserService, "adminUserService");
    this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
  }

  @Override
  public void verifyCurrentUserPassword(char[] password) {
    try {
      if (!validPassword(password)) {
        throw exception(PROVIDER_CURRENT_PASSWORD_INVALID);
      }
      LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
      Long loginUserId = SecurityFrameworkUtils.getLoginUserId();
      if (loginUser == null
          || loginUserId == null
          || loginUser.getTenantId() == null
          || !Objects.equals(loginUserId, loginUser.getId())) {
        throw exception(PROVIDER_CURRENT_PASSWORD_INVALID);
      }
      AdminUserDO current = adminUserService.getUserIgnoreTenant(loginUserId);
      if (current == null
          || current.getPassword() == null
          || !Objects.equals(current.getId(), loginUserId)
          || !Objects.equals(current.getTenantId(), loginUser.getTenantId())
          || !CommonStatusEnum.ENABLE.getStatus().equals(current.getStatus())
          || !passwordEncoder.matches(CharBuffer.wrap(password), current.getPassword())) {
        throw exception(PROVIDER_CURRENT_PASSWORD_INVALID);
      }
    } finally {
      if (password != null) {
        Arrays.fill(password, '\0');
      }
    }
  }

  private static boolean validPassword(char[] password) {
    if (password == null || password.length == 0 || password.length > MAX_PASSWORD_CHARACTERS) {
      return false;
    }
    for (char character : password) {
      if (!Character.isWhitespace(character)) {
        return true;
      }
    }
    return false;
  }
}
