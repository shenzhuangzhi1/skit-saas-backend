package cn.iocoder.yudao.module.skit.framework.security;

import java.util.Objects;

/** Immutable authority captured from the original authenticated login. */
public final class SkitAdminTenantScope {

    private final Long operatorUserId;
    private final Long originalTenantId;
    private final Long targetTenantId;
    private final boolean platformAdmin;
    private final boolean delegated;
    private final SkitManagementAccessMode accessMode;
    private final SkitManagementCommandType authorizedCommandType;

    SkitAdminTenantScope(Long operatorUserId, Long originalTenantId, Long targetTenantId,
                         boolean platformAdmin, boolean delegated,
                         SkitManagementAccessMode accessMode,
                         SkitManagementCommandType authorizedCommandType) {
        this.operatorUserId = Objects.requireNonNull(operatorUserId, "operatorUserId");
        this.originalTenantId = Objects.requireNonNull(originalTenantId, "originalTenantId");
        this.targetTenantId = targetTenantId;
        this.platformAdmin = platformAdmin;
        this.delegated = delegated;
        this.accessMode = Objects.requireNonNull(accessMode, "accessMode");
        if ((accessMode == SkitManagementAccessMode.OPERATIONAL_WRITE)
                != (authorizedCommandType != null)) {
            throw new IllegalArgumentException(
                    "A management command type must be bound exactly once to a write scope");
        }
        this.authorizedCommandType = authorizedCommandType;
    }

    public Long getOperatorUserId() {
        return operatorUserId;
    }

    public Long getOriginalTenantId() {
        return originalTenantId;
    }

    public Long getTargetTenantId() {
        return targetTenantId;
    }

    public boolean isPlatformAdmin() {
        return platformAdmin;
    }

    public boolean isDelegated() {
        return delegated;
    }

    public SkitManagementAccessMode getAccessMode() {
        return accessMode;
    }

    /** The exact command authorized by the guard; null for every read scope. */
    public SkitManagementCommandType getAuthorizedCommandType() {
        return authorizedCommandType;
    }

}
