package cn.iocoder.yudao.module.skit.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * MySQL smoke coverage for the integration-test foundation.
 *
 * <p>Maven Failsafe explicitly includes this foundation smoke test alongside
 * the later {@code *MySqlIT} integration tests.</p>
 */
public class SkitMySqlIntegrationSmokeTest extends SkitMySqlIntegrationTestBase {

    @Test
    void usesRepeatableReadTransactionIsolation() {
        String isolation = inTransaction(() ->
                jdbc().queryForObject("SELECT @@transaction_isolation", String.class));
        assertEquals("REPEATABLE-READ", isolation);
    }

    @Test
    void acquiresExistingRowIdWithLastInsertIdUpsert() {
        jdbc().execute("CREATE TABLE `skit_it_acquisition` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,"
                + "`business_key` varchar(64) NOT NULL,"
                + "PRIMARY KEY (`id`),"
                + "UNIQUE KEY `uk_skit_it_acquisition_business_key` (`business_key`)) ENGINE=InnoDB");

        Long insertedId = acquire("event-1");
        Long existingId = acquire("event-1");

        assertNotNull(insertedId);
        assertEquals(insertedId, existingId);
        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM `skit_it_acquisition`", Integer.class));
    }

    private Long acquire(String businessKey) {
        return inTransaction(() -> {
            jdbc().update("INSERT INTO `skit_it_acquisition` (`business_key`) VALUES (?) "
                            + "ON DUPLICATE KEY UPDATE `id` = LAST_INSERT_ID(`id`)",
                    businessKey);
            return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        });
    }

}
