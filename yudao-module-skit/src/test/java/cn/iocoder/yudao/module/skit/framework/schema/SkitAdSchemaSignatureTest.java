package cn.iocoder.yudao.module.skit.framework.schema;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkitAdSchemaSignatureTest {

    @Test
    void shouldExcludeOnlyTableQualifiedReleasedAdditiveIndexes() {
        String releasedIndex = "uk_skit_ad_session_show|0|0001|tenant_id|<NULL>|A|BTREE|<NULL>";
        List<String> additiveIndex = indexRows("idx_skit_ad_session_management_account", false,
                "tenant_id,ad_account_id,create_time,id");
        String unknownIndex = "idx_unreleased_ad_session_query|1|0001|tenant_id|<NULL>|A|BTREE|<NULL>";
        List<String> currentRows = new ArrayList<>();
        currentRows.add(releasedIndex);
        currentRows.addAll(additiveIndex);
        currentRows.add(unknownIndex);

        assertEquals(Arrays.asList(releasedIndex, unknownIndex),
                SkitAdSchemaSignature.releasedIndexRows("skit_ad_session", currentRows));
        assertEquals(currentRows,
                SkitAdSchemaSignature.releasedIndexRows("skit_ad_callback_inbox", currentRows),
                "an allowlisted index name must remain visible when attached to a different table");
    }

    @Test
    void shouldCoverEveryTask10AndTask11IndexThatExtendsAReleasedFingerprintTable() {
        assertAllExcluded("skit_ad_session",
                indexRows("idx_skit_ad_session_management_account", false,
                        "tenant_id,ad_account_id,create_time,id"),
                indexRows("idx_skit_ad_session_management_reward", false,
                        "tenant_id,reward_verification_status,create_time,id"),
                indexRows("idx_skit_ad_session_global_created", false, "create_time,id"));
        assertAllExcluded("skit_ad_callback_inbox",
                indexRows("idx_skit_callback_inbox_management_account", false,
                        "tenant_id,ad_account_id,received_at,id"),
                indexRows("idx_skit_callback_inbox_management_status", false,
                        "tenant_id,processing_status,received_at,id"),
                indexRows("idx_skit_ad_callback_global_received", false, "received_at,id"));
        assertAllExcluded("skit_ad_revenue_event",
                indexRows("idx_skit_revenue_report_pending", false,
                        "tenant_id,ad_account_id,reconciliation_revision_id,occurred_time,id"),
                indexRows("idx_skit_revenue_management_time", false,
                        "tenant_id,occurred_time,id"),
                indexRows("idx_skit_revenue_management_member", false,
                        "tenant_id,source_member_id,occurred_time,id"),
                indexRows("idx_skit_revenue_management_reconciliation", false,
                        "tenant_id,reconciliation_status,source_currency,occurred_time,id"),
                indexRows("idx_skit_ad_revenue_global_occurred", false, "occurred_time,id"));
        assertAllExcluded("skit_commission_ledger",
                indexRows("idx_skit_ledger_management_balance", false,
                        "tenant_id,currency,balance_bucket,create_time,id"),
                indexRows("idx_skit_ledger_management_event", false, "tenant_id,event_id,id"));
        assertAllExcluded("skit_ad_report_pull",
                indexRows("idx_skit_report_pull_management_account", false,
                        "tenant_id,ad_account_id,pulled_at,id"),
                indexRows("idx_skit_report_pull_management_status", false,
                        "tenant_id,status,pulled_at,id"));
        assertAllExcluded("skit_ad_reconciliation_bucket",
                indexRows("idx_skit_recon_bucket_management_account", false,
                        "tenant_id,ad_account_id,report_date,id"),
                indexRows("idx_skit_ad_recon_bucket_global_date", false, "report_date,id"));
        assertAllExcluded("skit_ad_reconciliation_revision",
                indexRows("idx_skit_recon_revision_management_bucket", false,
                        "tenant_id,reconciliation_bucket_id,revision_no,id"));
    }

    @Test
    void shouldRejectSameNamedAdditiveIndexWithDriftedDefinition() {
        List<String> drifted = indexRows("idx_skit_ad_session_management_account", true,
                "tenant_id,ad_account_id,create_time,id");

        assertThrows(IllegalStateException.class,
                () -> SkitAdSchemaSignature.releasedIndexRows("skit_ad_session", drifted));
    }

    @Test
    void shouldRejectSameNamedAdditiveColumnWithDriftedDefinition() {
        List<String> drifted = Collections.singletonList(
                "0030|report_date|datetime|YES|<NULL>||<NULL>");

        assertThrows(IllegalStateException.class,
                () -> SkitAdSchemaSignature.releasedColumnRows("skit_ad_report_pull", drifted));
    }

    @Test
    void shouldAcceptMySqlEmptyGenerationExpressionForLegalNullableTask10PrefixColumns() {
        List<String> nullablePrefix = Arrays.asList(
                "0030|report_date|date|YES|<NULL>||",
                "0034|request_hash|binary(32)|YES|<NULL>||");

        assertEquals(Collections.emptyList(),
                SkitAdSchemaSignature.releasedColumnRows("skit_ad_report_pull", nullablePrefix));
    }

    @Test
    void shouldAcceptMySqlEmptyGenerationExpressionForOrdinaryNotNullColumns() {
        List<String> ordinaryColumns = Arrays.asList(
                "0030|report_date|date|NO|<NULL>||",
                "0031|report_timezone|varchar(64)|NO|UTC+8||");

        assertEquals(Collections.emptyList(),
                SkitAdSchemaSignature.releasedColumnRows("skit_ad_report_pull", ordinaryColumns));
    }

    @Test
    void shouldRejectSameNamedAdditiveForeignKeyWithDriftedDefinition() {
        List<String> drifted = Arrays.asList(
                "fk_skit_report_pull_credential|0001|tenant_id|skit_ad_reporting_credential_version|tenant_id|"
                        + "CASCADE|RESTRICT",
                "fk_skit_report_pull_credential|0002|ad_account_id|skit_ad_reporting_credential_version|"
                        + "ad_account_id|CASCADE|RESTRICT",
                "fk_skit_report_pull_credential|0003|credential_version|skit_ad_reporting_credential_version|"
                        + "credential_version|CASCADE|RESTRICT");

        assertThrows(IllegalStateException.class,
                () -> SkitAdSchemaSignature.releasedForeignKeyRows("skit_ad_report_pull", drifted));
    }

    @Test
    void shouldRejectSameNamedAdditiveCheckWithDriftedDefinition() {
        List<String> drifted = Collections.singletonList(
                "ck_skit_report_pull_credential_version|(`credential_version` is null) or "
                        + "(`credential_version` >= 0)");

        assertThrows(IllegalStateException.class,
                () -> SkitAdSchemaSignature.releasedCheckRows("skit_ad_report_pull", drifted));
    }

    @Test
    void shouldProjectExactTask10ReplacementIndexBackToReleasedShape() {
        List<String> task10Rows = indexRows("uk_skit_report_pull_response", true,
                "tenant_id,ad_account_id,range_start,range_end,request_hash,response_hash,credential_version,"
                        + "final_window");

        assertEquals(indexRows("uk_skit_report_pull_response", true,
                        "tenant_id,ad_account_id,range_start,range_end,response_hash"),
                SkitAdSchemaSignature.releasedIndexRows("skit_ad_report_pull", task10Rows));
    }

    @Test
    void shouldNeverDelegateAnEntireReleasedTableFingerprint() {
        assertFalse(SkitSchemaInitializer.laterValidatorOwnsReleasedFingerprint(
                "skit_ad_report_pull", true, false));
        assertFalse(SkitSchemaInitializer.laterValidatorOwnsReleasedFingerprint(
                "skit_ad_reconciliation_bucket", true, false));
        assertFalse(SkitSchemaInitializer.laterValidatorOwnsReleasedFingerprint(
                "skit_ad_reconciliation_revision", true, false));
        assertFalse(SkitSchemaInitializer.laterValidatorOwnsReleasedFingerprint(
                "skit_tenant_ad_capability", false, true));
        assertFalse(SkitSchemaInitializer.laterValidatorOwnsReleasedFingerprint(
                "skit_ad_session", true, true));
        assertFalse(SkitSchemaInitializer.laterValidatorOwnsReleasedFingerprint(
                "skit_tenant_ad_capability", true, false));
        assertFalse(SkitSchemaInitializer.laterValidatorOwnsReleasedFingerprint(
                "skit_unknown", true, true));
    }

    @SafeVarargs
    private static void assertAllExcluded(String table, List<String>... indexes) {
        for (List<String> index : indexes) {
            assertEquals(Collections.emptyList(), SkitAdSchemaSignature.releasedIndexRows(table, index),
                    table + "." + index.get(0).substring(0, index.get(0).indexOf('|')));
        }
    }

    private static List<String> indexRows(String index, boolean unique, String columns) {
        List<String> rows = new ArrayList<>();
        String[] columnNames = columns.split(",");
        for (int i = 0; i < columnNames.length; i++) {
            rows.add(index + "|" + (unique ? "0" : "1") + "|" + String.format("%04d", i + 1)
                    + "|" + columnNames[i] + "|<NULL>|A|BTREE|<NULL>");
        }
        return rows;
    }

}
