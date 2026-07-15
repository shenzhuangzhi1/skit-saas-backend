package cn.iocoder.yudao.module.skit.framework.schema;

import cn.iocoder.yudao.module.skit.integration.SkitMySqlIntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdSessionSchemaHardeningPreflightMySqlIT extends SkitMySqlIntegrationTestBase {

    @Override
    protected boolean initializeSkitSchemaInBeforeAll() {
        return false;
    }

    @Test
    void unreconstructableReleasedActiveScopeFailsBeforeAnyTask5Ddl() throws Exception {
        applyReleasedMigrationsThrough2026071402();
        installReleasedSessionWithoutScopeReleaseEvidence();

        IllegalStateException exception = assertThrows(IllegalStateException.class, this::initializeSkitSchema);

        assertTrue(exception.getMessage().contains("Task 5 schema preflight rejected"), exception.getMessage());
        assertTrue(exception.getMessage().contains("active scope release evidence"), exception.getMessage());
        assertEquals(0, columnCount("skit_ad_session", "session_token_key_version"),
                "preflight must fail before the first Task 5 ALTER TABLE");
        assertEquals(0, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_schema_migration WHERE version=2026071403", Integer.class));
    }

    @SuppressWarnings("unchecked")
    private void applyReleasedMigrationsThrough2026071402() throws Exception {
        SkitSchemaInitializer initializer = new SkitSchemaInitializer(jdbc());
        Field field = SkitSchemaInitializer.class.getDeclaredField("migrations");
        field.setAccessible(true);
        List<SkitSchemaInitializer.Migration> released = new ArrayList<>();
        for (SkitSchemaInitializer.Migration migration
                : (List<SkitSchemaInitializer.Migration>) field.get(initializer)) {
            if (migration.getVersion() <= 2026071402) {
                released.add(migration);
            }
        }
        new SkitSchemaInitializer(jdbc(), released).run(null);
    }

    private void installReleasedSessionWithoutScopeReleaseEvidence() {
        long tenantId = 97001L;
        long memberId = 9700101L;
        long accountId = 9700102L;
        long planId = 9700103L;
        long snapshotId = 9700104L;
        jdbc().update("INSERT INTO system_tenant (id,name,package_id) "
                        + "VALUES (?,\'Task5 preflight\',(SELECT MIN(id) FROM system_tenant_package))",
                tenantId);
        jdbc().update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                        + "VALUES (?,?,?,?,?,?,1,0)",
                memberId, tenantId, Long.toString(memberId), "encoded", "preflight-member", "T5P97001");
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,app_key,status) "
                        + "VALUES (?,?,\'TAKU\',\'Task5 preflight\',\'preflight-account\',\'preflight-app\',\'\',1)",
                accountId, tenantId);
        jdbc().update("INSERT INTO skit_commission_plan "
                        + "(id,tenant_id,version,status,published_time) VALUES (?,?,1,0,CURRENT_TIMESTAMP)",
                planId, tenantId);
        jdbc().update("INSERT INTO skit_ad_policy_snapshot "
                        + "(id,tenant_id,plan_id,source_member_id,rule_version,snapshot_schema_version,"
                        + "snapshot_json,snapshot_hash,policy_snapshot_at) "
                        + "VALUES (?,?,?,?,1,1,\'{}\',UNHEX(SHA2(\'preflight-snapshot\',256)),CURRENT_TIMESTAMP)",
                snapshotId, tenantId, planId, memberId);
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) "
                        + "VALUES (?,?,1,UNHEX(SHA2(\'preflight-callback\',256)),b\'1\')",
                tenantId, accountId);
        jdbc().update("INSERT INTO skit_ad_reward_secret_version "
                        + "(tenant_id,ad_account_id,secret_version,ciphertext,nonce,encryption_key_id,"
                        + "envelope_version,active) VALUES (?,?,1,UNHEX(SHA2(\'preflight-secret\',256)),"
                        + "UNHEX(REPEAT(\'11\',12)),\'test-key\',1,b\'1\')",
                tenantId, accountId);
        jdbc().update("INSERT INTO skit_ad_session "
                        + "(id,tenant_id,session_id,session_token_hash,protocol_version,member_id,ad_account_id,"
                        + "policy_snapshot_id,callback_key_version,reward_secret_version,provider,placement_id,"
                        + "scenario_id,business_type,drama_id,episode_from,episode_to,unlock_scope,active_scope_hash,"
                        + "pseudonymous_user_id,load_expires_at,reward_accept_until) "
                        + "VALUES (9700120,?,\'preflight-session\',UNHEX(SHA2(\'preflight-token\',256)),1,?,?,?,1,1,"
                        + "\'TAKU\',\'placement\',\'drama_unlock\',\'DRAMA_EPISODE_UNLOCK\',41,1,1,"
                        + "\'v1|DRAMA_EPISODE_UNLOCK|PANGLE_DJX|41|1|1\',NULL,\'pseudo\',CURRENT_TIMESTAMP,"
                        + "DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 20 MINUTE))",
                tenantId, memberId, accountId, snapshotId);
    }

    private int columnCount(String table, String column) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?",
                Integer.class, table, column);
    }

}
