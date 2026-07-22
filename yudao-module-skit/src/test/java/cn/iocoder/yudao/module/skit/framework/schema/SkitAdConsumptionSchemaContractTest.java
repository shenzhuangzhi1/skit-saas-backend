package cn.iocoder.yudao.module.skit.framework.schema;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdConsumptionSchemaContractTest {

    @Test
    void consumptionIndexesUseANewAdditiveIdempotentMigration() throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(new JdbcTemplate());
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SkitSchemaInitializer.Migration> migrations =
                (List<SkitSchemaInitializer.Migration>) field.get(initializer);
        SkitSchemaInitializer.Migration migration = migrations.stream()
                .filter(item -> item.getVersion() == 2026072301)
                .findFirst().orElse(null);

        assertNotNull(migration, "ad consumption query indexes require a new migration version");
        assertEquals("add tenant-safe ad consumption query indexes", migration.getDescription());
        String manifest = String.join("\n", migration.getManifest());
        assertTrue(manifest.contains("idx_skit_ad_session_consumption_time"), manifest);
        assertTrue(manifest.contains("`tenant_id`,`create_time`,`id`"), manifest);
        assertTrue(manifest.contains("idx_skit_ad_session_global_consumption_time"), manifest);
        assertTrue(manifest.contains("`create_time`,`id`,`tenant_id`"), manifest);
        assertTrue(manifest.contains("idx_skit_callback_inbox_consumption_session"), manifest);
        assertTrue(manifest.contains("`tenant_id`,`ad_session_id`,`received_at`,`id`"), manifest);
        assertTrue(manifest.contains("validate-ad-consumption-query-indexes"), manifest);
    }

    @Test
    void bothBootstrapScriptsApplyTheSameConsumptionIndexes() throws Exception {
        Path root = repositoryRoot();
        String standalone = new String(Files.readAllBytes(
                root.resolve("sql/mysql/skit-saas.sql")), StandardCharsets.UTF_8);
        String main = new String(Files.readAllBytes(
                root.resolve("sql/mysql/ruoyi-vue-pro.sql")), StandardCharsets.UTF_8);
        for (String source : Arrays.asList(standalone, main)) {
            assertTrue(source.contains("`idx_skit_ad_session_consumption_time`"));
            assertTrue(source.contains("(`tenant_id`,`create_time`,`id`)"));
            assertTrue(source.contains("`idx_skit_ad_session_global_consumption_time`"));
            assertTrue(source.contains("(`create_time`,`id`,`tenant_id`)"));
            assertTrue(source.contains("`idx_skit_callback_inbox_consumption_session`"));
            assertTrue(source.contains("(`tenant_id`,`ad_session_id`,`received_at`,`id`)"));
        }
    }

    @Test
    void releasedFingerprintAllowsOnlyTheExactConsumptionIndexShapes() {
        assertEquals(new ArrayList<>(), SkitAdSchemaSignature.releasedIndexRows(
                "skit_ad_session", indexRows("idx_skit_ad_session_consumption_time",
                        "tenant_id,create_time,id")));
        assertEquals(new ArrayList<>(), SkitAdSchemaSignature.releasedIndexRows(
                "skit_ad_session", indexRows("idx_skit_ad_session_global_consumption_time",
                        "create_time,id,tenant_id")));
        assertEquals(new ArrayList<>(), SkitAdSchemaSignature.releasedIndexRows(
                "skit_ad_callback_inbox", indexRows(
                        "idx_skit_callback_inbox_consumption_session",
                        "tenant_id,ad_session_id,received_at,id")));
    }

    private static List<String> indexRows(String indexName, String columns) {
        List<String> rows = new ArrayList<>();
        String[] names = columns.split(",");
        for (int i = 0; i < names.length; i++) {
            rows.add(indexName + "|1|" + String.format("%04d", i + 1) + "|" + names[i]
                    + "|<NULL>|A|BTREE|<NULL>");
        }
        return rows;
    }

    private static Path repositoryRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null
                && !Files.isRegularFile(candidate.resolve("sql/mysql/skit-saas.sql"))) {
            candidate = candidate.getParent();
        }
        assertNotNull(candidate, "could not locate repository SQL bootstraps");
        return candidate;
    }

}
