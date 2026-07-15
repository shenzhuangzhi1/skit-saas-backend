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

class SkitTask17RuntimeUpdateSchemaContractTest {

    @Test
    void task17UsesTheAdditive1408SlotForSignedManifestState() throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(mock(JdbcTemplate.class));
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SkitSchemaInitializer.Migration> migrations =
                (List<SkitSchemaInitializer.Migration>) field.get(initializer);
        SkitSchemaInitializer.Migration task17 = migrations.stream()
                .filter(migration -> migration.getVersion() == 2026071408)
                .findFirst().orElse(null);

        assertNotNull(task17, "Task 17 signed manifests must use a new additive migration");
        assertEquals("add signed runtime update manifest state", task17.getDescription());
        String manifest = String.join("\n", task17.getManifest());
        assertContains(manifest, "hot_release_no", "hot_manifest_signature",
                "ck_skit_app_release_hot_release", "ck_skit_app_release_hot_signature",
                "validate-task17-runtime-update-schema");
    }

    @Test
    void bothBootstrapScriptsContainTheSameSignedManifestColumnsAndChecks() throws Exception {
        Path root = repositoryRoot();
        String standalone = new String(Files.readAllBytes(
                root.resolve("sql/mysql/skit-saas.sql")), StandardCharsets.UTF_8);
        String main = new String(Files.readAllBytes(
                root.resolve("sql/mysql/ruoyi-vue-pro.sql")), StandardCharsets.UTF_8);
        String[] fragments = {"`hot_release_no`", "`hot_manifest_signature`",
                "`ck_skit_app_release_hot_release`", "`ck_skit_app_release_hot_signature`"};
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
