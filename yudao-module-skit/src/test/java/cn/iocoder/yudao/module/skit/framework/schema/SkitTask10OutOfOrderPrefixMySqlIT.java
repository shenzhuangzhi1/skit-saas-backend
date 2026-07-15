package cn.iocoder.yudao.module.skit.framework.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitTask10OutOfOrderPrefixMySqlIT extends SkitPartialMigrationMySqlITBase {

    @Test
    void rejectsAnOutOfOrderTask10TableBeforeRunningMoreDdl() throws Exception {
        runThrough(2026071404);
        executeOnly("task10ReconciliationSteps", 1);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> runThrough(2026071405));

        assertTrue(exception.getMessage().contains("Task 10"), exception.getMessage());
        assertEquals(0, tableCount("skit_ad_reporting_credential_version"));
        assertEquals(0, migrationCount(2026071405));
    }

}
