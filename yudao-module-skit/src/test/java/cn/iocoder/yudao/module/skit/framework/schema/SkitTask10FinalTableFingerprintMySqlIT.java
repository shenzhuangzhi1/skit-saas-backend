package cn.iocoder.yudao.module.skit.framework.schema;

import cn.iocoder.yudao.module.skit.integration.SkitMySqlIntegrationTestBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SkitTask10FinalTableFingerprintMySqlIT extends SkitMySqlIntegrationTestBase {

    @Test
    void rejectsMissingIndexForeignKeyAndCheckOnTask10OwnedTable() {
        jdbc().execute("ALTER TABLE skit_ad_reconciliation_allocation "
                + "DROP FOREIGN KEY fk_skit_recon_allocation_bucket,"
                + "DROP CHECK ck_skit_recon_allocation_money,"
                + "DROP INDEX idx_skit_recon_allocation_prior");

        assertThrows(IllegalStateException.class,
                () -> new SkitSchemaInitializer(jdbc()).validateTask10ReconciliationSchema(true));
    }

}
