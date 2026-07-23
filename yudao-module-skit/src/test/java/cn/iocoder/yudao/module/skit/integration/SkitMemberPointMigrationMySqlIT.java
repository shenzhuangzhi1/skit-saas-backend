package cn.iocoder.yudao.module.skit.integration;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkitMemberPointMigrationMySqlIT extends SkitMySqlIntegrationTestBase {

    @Test
    void installsTenantSafeBalanceAndOneCheckInPerBeijingDateLedger() {
        assertEquals(1, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_schema_migration WHERE version=2026072401",
                Integer.class));
        assertEquals("0", jdbc().queryForObject(
                "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='skit_member' "
                        + "AND COLUMN_NAME='point_balance'",
                String.class));
        assertEquals("0:tenant_id,member_id,biz_type,biz_id", jdbc().queryForObject(
                "SELECT CONCAT(MIN(NON_UNIQUE),':',"
                        + "GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')) "
                        + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='skit_member_point_record' "
                        + "AND INDEX_NAME='uk_skit_member_point_business'",
                String.class));

        insertMember(42L, 81L, "13800000081", "POINT081");
        insertMember(43L, 82L, "13800000082", "POINT082");
        assertEquals(0, jdbc().queryForObject(
                "SELECT point_balance FROM skit_member WHERE tenant_id=42 AND id=81",
                Integer.class));

        insertPoint(42L, 81L, "2026-07-24", 1);
        assertThrows(DataIntegrityViolationException.class,
                () -> insertPoint(42L, 81L, "2026-07-24", 2));
        insertPoint(42L, 81L, "2026-07-25", 2);
        assertThrows(DataIntegrityViolationException.class,
                () -> insertPoint(43L, 81L, "2026-07-24", 1));

        assertEquals(2, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_member_point_record "
                        + "WHERE tenant_id=42 AND member_id=81 AND biz_type='CHECK_IN'",
                Integer.class));
    }

    private void insertMember(long tenantId, long memberId, String mobile, String inviteCode) {
        jdbc().update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                        + "VALUES (?,?,?,'hash','member',?,0,0)",
                memberId, tenantId, mobile, inviteCode);
    }

    private void insertPoint(long tenantId, long memberId, String date, int balanceAfter) {
        jdbc().update("INSERT INTO skit_member_point_record "
                        + "(tenant_id,member_id,biz_type,biz_id,title,description,"
                        + "point_delta,balance_after) "
                        + "VALUES (?,?,'CHECK_IN',?,'签到','签到获得 1 积分',1,?)",
                tenantId, memberId, date, balanceAfter);
    }

}
