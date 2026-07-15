package cn.iocoder.yudao.module.skit.service.management;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_TIMEZONE_INVALID;
import static cn.iocoder.yudao.module.skit.enums.SkitDomainConstants.MANAGEMENT_TIMEZONE_DEFAULT;

/** Fixed-offset timezone used by Taku reporting and management projections. */
public final class SkitManagementTimezone {

    private static final ZoneOffset DATABASE_OFFSET = ZoneOffset.ofHours(8);
    private static final String DATABASE_SQL_OFFSET = "+08:00";

    private final String name;
    private final ZoneOffset offset;
    private final String sqlOffset;

    private SkitManagementTimezone(String name, ZoneOffset offset, String sqlOffset) {
        this.name = name;
        this.offset = offset;
        this.sqlOffset = sqlOffset;
    }

    public static SkitManagementTimezone of(String requested) {
        String canonical = requested == null ? MANAGEMENT_TIMEZONE_DEFAULT : requested;
        if ("UTC+8".equals(canonical)) {
            return new SkitManagementTimezone(canonical, ZoneOffset.ofHours(8), "+08:00");
        }
        if ("UTC-8".equals(canonical)) {
            return new SkitManagementTimezone(canonical, ZoneOffset.ofHours(-8), "-08:00");
        }
        if ("UTC+0".equals(canonical)) {
            return new SkitManagementTimezone(canonical, ZoneOffset.UTC, "+00:00");
        }
        throw exception(MANAGEMENT_TIMEZONE_INVALID);
    }

    public String getName() {
        return name;
    }

    public LocalDateTime now(Clock clock) {
        return LocalDateTime.ofInstant(Objects.requireNonNull(clock, "clock").instant(), offset);
    }

    public LocalDateTime toDatabase(LocalDateTime displayValue) {
        if (displayValue == null) return null;
        return LocalDateTime.ofInstant(displayValue.toInstant(offset), DATABASE_OFFSET);
    }

    public LocalDateTime fromDatabase(LocalDateTime databaseValue) {
        if (databaseValue == null) return null;
        return LocalDateTime.ofInstant(databaseValue.toInstant(DATABASE_OFFSET), offset);
    }

    public String sqlProjection(String quotedColumn) {
        return "CONVERT_TZ(" + quotedColumn + ",'" + DATABASE_SQL_OFFSET + "','" + sqlOffset + "')";
    }

}
