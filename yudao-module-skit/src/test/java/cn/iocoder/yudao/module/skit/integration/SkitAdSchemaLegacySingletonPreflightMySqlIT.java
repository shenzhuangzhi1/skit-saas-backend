package cn.iocoder.yudao.module.skit.integration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdSchemaLegacySingletonPreflightMySqlIT extends SkitMySqlIntegrationTestBase {

    @Override
    protected void beforeSkitSchemaInitialization(JdbcTemplate jdbc) {
        SkitLegacyAdSchemaFixture.installLegacySingletonDuplicates(jdbc);
    }

    @Override
    protected boolean initializeSkitSchemaInBeforeAll() {
        return false;
    }

    @Test
    void duplicateLegacySingletonsFailBeforeAnyTask2Ddl() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, this::initializeSkitSchema);

        assertTrue(failure.getMessage().contains("legacy admin record singleton"));
        SkitTask2SchemaAssertions.assertNoTask2Artifacts(jdbc());

        jdbc().update("DELETE FROM skit_admin_record WHERE id=(SELECT duplicate_id FROM "
                + "(SELECT MAX(id) AS duplicate_id FROM skit_admin_record) duplicate_row)");
        failure = assertThrows(IllegalStateException.class, this::initializeSkitSchema);
        assertTrue(failure.getMessage().contains("legacy system config singleton"));
        SkitTask2SchemaAssertions.assertNoTask2Artifacts(jdbc());
    }

}
