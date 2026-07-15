package cn.iocoder.yudao.module.skit.service.management;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.skit.dal.dataobject.management.SkitManagementCommandAuditDO;
import cn.iocoder.yudao.module.skit.dal.mysql.management.SkitManagementCommandAuditMapper;
import cn.iocoder.yudao.module.skit.framework.security.SkitAdminTenantScope;
import cn.iocoder.yudao.module.skit.framework.security.SkitManagementAccessMode;
import cn.iocoder.yudao.module.skit.framework.security.SkitManagementCommandType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.skit.enums.ErrorCodeConstants.MANAGEMENT_COMMAND_FORBIDDEN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkitManagementCommandExecutorTest {

    private static final long TENANT_ID = 41L;
    private static final Instant NOW = Instant.parse("2026-07-15T01:00:00Z");

    @Mock private SkitManagementCommandAuditMapper auditMapper;
    @Mock private SkitAdminTenantScope scope;

    private SkitManagementCommandExecutor executor;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(TENANT_ID);
        executor = new SkitManagementCommandExecutor(auditMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "command-opaque-1");
        org.mockito.Mockito.lenient().when(scope.getOperatorUserId()).thenReturn(81L);
        org.mockito.Mockito.lenient().when(scope.getOriginalTenantId()).thenReturn(1L);
        org.mockito.Mockito.lenient().when(scope.getTargetTenantId()).thenReturn(TENANT_ID);
        org.mockito.Mockito.lenient().when(scope.getAccessMode())
                .thenReturn(SkitManagementAccessMode.OPERATIONAL_WRITE);
        org.mockito.Mockito.lenient().when(scope.getAuthorizedCommandType())
                .thenReturn(SkitManagementCommandType.MEMBER_STATUS_CHANGE);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void successfulMutationAppendsSecretFreeAuditAfterMutation() throws Exception {
        when(scope.getAuthorizedCommandType())
                .thenReturn(SkitManagementCommandType.COMMISSION_PLAN_PUBLISH);
        AtomicBoolean mutated = new AtomicBoolean(false);
        when(auditMapper.insertSuccess(any(SkitManagementCommandAuditDO.class))).thenAnswer(invocation -> {
            assertTrue(mutated.get(), "audit insert must happen after the domain mutation");
            invocation.<SkitManagementCommandAuditDO>getArgument(0).setId(91L);
            return 1;
        });

        String value = executor.execute(scope, SkitManagementCommandType.COMMISSION_PLAN_PUBLISH,
                "COMMISSION_PLAN", "plan:7", "publish verified allocation change",
                "before-version=6", "rules-hash-only", () -> {
                    mutated.set(true);
                    return new SkitManagementCommandExecutor.CommandResult<>("published", "after-version=7");
                });

        assertEquals("published", value);
        ArgumentCaptor<SkitManagementCommandAuditDO> captor =
                ArgumentCaptor.forClass(SkitManagementCommandAuditDO.class);
        verify(auditMapper).insertSuccess(captor.capture());
        SkitManagementCommandAuditDO row = captor.getValue();
        assertEquals(TENANT_ID, row.getTenantId());
        assertEquals("command-opaque-1", row.getCommandId());
        assertEquals(81L, row.getOperatorUserId());
        assertEquals(1L, row.getOriginalTenantId());
        assertEquals(TENANT_ID, row.getTargetTenantId());
        assertEquals("COMMISSION_PLAN_PUBLISH", row.getCommandType());
        assertEquals("COMMISSION_PLAN", row.getResourceType());
        assertEquals("plan:7", row.getResourceId());
        assertEquals("publish verified allocation change", row.getReason());
        assertEquals("SUCCESS", row.getResultStatus());
        assertEquals(NOW, row.getCreatedAt().toInstant(ZoneOffset.UTC));
        assertArrayEquals(sha256("before-version=6"), row.getBeforeStateHash());
        assertArrayEquals(sha256("after-version=7"), row.getAfterStateHash());
        assertArrayEquals(sha256("rules-hash-only"), row.getRequestFingerprint());
        assertFalse(row.toString().contains("before-version=6"));
        assertFalse(row.toString().contains("after-version=7"));
    }

    @Test
    void auditInsertFailureFailsTheCommandTransaction() {
        when(scope.getAuthorizedCommandType())
                .thenReturn(SkitManagementCommandType.REPORT_PULL_RETRY_SINGLE);
        when(auditMapper.insertSuccess(any(SkitManagementCommandAuditDO.class))).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> executor.execute(scope,
                SkitManagementCommandType.REPORT_PULL_RETRY_SINGLE,
                "REPORT_PULL", "91", "retry one transient report pull",
                "FAILED", "retry-request", () ->
                        new SkitManagementCommandExecutor.CommandResult<>(true, "RETRY_QUEUED")));
    }

    @Test
    void failedMutationDoesNotAppendFalseSuccessAudit() {
        assertThrows(IllegalStateException.class, () -> executor.execute(scope,
                SkitManagementCommandType.MEMBER_STATUS_CHANGE,
                "MEMBER", "51", "disable member after verified abuse",
                "ENABLED", "status=disabled", () -> {
                    throw new IllegalStateException("state conflict");
                }));

        verify(auditMapper, never()).insertSuccess(any());
    }

    @Test
    void executorRejectsWrongTenantOrNonWriteScopeBeforeMutation() {
        AtomicBoolean mutated = new AtomicBoolean(false);
        when(scope.getTargetTenantId()).thenReturn(42L);
        assertThrows(IllegalStateException.class, () -> executeNoop(mutated));
        assertFalse(mutated.get());

        when(scope.getTargetTenantId()).thenReturn(TENANT_ID);
        when(scope.getAccessMode()).thenReturn(SkitManagementAccessMode.ACTIVE_READ);
        assertThrows(IllegalStateException.class, () -> executeNoop(mutated));
        assertFalse(mutated.get());
        verify(auditMapper, never()).insertSuccess(any());
    }

    @Test
    void tenantAdminWriteScopeCannotBeReusedForPlatformOnlyCommand() {
        AtomicBoolean mutated = new AtomicBoolean(false);
        when(scope.getAuthorizedCommandType())
                .thenReturn(SkitManagementCommandType.MEMBER_STATUS_CHANGE);

        assertServiceException(() -> executor.execute(scope,
                        SkitManagementCommandType.CALLBACK_DEAD_LETTER_REPLAY,
                        "CALLBACK_INBOX", "91", "replay verified dead letter after incident",
                        "DEAD_LETTER", "single-replay-request", () -> {
                            mutated.set(true);
                            return new SkitManagementCommandExecutor.CommandResult<>(true, "REPLAY_QUEUED");
                        }),
                MANAGEMENT_COMMAND_FORBIDDEN);

        assertFalse(mutated.get(), "executor must reject before invoking the privileged mutation");
        verify(auditMapper, never()).insertSuccess(any());
    }

    @Test
    void executorAndAuditMapperExposeOnlyTransactionalAppendContract() throws Exception {
        Transactional transactional = SkitManagementCommandExecutor.class
                .getMethod("execute", SkitAdminTenantScope.class, SkitManagementCommandType.class,
                        String.class, String.class, String.class, String.class, String.class,
                        java.util.function.Supplier.class)
                .getAnnotation(Transactional.class);
        assertNotNull(transactional);
        assertTrue(transactional.rollbackFor().length > 0);

        List<String> mutatingMethods = Arrays.stream(SkitManagementCommandAuditMapper.class.getMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("update") || name.startsWith("delete"))
                .collect(Collectors.toList());
        assertTrue(mutatingMethods.isEmpty(), "audit facts must not expose update/delete: " + mutatingMethods);
    }

    private void executeNoop(AtomicBoolean mutated) {
        executor.execute(scope, SkitManagementCommandType.MEMBER_STATUS_CHANGE,
                "MEMBER", "51", "disable member after verified abuse",
                "ENABLED", "status=disabled", () -> {
                    mutated.set(true);
                    return new SkitManagementCommandExecutor.CommandResult<>(true, "DISABLED");
                });
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
    }
}
