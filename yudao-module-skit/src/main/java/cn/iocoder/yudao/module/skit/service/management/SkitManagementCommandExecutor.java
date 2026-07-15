package cn.iocoder.yudao.module.skit.service.management;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.management.SkitManagementCommandAuditDO;
import cn.iocoder.yudao.module.skit.dal.mysql.management.SkitManagementCommandAuditMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScope;
import cn.iocoder.yudao.module.skit.framework.security.SkitManagementAccessMode;
import cn.iocoder.yudao.module.skit.framework.security.SkitManagementCommandType;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_COMMAND_FORBIDDEN;

/** Runs one whitelisted domain mutation and appends its success audit in the same transaction. */
@Service
public class SkitManagementCommandExecutor {

    private final SkitManagementCommandAuditMapper auditMapper;
    private final Clock clock;
    private final Supplier<String> commandIdSupplier;

    @Autowired
    public SkitManagementCommandExecutor(SkitManagementCommandAuditMapper auditMapper) {
        this(auditMapper, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    SkitManagementCommandExecutor(SkitManagementCommandAuditMapper auditMapper, Clock clock,
                                  Supplier<String> commandIdSupplier) {
        this.auditMapper = Objects.requireNonNull(auditMapper, "auditMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.commandIdSupplier = Objects.requireNonNull(commandIdSupplier, "commandIdSupplier");
    }

    @Transactional(rollbackFor = Exception.class)
    public <T> T execute(SkitAdminTenantScope scope, SkitManagementCommandType commandType,
                         String resourceType, String resourceId, String reason,
                         String beforeCanonicalState, String requestCanonicalState,
                         Supplier<CommandResult<T>> mutation) {
        validateExecutionScope(scope);
        validateAuthorizedCommand(scope, commandType);
        requireBounded(resourceType, "resourceType", 1, 64);
        requireBounded(resourceId, "resourceId", 1, 128);
        requireBounded(reason, "reason", 10, 500);
        Objects.requireNonNull(mutation, "mutation");

        CommandResult<T> result = Objects.requireNonNull(mutation.get(), "mutation result");
        SkitManagementCommandAuditDO row = new SkitManagementCommandAuditDO()
                .setTenantId(scope.getTargetTenantId())
                .setCommandId(requireCommandId(commandIdSupplier.get()))
                .setOperatorUserId(scope.getOperatorUserId())
                .setOriginalTenantId(scope.getOriginalTenantId())
                .setTargetTenantId(scope.getTargetTenantId())
                .setCommandType(commandType.name())
                .setResourceType(resourceType.trim())
                .setResourceId(resourceId.trim())
                .setReason(reason.trim())
                .setBeforeStateHash(hash(beforeCanonicalState))
                .setAfterStateHash(hash(result.afterCanonicalState))
                .setRequestFingerprint(hash(requestCanonicalState))
                .setTraceId(normalizeTraceId(MDC.get("traceId")))
                .setResultStatus("SUCCESS")
                .setCreatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        if (auditMapper.insertSuccess(row) != 1 || row.getId() == null || row.getId() <= 0) {
            throw new IllegalStateException("Management command audit was not inserted exactly once");
        }
        return result.value;
    }

    private void validateExecutionScope(SkitAdminTenantScope scope) {
        Objects.requireNonNull(scope, "scope");
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        if (!tenantId.equals(scope.getTargetTenantId())
                || scope.getAccessMode() != SkitManagementAccessMode.OPERATIONAL_WRITE) {
            throw new IllegalStateException("Management command scope does not match the active tenant write context");
        }
    }

    private void validateAuthorizedCommand(SkitAdminTenantScope scope,
                                           SkitManagementCommandType commandType) {
        if (commandType == null || scope.getAuthorizedCommandType() != commandType
                || (scope.isPlatformAdmin()
                ? !commandType.isPlatformAdminAllowed() : !commandType.isTenantAdminAllowed())) {
            throw exception(MANAGEMENT_COMMAND_FORBIDDEN);
        }
    }

    private String requireCommandId(String commandId) {
        requireBounded(commandId, "commandId", 16, 64);
        return commandId.trim();
    }

    private void requireBounded(String value, String field, int min, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < min || normalized.length() > max) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
    }

    private byte[] hash(String canonicalState) {
        String normalized = canonicalState == null ? "<none>" : canonicalState;
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String normalizeTraceId(String traceId) {
        if (traceId == null || traceId.trim().isEmpty()) {
            return "";
        }
        String normalized = traceId.trim();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    public static final class CommandResult<T> {
        private final T value;
        private final String afterCanonicalState;

        public CommandResult(T value, String afterCanonicalState) {
            this.value = value;
            this.afterCanonicalState = afterCanonicalState;
        }
    }

}
