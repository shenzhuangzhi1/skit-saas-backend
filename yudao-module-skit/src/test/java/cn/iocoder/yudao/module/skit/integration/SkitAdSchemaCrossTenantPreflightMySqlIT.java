package cn.iocoder.yudao.module.skit.integration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdSchemaCrossTenantPreflightMySqlIT extends SkitMySqlIntegrationTestBase {

    @Override
    protected void beforeSkitSchemaInitialization(JdbcTemplate jdbc) {
        SkitLegacyAdSchemaFixture.installCrossTenantRevenueReference(jdbc);
    }

    @Override
    protected boolean initializeSkitSchemaInBeforeAll() {
        return false;
    }

    @Test
    void crossTenantFinanceReferenceFailsBeforeAnyTask2Ddl() {
        SkitTask2SchemaAssertions.assertNoTask2Artifacts(jdbc());

        IllegalStateException exception = assertThrows(IllegalStateException.class, this::initializeSkitSchema);

        assertTrue(exception.getMessage().contains(
                "orphan or cross-tenant reference skit_ad_revenue_event.ad_account_id"));
        assertTrue(exception.getMessage().contains("tenant_id=101"));
        assertTrue(exception.getMessage().contains("owner_id=601"));
        assertTrue(exception.getMessage().contains("referenced_tenant_id=102"));
        SkitTask2SchemaAssertions.assertNoTask2Artifacts(jdbc());
    }

}
