package cn.iocoder.yudao.module.skit.integration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdSchemaSameTableInviteCollisionMySqlIT extends SkitMySqlIntegrationTestBase {

    @Override
    protected void beforeSkitSchemaInitialization(JdbcTemplate jdbc) {
        SkitLegacyAdSchemaFixture.installSameTableInviteCollisions(jdbc);
    }

    @Override
    protected boolean initializeSkitSchemaInBeforeAll() {
        return false;
    }

    @Test
    void normalizedSameTableInviteCollisionsFailBeforeAnyTask2Ddl() {
        SkitTask2SchemaAssertions.assertNoTask2Artifacts(jdbc());

        IllegalStateException exception = assertThrows(IllegalStateException.class, this::initializeSkitSchema);

        assertTrue(exception.getMessage().contains("normalized global invite-code collision"));
        assertTrue(exception.getMessage().contains("AGENT-CODE"));
        assertTrue(exception.getMessage().contains("MEMBER-CODE"));
        assertTrue(exception.getMessage().contains("AGENT:101:101"));
        assertTrue(exception.getMessage().contains("AGENT:102:102"));
        assertTrue(exception.getMessage().contains("MEMBER:101:301"));
        assertTrue(exception.getMessage().contains("MEMBER:101:302"));
        SkitTask2SchemaAssertions.assertNoTask2Artifacts(jdbc());
    }

}
