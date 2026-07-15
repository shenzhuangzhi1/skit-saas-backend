package cn.iocoder.yudao.module.skit.framework.schema;

import cn.iocoder.yudao.module.skit.integration.SkitMySqlIntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdCallbackSchemaHardeningPreflightMySqlIT extends SkitMySqlIntegrationTestBase {

    @Override
    protected boolean initializeSkitSchemaInBeforeAll() {
        return false;
    }

    @Test
    void crossAccountHistoricalInboxFailsBeforeAnyTask7Ddl() throws Exception {
        applyReleasedMigrationsThrough2026071403();
        installCrossAccountHistoricalInbox();

        IllegalStateException exception = assertThrows(IllegalStateException.class, this::initializeSkitSchema);

        assertTrue(exception.getMessage().contains("Task 7 schema preflight rejected"), exception.getMessage());
        assertTrue(exception.getMessage().contains("callback inbox session/account binding"),
                exception.getMessage());
        assertEquals(0, columnCount("skit_ad_session", "reward_callback_inbox_id"),
                "preflight must fail before the first Task 7 ALTER TABLE");
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

    private void installCrossAccountHistoricalInbox() {
        long tenantId = 97101L;
        long memberId = 9710101L;
        long accountOne = 9710110L;
        long accountTwo = 9710111L;
        long planId = 9710120L;
        long snapshotId = 9710130L;
        long sessionId = 9710140L;
        jdbc().update("INSERT INTO system_tenant (id,name,package_id,status,expire_time) "
                        + "VALUES (?,'Task7 preflight',0,0,'2099-01-01 00:00:00')", tenantId);
        jdbc().update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                        + "VALUES (?,?,?,?,?,?,1,0)", memberId, tenantId, Long.toString(memberId),
                "encoded", "preflight-member", "T7P97101");
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,status) "
                        + "VALUES (?,?,'TAKU','Task7 preflight A','preflight-a','preflight-a',0)",
                accountOne, tenantId);
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,status) "
                        + "VALUES (?,?,'PANGLE','Task7 preflight B','preflight-b','preflight-b',0)",
                accountTwo, tenantId);
        jdbc().update("INSERT INTO skit_commission_plan "
                        + "(id,tenant_id,version,status,published_time) VALUES (?,?,1,0,NOW())",
                planId, tenantId);
        jdbc().update("INSERT INTO skit_ad_policy_snapshot "
                        + "(id,tenant_id,plan_id,source_member_id,rule_version,snapshot_schema_version,"
                        + "snapshot_json,snapshot_hash,policy_snapshot_at) "
                        + "VALUES (?,?,?,?,1,1,'{}',UNHEX(SHA2('task7-preflight-snapshot',256)),NOW())",
                snapshotId, tenantId, planId, memberId);
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) "
                        + "VALUES (?,?,1,UNHEX(SHA2('task7-preflight-callback-a',256)),b'1')",
                tenantId, accountOne);
        jdbc().update("INSERT INTO skit_ad_reward_secret_version "
                        + "(tenant_id,ad_account_id,secret_version,ciphertext,nonce,encryption_key_id,"
                        + "envelope_version,active) VALUES (?,?,1,UNHEX(SHA2('task7-preflight-secret-a',256)),"
                        + "UNHEX(REPEAT('11',12)),'test-key',1,b'1')", tenantId, accountOne);
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) "
                        + "VALUES (?,?,1,UNHEX(SHA2('task7-preflight-callback-b',256)),b'1')",
                tenantId, accountTwo);
        jdbc().update("INSERT INTO skit_ad_reward_secret_version "
                        + "(tenant_id,ad_account_id,secret_version,ciphertext,nonce,encryption_key_id,"
                        + "envelope_version,active) VALUES (?,?,1,UNHEX(SHA2('task7-preflight-secret-b',256)),"
                        + "UNHEX(REPEAT('22',12)),'test-key',1,b'1')", tenantId, accountTwo);
        jdbc().update("INSERT INTO skit_ad_session "
                        + "(id,tenant_id,session_id,session_token_hash,session_token_key_version,protocol_version,"
                        + "member_id,ad_account_id,policy_snapshot_id,callback_key_version,reward_secret_version,"
                        + "provider,placement_id,scenario_id,business_type,drama_id,episode_from,episode_to,"
                        + "unlock_scope,active_scope_hash,pseudonymous_user_id,access_mode,client_lifecycle_status,"
                        + "reward_verification_status,entitlement_status,revenue_status,load_expires_at,"
                        + "reward_accept_until,last_callback_sequence,version) "
                        + "VALUES (?,?,?,?,1,1,?,?,?,1,1,'TAKU','placement','drama_unlock','EPISODE_UNLOCK',41,1,1,"
                        + "'scope',UNHEX(SHA2('task7-preflight-scope',256)),'pseudo','MEMBER_OAUTH','CREATED',"
                        + "'PENDING','NONE','NONE',DATE_ADD(NOW(),INTERVAL 5 MINUTE),"
                        + "DATE_ADD(NOW(),INTERVAL 20 MINUTE),-1,0)",
                sessionId, tenantId, "task7-preflight-session", hash("task7-preflight-token"),
                memberId, accountOne, snapshotId);
        jdbc().update("INSERT INTO skit_ad_callback_inbox "
                        + "(id,tenant_id,ad_account_id,ad_session_id,callback_key_version,reward_secret_version,"
                        + "provider,callback_type,idempotency_key,canonical_payload_hash,authentication_level,"
                        + "signature_status,received_at) VALUES (9710150,?,?,?,1,1,'TAKU','REWARD',"
                        + "'task7-preflight-inbox',UNHEX(SHA2('task7-preflight-payload',256)),"
                        + "'SIGNED_REWARD','VALID',NOW())",
                tenantId, accountTwo, sessionId);
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
