package cn.iocoder.yudao.module.skit.integration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdSchemaLegacySingletonPreflightMySqlIT extends SkitMySqlIntegrationTestBase {

    @Override
    protected void beforeSkitSchemaInitialization(JdbcTemplate jdbc) {
        SkitLegacyAdSchemaFixture.installLegacySystemConfigDuplicates(jdbc);
    }

    @Override
    protected boolean initializeSkitSchemaInBeforeAll() {
        return false;
    }

    @Test
    void duplicateLegacySystemConfigFailsBeforeAnyTask2Ddl() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, this::initializeSkitSchema);

        assertTrue(failure.getMessage().contains("legacy system config singleton"));
        SkitTask2SchemaAssertions.assertNoTask2Artifacts(jdbc());
        Integer repairAuditTableCount = jdbc().queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='skit_admin_record_migration_audit'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, repairAuditTableCount,
                "an unrelated fail-closed singleton must not trigger admin-record repair DDL");
    }

}
