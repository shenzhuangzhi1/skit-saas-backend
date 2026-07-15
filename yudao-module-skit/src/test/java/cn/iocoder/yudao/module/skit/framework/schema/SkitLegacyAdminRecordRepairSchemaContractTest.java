package cn.iocoder.yudao.module.skit.framework.schema;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SkitLegacyAdminRecordRepairSchemaContractTest {

    @Test
    void repairUsesANewAdditiveAuditedMigrationWithoutChangingReleasedTask2() throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(mock(JdbcTemplate.class));
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SkitSchemaInitializer.Migration> migrations =
                (List<SkitSchemaInitializer.Migration>) field.get(initializer);
        SkitSchemaInitializer.Migration task2 = migrations.stream()
                .filter(item -> item.getVersion() == 2026071401).findFirst().orElse(null);
        SkitSchemaInitializer.Migration repair = migrations.stream()
                .filter(item -> item.getVersion() == 2026071502).findFirst().orElse(null);

        assertNotNull(task2);
        assertEquals("64e450e4b8048a00b0ce7fbbe9f4b162ec519b5cd3f2c83d12470d92fe72fdbf",
                task2.getChecksum(), "released Task 2 checksum must remain immutable");
        assertNotNull(repair, "legacy repair must be a new additive migration");
        assertEquals("audit and normalize legacy admin record singleton keys", repair.getDescription());
        String manifest = String.join("\n", repair.getManifest());
        assertContains(manifest, "skit_admin_record_migration_audit",
                "rekey-legacy-admin-singletons-v1", "validate-legacy-admin-record-repair-schema");
    }

    @Test
    void bothBootstrapScriptsContainTheAdminRecordRepairAuditTable() throws Exception {
        Path root = repositoryRoot();
        String standalone = Files.readString(root.resolve("sql/mysql/skit-saas.sql"), StandardCharsets.UTF_8);
        String main = Files.readString(root.resolve("sql/mysql/ruoyi-vue-pro.sql"), StandardCharsets.UTF_8);
        String[] fragments = {"`skit_admin_record_migration_audit`",
                "`uk_skit_admin_record_migration_source`", "`original_row_key`", "`repaired_row_key`"};
        assertContains(standalone, fragments);
        assertContains(main, fragments);
    }

    private static Path repositoryRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("sql/mysql/skit-saas.sql"))) {
            candidate = candidate.getParent();
        }
        assertNotNull(candidate, "could not locate repository SQL bootstraps");
        return candidate;
    }

    private static void assertContains(String source, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(source.contains(fragment), () -> "missing '" + fragment + "'");
        }
    }

}
