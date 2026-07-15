package cn.iocoder.yudao.module.skit.integration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdSchemaLegacyAdminRepairMySqlIT extends SkitMySqlIntegrationTestBase {

    private static final String BUSINESS_STATE_QUERY = "SELECT `id`,`tenant_id`,`page_key`,`record_data`,"
            + "`status`,`sort`,`creator`,DATE_FORMAT(`create_time`,'%Y-%m-%d %H:%i:%s') AS `create_time`,"
            + "`updater`,DATE_FORMAT(`update_time`,'%Y-%m-%d %H:%i:%s') AS `update_time`,"
            + "HEX(`deleted`) AS `deleted` FROM `skit_admin_record` ORDER BY `id`";

    @Override
    protected void beforeSkitSchemaInitialization(JdbcTemplate jdbc) {
        SkitLegacyAdSchemaFixture.installLegacyAdminRecordDuplicates(jdbc);
    }

    @Override
    protected boolean initializeSkitSchemaInBeforeAll() {
        return false;
    }

    @Test
    void repairsLegacyAdminKeysWithoutLosingOrCrossingTenantDataAndAuditsEveryChange() {
        List<Map<String, Object>> businessStateBefore = jdbc().queryForList(BUSINESS_STATE_QUERY);

        initializeSkitSchema();

        assertEquals(businessStateBefore, jdbc().queryForList(BUSINESS_STATE_QUERY),
                "repair must preserve every business and audit field other than row_key");
        assertEquals(9, rowCount("skit_admin_record"), "no legacy admin row may be deleted");
        assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM (SELECT 1 FROM skit_admin_record "
                + "GROUP BY tenant_id,page_key,row_key HAVING COUNT(*)>1) duplicate_rows", Integer.class));

        Map<Long, String> keys = jdbc().query("SELECT id,row_key FROM skit_admin_record ORDER BY id",
                        (rs, rowNum) -> new AbstractMap.SimpleImmutableEntry<>(
                                rs.getLong("id"), rs.getString("row_key")))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertEquals("duplicate", keys.get(11L));
        assertEquals("duplicate~legacy-dup-c", keys.get(12L));
        assertEquals("duplicate", keys.get(21L), "the other tenant retains its own lowest-id owner");
        assertEquals("duplicate~legacy-dup-m", keys.get(22L));
        assertEquals(String.join("", Collections.nCopies(128, "x")), keys.get(31L));
        assertTrue(keys.get(32L).endsWith("~legacy-dup-w-1"),
                "a preoccupied first candidate must advance deterministically");
        assertNotEquals(keys.get(32L), keys.get(33L));
        assertEquals("canonical~legacy-dup-15", keys.get(41L),
                "a deleted lower-id row must not retain the canonical business key");
        assertEquals("canonical", keys.get(42L),
                "an active row must retain the canonical business key even when its id is higher");
        assertTrue(keys.values().stream().allMatch(key -> key.length() <= 128));

        List<Map<String, Object>> auditRows = jdbc().queryForList(
                "SELECT migration_version,source_id,tenant_id,page_key,original_row_key,repaired_row_key,"
                        + "retained_id,algorithm FROM skit_admin_record_migration_audit ORDER BY source_id");
        assertEquals(4, auditRows.size());
        assertEquals(Arrays.asList(12L, 22L, 32L, 41L), auditRows.stream()
                .map(row -> ((Number) row.get("source_id")).longValue()).collect(Collectors.toList()));
        assertTrue(auditRows.stream().allMatch(row -> ((Number) row.get("migration_version")).intValue()
                == 2026071502));
        assertTrue(auditRows.stream().allMatch(row -> "rekey-legacy-admin-singletons-v1"
                .equals(row.get("algorithm"))));

        String indexDefinition = jdbc().queryForObject("SELECT CONCAT(MIN(NON_UNIQUE),':',"
                        + "GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')) "
                        + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='skit_admin_record' "
                        + "AND INDEX_NAME='uk_skit_admin_record_tenant_page_row'", String.class);
        assertEquals("0:tenant_id,page_key,row_key", indexDefinition);
        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM skit_schema_migration "
                + "WHERE version=2026071502", Integer.class));

        List<Map<String, Object>> keysBeforeRetry = jdbc().queryForList(
                "SELECT id,row_key FROM skit_admin_record ORDER BY id");
        initializeSkitSchema();
        assertEquals(keysBeforeRetry, jdbc().queryForList(
                "SELECT id,row_key FROM skit_admin_record ORDER BY id"));
        assertEquals(auditRows, jdbc().queryForList(
                "SELECT migration_version,source_id,tenant_id,page_key,original_row_key,repaired_row_key,"
                        + "retained_id,algorithm FROM skit_admin_record_migration_audit ORDER BY source_id"));
    }

    private int rowCount(String table) {
        Integer count = jdbc().queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class);
        return count == null ? 0 : count;
    }

}
