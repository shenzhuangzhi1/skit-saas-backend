package cn.iocoder.yudao.module.skit.framework.schema;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SkitProviderImpressionPhase1MySqlIT extends SkitPartialMigrationMySqlITBase {

    private static final int MIGRATION_VERSION = 2026080201;
    private static final List<String> TABLES = Arrays.asList(
            "skit_ad_provider_connection",
            "skit_ad_provider_callback_route",
            "skit_ad_callback_route_registry",
            "skit_ad_callback_route_registry_migration",
            "skit_provider_impression_inbox",
            "skit_provider_callback_attempt",
            "skit_platform_provider_command_audit");

    @Test
    void freshDatabaseInstallsExactGlobalCaptureSchemaAndMigrationOnce() throws Exception {
        runThrough(MIGRATION_VERSION);

        for (String table : TABLES) {
            assertEquals(1, tableCount(table), "missing table " + table);
            assertEquals(0, columnCount(table, "tenant_id"),
                    table + " must not acquire tenant ownership");
        }
        assertEquals("0:provider_connection_id,dedupe_scheme,dedupe_key_hash",
                indexDefinition("skit_provider_impression_inbox",
                        "uk_provider_impression_inbox_dedupe"));
        assertEquals("binary(32):NO", columnDefinition(
                "skit_provider_impression_inbox", "dedupe_key_hash"));
        assertEquals("binary(12):NO", columnDefinition(
                "skit_provider_callback_attempt", "payload_nonce"));
        assertEquals("canonical_attempt_id->skit_provider_callback_attempt(id):RESTRICT:RESTRICT",
                foreignKeyDefinition("skit_provider_impression_inbox",
                        "fk_provider_impression_inbox_canonical_attempt"));
        assertEquals("provider_connection_id->skit_ad_provider_connection(id):RESTRICT:RESTRICT",
                foreignKeyDefinition("skit_provider_callback_attempt",
                        "fk_provider_callback_attempt_connection"));
        assertEquals("inbox_id->skit_provider_impression_inbox(id):RESTRICT:RESTRICT",
                foreignKeyDefinition("skit_provider_callback_attempt",
                        "fk_provider_callback_attempt_inbox"));
        assertNotNull(checkDefinition("skit_ad_provider_callback_route",
                "ck_provider_callback_route_purpose"));
        assertNotNull(checkDefinition("skit_ad_callback_route_registry",
                "ck_callback_route_registry_owner_xor"));
        assertNotNull(triggerDefinition("trg_provider_callback_route_purpose_immutable"));
        assertNotNull(triggerDefinition("trg_callback_route_registry_migration_monotonic"));
        assertNotNull(triggerDefinition("trg_platform_provider_command_audit_immutable"));
        assertNotNull(triggerDefinition("trg_platform_provider_command_audit_no_delete"));
        assertEquals(1, jdbc().queryForObject(
                "SELECT COUNT(*) FROM `skit_ad_callback_route_registry_migration` WHERE `singleton_id`=1 "
                        + "AND `last_callback_key_id`=0", Integer.class));

        String storedChecksum = jdbc().queryForObject(
                "SELECT `checksum` FROM `skit_schema_migration` WHERE `version`=?",
                String.class, MIGRATION_VERSION);
        assertEquals(migration(MIGRATION_VERSION).getChecksum(), storedChecksum);
        runThrough(MIGRATION_VERSION);
        assertEquals(1, migrationCount(MIGRATION_VERSION));
        assertEquals(storedChecksum, jdbc().queryForObject(
                "SELECT `checksum` FROM `skit_schema_migration` WHERE `version`=?",
                String.class, MIGRATION_VERSION));
    }

    private String columnDefinition(String table, String column) {
        return jdbc().queryForObject("SELECT CONCAT(`COLUMN_TYPE`,':',`IS_NULLABLE`) "
                        + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME=? AND COLUMN_NAME=?",
                String.class, table, column);
    }

    private String indexDefinition(String table, String index) {
        return jdbc().queryForObject("SELECT CONCAT(MIN(`NON_UNIQUE`),':',"
                        + "GROUP_CONCAT(`COLUMN_NAME` ORDER BY `SEQ_IN_INDEX` SEPARATOR ',')) "
                        + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME=? AND INDEX_NAME=?",
                String.class, table, index);
    }

    private String foreignKeyDefinition(String table, String constraint) {
        return jdbc().queryForObject("SELECT CONCAT(GROUP_CONCAT(`k`.`COLUMN_NAME` "
                        + "ORDER BY `k`.`ORDINAL_POSITION` SEPARATOR ','),'->',"
                        + "MIN(`k`.`REFERENCED_TABLE_NAME`),'(',GROUP_CONCAT(`k`.`REFERENCED_COLUMN_NAME` "
                        + "ORDER BY `k`.`ORDINAL_POSITION` SEPARATOR ','),'):',MIN(`r`.`UPDATE_RULE`),':',"
                        + "MIN(`r`.`DELETE_RULE`)) FROM information_schema.KEY_COLUMN_USAGE `k` "
                        + "JOIN information_schema.REFERENTIAL_CONSTRAINTS `r` "
                        + "ON `r`.`CONSTRAINT_SCHEMA`=`k`.`CONSTRAINT_SCHEMA` "
                        + "AND `r`.`CONSTRAINT_NAME`=`k`.`CONSTRAINT_NAME` "
                        + "AND `r`.`TABLE_NAME`=`k`.`TABLE_NAME` WHERE `k`.`TABLE_SCHEMA`=DATABASE() "
                        + "AND `k`.`TABLE_NAME`=? AND `k`.`CONSTRAINT_NAME`=? "
                        + "AND `k`.`REFERENCED_TABLE_NAME` IS NOT NULL",
                String.class, table, constraint);
    }

    private String checkDefinition(String table, String constraint) {
        return jdbc().queryForObject("SELECT `cc`.`CHECK_CLAUSE` FROM "
                        + "information_schema.TABLE_CONSTRAINTS `tc` "
                        + "JOIN information_schema.CHECK_CONSTRAINTS `cc` "
                        + "ON `cc`.`CONSTRAINT_SCHEMA`=`tc`.`CONSTRAINT_SCHEMA` "
                        + "AND `cc`.`CONSTRAINT_NAME`=`tc`.`CONSTRAINT_NAME` "
                        + "WHERE `tc`.`TABLE_SCHEMA`=DATABASE() AND `tc`.`TABLE_NAME`=? "
                        + "AND `tc`.`CONSTRAINT_NAME`=? AND `tc`.`CONSTRAINT_TYPE`='CHECK'",
                String.class, table, constraint);
    }

    private String triggerDefinition(String trigger) {
        return jdbc().queryForObject("SELECT `ACTION_STATEMENT` FROM information_schema.TRIGGERS "
                        + "WHERE `TRIGGER_SCHEMA`=DATABASE() AND `TRIGGER_NAME`=?",
                String.class, trigger);
    }

}
