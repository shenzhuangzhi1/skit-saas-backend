package cn.iocoder.yudao.module.skit.framework.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitTask10WrongTablePrefixMySqlIT extends SkitPartialMigrationMySqlITBase {

    @Test
    void rejectsWrongSameNamedTask10TableBeforeRunningMoreDdl() throws Exception {
        runThrough(2026071404);
        jdbc().execute("CREATE TABLE skit_ad_reporting_credential_version (id bigint NOT NULL PRIMARY KEY) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> runThrough(2026071405));

        assertTrue(exception.getMessage().contains("Task 10"), exception.getMessage());
        assertEquals(0, tableCount("skit_ad_reconciliation_allocation"));
        assertEquals(0, migrationCount(2026071405));
    }

}
