package cn.iocoder.yudao.module.skit.framework.schema;

import cn.iocoder.yudao.module.skit.integration.SkitMySqlIntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdNetworkCapabilityHardeningPreflightMySqlIT extends SkitMySqlIntegrationTestBase {

    @Override
    protected boolean initializeSkitSchemaInBeforeAll() {
        return false;
    }

    @Test
    void signedRewardWithoutVerifiedBindingCapabilitiesFailsBeforeAnyTask7Ddl() throws Exception {
        applyReleasedMigrationsThrough2026071403();
        long tenantId = 97201L;
        long accountId = 9720110L;
        jdbc().update("INSERT INTO system_tenant (id,name,package_id,status,expire_time) "
                        + "VALUES (?,'Task7 capability preflight',0,0,'2099-01-01 00:00:00')", tenantId);
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,status) "
                        + "VALUES (?,?,'TAKU','Task7 capability preflight','cap-preflight','cap-preflight',0)",
                accountId, tenantId);
        jdbc().update("INSERT INTO skit_ad_network_capability "
                        + "(id,tenant_id,ad_account_id,network_firm_id,reward_authority,enabled) "
                        + "VALUES (9720120,?,?,66,'SIGNED_REWARD',b'1')", tenantId, accountId);

        IllegalStateException exception = assertThrows(IllegalStateException.class, this::initializeSkitSchema);

        assertTrue(exception.getMessage().contains("Task 7 schema preflight rejected"), exception.getMessage());
        assertTrue(exception.getMessage().contains("signed reward network readiness"), exception.getMessage());
        assertEquals(0, columnCount("skit_ad_session", "reward_callback_inbox_id"),
                "network readiness preflight must fail before the first Task 7 ALTER TABLE");
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

    private int columnCount(String table, String column) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?",
                Integer.class, table, column);
    }

}
