package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.module.skit.framework.schema.SkitSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkitAdSchemaMigrationMySqlIT extends SkitMySqlIntegrationTestBase {

    private static final List<String> TASK_2_TABLES = Arrays.asList(
            "skit_ad_callback_key",
            "skit_ad_reward_secret_version",
            "skit_ad_policy_snapshot",
            "skit_ad_session",
            "skit_ad_client_event",
            "skit_ad_callback_edge_attempt",
            "skit_ad_callback_inbox",
            "skit_ad_callback_attempt",
            "skit_ad_network_capability",
            "skit_content_entitlement",
            "skit_entitlement_grant",
            "skit_native_player_grant",
            "skit_ad_report_pull",
            "skit_ad_reconciliation_bucket",
            "skit_ad_reconciliation_revision",
            "skit_tenant_ad_capability",
            "skit_invite_code_registry");

    @Override
    protected void beforeSkitSchemaInitialization(JdbcTemplate jdbc) {
        SkitLegacyAdSchemaFixture.installValidLegacyRows(jdbc);
    }

    @Test
    void legacyUpgradeCreatesTheCompleteTask2Schema() {
        Integer tableCount = jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (" + placeholders(TASK_2_TABLES.size())
                        + ")", Integer.class, TASK_2_TABLES.toArray());

        assertEquals(TASK_2_TABLES.size(), tableCount);
    }

    @Test
    void legacyMoneyIsDeterministicallyBackfilledAndPermanentlyNonSettleable() {
        assertEquals("LEGACY_CLIENT", value("source_type", "skit_ad_revenue_event", 601L));
        assertEquals("LEGACY_UNVERIFIED", value("source_verification_status", "skit_ad_revenue_event", 601L));
        assertEquals("NON_SETTLEABLE", value("reconciliation_status", "skit_ad_revenue_event", 601L));
        assertEquals(1234567890L, ((Number) value("source_amount_units", "skit_ad_revenue_event", 601L)).longValue());
        assertEquals(1234567890L, ((Number) value("estimated_amount_units", "skit_ad_revenue_event", 601L)).longValue());
        assertEquals("CNY", value("source_currency", "skit_ad_revenue_event", 601L));
        assertEquals(8, ((Number) value("amount_scale", "skit_ad_revenue_event", 601L)).intValue());

        assertEquals("LEGACY_ESTIMATE", value("entry_type", "skit_commission_ledger", 701L));
        assertEquals("NON_SETTLEABLE", value("balance_bucket", "skit_commission_ledger", 701L));
        assertEquals(308641973L, ((Number) value("amount_units", "skit_commission_ledger", 701L)).longValue());
        assertEquals(0, ((Number) value("revision_no", "skit_commission_ledger", 701L)).intValue());

        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE skit_ad_revenue_event SET reconciliation_status='RECONCILED' WHERE id=601"));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE skit_commission_ledger SET balance_bucket='AVAILABLE' WHERE id=701"));

        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE skit_ad_revenue_event SET legacy_unverified=b'0',"
                        + "source_type='SERVER_CALLBACK',match_status='MATCHED',"
                        + "source_verification_status='VERIFIED',reward_qualification_status='REWARDED',"
                        + "reconciliation_status='RECONCILED',status=1 WHERE id=601"));
        assertEquals(true, value("legacy_unverified", "skit_ad_revenue_event", 601L));
        assertEquals("LEGACY_CLIENT", value("source_type", "skit_ad_revenue_event", 601L));
        assertEquals("LEGACY_UNMATCHED", value("match_status", "skit_ad_revenue_event", 601L));
        assertEquals("LEGACY_UNVERIFIED", value("source_verification_status", "skit_ad_revenue_event", 601L));
        assertEquals("NOT_APPLICABLE", value("reward_qualification_status", "skit_ad_revenue_event", 601L));
        assertEquals("NON_SETTLEABLE", value("reconciliation_status", "skit_ad_revenue_event", 601L));
        assertEquals(0, ((Number) value("status", "skit_ad_revenue_event", 601L)).intValue());

        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE skit_commission_ledger SET legacy_unverified=b'0',"
                        + "entry_type='RECONCILIATION_ADJUSTMENT',balance_bucket='AVAILABLE',revision_no=1,status=1 "
                        + "WHERE id=701"));
        assertEquals(true, value("legacy_unverified", "skit_commission_ledger", 701L));
        assertEquals("LEGACY_ESTIMATE", value("entry_type", "skit_commission_ledger", 701L));
        assertEquals("NON_SETTLEABLE", value("balance_bucket", "skit_commission_ledger", 701L));
        assertEquals(0, ((Number) value("revision_no", "skit_commission_ledger", 701L)).intValue());
        assertEquals(0, ((Number) value("status", "skit_commission_ledger", 701L)).intValue());
    }

    @Test
    void everyTenantParentHasAnExactTenantIdCandidateKey() {
        List<String> tenantParents = Arrays.asList(
                "skit_ad_account", "skit_member", "skit_commission_plan", "skit_ad_revenue_event",
                "skit_commission_ledger", "skit_ad_callback_key", "skit_ad_reward_secret_version",
                "skit_ad_policy_snapshot", "skit_ad_session", "skit_ad_client_event",
                "skit_ad_callback_inbox", "skit_ad_callback_attempt", "skit_ad_network_capability",
                "skit_content_entitlement", "skit_entitlement_grant", "skit_native_player_grant",
                "skit_ad_report_pull", "skit_ad_reconciliation_bucket", "skit_ad_reconciliation_revision");
        for (String table : tenantParents) {
            Integer exactKey = jdbc().queryForObject("SELECT COUNT(*) FROM ("
                            + "SELECT INDEX_NAME,NON_UNIQUE,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) columns_in_order "
                            + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? "
                            + "GROUP BY INDEX_NAME,NON_UNIQUE) indexes_found "
                            + "WHERE NON_UNIQUE=0 AND columns_in_order='tenant_id,id'",
                    Integer.class, table);
            assertEquals(1, exactKey, table + " must expose UNIQUE(tenant_id,id)");
        }
    }

    @Test
    void compoundTenantForeignKeysAreExactAndRejectCrossTenantReferences() {
        String[][] expectedForeignKeys = {
                {"skit_commission_rule", "fk_skit_commission_rule_plan", "tenant_id,plan_id", "skit_commission_plan", "tenant_id,id"},
                {"skit_member", "fk_skit_member_inviter", "tenant_id,inviter_id", "skit_member", "tenant_id,id"},
                {"skit_member_closure", "fk_skit_member_closure_ancestor", "tenant_id,ancestor_id", "skit_member", "tenant_id,id"},
                {"skit_member_closure", "fk_skit_member_closure_descendant", "tenant_id,descendant_id", "skit_member", "tenant_id,id"},
                {"skit_ad_revenue_event", "fk_skit_revenue_event_account", "tenant_id,ad_account_id", "skit_ad_account", "tenant_id,id"},
                {"skit_ad_revenue_event", "fk_skit_revenue_event_member", "tenant_id,source_member_id", "skit_member", "tenant_id,id"},
                {"skit_commission_ledger", "fk_skit_ledger_event", "tenant_id,event_id", "skit_ad_revenue_event", "tenant_id,id"},
                {"skit_commission_ledger", "fk_skit_ledger_beneficiary_member", "tenant_id,beneficiary_member_ref_id", "skit_member", "tenant_id,id"},
                {"skit_ad_callback_key", "fk_skit_callback_key_account", "tenant_id,ad_account_id", "skit_ad_account", "tenant_id,id"},
                {"skit_ad_reward_secret_version", "fk_skit_reward_secret_account", "tenant_id,ad_account_id", "skit_ad_account", "tenant_id,id"},
                {"skit_ad_policy_snapshot", "fk_skit_policy_snapshot_plan", "tenant_id,plan_id", "skit_commission_plan", "tenant_id,id"},
                {"skit_ad_policy_snapshot", "fk_skit_policy_snapshot_member", "tenant_id,source_member_id", "skit_member", "tenant_id,id"},
                {"skit_ad_session", "fk_skit_ad_session_member", "tenant_id,member_id", "skit_member", "tenant_id,id"},
                {"skit_ad_session", "fk_skit_ad_session_account", "tenant_id,ad_account_id", "skit_ad_account", "tenant_id,id"},
                {"skit_ad_session", "fk_skit_ad_session_snapshot", "tenant_id,policy_snapshot_id", "skit_ad_policy_snapshot", "tenant_id,id"},
                {"skit_ad_client_event", "fk_skit_client_event_session", "tenant_id,ad_session_id", "skit_ad_session", "tenant_id,id"},
                {"skit_ad_callback_inbox", "fk_skit_callback_inbox_account", "tenant_id,ad_account_id", "skit_ad_account", "tenant_id,id"},
                {"skit_ad_callback_inbox", "fk_skit_callback_inbox_session", "tenant_id,ad_session_id", "skit_ad_session", "tenant_id,id"},
                {"skit_ad_callback_attempt", "fk_skit_callback_attempt_inbox", "tenant_id,callback_inbox_id", "skit_ad_callback_inbox", "tenant_id,id"},
                {"skit_ad_network_capability", "fk_skit_network_cap_account", "tenant_id,ad_account_id", "skit_ad_account", "tenant_id,id"},
                {"skit_content_entitlement", "fk_skit_entitlement_member", "tenant_id,member_id", "skit_member", "tenant_id,id"},
                {"skit_entitlement_grant", "fk_skit_grant_session", "tenant_id,ad_session_id", "skit_ad_session", "tenant_id,id"},
                {"skit_entitlement_grant", "fk_skit_grant_entitlement", "tenant_id,entitlement_id", "skit_content_entitlement", "tenant_id,id"},
                {"skit_native_player_grant", "fk_skit_player_grant_member", "tenant_id,member_id", "skit_member", "tenant_id,id"},
                {"skit_ad_report_pull", "fk_skit_report_pull_account", "tenant_id,ad_account_id", "skit_ad_account", "tenant_id,id"},
                {"skit_ad_reconciliation_bucket", "fk_skit_recon_bucket_account", "tenant_id,ad_account_id", "skit_ad_account", "tenant_id,id"},
                {"skit_ad_reconciliation_revision", "fk_skit_recon_revision_bucket", "tenant_id,reconciliation_bucket_id", "skit_ad_reconciliation_bucket", "tenant_id,id"},
                {"skit_ad_reconciliation_revision", "fk_skit_recon_revision_account", "tenant_id,ad_account_id", "skit_ad_account", "tenant_id,id"},
                {"skit_tenant_ad_capability", "fk_skit_tenant_capability_account", "tenant_id,ad_account_id", "skit_ad_account", "tenant_id,id"},
                {"skit_invite_code_registry", "fk_skit_invite_registry_agent", "tenant_id,agent_id", "skit_agent", "tenant_id,id"},
                {"skit_invite_code_registry", "fk_skit_invite_registry_member", "tenant_id,member_id", "skit_member", "tenant_id,id"}
        };
        for (String[] foreignKey : expectedForeignKeys) {
            assertCompoundForeignKey(foreignKey);
        }

        jdbc().update("INSERT IGNORE INTO system_tenant (id,package_id) VALUES (102,100)");
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,app_key,status) "
                        + "VALUES (92002,102,'TAKU','foreign','foreign','foreign','',1)");
        jdbc().update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                        + "VALUES (93002,102,'13900000302','hash','foreign','MEMBER302',1,0)");

        assertThrows(DataAccessException.class, () -> jdbc().update(
                "INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) "
                        + "VALUES (101,92002,1,UNHEX(REPEAT('21',32)),b'1')"));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "INSERT INTO skit_content_entitlement "
                        + "(tenant_id,member_id,drama_id,episode_no,status,granted_at) "
                        + "VALUES (101,93002,1,1,'GRANTED',CURRENT_TIMESTAMP)"));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "INSERT INTO skit_ad_revenue_event "
                        + "(tenant_id,ad_account_id,provider,placement_id,external_event_id,source_member_id,"
                        + "gross_amount,occurred_time,completed,mock,status) "
                        + "VALUES (101,92002,'TAKU','foreign','cross-insert',301,1,CURRENT_TIMESTAMP,b'1',b'0',0)"));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "INSERT INTO skit_commission_ledger "
                        + "(tenant_id,event_id,beneficiary_type,beneficiary_member_id,level_no,gross_amount,"
                        + "rate_bps,amount,rule_version,status) VALUES (102,601,1,93002,1,1,1000,0.1,1,0)"));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "INSERT INTO skit_commission_ledger "
                        + "(tenant_id,event_id,beneficiary_type,beneficiary_member_id,level_no,gross_amount,"
                        + "rate_bps,amount,rule_version,status) VALUES (101,601,1,93002,2,1,1000,0.1,1,0)"));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "INSERT INTO skit_commission_ledger "
                        + "(tenant_id,event_id,beneficiary_type,beneficiary_member_id,level_no,gross_amount,"
                        + "rate_bps,amount,rule_version,status) VALUES (101,601,1,0,3,1,1000,0.1,1,0)"));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "INSERT INTO skit_commission_ledger "
                        + "(tenant_id,event_id,beneficiary_type,beneficiary_member_id,level_no,gross_amount,"
                        + "rate_bps,amount,rule_version,status) VALUES (101,601,2,301,-1,1,1000,0.1,1,0)"));
        jdbc().update("INSERT INTO skit_commission_ledger "
                + "(tenant_id,event_id,beneficiary_type,beneficiary_member_id,level_no,gross_amount,"
                + "rate_bps,amount,rule_version,status) VALUES (101,601,2,0,-1,1,1000,0.1,1,0)");
    }

    @Test
    void rerunningInitializerIsRestartIdempotent() {
        Integer before = jdbc().queryForObject("SELECT COUNT(*) FROM skit_schema_migration", Integer.class);

        new SkitSchemaInitializer(jdbc()).run(null);

        Integer after = jdbc().queryForObject("SELECT COUNT(*) FROM skit_schema_migration", Integer.class);
        assertEquals(before, after);
    }

    @Test
    void callbackInboxAcceptsTheLargestDocumentedRawPayloadInStrictMode() {
        String columnType = jdbc().queryForObject("SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='skit_ad_callback_inbox' "
                        + "AND COLUMN_NAME='payload_ciphertext'",
                String.class);
        assertEquals("mediumblob", columnType);

        jdbc().execute("SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION'");
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(tenant_id,provider,account_name,account_id,app_id,app_key,status) "
                        + "VALUES (?,?,?,?,?,?,?)",
                91001L, "PAYLOAD_TEST", "capacity", "capacity", "capacity", "", 1);
        Long accountId = jdbc().queryForObject("SELECT id FROM skit_ad_account "
                + "WHERE tenant_id=91001 AND provider='PAYLOAD_TEST'", Long.class);

        byte[] payload = new byte[32 * 1024];
        Arrays.fill(payload, (byte) 0x5a);
        jdbc().update("INSERT INTO skit_ad_callback_inbox "
                        + "(tenant_id,ad_account_id,provider,callback_type,idempotency_key,canonical_payload_hash,"
                        + "authentication_level,signature_status,payload_ciphertext,payload_nonce,payload_key_id,"
                        + "payload_envelope_version,payload_expires_at,received_at) "
                        + "VALUES (?,?,?,?,?,UNHEX(REPEAT('ab',32)),?,?,?,?,?,?,?,?)",
                91001L, accountId, "TAKU", "ILRD", "capacity-32-kib", "SIGNED_CORE", "VERIFIED",
                payload, new byte[12], "test-key-id", 1, LocalDateTime.now().plusDays(1), LocalDateTime.now());

        Integer storedLength = jdbc().queryForObject("SELECT OCTET_LENGTH(payload_ciphertext) "
                        + "FROM skit_ad_callback_inbox WHERE tenant_id=91001 AND idempotency_key='capacity-32-kib'",
                Integer.class);
        assertEquals(payload.length, storedLength);
    }

    private static String placeholders(int count) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append('?');
        }
        return result.toString();
    }

    private Object value(String column, String table, long id) {
        return jdbc().queryForObject("SELECT `" + column + "` FROM `" + table + "` WHERE id=?", Object.class, id);
    }

    private void assertCompoundForeignKey(String[] expected) {
        String actual = jdbc().queryForObject("SELECT CONCAT("
                        + "GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ','),'->',"
                        + "MIN(k.REFERENCED_TABLE_NAME),'(',GROUP_CONCAT(k.REFERENCED_COLUMN_NAME "
                        + "ORDER BY k.ORDINAL_POSITION SEPARATOR ','),'):',MIN(r.UPDATE_RULE),':',MIN(r.DELETE_RULE)) "
                        + "FROM information_schema.KEY_COLUMN_USAGE k "
                        + "JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
                        + "ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME "
                        + "AND r.TABLE_NAME=k.TABLE_NAME WHERE k.TABLE_SCHEMA=DATABASE() AND k.TABLE_NAME=? "
                        + "AND k.CONSTRAINT_NAME=? AND k.REFERENCED_TABLE_NAME IS NOT NULL",
                String.class, expected[0], expected[1]);
        assertEquals(expected[2] + "->" + expected[3] + "(" + expected[4] + "):RESTRICT:RESTRICT",
                actual, expected[0] + "." + expected[1]);
    }

}
