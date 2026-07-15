package cn.iocoder.yudao.module.skit.framework.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitTask12OutOfOrderPrefixMySqlIT extends SkitPartialMigrationMySqlITBase {

    @Test
    void rejectsAnOutOfOrderTask12ColumnBeforeRunningMoreDdl() throws Exception {
        runThrough(2026071405);
        executeOnly("task12ReadinessSteps", 1);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> runThrough(2026071406));

        assertTrue(exception.getMessage().contains("Task 12"), exception.getMessage());
        assertEquals(0, columnCount("skit_tenant_ad_capability", "dedicated_unlock_placement_id"));
        assertEquals(0, migrationCount(2026071406));
    }

}
