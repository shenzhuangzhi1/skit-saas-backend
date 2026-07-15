package cn.iocoder.yudao.module.skit.framework.security;

import cn.iocoder.yudao.module.system.framework.security.SystemPlatformAdminGuard;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.PLATFORM_ADMIN_REQUIRED;

/**
 * 平台级操作硬门禁。直接校验原始登录租户和原始角色，不受 visit-tenant 跳过权限逻辑影响。
 */
@Component
public class SkitPlatformAdminGuard {

    @Resource
    private SystemPlatformAdminGuard systemPlatformAdminGuard;

    public void check() {
        if (!isPlatformAdmin()) {
            throw exception(PLATFORM_ADMIN_REQUIRED);
        }
    }

    /** Uses the original authenticated tenant/roles held by the system guard. */
    public boolean isPlatformAdmin() {
        return systemPlatformAdminGuard.isPlatformAdmin();
    }

}
