package cn.iocoder.yudao.module.skit.integration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdSchemaLedgerBeneficiaryPreflightMySqlIT extends SkitMySqlIntegrationTestBase {

    @Override
    protected void beforeSkitSchemaInitialization(JdbcTemplate jdbc) {
        SkitLegacyAdSchemaFixture.installCrossTenantLedgerBeneficiary(jdbc);
    }

    @Override
    protected boolean initializeSkitSchemaInBeforeAll() {
        return false;
    }

    @Test
    void crossTenantMemberBeneficiaryFailsBeforeAnyTask2Ddl() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, this::initializeSkitSchema);

        assertTrue(failure.getMessage().contains("ledger member beneficiary"));
        assertTrue(failure.getMessage().contains("701"));
        SkitTask2SchemaAssertions.assertNoTask2Artifacts(jdbc());
    }

}
