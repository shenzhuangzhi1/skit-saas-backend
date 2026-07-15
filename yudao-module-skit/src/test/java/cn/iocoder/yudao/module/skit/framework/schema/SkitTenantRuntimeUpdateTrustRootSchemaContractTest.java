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

class SkitTenantRuntimeUpdateTrustRootSchemaContractTest {

    @Test
    void tenantTrustRootsUseANewAdditiveMigrationWithoutChangingTask17() throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(mock(JdbcTemplate.class));
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SkitSchemaInitializer.Migration> migrations =
                (List<SkitSchemaInitializer.Migration>) field.get(initializer);
        SkitSchemaInitializer.Migration migration = migrations.stream()
                .filter(item -> item.getVersion() == 2026071501)
                .findFirst().orElse(null);

        assertNotNull(migration, "tenant trust roots must use a new additive migration");
        assertEquals("add tenant runtime update trust roots", migration.getDescription());
        String manifest = String.join("\n", migration.getManifest());
        assertContains(manifest, "runtime_update_public_key", "runtime_update_key_fingerprint",
                "active_runtime_update_key_fingerprint", "uk_skit_app_release_runtime_key",
                "ck_skit_app_release_runtime_key", "validate-tenant-runtime-update-trust-root-schema");
    }

    @Test
    void bothBootstrapScriptsContainTheCompleteTenantTrustRootSchema() throws Exception {
        Path root = repositoryRoot();
        String standalone = new String(Files.readAllBytes(
                root.resolve("sql/mysql/skit-saas.sql")), StandardCharsets.UTF_8);
        String main = new String(Files.readAllBytes(
                root.resolve("sql/mysql/ruoyi-vue-pro.sql")), StandardCharsets.UTF_8);
        String[] fragments = {"`runtime_update_public_key`", "`runtime_update_key_fingerprint`",
                "`active_runtime_update_key_fingerprint`", "`uk_skit_app_release_runtime_key`",
                "`ck_skit_app_release_runtime_key`"};
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
