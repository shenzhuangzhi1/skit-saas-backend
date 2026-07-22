package cn.iocoder.yudao.module.skit.framework.schema;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SkitContentEntitlementLeaseActivationMigrationMySqlIT
        extends SkitPartialMigrationMySqlITBase {

    @Test
    void resumesAfterNullableAddAndBackfillsTheImmutableProofAnchorExactlyOnce() throws Exception {
        runThrough(2026071701);
        jdbc().update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                        + "VALUES (51,41,'13900000051','hash','member-51','INVITE051',1,0)");
        LocalDateTime grantedAt = LocalDateTime.of(2026, 7, 22, 1, 2, 3);
        jdbc().update("INSERT INTO skit_content_entitlement "
                        + "(id,tenant_id,member_id,drama_id,episode_no,status,granted_at,version) "
                        + "VALUES (81,41,51,61,3,'GRANTED',?,4)", grantedAt);

        executePrefix("contentEntitlementLeaseActivationSteps", 1);

        assertEquals("YES", nullable("skit_content_entitlement", "lease_activated_at"));
        assertNull(jdbc().queryForObject("SELECT lease_activated_at FROM skit_content_entitlement "
                + "WHERE tenant_id=41 AND id=81", LocalDateTime.class));
        assertEquals(0, migrationCount(2026072201));

        runThrough(2026072201);
        runThrough(2026072201);

        assertEquals("NO", nullable("skit_content_entitlement", "lease_activated_at"));
        assertEquals(grantedAt, jdbc().queryForObject(
                "SELECT lease_activated_at FROM skit_content_entitlement "
                        + "WHERE tenant_id=41 AND id=81", LocalDateTime.class));
        assertEquals(grantedAt, jdbc().queryForObject(
                "SELECT granted_at FROM skit_content_entitlement WHERE tenant_id=41 AND id=81",
                LocalDateTime.class));
        assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM skit_content_entitlement "
                + "WHERE lease_activated_at IS NULL", Integer.class));
        assertEquals(1, migrationCount(2026072201));
    }
}
