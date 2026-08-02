package cn.iocoder.yudao.module.skit.integration;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Real MySQL proof for phase-1 global connection constraints; each class owns an isolated schema. */
class SkitProviderConnectionLifecycleMySqlIT extends SkitMySqlIntegrationTestBase {

    @Test
    void sharedMasterSlotAndOwnerModeAreEnforcedByMySql() {
        insertConnection("one", "SHARED_MASTER", null, null, 1);
        assertThrows(DataAccessException.class,
                () -> insertConnection("two", "SHARED_MASTER", null, null, 2));
        assertThrows(DataAccessException.class,
                () -> insertConnection("bad", "SHARED_MASTER", 99L, null, 3));
        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_provider_connection WHERE account_mode='SHARED_MASTER'", Integer.class));
    }

    @Test
    void registryIsAppendOnlyAndCannotBeDeleted() {
        insertConnection("route-owner", "TENANT_OWNED", 97L, null, 7);
        Long connectionId = jdbc().queryForObject("SELECT id FROM skit_ad_provider_connection WHERE connection_code='route-owner'", Long.class);
        jdbc().update("INSERT INTO skit_ad_provider_callback_route (provider_connection_id,route_version,purpose,state,route_slot,created_by_user_id,created_at,updated_by_user_id,updated_at) VALUES (?,1,'GATE_TEST','DRAFT','INACTIVE',7,NOW(),7,NOW())", connectionId);
        Long routeId = jdbc().queryForObject("SELECT id FROM skit_ad_provider_callback_route WHERE provider_connection_id=?", Long.class, connectionId);
        jdbc().update("INSERT INTO skit_ad_callback_route_registry (key_hash,route_type,provider_callback_route_id,registered_at) VALUES (UNHEX(SHA2('registry-it',256)),'PROVIDER_CALLBACK_ROUTE',?,NOW())", routeId);
        assertThrows(DataAccessException.class,
                () -> jdbc().update("DELETE FROM skit_ad_callback_route_registry WHERE provider_callback_route_id=?", routeId));
    }

    @Test
    void routeStateTriggerRejectsSkippingIssuedAndConnectionCheckRejectsInvalidOwner() {
        insertConnection("trigger-owner", "TENANT_OWNED", 98L, null, 8);
        Long connectionId = jdbc().queryForObject("SELECT id FROM skit_ad_provider_connection WHERE connection_code='trigger-owner'", Long.class);
        jdbc().update("INSERT INTO skit_ad_provider_callback_route (provider_connection_id,route_version,purpose,state,route_slot,created_by_user_id,created_at,updated_by_user_id,updated_at) VALUES (?,1,'GATE_TEST','DRAFT','INACTIVE',8,NOW(),8,NOW())", connectionId);
        Long routeId = jdbc().queryForObject("SELECT id FROM skit_ad_provider_callback_route WHERE provider_connection_id=?", Long.class, connectionId);
        assertThrows(DataAccessException.class,
                () -> jdbc().update("UPDATE skit_ad_provider_callback_route SET state='SUBMITTED' WHERE id=?", routeId));
        assertThrows(DataAccessException.class,
                () -> insertConnection("bad-tenant-owned", "TENANT_OWNED", null, null, 9));
    }

    private void insertConnection(String code, String mode, Long tenantId, Long accountId, long actor) {
        jdbc().update("INSERT INTO skit_ad_provider_connection (connection_code,provider,account_mode,owner_tenant_id,owner_ad_account_id,external_account_ref_hash,state,created_by_user_id,created_at,updated_by_user_id,updated_at) VALUES (?,'TAKU',?,?,?,UNHEX(SHA2(?,256)),'CONFIGURING',?,NOW(),?,NOW())",
                code, mode, tenantId, accountId, code, actor, actor);
    }
}
