package cn.iocoder.yudao.module.skit.service.member;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_CURSOR_INVALID;

/**
 * Opaque pagination token. Tenant and parent values are bindings only: the guarded login scope
 * remains the authority and every SQL statement still carries an explicit tenant predicate.
 */
@Component
public class SkitMemberTreeCursorCodec {

    private static final String VERSION = "v1";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public String encode(long tenantId, long parentId, LocalDateTime asOf,
                         LocalDateTime lastCreatedAt, long lastId) {
        validatePositive(tenantId);
        validatePositive(parentId);
        validatePositive(lastId);
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(lastCreatedAt, "lastCreatedAt");
        if (lastCreatedAt.isAfter(asOf)) {
            throw exception(MANAGEMENT_CURSOR_INVALID);
        }
        String value = String.join("|", VERSION, Long.toString(tenantId),
                Long.toString(parentId), FORMATTER.format(asOf),
                FORMATTER.format(lastCreatedAt), Long.toString(lastId));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public Cursor decode(String encoded, long expectedTenantId, long expectedParentId) {
        validatePositive(expectedTenantId);
        validatePositive(expectedParentId);
        try {
            if (encoded == null || encoded.trim().isEmpty() || encoded.length() > 1024) {
                throw exception(MANAGEMENT_CURSOR_INVALID);
            }
            String decoded = new String(Base64.getUrlDecoder().decode(encoded.trim()),
                    StandardCharsets.UTF_8);
            String[] values = decoded.split("\\|", -1);
            if (values.length != 6 || !VERSION.equals(values[0])) {
                throw exception(MANAGEMENT_CURSOR_INVALID);
            }
            long tenantId = Long.parseLong(values[1]);
            long parentId = Long.parseLong(values[2]);
            LocalDateTime asOf = LocalDateTime.parse(values[3], FORMATTER);
            LocalDateTime lastCreatedAt = LocalDateTime.parse(values[4], FORMATTER);
            long lastId = Long.parseLong(values[5]);
            if (tenantId != expectedTenantId || parentId != expectedParentId || lastId <= 0L
                    || lastCreatedAt.isAfter(asOf)) {
                throw exception(MANAGEMENT_CURSOR_INVALID);
            }
            return new Cursor(asOf, lastCreatedAt, lastId);
        } catch (RuntimeException ignored) {
            throw exception(MANAGEMENT_CURSOR_INVALID);
        }
    }

    private void validatePositive(long value) {
        if (value <= 0L) {
            throw exception(MANAGEMENT_CURSOR_INVALID);
        }
    }

    public static final class Cursor {
        private final LocalDateTime asOf;
        private final LocalDateTime lastCreatedAt;
        private final long lastId;

        private Cursor(LocalDateTime asOf, LocalDateTime lastCreatedAt, long lastId) {
            this.asOf = asOf;
            this.lastCreatedAt = lastCreatedAt;
            this.lastId = lastId;
        }

        public LocalDateTime getAsOf() {
            return asOf;
        }

        public LocalDateTime getLastCreatedAt() {
            return lastCreatedAt;
        }

        public long getLastId() {
            return lastId;
        }
    }
}
