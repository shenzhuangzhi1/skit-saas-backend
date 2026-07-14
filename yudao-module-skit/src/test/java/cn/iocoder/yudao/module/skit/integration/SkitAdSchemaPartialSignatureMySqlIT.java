package cn.iocoder.yudao.module.skit.integration;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SkitAdSchemaPartialSignatureMySqlIT extends SkitMySqlIntegrationTestBase {

    @Override
    protected void beforeSkitSchemaInitialization(JdbcTemplate jdbc) {
        SkitLegacyAdSchemaFixture.installDisguisedPartialTask2Table(jdbc);
    }

    @Override
    protected boolean initializeSkitSchemaInBeforeAll() {
        return false;
    }

    @Test
    @Order(1)
    void matchingCriticalNamesCannotDisguiseAnIncompleteTask2Table() {
        Integer beforeColumns = callbackKeyColumnCount();
        assertEquals(5, beforeColumns);

        IllegalStateException exception = assertThrows(IllegalStateException.class, this::initializeSkitSchema);

        assertTrue(exception.getMessage().contains("Incompatible canonical Task 2 table skit_ad_callback_key"));
        assertEquals(beforeColumns, callbackKeyColumnCount());
        Integer otherTask2Tables = jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('skit_ad_reward_secret_version',"
                + "'skit_ad_policy_snapshot','skit_ad_session','skit_ad_callback_inbox')", Integer.class);
        assertEquals(0, otherTask2Tables, "fingerprint preflight must fail before further Task 2 DDL");
        Integer installed = jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_schema_migration WHERE version=2026071401", Integer.class);
        assertEquals(0, installed);
    }

    @Test
    @Order(2)
    void incompatibleLegacyPlacementColumnFailsBeforeAnyTask2Ddl() {
        jdbc().execute("DROP TABLE skit_ad_callback_key");
        jdbc().execute("ALTER TABLE skit_ad_revenue_event MODIFY COLUMN placement_id "
                + "varchar(64) NULL DEFAULT 'wrong'");
        Integer beforeTask2Tables = task2OwnedTableCount();
        assertEquals(0, beforeTask2Tables);

        IllegalStateException exception = assertThrows(IllegalStateException.class, this::initializeSkitSchema);

        assertTrue(exception.getMessage().contains("skit_ad_revenue_event.placement_id"));
        assertEquals(beforeTask2Tables, task2OwnedTableCount(),
                "parent-column signature preflight must fail before Task 2 DDL");
        Integer installed = jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_schema_migration WHERE version=2026071401", Integer.class);
        assertEquals(0, installed);
    }

    private Integer callbackKeyColumnCount() {
        return jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='skit_ad_callback_key'", Integer.class);
    }

    private Integer task2OwnedTableCount() {
        return jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('skit_ad_callback_key',"
                + "'skit_ad_reward_secret_version','skit_ad_policy_snapshot','skit_ad_session',"
                + "'skit_ad_callback_inbox')", Integer.class);
    }

}
