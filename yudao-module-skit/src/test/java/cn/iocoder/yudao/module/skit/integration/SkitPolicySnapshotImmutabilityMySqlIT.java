package cn.iocoder.yudao.module.skit.integration;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitPolicySnapshotImmutabilityMySqlIT extends SkitMySqlIntegrationTestBase {

    private static final String IMMUTABLE_MESSAGE = "policy snapshot rows are immutable";

    @Test
    void migrationInstallsBothUnconditionalSnapshotTriggers() {
        List<Map<String, Object>> triggers = jdbc().queryForList(
                "SELECT TRIGGER_NAME,EVENT_MANIPULATION,ACTION_STATEMENT FROM information_schema.TRIGGERS "
                        + "WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='skit_ad_policy_snapshot' "
                        + "ORDER BY TRIGGER_NAME");

        assertEquals(2, triggers.size());
        assertEquals(Arrays.asList("trg_skit_policy_snapshot_immutable", "trg_skit_policy_snapshot_no_delete"),
                Arrays.asList(triggers.get(0).get("TRIGGER_NAME"), triggers.get(1).get("TRIGGER_NAME")));
        assertEquals("UPDATE", triggers.get(0).get("EVENT_MANIPULATION"));
        assertEquals("DELETE", triggers.get(1).get("EVENT_MANIPULATION"));
        assertTrue(String.valueOf(triggers.get(0).get("ACTION_STATEMENT")).contains(IMMUTABLE_MESSAGE));
        assertTrue(String.valueOf(triggers.get(1).get("ACTION_STATEMENT")).contains(IMMUTABLE_MESSAGE));

        Map<String, Object> migration = jdbc().queryForMap(
                "SELECT description,checksum FROM skit_schema_migration WHERE version=2026071402");
        assertEquals("enforce ad policy snapshot immutability", migration.get("description"));
        assertEquals(64, String.valueOf(migration.get("checksum")).length());
    }

    @Test
    void insertRemainsAllowedButJsonAndHashCannotBeRewrittenTogether() {
        long snapshotId = insertSnapshot(9201L);

        assertDoesNotThrow(() -> assertEquals("{\"version\":1}", snapshotJson(snapshotId)));
        assertMutationRejected("UPDATE skit_ad_policy_snapshot SET snapshot_json='{\"version\":2}',"
                + "snapshot_hash=UNHEX(REPEAT('22',32)) WHERE id=" + snapshotId);
        assertEquals("{\"version\":1}", snapshotJson(snapshotId));
        assertEquals(repeat("11", 32), snapshotHash(snapshotId));
    }

    @Test
    void softDeleteAndPhysicalDeleteAreBothRejected() {
        long softDeleteTarget = insertSnapshot(9202L);
        long physicalDeleteTarget = insertSnapshot(9203L);

        assertMutationRejected("UPDATE skit_ad_policy_snapshot SET deleted=b'1' WHERE id=" + softDeleteTarget);
        assertMutationRejected("DELETE FROM skit_ad_policy_snapshot WHERE id=" + physicalDeleteTarget);
        assertEquals(1, snapshotCount(softDeleteTarget));
        assertEquals(false, jdbc().queryForObject(
                "SELECT deleted FROM skit_ad_policy_snapshot WHERE id=?", Boolean.class, softDeleteTarget));
        assertEquals(1, snapshotCount(physicalDeleteTarget));
    }

    private long insertSnapshot(long tenantId) {
        long memberId = tenantId * 10 + 1;
        long planId = tenantId * 10 + 2;
        long snapshotId = tenantId * 10 + 3;
        jdbc().update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                        + "VALUES (?,?,?,?,?,?,0,0)",
                memberId, tenantId, Long.toString(memberId), "encoded", "snapshot-member-" + tenantId,
                "SNAP" + tenantId);
        jdbc().update("INSERT INTO skit_commission_plan "
                        + "(id,tenant_id,version,status,published_time) VALUES (?,?,1,0,CURRENT_TIMESTAMP)",
                planId, tenantId);
        jdbc().update("INSERT INTO skit_ad_policy_snapshot "
                        + "(id,tenant_id,plan_id,source_member_id,rule_version,snapshot_schema_version,"
                        + "snapshot_json,snapshot_hash,policy_snapshot_at) "
                        + "VALUES (?,?,?,?,1,1,'{\"version\":1}',UNHEX(REPEAT('11',32)),CURRENT_TIMESTAMP)",
                snapshotId, tenantId, planId, memberId);
        return snapshotId;
    }

    private void assertMutationRejected(String sql) {
        DataAccessException exception = assertThrows(DataAccessException.class, () -> jdbc().update(sql));
        assertTrue(exception.getMostSpecificCause().getMessage().contains(IMMUTABLE_MESSAGE),
                exception.getMostSpecificCause().getMessage());
    }

    private String snapshotJson(long snapshotId) {
        return jdbc().queryForObject("SELECT snapshot_json FROM skit_ad_policy_snapshot WHERE id=?",
                String.class, snapshotId);
    }

    private String snapshotHash(long snapshotId) {
        return jdbc().queryForObject("SELECT LOWER(HEX(snapshot_hash)) FROM skit_ad_policy_snapshot WHERE id=?",
                String.class, snapshotId);
    }

    private int snapshotCount(long snapshotId) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_policy_snapshot WHERE id=?",
                Integer.class, snapshotId);
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
