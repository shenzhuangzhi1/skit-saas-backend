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

class SkitTask11ManagementSchemaContractTest {

    @Test
    void task11UsesTheAdditive1407SlotForDurableCommandsExportsAndManagementIndexes()
            throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(mock(JdbcTemplate.class));
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SkitSchemaInitializer.Migration> migrations =
                (List<SkitSchemaInitializer.Migration>) field.get(initializer);
        SkitSchemaInitializer.Migration task11 = migrations.stream()
                .filter(migration -> migration.getVersion() == 2026071407)
                .findFirst().orElse(null);

        assertNotNull(task11, "Task 11 management schema must use a new additive migration");
        assertEquals("add tenant-safe management audit and query schema", task11.getDescription());
        String manifest = String.join("\n", task11.getManifest());
        assertContains(manifest,
                "skit_management_command_audit",
                "skit_ad_callback_replay_command",
                "skit_entitlement_security_revocation",
                "skit_management_export_task",
                "idx_skit_ad_session_management_account",
                "idx_skit_callback_inbox_management_account",
                "idx_skit_revenue_management_time",
                "idx_skit_ledger_management_balance",
                "idx_skit_report_pull_management_account",
                "idx_skit_recon_bucket_management_account",
                "idx_skit_member_closure_ancestor_distance",
                "idx_skit_ad_session_global_created",
                "idx_skit_ad_revenue_global_occurred",
                "idx_skit_ad_callback_global_received",
                "idx_skit_ad_recon_bucket_global_date",
                "trg_skit_management_audit_immutable",
                "trg_skit_callback_replay_immutable",
                "trg_skit_security_revocation_immutable",
                "validate-task11-management-schema");
    }

    @Test
    void bothBootstrapScriptsContainTheSameManagementTablesIndexesAndImmutabilityTriggers()
            throws Exception {
        Path root = repositoryRoot();
        String standalone = new String(Files.readAllBytes(
                root.resolve("sql/mysql/skit-saas.sql")), StandardCharsets.UTF_8);
        String main = new String(Files.readAllBytes(
                root.resolve("sql/mysql/ruoyi-vue-pro.sql")), StandardCharsets.UTF_8);
        String[] fragments = {
                "`skit_management_command_audit`",
                "`skit_ad_callback_replay_command`",
                "`skit_entitlement_security_revocation`",
                "`skit_management_export_task`",
                "`idx_skit_ad_session_management_account`",
                "`idx_skit_callback_inbox_management_account`",
                "`idx_skit_revenue_management_time`",
                "`idx_skit_ledger_management_balance`",
                "`idx_skit_report_pull_management_account`",
                "`idx_skit_recon_bucket_management_account`",
                "`idx_skit_member_closure_ancestor_distance`",
                "`idx_skit_ad_session_global_created`",
                "`idx_skit_ad_revenue_global_occurred`",
                "`idx_skit_ad_callback_global_received`",
                "`idx_skit_ad_recon_bucket_global_date`",
                "`trg_skit_management_audit_immutable`",
                "`trg_skit_callback_replay_immutable`",
                "`trg_skit_security_revocation_immutable`"};
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
