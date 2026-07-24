package cn.iocoder.yudao.module.skit.framework.schema;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SkitAdAccountAppKeyCapacitySchemaContractTest {

    private static final int AD_ACCOUNT_APP_KEY_CAPACITY_MIGRATION_VERSION = 2026072501;
    private static final String EXPANDED_APP_KEY_DEFINITION = "`app_key` varchar(1024) DEFAULT ''";

    @Test
    void encryptedAppKeysUseANewAdditiveCapacityMigration() throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(mock(JdbcTemplate.class));
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SkitSchemaInitializer.Migration> migrations =
                (List<SkitSchemaInitializer.Migration>) field.get(initializer);
        SkitSchemaInitializer.Migration migration = migrations.stream()
                .filter(item -> item.getVersion() == AD_ACCOUNT_APP_KEY_CAPACITY_MIGRATION_VERSION)
                .findFirst().orElse(null);

        assertNotNull(migration, "encrypted app keys require a new additive migration");
        assertEquals("expand encrypted ad account app key capacity", migration.getDescription());
        String manifest = String.join("\n", migration.getManifest());
        assertTrue(manifest.contains(
                "ALTER TABLE `skit_ad_account` MODIFY COLUMN " + EXPANDED_APP_KEY_DEFINITION), manifest);
    }

    @Test
    void bothBootstrapScriptsUseTheExpandedAppKeyCapacity() throws Exception {
        Path root = repositoryRoot();
        String standalone = new String(Files.readAllBytes(
                root.resolve("sql/mysql/skit-saas.sql")), StandardCharsets.UTF_8);
        String main = new String(Files.readAllBytes(
                root.resolve("sql/mysql/ruoyi-vue-pro.sql")), StandardCharsets.UTF_8);

        for (String source : Arrays.asList(standalone, main)) {
            assertTrue(source.contains(EXPANDED_APP_KEY_DEFINITION), source);
            assertFalse(source.contains("`app_key` varchar(255) DEFAULT ''"), source);
            assertFalse(source.contains("`app_key` varchar(512) DEFAULT ''"), source);
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("sql/mysql/skit-saas.sql"))) {
            candidate = candidate.getParent();
        }
        assertNotNull(candidate, "could not locate repository SQL bootstraps");
        return candidate;
    }

}
