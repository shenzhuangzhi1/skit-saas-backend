package cn.iocoder.yudao.module.skit.framework.security;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.enums.permission.RoleCodeEnum;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.PLATFORM_ADMIN_REQUIRED;

/**
 * 平台级操作硬门禁。直接校验原始登录租户和原始角色，不受 visit-tenant 跳过权限逻辑影响。
 */
@Component
public class SkitPlatformAdminGuard {

    @Resource
    private TenantService tenantService;
    @Resource
    private PermissionApi permissionApi;

    public void check() {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser == null || loginUser.getTenantId() == null) {
            throw exception(PLATFORM_ADMIN_REQUIRED);
        }
        TenantDO tenant = tenantService.getTenant(loginUser.getTenantId());
        boolean systemTenant = tenant != null && Objects.equals(tenant.getPackageId(), TenantDO.PACKAGE_ID_SYSTEM);
        boolean superAdmin = permissionApi.hasAnyRoles(loginUser.getId(), RoleCodeEnum.SUPER_ADMIN.getCode());
        if (!systemTenant || !superAdmin) {
            throw exception(PLATFORM_ADMIN_REQUIRED);
        }
    }

}
