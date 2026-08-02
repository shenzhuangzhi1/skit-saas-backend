package cn.iocoder.yudao.module.skit.service.provider;

/**
 * Performs one immediate password comparison and never creates a reusable reauthentication grant.
 */
public interface SkitCurrentAdminPasswordReauthService {

  void verifyCurrentUserPassword(char[] password);
}
