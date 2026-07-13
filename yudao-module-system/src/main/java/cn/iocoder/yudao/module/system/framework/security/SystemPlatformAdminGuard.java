package cn.iocoder.yudao.module.system.framework.security;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.enums.permission.RoleCodeEnum;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Objects;

/**
 * Verifies the immutable origin of a platform administrator. Visit-tenant headers never change
 * {@link LoginUser#getTenantId()}, so a tenant-local role cannot cross this boundary.
 */
@Component
public class SystemPlatformAdminGuard {

    @Resource
    private TenantProperties tenantProperties;
    @Resource
    private TenantService tenantService;
    @Resource
    private PermissionService permissionService;

    public boolean isPlatformAdmin() {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser == null || loginUser.getId() == null || loginUser.getTenantId() == null
                || !Objects.equals(loginUser.getTenantId(), tenantProperties.getPlatformTenantId())) {
            return false;
        }
        TenantDO tenant = tenantService.getTenant(loginUser.getTenantId());
        if (tenant == null || !Objects.equals(tenant.getPackageId(), TenantDO.PACKAGE_ID_SYSTEM)) {
            return false;
        }
        return permissionService.hasAnyRoles(loginUser.getId(), RoleCodeEnum.SUPER_ADMIN.getCode());
    }
}
