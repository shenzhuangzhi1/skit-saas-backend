package cn.iocoder.yudao.module.skit.framework.schema;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SkitProviderImpressionPhase1SchemaContractTest {

    private static final int MIGRATION_VERSION = 2026080201;
    private static final String PUBLISHED_PANGLE_MIGRATION_CHECKSUM =
            "93e3fe6c770c91e5120a81cba4578fb2315009e092ddfc909d0c14ea8e680d3c";
    private static final String CANONICAL_BEGIN = "-- SKIT_CANONICAL_SCHEMA_BEGIN";
    private static final String CANONICAL_END = "-- SKIT_CANONICAL_SCHEMA_END";
    private static final List<String> TABLES = Arrays.asList(
            "skit_ad_provider_connection",
            "skit_ad_provider_callback_route",
            "skit_ad_callback_route_registry",
            "skit_ad_callback_route_registry_migration",
            "skit_provider_impression_inbox",
            "skit_provider_callback_attempt",
            "skit_platform_provider_command_audit");

    @Test
    void providerPhase1MigrationIsAdditiveAndKeepsPublishedBaselineChecksum() throws Exception {
        SkitSchemaInitializer.Migration migration = migration(MIGRATION_VERSION);

        assertNotNull(migration, "account-level capture requires an independent additive migration");
        assertEquals("add account-level Taku impression capture", migration.getDescription());
        assertEquals(PUBLISHED_PANGLE_MIGRATION_CHECKSUM, migration(2026073001).getChecksum());
        for (Method method : SkitAdSchemaDdl.class.getDeclaredMethods()) {
            assertFalse(method.getName().contains("providerImpression"),
                    "the published SkitAdSchemaDdl baseline must remain byte-stable");
        }
        assertTrue(migration.getManifest().get(migration.getManifest().size() - 1)
                        .contains("validate-provider-impression-phase1-schema"),
                "the migration ledger must be appended only after physical-shape validation");
    }

    @Test
    void packageSafeSchemaStepsDeclareSevenGlobalTablesAndRequiredInvariants() {
        List<SkitProviderImpressionPhase1Schema.Step> steps =
                SkitProviderImpressionPhase1Schema.steps();
        String manifest = steps.stream().map(SkitProviderImpressionPhase1Schema.Step::getManifestEntry)
                .collect(Collectors.joining("\n"));
        String connection = tableSql(steps, "skit_ad_provider_connection");
        String route = tableSql(steps, "skit_ad_provider_callback_route");
        String registry = tableSql(steps, "skit_ad_callback_route_registry");
        String migrationState = tableSql(steps, "skit_ad_callback_route_registry_migration");
        String inbox = tableSql(steps, "skit_provider_impression_inbox");
        String attempt = tableSql(steps, "skit_provider_callback_attempt");

        for (String table : TABLES) {
            assertTrue(manifest.contains("CREATE TABLE IF NOT EXISTS `" + table + "`"),
                    "missing table " + table);
        }
        assertEquals(TABLES.size(), steps.stream()
                .filter(step -> step.getManifestEntry().startsWith("create-table:"))
                .count(), "only the seven brand-new tables may use CREATE TABLE IF NOT EXISTS");
        assertFalse(manifest.contains("`tenant_id`"),
                "phase-1 capture tables are global; original_login_tenant_id is audit evidence only");
        assertContains(manifest,
                "uk_provider_connection_shared_master",
                "ck_provider_connection_owner",
                "ck_provider_connection_state",
                "fk_provider_connection_active_route",
                "trg_provider_connection_lifecycle_immutable",
                "uk_provider_callback_route_slot",
                "FOREIGN KEY (`provider_connection_id`,`supersedes_callback_route_id`) "
                        + "REFERENCES `skit_ad_provider_callback_route` (`provider_connection_id`,`id`)",
                "ck_provider_callback_route_state",
                "ck_provider_callback_route_purpose",
                "fk_provider_callback_route_registry",
                "trg_provider_callback_route_lifecycle_immutable",
                "uk_callback_route_registry_key_hash",
                "ck_callback_route_registry_route_xor",
                "trg_callback_route_registry_immutable",
                "trg_callback_route_registry_no_delete",
                "ck_callback_route_registry_migration_singleton",
                "trg_callback_route_registry_migration_monotonic",
                "uk_provider_impression_inbox_dedupe",
                "dedupe_key_hash` binary(32) NOT NULL",
                "fk_provider_impression_inbox_canonical_attempt",
                "fk_provider_callback_attempt_connection",
                "fk_provider_callback_attempt_inbox",
                "payload_nonce` binary(12) DEFAULT NULL",
                "trg_provider_impression_inbox_monotonic",
                "trg_provider_callback_attempt_immutable",
                "trg_provider_callback_attempt_no_delete",
                "trg_platform_provider_command_audit_immutable",
                "trg_platform_provider_command_audit_no_delete");
        assertContains(manifest, "'GATE_TEST','PRODUCTION'", "original_login_tenant_id",
                "actor_user_id", "reauthenticated_at", "trace_id", "result_status");
        assertContains(connection, "external_account_ref_hash", "active_callback_route_id",
                "activated_at", "blocked_at", "retired_at", "ck_provider_connection_lifecycle");
        assertFalse(route.contains("`key_hash`"),
                "the route must never duplicate the registry's full irreversible hash");
        assertContains(route, "callback_route_registry_id", "callback_key_fingerprint",
                "canonical_origin", "callback_path_version", "callback_template_version",
                "callback_origin_fingerprint", "callback_contract_fingerprint",
                "supersedes_callback_route_id", "submission_ticket", "submission_reference",
                "submission_recipient", "submitted_by_user_id", "ck_provider_callback_route_lifecycle");
        assertContains(registry, "`key_hash` binary(32) NOT NULL", "`route_type`",
                "`tombstoned_at`", "ck_callback_route_registry_route_type");
        assertFalse(registry.contains("`owner_type`"));
        assertContains(migrationState, "'DUAL_WRITE','BACKFILL','VERIFY','SHADOW_READ','HASH_FIRST','ENFORCED'",
                "blocked_reason_hash", "blocked_at", "phase_revision", "credential_mutation_epoch",
                "verification_run_id", "verification_snapshot_epoch",
                "verification_cursor_callback_key_id", "verification_expected_progress_count",
                "verification_actual_progress_count", "verification_progress_mismatch_count",
                "verification_expected_rolling_hash", "verification_actual_rolling_hash",
                "ck_callback_route_registry_migration_verification_progress", "expected_row_count",
                "verified_row_count", "verification_mismatch_count", "verification_hash",
                "verified_at", "ck_callback_route_registry_migration_blocked");
        assertFalse(migrationState.contains("'PENDING','RUNNING','COMPLETED','BLOCKED'"));
        assertFalse(migrationState.contains("'BLOCKED'"),
                "blocking is recoverable metadata on the current exact cutover phase");
        assertContains(inbox, "'OFFICIAL_V1','FALLBACK_WIRE_V1'",
                "provider_request_id_lexical", "adsource_id_lexical", "material_integrity_hash",
                "UNSIGNED_PROVIDER_OBSERVATION", "integrity_status", "integrity_revision",
                "integrity_conflict_at", "processing_status", "lease_owner", "lease_until",
                "processing_attempt_count", "next_attempt_at", "processed_at",
                "dead_letter_alerted_at", "uk_provider_impression_inbox_connection_id");
        assertFalse(inbox.contains("`delivery_count`"),
                "Attempt rows are the only durable delivery ledger");
        assertFalse(inbox.contains("TAKU_REQ_ADSOURCE"));
        assertFalse(inbox.contains("WIRE_HASH_FALLBACK"));
        assertContains(attempt, "`dedupe_scheme` varchar(32) NOT NULL",
                "material_integrity_hash", "delivery_integrity_status",
                "response_decision", "remote_address_hash", "user_agent_hash",
                "request_header_fingerprint", "trace_id", "payload_purged_at",
                "uk_provider_callback_attempt_connection_inbox_id",
                "FOREIGN KEY (`provider_connection_id`,`inbox_id`,`dedupe_scheme`) "
                        + "REFERENCES `skit_provider_impression_inbox` "
                        + "(`provider_connection_id`,`id`,`dedupe_scheme`)",
                "`dedupe_scheme`='OFFICIAL_V1'",
                "`dedupe_scheme`='FALLBACK_WIRE_V1'",
                "ck_provider_callback_attempt_payload_retention");
        assertContains(manifest,
                "OLD.`payload_expires_at`<=NEW.`payload_purged_at`",
                "NEW.`payload_purged_at`<=UTC_TIMESTAMP()",
                "`processing_status` IN ('SUCCEEDED','QUARANTINED')",
                "`dead_letter_alerted_at` IS NOT NULL");
        for (String prohibited : Arrays.asList("command_body", "callback_url", "raw_query",
                "plaintext", "provider_key", "secret_value")) {
            assertFalse(manifest.contains("`" + prohibited + "`"),
                    "forbidden provider material column: " + prohibited);
        }
    }

    @Test
    void canonicalSqlFilesKeepOneByteIdenticalSkitBlockWithSevenGlobalTables() throws Exception {
        Path repository = repositoryRoot();
        String standalone = canonicalBlock(repository.resolve("sql/mysql/skit-saas.sql"));
        String main = canonicalBlock(repository.resolve("sql/mysql/ruoyi-vue-pro.sql"));

        assertEquals(standalone, main,
                "both canonical SQL files must carry one byte-identical Skit block");
        for (String table : TABLES) {
            assertTrue(standalone.contains("CREATE TABLE IF NOT EXISTS `" + table + "`"),
                    "canonical SQL is missing " + table);
        }
        assertContains(standalone,
                "`external_account_ref_hash` binary(32) NOT NULL",
                "`callback_route_registry_id` bigint DEFAULT NULL",
                "`callback_key_fingerprint` char(16)",
                "`callback_path_version` smallint DEFAULT NULL",
                "`callback_contract_fingerprint` binary(32) DEFAULT NULL",
                "`route_type` varchar(32) NOT NULL",
                "'DUAL_WRITE','BACKFILL','VERIFY','SHADOW_READ','HASH_FIRST','ENFORCED'",
                "`credential_mutation_epoch` bigint NOT NULL DEFAULT 0",
                "`verification_run_id` bigint NOT NULL DEFAULT 0",
                "`verification_expected_rolling_hash` binary(32) DEFAULT NULL",
                "`dedupe_scheme` IN ('OFFICIAL_V1','FALLBACK_WIRE_V1')",
                "`authentication_level`='UNSIGNED_PROVIDER_OBSERVATION'",
                "`payload_purged_at` datetime DEFAULT NULL",
                "fk_provider_impression_inbox_canonical_attempt",
                "trg_provider_connection_lifecycle_immutable",
                "trg_callback_route_registry_immutable",
                "trg_provider_callback_attempt_immutable");
        assertFalse(standalone.contains("`delivery_count`"));
        assertFalse(standalone.contains("`owner_type` varchar(32) NOT NULL"));
        assertTrue(standalone.contains("-- Skit 阶段 1 全局 provider callback capture 表（7 张）"));
    }

    @Test
    void canonicalMigrationStateMatchesJavaTableAndTriggerExactly() throws Exception {
        List<SkitProviderImpressionPhase1Schema.Step> steps =
                SkitProviderImpressionPhase1Schema.steps();
        String migrationTable = tableSql(steps,
                "skit_ad_callback_route_registry_migration");
        SkitProviderImpressionPhase1Schema.Step trigger = steps.stream()
                .filter(step -> "trg_callback_route_registry_migration_monotonic"
                        .equals(step.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("missing migration trigger"));
        String canonical = canonicalBlock(
                repositoryRoot().resolve("sql/mysql/skit-saas.sql"));

        assertContains(trigger.getAction(),
                "OLD.`blocked_at` IS NOT NULL AND NOT ("
                        + "NEW.`blocked_at` <=> OLD.`blocked_at` AND "
                        + "NEW.`blocked_reason_hash` <=> OLD.`blocked_reason_hash`)");
        assertTrue(canonical.contains(migrationTable + "$$"),
                "canonical migration table must exactly match the Java migration table");
        String canonicalTrigger = "CREATE TRIGGER `" + trigger.getName() + "` BEFORE "
                + trigger.getEvent() + " ON `" + trigger.getTable() + "` FOR EACH ROW "
                + trigger.getAction() + "$$";
        assertTrue(canonical.contains(canonicalTrigger),
                "canonical migration trigger must exactly match the Java trigger action");
    }

    private static SkitSchemaInitializer.Migration migration(int version) throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(mock(JdbcTemplate.class));
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SkitSchemaInitializer.Migration> migrations =
                (List<SkitSchemaInitializer.Migration>) field.get(initializer);
        return migrations.stream().filter(item -> item.getVersion() == version)
                .findFirst().orElse(null);
    }

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("sql/mysql/skit-saas.sql"))) {
            current = current.getParent();
        }
        assertNotNull(current, "repository root containing canonical SQL");
        return current;
    }

    private static String canonicalBlock(Path path) throws Exception {
        String sql = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        int begin = sql.indexOf(CANONICAL_BEGIN);
        int end = sql.indexOf(CANONICAL_END);
        assertTrue(begin >= 0 && end > begin, "missing canonical block in " + path);
        return sql.substring(begin, end + CANONICAL_END.length());
    }

    private static String tableSql(List<SkitProviderImpressionPhase1Schema.Step> steps,
                                   String table) {
        String marker = "CREATE TABLE IF NOT EXISTS `" + table + "`";
        return steps.stream().map(SkitProviderImpressionPhase1Schema.Step::getSql)
                .filter(sql -> sql != null && sql.startsWith(marker))
                .findFirst().orElseThrow(() -> new AssertionError("missing table SQL " + table));
    }

    private static void assertContains(String value, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(value.contains(fragment), "missing fragment: " + fragment);
        }
    }

}
