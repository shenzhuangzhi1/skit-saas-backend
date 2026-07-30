package cn.iocoder.yudao.module.skit.framework.schema;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SkitPangleRewardCallbackSchemaContractTest {

    private static final int MIGRATION_VERSION = 2026073001;

    @Test
    void declaresAdditiveTenantBoundPangleRewardAttestationMigration() throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(mock(JdbcTemplate.class));
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SkitSchemaInitializer.Migration> migrations =
                (List<SkitSchemaInitializer.Migration>) field.get(initializer);
        SkitSchemaInitializer.Migration migration = migrations.stream()
                .filter(item -> item.getVersion() == MIGRATION_VERSION)
                .findFirst().orElse(null);

        assertNotNull(migration, "Pangle callbacks require a new additive migration");
        assertEquals("add tenant-bound Pangle reward attestations", migration.getDescription());
        String manifest = String.join("\n", migration.getManifest());
        assertContains(manifest,
                "skit_ad_session", "pangle_ad_account_id", "pangle_reward_secret_version",
                "pangle_reward_placement_id", "skit_pangle_reward_attestation",
                "uk_skit_ad_session_account_binding",
                "uk_skit_pangle_attestation_transaction",
                "uk_skit_pangle_attestation_session",
                "fk_skit_pangle_attestation_taku_account",
                "fk_skit_pangle_attestation_pangle_account",
                "fk_skit_pangle_attestation_session",
                "fk_skit_pangle_attestation_callback_key",
                "fk_skit_pangle_attestation_reward_secret",
                "ck_skit_pangle_attestation_provider",
                "provider_transaction_id` varchar(1024) CHARACTER SET ascii COLLATE ascii_bin",
                "provider_user_id` varchar(1024)",
                "reward_name` varchar(1024)",
                "trg_skit_pangle_attestation_immutable",
                "trg_skit_pangle_attestation_no_delete");
        assertFalse(manifest.contains("uk_skit_ad_session_tenant_id_account"),
                "Task 7 already provides the exact tenant/id/account parent key");
        assertTrue(migration.getManifest().get(migration.getManifest().size() - 1)
                        .contains("validate-pangle-reward-callback-schema"),
                "the ledger must be appended only after a final physical-shape validation");
    }

    @Test
    void acceptsRestartAtEveryPangleMigrationBoundary() throws Exception {
        for (int completedArtifacts = 0; completedArtifacts <= 12; completedArtifacts++) {
            PangleSchemaJdbcTemplate schema = PangleSchemaJdbcTemplate.atBoundary(completedArtifacts);
            invokeValidator(new SkitSchemaInitializer(schema),
                    "validatePangleRewardCallbackMigrationPrefix");
        }
    }

    @Test
    void acceptsExactCanonicalBootstrapSuccessorBeforePangleArtifacts() {
        PangleSchemaJdbcTemplate schema = PangleSchemaJdbcTemplate.atBoundary(0);
        schema.triggers.put("trg_skit_callback_inbox_monotonic",
                PangleSchemaJdbcTemplate.pangleCallbackInboxTrigger());

        assertDoesNotThrow(() -> invokeValidator(new SkitSchemaInitializer(schema),
                "validatePangleRewardCallbackMigrationPrefix"));
    }

    @Test
    void rejectsOutOfOrderSuccessorAfterPangleMigrationHasPartiallyStarted() {
        PangleSchemaJdbcTemplate schema = PangleSchemaJdbcTemplate.atBoundary(1);
        schema.triggers.put("trg_skit_callback_inbox_monotonic",
                PangleSchemaJdbcTemplate.pangleCallbackInboxTrigger());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> invokeValidator(new SkitSchemaInitializer(schema),
                        "validatePangleRewardCallbackMigrationPrefix"));

        assertTrue(exception.getMessage().contains("prefix is out of order"));
    }

    @Test
    void replacesInboxMonotonicTriggerWithOnlyThePanglePendingWakeException() throws Exception {
        SkitSchemaInitializer.Migration migration = pangleMigration();
        String manifest = String.join("\n", migration.getManifest());
        String action = callbackInboxAction("pangleCallbackInboxMonotonicAction");

        assertContains(manifest, "DROP TRIGGER IF EXISTS `trg_skit_callback_inbox_monotonic`");
        assertContains(action,
                "OLD.`processing_status`='RETRY_WAIT'",
                "OLD.`error_code`='PANGLE_ATTESTATION_PENDING'",
                "NEW.`processing_status`='PENDING'",
                "NEW.`processing_attempt_count`=OLD.`processing_attempt_count`",
                "NEW.`error_code` IS NULL",
                "NEW.`next_attempt_at` IS NULL");
        assertEquals(1, occurrences(action, "NEW.`processing_status`='PENDING'"),
                "no other terminal or retry state may be moved backwards to PENDING");
        assertTrue(action.indexOf("OLD.`error_code`='PANGLE_ATTESTATION_PENDING'")
                        < action.indexOf("NEW.`processing_status`='PENDING'"),
                "the exact pending reason must guard the only wake transition");
    }

    @Test
    void task7ValidatorAcceptsOnlyLegacyAndExactPangleMonotonicSuccessor() {
        SkitSchemaInitializer initializer =
                new SkitSchemaInitializer(PangleSchemaJdbcTemplate.atBoundary(12));
        String legacy = PangleSchemaJdbcTemplate.legacyCallbackInboxTrigger();
        String successor = PangleSchemaJdbcTemplate.pangleCallbackInboxTrigger();

        assertDoesNotThrow(() -> invokeTask7TriggerValidator(initializer,
                "trg_skit_callback_inbox_monotonic", legacy,
                Collections.singletonList(legacy)));
        assertDoesNotThrow(() -> invokeTask7TriggerValidator(initializer,
                "trg_skit_callback_inbox_monotonic", legacy,
                Collections.singletonList(successor)));
        assertThrows(IllegalStateException.class, () -> invokeTask7TriggerValidator(initializer,
                "trg_skit_callback_inbox_monotonic", legacy,
                Collections.singletonList(successor.replace(
                        "OLD.`error_code`='PANGLE_ATTESTATION_PENDING'",
                        "OLD.`error_code` IS NOT NULL"))));
        assertThrows(IllegalStateException.class, () -> invokeTask7TriggerValidator(initializer,
                "trg_other", legacy,
                Collections.singletonList(successor)));
    }

    @Test
    void rejectsOutOfOrderPangleMigrationPrefix() {
        PangleSchemaJdbcTemplate schema = PangleSchemaJdbcTemplate.atBoundary(0);
        schema.indexes.put(key("skit_ad_session", "idx_skit_ad_session_pangle_credential"),
                "1:tenant_id,pangle_ad_account_id,pangle_reward_secret_version");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> invokeValidator(new SkitSchemaInitializer(schema),
                        "validatePangleRewardCallbackMigrationPrefix"));

        assertTrue(exception.getMessage().contains("prefix is out of order"));
    }

    @Test
    void finalPangleShapeRejectsColumnIndexForeignKeyCheckAndTriggerDrift() {
        assertDriftRejected("column", schema -> schema.columns.put(
                key("skit_pangle_reward_attestation", "provider_transaction_id"),
                column("varchar(128)", false, null)));
        assertDriftRejected("index", schema -> schema.indexes.put(
                key("skit_pangle_reward_attestation", "uk_skit_pangle_attestation_transaction"),
                "0:tenant_id,provider_transaction_id"));
        assertDriftRejected("foreign key", schema -> schema.foreignKeys.put(
                key("skit_pangle_reward_attestation", "fk_skit_pangle_attestation_session"),
                "tenant_id,ad_session_id->skit_ad_session(tenant_id,id):RESTRICT:RESTRICT"));
        assertDriftRejected("check", schema -> schema.checks.put(
                key("skit_pangle_reward_attestation", "ck_skit_pangle_attestation_reward"),
                "`reward_amount`>=0"));
        assertDriftRejected("trigger", schema -> schema.triggers.put(
                "trg_skit_pangle_attestation_no_delete",
                "BEFORE:DELETE:skit_pangle_reward_attestation:BEGIN END"));
        assertDriftRejected("collation", schema -> schema.textDefinitions.put(
                key("skit_pangle_reward_attestation", "provider_transaction_id"),
                "utf8mb4:utf8mb4_unicode_ci"));
    }

    @Test
    void finalPangleShapeRejectsMissingCallbackInboxMonotonicTrigger() {
        PangleSchemaJdbcTemplate schema = PangleSchemaJdbcTemplate.atBoundary(12);
        schema.triggers.remove("trg_skit_callback_inbox_monotonic");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> invokeValidator(new SkitSchemaInitializer(schema),
                        "validatePangleRewardCallbackSchema", boolean.class, true));

        assertTrue(exception.getMessage().contains("trg_skit_callback_inbox_monotonic"));
    }

    @Test
    void finalPangleShapeRejectsLegacyCallbackInboxMonotonicTrigger() {
        PangleSchemaJdbcTemplate schema = PangleSchemaJdbcTemplate.atBoundary(12);
        schema.triggers.put("trg_skit_callback_inbox_monotonic",
                PangleSchemaJdbcTemplate.legacyCallbackInboxTrigger());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> invokeValidator(new SkitSchemaInitializer(schema),
                        "validatePangleRewardCallbackSchema", boolean.class, true));

        assertTrue(exception.getMessage().contains("trg_skit_callback_inbox_monotonic"));
    }

    private static void assertDriftRejected(String artifact, SchemaMutation mutation) {
        PangleSchemaJdbcTemplate schema = PangleSchemaJdbcTemplate.atBoundary(12);
        mutation.apply(schema);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> invokeValidator(new SkitSchemaInitializer(schema),
                        "validatePangleRewardCallbackSchema", boolean.class, true));

        assertTrue(exception.getMessage().toLowerCase().contains(artifact),
                () -> "expected " + artifact + " drift rejection, got: " + exception.getMessage());
    }

    private static void invokeValidator(SkitSchemaInitializer initializer, String methodName,
                                        Object... parameterTypesAndArguments) throws Exception {
        Class<?>[] parameterTypes;
        Object[] arguments;
        if (parameterTypesAndArguments.length == 0) {
            parameterTypes = new Class<?>[0];
            arguments = new Object[0];
        } else {
            parameterTypes = new Class<?>[]{(Class<?>) parameterTypesAndArguments[0]};
            arguments = new Object[]{parameterTypesAndArguments[1]};
        }
        Method validator = SkitSchemaInitializer.class.getDeclaredMethod(methodName, parameterTypes);
        validator.setAccessible(true);
        try {
            validator.invoke(initializer, arguments);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception) {
                throw (Exception) exception.getCause();
            }
            throw exception;
        }
    }

    private static void invokeTask7TriggerValidator(SkitSchemaInitializer initializer,
                                                    String triggerName,
                                                    String expectedDefinition,
                                                    List<String> existing) throws Exception {
        Method validator = SkitSchemaInitializer.class.getDeclaredMethod(
                "validateTask7TriggerDefinition", String.class, String.class, List.class);
        validator.setAccessible(true);
        try {
            validator.invoke(initializer, triggerName, expectedDefinition, existing);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception) {
                throw (Exception) exception.getCause();
            }
            throw exception;
        }
    }

    private static String key(String table, String artifact) {
        return table + "." + artifact;
    }

    private static int occurrences(String value, String fragment) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(fragment, offset)) >= 0) {
            count++;
            offset += fragment.length();
        }
        return count;
    }

    private static SkitSchemaInitializer.Migration pangleMigration() throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(mock(JdbcTemplate.class));
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SkitSchemaInitializer.Migration> migrations =
                (List<SkitSchemaInitializer.Migration>) field.get(initializer);
        return migrations.stream().filter(item -> item.getVersion() == MIGRATION_VERSION)
                .findFirst().orElseThrow(AssertionError::new);
    }

    private static String callbackInboxAction(String actionMethod) {
        try {
            Method action = SkitSchemaInitializer.class.getDeclaredMethod(actionMethod);
            action.setAccessible(true);
            return String.valueOf(action.invoke(null));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Map<String, Object> column(String type, boolean nullable, Object defaultValue) {
        Map<String, Object> result = new HashMap<>();
        result.put("COLUMN_TYPE", type);
        result.put("IS_NULLABLE", nullable ? "YES" : "NO");
        result.put("COLUMN_DEFAULT", defaultValue);
        result.put("EXTRA", "");
        result.put("GENERATION_EXPRESSION", null);
        return result;
    }

    @FunctionalInterface
    private interface SchemaMutation {

        void apply(PangleSchemaJdbcTemplate schema);

    }

    private static final class PangleSchemaJdbcTemplate extends JdbcTemplate {

        private final List<String> tables = new ArrayList<>();
        private final Map<String, Map<String, Object>> columns = new LinkedHashMap<>();
        private final Map<String, String> indexes = new LinkedHashMap<>();
        private final Map<String, String> foreignKeys = new LinkedHashMap<>();
        private final Map<String, String> checks = new LinkedHashMap<>();
        private final Map<String, String> triggers = new LinkedHashMap<>();
        private final Map<String, String> textDefinitions = new LinkedHashMap<>();

        static PangleSchemaJdbcTemplate atBoundary(int completedArtifacts) {
            PangleSchemaJdbcTemplate schema = new PangleSchemaJdbcTemplate();
            schema.tables.add("skit_ad_session");
            schema.indexes.put(key("skit_ad_session", "uk_skit_ad_session_account_binding"),
                    "0:tenant_id,id,ad_account_id");
            schema.triggers.put("trg_skit_callback_inbox_monotonic", legacyCallbackInboxTrigger());
            if (completedArtifacts >= 1) {
                schema.columns.put(key("skit_ad_session", "pangle_ad_account_id"),
                        column("bigint", true, null));
            }
            if (completedArtifacts >= 2) {
                schema.columns.put(key("skit_ad_session", "pangle_reward_secret_version"),
                        column("int", true, null));
            }
            if (completedArtifacts >= 3) {
                schema.columns.put(key("skit_ad_session", "pangle_reward_placement_id"),
                        column("varchar(128)", true, null));
            }
            if (completedArtifacts >= 4) {
                schema.indexes.put(key("skit_ad_session", "idx_skit_ad_session_pangle_credential"),
                        "1:tenant_id,pangle_ad_account_id,pangle_reward_secret_version");
            }
            if (completedArtifacts >= 5) {
                schema.foreignKeys.put(key("skit_ad_session", "fk_skit_ad_session_pangle_account"),
                        "tenant_id,pangle_ad_account_id->skit_ad_account(tenant_id,id):RESTRICT:RESTRICT");
            }
            if (completedArtifacts >= 6) {
                schema.foreignKeys.put(key("skit_ad_session", "fk_skit_ad_session_pangle_reward_secret"),
                        "tenant_id,pangle_ad_account_id,pangle_reward_secret_version"
                                + "->skit_ad_reward_secret_version"
                                + "(tenant_id,ad_account_id,secret_version):RESTRICT:RESTRICT");
            }
            if (completedArtifacts >= 7) {
                schema.checks.put(key("skit_ad_session", "ck_skit_ad_session_pangle_snapshot"),
                        "((`pangle_ad_account_id` IS NULL AND `pangle_reward_secret_version` IS NULL "
                                + "AND `pangle_reward_placement_id` IS NULL) OR "
                                + "(`pangle_ad_account_id` IS NOT NULL "
                                + "AND `pangle_reward_secret_version` IS NOT NULL "
                                + "AND `pangle_reward_secret_version`>0 "
                                + "AND `pangle_reward_placement_id` IS NOT NULL "
                                + "AND CHAR_LENGTH(`pangle_reward_placement_id`)>0))");
            }
            if (completedArtifacts >= 8) {
                schema.addAttestationTable();
            }
            if (completedArtifacts >= 9) {
                schema.triggers.put("trg_skit_pangle_attestation_immutable",
                        immutableTrigger("UPDATE"));
            }
            if (completedArtifacts >= 10) {
                schema.triggers.put("trg_skit_pangle_attestation_no_delete",
                        immutableTrigger("DELETE"));
            }
            if (completedArtifacts >= 11) {
                schema.triggers.remove("trg_skit_callback_inbox_monotonic");
            }
            if (completedArtifacts >= 12) {
                schema.triggers.put("trg_skit_callback_inbox_monotonic",
                        pangleCallbackInboxTrigger());
            }
            return schema;
        }

        private void addAttestationTable() {
            tables.add("skit_pangle_reward_attestation");
            addColumn("id", "bigint", false, null);
            addColumn("tenant_id", "bigint", false, null);
            addColumn("taku_ad_account_id", "bigint", false, null);
            addColumn("pangle_ad_account_id", "bigint", false, null);
            addColumn("ad_session_id", "bigint", false, null);
            addColumn("callback_key_version", "int", false, null);
            addColumn("pangle_reward_secret_version", "int", false, null);
            addColumn("pangle_reward_placement_id", "varchar(128)", false, null);
            addColumn("provider", "varchar(16)", false, null);
            addColumn("provider_transaction_id", "varchar(1024)", false, null);
            addColumn("provider_user_id", "varchar(1024)", false, null);
            addColumn("extra_data_hash", "binary(32)", false, null);
            addColumn("reward_name", "varchar(1024)", false, null);
            addColumn("reward_amount", "int", false, null);
            addColumn("canonical_payload_hash", "binary(32)", false, null);
            addColumn("credential_fingerprint", "binary(32)", false, null);
            addColumn("received_at", "datetime", false, null);
            addColumn("creator", "varchar(64)", true, "");
            addColumn("create_time", "datetime", false, "current_timestamp()");
            addColumn("updater", "varchar(64)", true, "");
            addColumn("update_time", "datetime", false, "CURRENT_TIMESTAMP");
            addColumn("deleted", "bit(1)", false, "b'0'");
            indexes.put(key("skit_pangle_reward_attestation", "PRIMARY"), "0:id");
            indexes.put(key("skit_pangle_reward_attestation",
                    "uk_skit_pangle_attestation_tenant_id"), "0:tenant_id,id");
            indexes.put(key("skit_pangle_reward_attestation",
                    "uk_skit_pangle_attestation_transaction"),
                    "0:tenant_id,pangle_ad_account_id,provider_transaction_id");
            indexes.put(key("skit_pangle_reward_attestation",
                    "uk_skit_pangle_attestation_session"), "0:tenant_id,ad_session_id");
            foreignKeys.put(key("skit_pangle_reward_attestation",
                    "fk_skit_pangle_attestation_taku_account"),
                    "tenant_id,taku_ad_account_id->skit_ad_account(tenant_id,id):RESTRICT:RESTRICT");
            foreignKeys.put(key("skit_pangle_reward_attestation",
                    "fk_skit_pangle_attestation_pangle_account"),
                    "tenant_id,pangle_ad_account_id->skit_ad_account(tenant_id,id):RESTRICT:RESTRICT");
            foreignKeys.put(key("skit_pangle_reward_attestation",
                    "fk_skit_pangle_attestation_session"),
                    "tenant_id,ad_session_id,taku_ad_account_id"
                            + "->skit_ad_session(tenant_id,id,ad_account_id):RESTRICT:RESTRICT");
            foreignKeys.put(key("skit_pangle_reward_attestation",
                    "fk_skit_pangle_attestation_callback_key"),
                    "tenant_id,taku_ad_account_id,callback_key_version"
                            + "->skit_ad_callback_key(tenant_id,ad_account_id,key_version):RESTRICT:RESTRICT");
            foreignKeys.put(key("skit_pangle_reward_attestation",
                    "fk_skit_pangle_attestation_reward_secret"),
                    "tenant_id,pangle_ad_account_id,pangle_reward_secret_version"
                            + "->skit_ad_reward_secret_version"
                            + "(tenant_id,ad_account_id,secret_version):RESTRICT:RESTRICT");
            checks.put(key("skit_pangle_reward_attestation",
                    "ck_skit_pangle_attestation_provider"), "`provider`='PANGLE'");
            checks.put(key("skit_pangle_reward_attestation",
                    "ck_skit_pangle_attestation_versions"),
                    "`callback_key_version`>0 AND `pangle_reward_secret_version`>0");
            checks.put(key("skit_pangle_reward_attestation",
                    "ck_skit_pangle_attestation_reward"), "`reward_amount`>0");
            textDefinitions.put(key("skit_pangle_reward_attestation", "provider_transaction_id"),
                    "ascii:ascii_bin");
        }

        private void addColumn(String name, String type, boolean nullable, Object defaultValue) {
            columns.put(key("skit_pangle_reward_attestation", name),
                    column(type, nullable, defaultValue));
        }

        private static String immutableTrigger(String event) {
            return "BEFORE:" + event + ":skit_pangle_reward_attestation:"
                    + "BEGIN SIGNAL SQLSTATE '45000' "
                    + "SET MESSAGE_TEXT='Pangle reward attestations are immutable'; END";
        }

        private static String legacyCallbackInboxTrigger() {
            return callbackInboxTrigger("callbackInboxMonotonicAction");
        }

        private static String pangleCallbackInboxTrigger() {
            return callbackInboxTrigger("pangleCallbackInboxMonotonicAction");
        }

        private static String callbackInboxTrigger(String actionMethod) {
            return "BEFORE:UPDATE:skit_ad_callback_inbox:" + callbackInboxAction(actionMethod);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            Object result;
            if (sql.contains("information_schema.TABLES")) {
                result = tables.contains(String.valueOf(args[0])) ? 1 : 0;
            } else if (sql.contains("SELECT COUNT(*) FROM information_schema.COLUMNS")) {
                result = columns.containsKey(key(String.valueOf(args[0]), String.valueOf(args[1]))) ? 1 : 0;
            } else if (sql.contains("CHARACTER_SET_NAME") && sql.contains("COLLATION_NAME")) {
                result = textDefinitions.get(key(String.valueOf(args[0]), String.valueOf(args[1])));
            } else if (sql.contains("information_schema.STATISTICS")) {
                result = indexes.get(key(String.valueOf(args[0]), String.valueOf(args[1])));
            } else if (sql.contains("information_schema.KEY_COLUMN_USAGE")) {
                result = foreignKeys.get(key(String.valueOf(args[0]), String.valueOf(args[1])));
            } else {
                throw new AssertionError("unsupported queryForObject SQL: " + sql);
            }
            return requiredType.cast(result);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (!sql.contains("information_schema.COLUMNS")) {
                throw new AssertionError("unsupported column query SQL: " + sql);
            }
            Map<String, Object> definition =
                    columns.get(key(String.valueOf(args[0]), String.valueOf(args[1])));
            return definition == null ? Collections.emptyList() : Collections.singletonList(definition);
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            String result;
            if (sql.contains("CHECK_CONSTRAINTS")) {
                result = checks.get(key(String.valueOf(args[0]), String.valueOf(args[1])));
            } else if (sql.contains("information_schema.TRIGGERS")) {
                result = triggers.get(String.valueOf(args[0]));
            } else {
                throw new AssertionError("unsupported typed query SQL: " + sql);
            }
            return result == null ? Collections.emptyList()
                    : Collections.singletonList(elementType.cast(result));
        }
    }

    private static void assertContains(String value, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(value.contains(fragment), () -> "missing '" + fragment + "' in " + value);
        }
    }

}
