package cn.iocoder.yudao.module.skit.framework.security;

/**
 * Closed management-write whitelist. Immutable facts deliberately have no generic update/delete command.
 */
public enum SkitManagementCommandType {

    AD_ACCOUNT_UPDATE(true),
    AD_CREDENTIAL_ROTATE_NORMAL(true),
    AD_CREDENTIAL_CLEAR(true),
    REPORTING_CONFIGURATION(true),
    AD_READINESS_CONFIGURATION(true),
    AD_ROLLOUT_TRANSITION(true),
    APP_RELEASE_UPDATE(false),
    APP_BUILD_MATERIAL_UPDATE(false),
    COMMISSION_PLAN_PUBLISH(true),
    MEMBER_STATUS_CHANGE(true),
    MEMBER_PASSWORD_RESET(true),
    REPORT_PULL_RETRY_SINGLE(true),
    CALLBACK_RETRY_DUE_SINGLE(true),
    MANAGEMENT_EXPORT_CREATE(true),
    CALLBACK_DEAD_LETTER_REPLAY(false),
    ENTITLEMENT_SECURITY_REVOKE(false),
    CREDENTIAL_HARD_REVOKE(false);

    private final boolean tenantAdminAllowed;

    SkitManagementCommandType(boolean tenantAdminAllowed) {
        this.tenantAdminAllowed = tenantAdminAllowed;
    }

    public boolean isTenantAdminAllowed() {
        return tenantAdminAllowed;
    }

    public boolean isPlatformAdminAllowed() {
        return true;
    }

}
