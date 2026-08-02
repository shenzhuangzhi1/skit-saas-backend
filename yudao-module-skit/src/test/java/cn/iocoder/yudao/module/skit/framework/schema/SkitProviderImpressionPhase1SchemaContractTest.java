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
                "uk_provider_callback_route_slot",
                "ck_provider_callback_route_state",
                "ck_provider_callback_route_purpose",
                "trg_provider_callback_route_purpose_immutable",
                "uk_callback_route_registry_key_hash",
                "ck_callback_route_registry_owner_xor",
                "ck_callback_route_registry_migration_singleton",
                "trg_callback_route_registry_migration_monotonic",
                "uk_provider_impression_inbox_dedupe",
                "dedupe_key_hash` binary(32) NOT NULL",
                "fk_provider_impression_inbox_canonical_attempt",
                "fk_provider_callback_attempt_connection",
                "fk_provider_callback_attempt_inbox",
                "payload_nonce` binary(12) NOT NULL",
                "trg_platform_provider_command_audit_immutable",
                "trg_platform_provider_command_audit_no_delete");
        assertContains(manifest, "'GATE_TEST','PRODUCTION'", "original_login_tenant_id",
                "actor_user_id", "reauthenticated_at", "trace_id", "result_status");
        assertFalse(manifest.contains("command_body"));
        assertFalse(manifest.contains("callback_url"));
        assertFalse(manifest.contains("raw_query"));
        assertFalse(manifest.contains("plaintext"));
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
        assertTrue(standalone.contains("-- Skit 阶段 1 全局 provider callback capture 表（7 张）"));
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

    private static void assertContains(String value, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(value.contains(fragment), "missing fragment: " + fragment);
        }
    }

}
