package cn.iocoder.yudao.module.skit.framework.security;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.mysql.agent.SkitAgentMapper;
import cn.iocoder.yudao.module.system.enums.permission.RoleCodeEnum;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_COMMAND_FORBIDDEN;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_DELEGATION_REASON_REQUIRED;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_RESOURCE_NOT_FOUND;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_TARGET_TENANT_REQUIRED;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_TENANT_SCOPE_FORBIDDEN;

/**
 * The single authority for selecting a tenant in management APIs. Request headers, visit-tenant
 * context, row IDs and cursors are never accepted as tenant authority.
 */
@Component
public class SkitAdminTenantScopeGuard {

    private static final int MIN_REASON_LENGTH = 10;
    private static final int MAX_REASON_LENGTH = 500;

    private final SkitPlatformAdminGuard platformAdminGuard;
    private final PermissionService permissionService;
    private final TenantService tenantService;
    private final SkitAgentMapper agentMapper;

    public SkitAdminTenantScopeGuard(SkitPlatformAdminGuard platformAdminGuard,
                                     PermissionService permissionService,
                                     TenantService tenantService,
                                     SkitAgentMapper agentMapper) {
        this.platformAdminGuard = Objects.requireNonNull(platformAdminGuard, "platformAdminGuard");
        this.permissionService = Objects.requireNonNull(permissionService, "permissionService");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService");
        this.agentMapper = Objects.requireNonNull(agentMapper, "agentMapper");
    }

    public <T> T readTenant(Long requestedTenantId, boolean archivedForPlatform,
                            Function<SkitAdminTenantScope, T> action) {
        LoginAuthority authority = requireAuthority();
        Long targetTenantId = resolveTenant(authority, requestedTenantId);
        SkitManagementAccessMode mode = authority.platformAdmin && archivedForPlatform
                ? SkitManagementAccessMode.ARCHIVED_AUDIT_READ
                : SkitManagementAccessMode.ACTIVE_READ;
        validateTarget(targetTenantId, mode);
        return executeTenant(scope(authority, targetTenantId, mode, null), action);
    }

    /**
     * Selects one guarded tenant, or the platform-wide read branch when an authenticated platform
     * administrator intentionally omits the target. Tenant administrators can never reach the
     * global branch; an omitted target continues to resolve to their original login tenant.
     */
    public <T> T readTenantOrGlobal(Long requestedTenantId, boolean archivedForPlatform,
                                    Function<SkitAdminTenantScope, T> tenantAction,
                                    Supplier<T> globalAction) {
        LoginAuthority authority = requireAuthority();
        if (authority.platformAdmin && requestedTenantId == null) {
            return Objects.requireNonNull(globalAction, "globalAction").get();
        }
        Long targetTenantId = resolveTenant(authority, requestedTenantId);
        SkitManagementAccessMode mode = authority.platformAdmin && archivedForPlatform
                ? SkitManagementAccessMode.ARCHIVED_AUDIT_READ
                : SkitManagementAccessMode.ACTIVE_READ;
        validateTarget(targetTenantId, mode);
        return executeTenant(scope(authority, targetTenantId, mode, null), tenantAction);
    }

    public <T> T writeTenant(Long requestedTenantId, SkitManagementCommandType commandType,
                             String reason, Function<SkitAdminTenantScope, T> action) {
        LoginAuthority authority = requireAuthority();
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.length() < MIN_REASON_LENGTH || normalizedReason.length() > MAX_REASON_LENGTH) {
            throw exception(MANAGEMENT_DELEGATION_REASON_REQUIRED);
        }
        if (commandType == null || (authority.platformAdmin
                ? !commandType.isPlatformAdminAllowed() : !commandType.isTenantAdminAllowed())) {
            throw exception(MANAGEMENT_COMMAND_FORBIDDEN);
        }
        Long targetTenantId = resolveTenant(authority, requestedTenantId);
        validateTarget(targetTenantId, SkitManagementAccessMode.OPERATIONAL_WRITE);
        return executeTenant(scope(authority, targetTenantId,
                SkitManagementAccessMode.OPERATIONAL_WRITE, commandType), action);
    }

    public <T> T globalRead(Supplier<T> action) {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser == null || loginUser.getId() == null || loginUser.getTenantId() == null
                || !platformAdminGuard.isPlatformAdmin()) {
            throw exception(MANAGEMENT_TENANT_SCOPE_FORBIDDEN);
        }
        return Objects.requireNonNull(action, "action").get();
    }

    private LoginAuthority requireAuthority() {
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser == null || loginUser.getId() == null || loginUser.getTenantId() == null) {
            throw exception(MANAGEMENT_TENANT_SCOPE_FORBIDDEN);
        }
        boolean platformAdmin = platformAdminGuard.isPlatformAdmin();
        if (!platformAdmin && !permissionService.hasAnyRoles(loginUser.getId(),
                RoleCodeEnum.TENANT_ADMIN.getCode())) {
            throw exception(MANAGEMENT_TENANT_SCOPE_FORBIDDEN);
        }
        return new LoginAuthority(loginUser.getId(), loginUser.getTenantId(), platformAdmin);
    }

    private Long resolveTenant(LoginAuthority authority, Long requestedTenantId) {
        if (authority.platformAdmin) {
            if (requestedTenantId == null || requestedTenantId <= 0) {
                throw exception(MANAGEMENT_TARGET_TENANT_REQUIRED);
            }
            return requestedTenantId;
        }
        if (requestedTenantId != null && !requestedTenantId.equals(authority.originalTenantId)) {
            throw exception(MANAGEMENT_TENANT_SCOPE_FORBIDDEN);
        }
        return authority.originalTenantId;
    }

    private void validateTarget(Long targetTenantId, SkitManagementAccessMode mode) {
        if (mode == SkitManagementAccessMode.ARCHIVED_AUDIT_READ) {
            if (tenantService.getTenant(targetTenantId) == null) {
                throw exception(MANAGEMENT_RESOURCE_NOT_FOUND);
            }
        } else {
            tenantService.validTenant(targetTenantId);
        }
        if (agentMapper.selectByTenantId(targetTenantId) == null) {
            throw exception(MANAGEMENT_RESOURCE_NOT_FOUND);
        }
    }

    private SkitAdminTenantScope scope(LoginAuthority authority, Long targetTenantId,
                                       SkitManagementAccessMode mode,
                                       SkitManagementCommandType authorizedCommandType) {
        return new SkitAdminTenantScope(authority.operatorUserId, authority.originalTenantId,
                targetTenantId, authority.platformAdmin,
                !authority.originalTenantId.equals(targetTenantId), mode, authorizedCommandType);
    }

    private <T> T executeTenant(SkitAdminTenantScope scope,
                                Function<SkitAdminTenantScope, T> action) {
        Objects.requireNonNull(action, "action");
        AtomicReference<T> result = new AtomicReference<>();
        TenantUtils.execute(scope.getTargetTenantId(), () -> result.set(action.apply(scope)));
        return result.get();
    }

    private static final class LoginAuthority {
        private final Long operatorUserId;
        private final Long originalTenantId;
        private final boolean platformAdmin;

        private LoginAuthority(Long operatorUserId, Long originalTenantId, boolean platformAdmin) {
            this.operatorUserId = operatorUserId;
            this.originalTenantId = originalTenantId;
            this.platformAdmin = platformAdmin;
        }
    }

}
