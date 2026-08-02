package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRouteRegistryService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses an isolated immutable registry to prove that verification evidence survives fail-closed cutover. */
class SkitCallbackRouteRegistryMismatchMySqlIT extends SkitMySqlIntegrationTestBase {

    private AnnotationConfigApplicationContext context;
    private SkitCallbackRouteRegistryService registryService;

    @BeforeAll
    void openContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, this::dataSource);
        context.register(SkitCallbackRouteRegistryMySqlIT.RegistryConfiguration.class);
        context.refresh();
        registryService = context.getBean(SkitCallbackRouteRegistryService.class);
    }

    @AfterAll
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void ownerAndFullSetMismatchPersistentlyBlockBeforeHashFirst() {
        long tenantId = 9301L;
        long accountId = 9302L;
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,app_key,status) "
                        + "VALUES (?,?,'TAKU','MISMATCH','MISMATCH','MISMATCH','',1)",
                accountId, tenantId);
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active,accept_until) "
                        + "VALUES (?,?,1,?,b'0',CURRENT_TIMESTAMP)",
                tenantId, accountId, sha256("mismatch-1"));
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) "
                        + "VALUES (?,?,2,?,b'1')",
                tenantId, accountId, sha256("mismatch-2"));
        List<java.util.Map<String, Object>> keys = jdbc().queryForList(
                "SELECT id,callback_key_hash FROM skit_ad_callback_key ORDER BY id");
        assertEquals(2, keys.size());
        jdbc().update("INSERT INTO skit_ad_callback_route_registry "
                        + "(key_hash,route_type,tenant_callback_key_id,registered_at) "
                        + "VALUES (?,'TENANT_CALLBACK_KEY',?,CURRENT_TIMESTAMP)",
                keys.get(0).get("callback_key_hash"), keys.get(1).get("id"));
        assertEquals(1, jdbc().update("UPDATE skit_ad_callback_route_registry_migration SET "
                + "migration_phase='BACKFILL',phase_revision=1,updated_at=CURRENT_TIMESTAMP "
                + "WHERE singleton_id=1 AND migration_phase='DUAL_WRITE' AND phase_revision=0"));

        assertThrows(SkitCallbackRouteRegistryService.RegistryMigrationBlockedException.class,
                registryService::backfillAndVerifyTenantKeys);
        assertEquals("BACKFILL", migrationPhase());
        assertNotNull(jdbc().queryForObject("SELECT blocked_at FROM "
                + "skit_ad_callback_route_registry_migration WHERE singleton_id=1",
                java.sql.Timestamp.class));
        assertNull(jdbc().queryForObject("SELECT verified_at FROM "
                + "skit_ad_callback_route_registry_migration WHERE singleton_id=1",
                java.sql.Timestamp.class));
        assertThrows(SkitCallbackRouteRegistryService.RegistryMigrationBlockedException.class,
                registryService::enableHashFirstReads);
        assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM "
                        + "skit_ad_callback_route_registry_migration WHERE migration_phase IN "
                        + "('HASH_FIRST','ENFORCED')", Integer.class));
        assertTrue(registryService.migrationReport().isBlocked());
    }

    private String migrationPhase() {
        return jdbc().queryForObject("SELECT migration_phase FROM "
                + "skit_ad_callback_route_registry_migration WHERE singleton_id=1", String.class);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
