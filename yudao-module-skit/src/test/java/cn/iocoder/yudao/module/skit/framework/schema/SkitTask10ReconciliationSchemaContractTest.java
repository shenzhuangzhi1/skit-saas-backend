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

class SkitTask10ReconciliationSchemaContractTest {

    private static final String TASK_2_CHECKSUM =
            "64e450e4b8048a00b0ce7fbbe9f4b162ec519b5cd3f2c83d12470d92fe72fdbf";

    @Test
    void task10IsAnAdditiveMigrationAndLeavesReleasedTask2ChecksumUntouched() throws Exception {
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

        assertEquals(TASK_2_CHECKSUM, byVersion.get(2026071401).getChecksum(),
                "the released Task 2 migration checksum is immutable");
        SkitSchemaInitializer.Migration task10 = byVersion.get(2026071405);
        assertNotNull(task10, "Task 10 must be a new additive migration");
        assertEquals("add tenant-safe Taku reporting and reconciliation pipeline",
                task10.getDescription());
        String manifest = String.join("\n", task10.getManifest());
        assertContains(manifest,
                "skit_ad_reporting_credential_version",
                "skit_ad_reconciliation_allocation",
                "skit_ad_reconciliation_event_link",
                "report_timezone", "report_currency", "report_amount_scale",
                "report_pull_lease_owner", "report_pull_lease_until", "report_next_allowed_at",
                "report_last_success_at", "report_failure_count", "credential_version",
                "request_hash", "report_date", "final_window", "idx_skit_report_pull_final_window",
                "ck_skit_report_pull_status",
                "app_id", "ad_format", "network_account_id", "attributable_actual_units",
                "suspense_units", "report_impressions_available", "source_report_impressions",
                "source_report_impressions_available", "matched_event_count",
                "idx_skit_revenue_report_pending",
                "`range_end`,`request_hash`,`response_hash`,`credential_version`,`final_window`",
                "trg_skit_reporting_credential_no_delete", "trg_skit_report_pull_immutable",
                "trg_skit_recon_revision_immutable", "trg_skit_recon_allocation_immutable",
                "trg_skit_recon_event_link_immutable",
                "validate-task10-reconciliation-schema");
    }

    @Test
    void bothBootstrapScriptsExposeTheSameTask10Schema() throws Exception {
        Path root = repositoryRoot();
        String standalone = new String(Files.readAllBytes(
                root.resolve("sql/mysql/skit-saas.sql")), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        String main = new String(Files.readAllBytes(
                root.resolve("sql/mysql/ruoyi-vue-pro.sql")), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        assertContains(standalone,
                "CREATE TABLE IF NOT EXISTS `skit_ad_reporting_credential_version`",
                "CREATE TABLE IF NOT EXISTS `skit_ad_reconciliation_allocation`",
                "CREATE TABLE IF NOT EXISTS `skit_ad_reconciliation_event_link`",
                "`report_pull_lease_owner`", "`report_timezone`", "`report_amount_scale`",
                "`final_window`", "`idx_skit_report_pull_final_window`",
                "`ck_skit_report_pull_status`");
        assertContains(main,
                "CREATE TABLE IF NOT EXISTS `skit_ad_reporting_credential_version`",
                "CREATE TABLE IF NOT EXISTS `skit_ad_reconciliation_allocation`",
                "CREATE TABLE IF NOT EXISTS `skit_ad_reconciliation_event_link`",
                "`report_pull_lease_owner`", "`report_timezone`", "`report_amount_scale`",
                "`final_window`", "`idx_skit_report_pull_final_window`",
                "`ck_skit_report_pull_status`");
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
