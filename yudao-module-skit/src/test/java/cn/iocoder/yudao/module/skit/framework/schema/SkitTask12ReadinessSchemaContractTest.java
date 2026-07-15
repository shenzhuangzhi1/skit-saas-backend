package cn.iocoder.yudao.module.skit.framework.schema;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SkitTask12ReadinessSchemaContractTest {

    private static final Map<Integer, String> RELEASED_CHECKSUMS = new HashMap<>();

    static {
        RELEASED_CHECKSUMS.put(2026071401,
                "64e450e4b8048a00b0ce7fbbe9f4b162ec519b5cd3f2c83d12470d92fe72fdbf");
        RELEASED_CHECKSUMS.put(2026071402,
                "11f815a76c7f15bacfc9d9a29a60121f6736ec18bbd712a02a0190ff69c8c18d");
        RELEASED_CHECKSUMS.put(2026071403,
                "88b57c75266fc10a56dfef4ce17fedfd0683bb63e4e80c1f6cd9353698355892");
        RELEASED_CHECKSUMS.put(2026071404,
                "8940c5da3ef12d1ec8bbeefe54ec4e08c87743b97bebc52825c76f2eadf29ea3");
    }

    @Test
    void task12UsesOnlyTheAdditive1406SlotAfterTask10AndKeepsReleasedHistoryStable()
            throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(mock(JdbcTemplate.class));
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SkitSchemaInitializer.Migration> migrations =
                (List<SkitSchemaInitializer.Migration>) field.get(initializer);
        Map<Integer, SkitSchemaInitializer.Migration> byVersion = new HashMap<>();
        for (SkitSchemaInitializer.Migration migration : migrations) {
            byVersion.put(migration.getVersion(), migration);
        }
        for (Map.Entry<Integer, String> released : RELEASED_CHECKSUMS.entrySet()) {
            assertNotNull(byVersion.get(released.getKey()));
            assertEquals(released.getValue(), byVersion.get(released.getKey()).getChecksum(),
                    "released migration checksum must remain immutable: " + released.getKey());
        }
        assertNotNull(byVersion.get(2026071405), "Task 10 must own the immediately prior slot");

        SkitSchemaInitializer.Migration task12 = byVersion.get(2026071406);
        assertNotNull(task12, "Task 12 readiness must be a new additive migration");
        assertEquals("add tenant ad readiness rollout gates", task12.getDescription());
        String manifest = String.join("\n", task12.getManifest());
        assertContains(manifest, "dedicated_unlock_placement_id", "dedicated_placement_verified_at",
                "reward_callback_template_verified_at", "impression_callback_template_verified_at",
                "unlock_network_firm_ids_json", "shadow_test_member_ids_json",
                "min_protocol_version", "native_protocol_version",
                "ck_skit_tenant_capability_network_json", "ck_skit_tenant_capability_shadow_json",
                "ck_skit_tenant_capability_protocol", "ck_skit_app_release_native_protocol",
                "validate-task12-ad-readiness-schema");
    }

    @Test
    void bothBootstrapScriptsExposeTheSameFinalReadinessColumnsAndChecks() throws Exception {
        Path root = repositoryRoot();
        String standalone = new String(Files.readAllBytes(
                root.resolve("sql/mysql/skit-saas.sql")), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        String main = new String(Files.readAllBytes(
                root.resolve("sql/mysql/ruoyi-vue-pro.sql")), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        String[] fragments = {"`dedicated_unlock_placement_id`", "`shadow_test_member_ids_json`",
                "`min_protocol_version`", "`native_protocol_version`",
                "`ck_skit_tenant_capability_network_json`", "`ck_skit_tenant_capability_shadow_json`",
                "`ck_skit_app_release_native_protocol`"};
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
