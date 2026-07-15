package cn.iocoder.yudao.module.skit.framework.security;

/** Explicit authority mode for one management operation. */
public enum SkitManagementAccessMode {

    ACTIVE_READ,
    ARCHIVED_AUDIT_READ,
    OPERATIONAL_WRITE,
    GLOBAL_READ

}
