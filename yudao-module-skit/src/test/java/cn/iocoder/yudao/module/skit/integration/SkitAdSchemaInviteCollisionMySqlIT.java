package cn.iocoder.yudao.module.skit.integration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdSchemaInviteCollisionMySqlIT extends SkitMySqlIntegrationTestBase {

    @Override
    protected void beforeSkitSchemaInitialization(JdbcTemplate jdbc) {
        SkitLegacyAdSchemaFixture.installInviteCollision(jdbc);
    }

    @Override
    protected boolean initializeSkitSchemaInBeforeAll() {
        return false;
    }

    @Test
    void normalizedCrossTableInviteCollisionFailsBeforeAnyTask2Ddl() {
        SkitTask2SchemaAssertions.assertNoTask2Artifacts(jdbc());

        IllegalStateException exception = assertThrows(IllegalStateException.class, this::initializeSkitSchema);

        assertTrue(exception.getMessage().contains("normalized global invite-code collision"));
        assertTrue(exception.getMessage().contains("AGENT:101:101"));
        assertTrue(exception.getMessage().contains("MEMBER:101:301"));
        SkitTask2SchemaAssertions.assertNoTask2Artifacts(jdbc());
    }

}
