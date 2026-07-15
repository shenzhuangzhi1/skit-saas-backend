package cn.iocoder.yudao.module.skit.framework.schema;

import cn.iocoder.yudao.module.skit.integration.SkitMySqlIntegrationTestBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SkitForwardSchemaDriftMySqlIT extends SkitMySqlIntegrationTestBase {

    @Test
    void markerDoesNotHideReleasedForeignKeyAndCheckDrift() {
        jdbc().execute("ALTER TABLE skit_ad_report_pull DROP FOREIGN KEY fk_skit_report_pull_account");
        jdbc().execute("ALTER TABLE skit_tenant_ad_capability DROP CHECK ck_skit_tenant_capability_state");

        assertThrows(IllegalStateException.class,
                () -> new SkitSchemaInitializer(jdbc()).validateTask2TableSignatures(true));
    }

}
