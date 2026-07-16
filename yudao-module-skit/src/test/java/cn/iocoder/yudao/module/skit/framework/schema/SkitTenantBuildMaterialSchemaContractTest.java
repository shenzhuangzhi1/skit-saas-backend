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

class SkitTenantBuildMaterialSchemaContractTest {

    @Test
    void additiveMigrationDeclaresTenantScopedVersionedMaterial() throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(mock(JdbcTemplate.class));
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SkitSchemaInitializer.Migration> migrations =
                (List<SkitSchemaInitializer.Migration>) field.get(initializer);
        SkitSchemaInitializer.Migration migration = migrations.stream()
                .filter(item -> item.getVersion() == 2026071701)
                .findFirst().orElse(null);

        assertNotNull(migration);
        assertEquals("add tenant app build material versions", migration.getDescription());
        String manifest = String.join("\n", migration.getManifest());
        assertContains(manifest, "skit_app_build_material", "tenant_id", "material_version",
                "uk_skit_app_build_material_version", "uk_skit_app_build_material_active",
                "ck_skit_app_build_material_secret");
    }

    @Test
    void bothBootstrapScriptsContainTheBuildMaterialTable() throws Exception {
        Path root = repositoryRoot();
        String standalone = new String(Files.readAllBytes(
                root.resolve("sql/mysql/skit-saas.sql")), StandardCharsets.UTF_8);
        String main = new String(Files.readAllBytes(
                root.resolve("sql/mysql/ruoyi-vue-pro.sql")), StandardCharsets.UTF_8);
        assertContains(standalone, "CREATE TABLE IF NOT EXISTS `skit_app_build_material`",
                "`uk_skit_app_build_material_version`", "`uk_skit_app_build_material_active`");
        assertContains(main, "CREATE TABLE IF NOT EXISTS `skit_app_build_material`",
                "`uk_skit_app_build_material_version`", "`uk_skit_app_build_material_active`");
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
