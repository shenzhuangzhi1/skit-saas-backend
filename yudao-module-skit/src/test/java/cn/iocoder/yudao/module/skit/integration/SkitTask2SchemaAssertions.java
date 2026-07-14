package cn.iocoder.yudao.module.skit.integration;

import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SkitTask2SchemaAssertions {

    private SkitTask2SchemaAssertions() {
    }

    static void assertNoTask2Artifacts(JdbcTemplate jdbc) {
        Integer tableCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ("
                + "'skit_ad_callback_key','skit_ad_reward_secret_version','skit_ad_policy_snapshot',"
                + "'skit_ad_session','skit_ad_client_event','skit_ad_callback_edge_attempt',"
                + "'skit_ad_callback_inbox','skit_ad_callback_attempt','skit_ad_network_capability',"
                + "'skit_content_entitlement','skit_entitlement_grant','skit_native_player_grant',"
                + "'skit_ad_report_pull','skit_ad_reconciliation_bucket','skit_ad_reconciliation_revision',"
                + "'skit_tenant_ad_capability','skit_invite_code_registry')", Integer.class);
        assertEquals(0, tableCount, "all 17 Task 2 tables must still be absent");

        Integer extensionColumnCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND ((TABLE_NAME='skit_ad_revenue_event' AND COLUMN_NAME IN ("
                + "'ad_session_id','callback_inbox_id','policy_snapshot_id','reconciliation_bucket_id',"
                + "'reconciliation_revision_id','source_type','provider_transaction_id','provider_show_id',"
                + "'sdk_request_id','adsource_id','source_amount_units','estimated_amount_units',"
                + "'reconciled_amount_units','amount_scale','source_currency','match_status',"
                + "'source_verification_status','reward_qualification_status','reconciliation_status',"
                + "'reconciled_at','verified_at','payload_hash','version','legacy_unverified')) OR "
                + "(TABLE_NAME='skit_commission_ledger' AND COLUMN_NAME IN ("
                + "'entry_type','balance_bucket','currency','gross_amount_units','amount_units','amount_scale',"
                + "'reversal_of_id','reconciliation_revision_id','policy_snapshot_id','revision_no',"
                + "'legacy_unverified','beneficiary_member_ref_id')))", Integer.class);
        assertEquals(0, extensionColumnCount, "Task 2 finance columns must still be absent");

        Integer indexCount = jdbc.queryForObject("SELECT COUNT(DISTINCT TABLE_NAME,INDEX_NAME) "
                + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND INDEX_NAME IN ("
                + "'uk_skit_agent_tenant_id','uk_skit_ad_account_tenant_id','uk_skit_member_tenant_id',"
                + "'uk_skit_commission_plan_tenant_id','uk_skit_revenue_event_tenant_id',"
                + "'uk_skit_ledger_tenant_id','uk_skit_revenue_source_idem','uk_skit_revenue_inbox_source',"
                + "'uk_skit_revenue_session_source','uk_skit_ledger_entry_revision',"
                + "'uk_skit_admin_record_tenant_page_row','uk_skit_system_config_tenant')", Integer.class);
        assertEquals(0, indexCount, "Task 2 parent indexes must still be absent");

        Integer foreignKeyCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND CONSTRAINT_TYPE='FOREIGN KEY' "
                + "AND CONSTRAINT_NAME LIKE 'fk_skit_%'", Integer.class);
        assertEquals(0, foreignKeyCount, "Task 2 compound foreign keys must still be absent");

        Integer checkCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND CONSTRAINT_TYPE='CHECK' "
                + "AND CONSTRAINT_NAME LIKE 'ck_skit_%'", Integer.class);
        assertEquals(0, checkCount, "Task 2 checks must still be absent");

        Integer triggerCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TRIGGERS "
                + "WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME IN ("
                + "'trg_skit_revenue_legacy_immutable','trg_skit_ledger_legacy_immutable')", Integer.class);
        assertEquals(0, triggerCount, "Task 2 legacy guards must still be absent");

        Integer migrationTableCount = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='skit_schema_migration'", Integer.class);
        if (migrationTableCount != null && migrationTableCount > 0) {
            Integer migrationCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM skit_schema_migration WHERE version=2026071401", Integer.class);
            assertEquals(0, migrationCount, "Task 2 migration history row must not be installed");
        }
    }

}
