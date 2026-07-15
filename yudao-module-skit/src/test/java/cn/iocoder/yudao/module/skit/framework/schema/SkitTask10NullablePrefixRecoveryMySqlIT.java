package cn.iocoder.yudao.module.skit.framework.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkitTask10NullablePrefixRecoveryMySqlIT extends SkitPartialMigrationMySqlITBase {

    @Test
    void resumesAfterBothTransitionalColumnsWereAddedAsNullable() throws Exception {
        runThrough(2026071404);
        executePrefix("task10ReconciliationSteps", 5);
        assertEquals("YES", nullable("skit_ad_report_pull", "report_date"));
        assertEquals("YES", nullable("skit_ad_report_pull", "request_hash"));
        assertEquals(0, migrationCount(2026071405));

        runThrough(2026071405);

        assertEquals("NO", nullable("skit_ad_report_pull", "report_date"));
        assertEquals("NO", nullable("skit_ad_report_pull", "request_hash"));
        assertEquals(1, migrationCount(2026071405));
    }

}
