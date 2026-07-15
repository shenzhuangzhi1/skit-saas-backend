package cn.iocoder.yudao.module.skit.framework.schema;

import cn.iocoder.yudao.module.skit.integration.SkitMySqlIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;

import java.lang.reflect.Field;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitEntitlementGrantRangeHardeningPreflightMySqlIT extends SkitMySqlIntegrationTestBase {

    @Override
    protected boolean initializeSkitSchemaInBeforeAll() {
        return false;
    }

    @Test
    void historicalGrantOutsideSessionRangeFailsBeforeReplacingLegacyForeignKey() throws Exception {
        applyReleasedMigrationsThrough2026071403();
        installOutOfRangeHistoricalGrant();

        IllegalStateException exception = assertThrows(IllegalStateException.class, this::initializeSkitSchema);

        assertTrue(exception.getMessage().contains("Task 7 schema preflight rejected"), exception.getMessage());
        assertTrue(exception.getMessage().contains("entitlement grant session range binding"),
                exception.getMessage());
        assertEquals(0, columnCount("skit_ad_session", "reward_callback_inbox_id"),
                "range preflight must fail before the first Task 7 ALTER TABLE");
        assertEquals(0, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_schema_migration WHERE version=2026071404", Integer.class));
    }

    @SuppressWarnings("unchecked")
    private void applyReleasedMigrationsThrough2026071403() throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbc());
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        List<SkitSchemaInitializer.Migration> released = new ArrayList<>();
        for (SkitSchemaInitializer.Migration migration
                : (List<SkitSchemaInitializer.Migration>) field.get(initializer)) {
            if (migration.getVersion() <= 2026071403) {
                released.add(migration);
            }
        }
        new SkitSchemaInitializer(jdbc(), released).run(null);
    }

    private void installOutOfRangeHistoricalGrant() {
        long tenantId = 97301L;
        long memberId = 9730101L;
        long accountId = 9730110L;
        long planId = 9730120L;
        long snapshotId = 9730130L;
        long sessionId = 9730140L;
        long entitlementId = 9730150L;
        jdbc().update("INSERT INTO system_tenant (id,name,package_id,status,expire_time) "
                        + "VALUES (?,'Task7 grant preflight',0,0,'2099-01-01 00:00:00')", tenantId);
        jdbc().update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                        + "VALUES (?,?,?,?,?,?,1,0)", memberId, tenantId, Long.toString(memberId),
                "encoded", "grant-preflight-member", "T7G97301");
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,status) "
                        + "VALUES (?,?,'TAKU','Task7 grant preflight','grant-preflight','grant-preflight',0)",
                accountId, tenantId);
        jdbc().update("INSERT INTO skit_commission_plan "
                        + "(id,tenant_id,version,status,published_time) VALUES (?,?,1,0,NOW())",
                planId, tenantId);
        jdbc().update("INSERT INTO skit_ad_policy_snapshot "
                        + "(id,tenant_id,plan_id,source_member_id,rule_version,snapshot_schema_version,"
                        + "snapshot_json,snapshot_hash,policy_snapshot_at) "
                        + "VALUES (?,?,?,?,1,1,'{}',UNHEX(SHA2('task7-grant-preflight-snapshot',256)),NOW())",
                snapshotId, tenantId, planId, memberId);
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) "
                        + "VALUES (?,?,1,UNHEX(SHA2('task7-grant-preflight-key',256)),b'1')",
                tenantId, accountId);
        jdbc().update("INSERT INTO skit_ad_reward_secret_version "
                        + "(tenant_id,ad_account_id,secret_version,ciphertext,nonce,encryption_key_id,"
                        + "envelope_version,active) VALUES (?,?,1,UNHEX(SHA2('task7-grant-preflight-secret',256)),"
                        + "UNHEX(REPEAT('11',12)),'test-key',1,b'1')", tenantId, accountId);
        jdbc().update("INSERT INTO skit_ad_session "
                        + "(id,tenant_id,session_id,session_token_hash,session_token_key_version,protocol_version,"
                        + "member_id,ad_account_id,policy_snapshot_id,callback_key_version,reward_secret_version,"
                        + "provider,placement_id,scenario_id,business_type,drama_id,episode_from,episode_to,"
                        + "unlock_scope,active_scope_hash,pseudonymous_user_id,access_mode,client_lifecycle_status,"
                        + "reward_verification_status,entitlement_status,revenue_status,load_expires_at,"
                        + "reward_accept_until,provider_transaction_id,last_callback_sequence,version) "
                        + "VALUES (?,?,?,?,1,1,?,?,?,1,1,'TAKU','placement','unlock','EPISODE_UNLOCK',41,1,3,"
                        + "'1-3',UNHEX(SHA2('task7-grant-preflight-scope',256)),'pseudo','MEMBER_OAUTH','CREATED',"
                        + "'PENDING','NONE','NONE',DATE_ADD(NOW(),INTERVAL 5 MINUTE),"
                        + "DATE_ADD(NOW(),INTERVAL 20 MINUTE),'grant-preflight-tx',-1,0)",
                sessionId, tenantId, "task7-grant-preflight-session", hash("task7-grant-preflight-token"),
                memberId, accountId, snapshotId);
        jdbc().update("INSERT INTO skit_content_entitlement "
                        + "(id,tenant_id,member_id,drama_id,episode_no,status,granted_at,version) "
                        + "VALUES (?,?,?,?,4,'GRANTED',NOW(),0)",
                entitlementId, tenantId, memberId, 41L);

        // Simulate a corrupted pre-Task-7 row without changing the released 1403 schema fingerprint.
        jdbc().execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=0");
                try {
                    statement.executeUpdate("INSERT INTO skit_entitlement_grant "
                            + "(id,tenant_id,ad_session_id,entitlement_id,member_id,drama_id,episode_no,"
                            + "provider_transaction_id,grant_result,granted_at) VALUES "
                            + "(9730160,97301,9730140,9730150,9730101,41,4,"
                            + "'grant-preflight-tx','CREATED',NOW())");
                } finally {
                    statement.execute("SET FOREIGN_KEY_CHECKS=1");
                }
            }
            return null;
        });
    }

    private int columnCount(String table, String column) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?",
                Integer.class, table, column);
    }

    private static byte[] hash(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
