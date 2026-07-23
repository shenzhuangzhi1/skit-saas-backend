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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitMemberPointSchemaContractTest {

    private static final int MEMBER_POINT_MIGRATION_VERSION = 2026072401;

    @Test
    void memberPointsUseANewTenantSafeAdditiveMigration() throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(new JdbcTemplate());
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SkitSchemaInitializer.Migration> migrations =
                (List<SkitSchemaInitializer.Migration>) field.get(initializer);
        SkitSchemaInitializer.Migration migration = migrations.stream()
                .filter(item -> item.getVersion() == MEMBER_POINT_MIGRATION_VERSION)
                .findFirst().orElse(null);

        assertNotNull(migration, "member points require a new additive migration");
        assertEquals("add tenant-safe member check-in point ledger", migration.getDescription());
        String manifest = String.join("\n", migration.getManifest());
        assertContains(manifest,
                "skit_member|point_balance",
                "CREATE TABLE IF NOT EXISTS `skit_member_point_record`",
                "`uk_skit_member_point_business` (`tenant_id`,`member_id`,`biz_type`,`biz_id`)",
                "`idx_skit_member_point_member_time` (`tenant_id`,`member_id`,`create_time`,`id`)",
                "CONSTRAINT `fk_skit_member_point_member` FOREIGN KEY (`tenant_id`,`member_id`)",
                "CONSTRAINT `ck_skit_member_point_delta` CHECK (`point_delta` <> 0)",
                "CONSTRAINT `ck_skit_member_point_balance` CHECK (`balance_after` >= 0)");
    }

    @Test
    void bothBootstrapScriptsContainTheSameMemberPointSchema() throws Exception {
        Path root = repositoryRoot();
        String standalone = new String(Files.readAllBytes(
                root.resolve("sql/mysql/skit-saas.sql")), StandardCharsets.UTF_8);
        String main = new String(Files.readAllBytes(
                root.resolve("sql/mysql/ruoyi-vue-pro.sql")), StandardCharsets.UTF_8);
        String[] fragments = {
                "`point_balance` int NOT NULL DEFAULT 0",
                "CREATE TABLE IF NOT EXISTS `skit_member_point_record`",
                "`uk_skit_member_point_business` (`tenant_id`,`member_id`,`biz_type`,`biz_id`)",
                "`idx_skit_member_point_member_time` (`tenant_id`,`member_id`,`create_time`,`id`)",
                "`fk_skit_member_point_member`",
                "`ck_skit_member_point_delta`",
                "`ck_skit_member_point_balance`"
        };
        for (String source : Arrays.asList(standalone, main)) {
            assertContains(source, fragments);
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

    private static void assertContains(String source, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(source.contains(fragment), () -> "missing '" + fragment + "'");
        }
    }

}
