package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdCallbackRouteRegistryMigrationDO;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdCallbackRouteRegistryMigrationMapper;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRouteRegistryService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRoutingService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Proves that an account-level provider owner can never fall through to tenant reward routing. */
class SkitCallbackRoutingProviderCollisionMySqlIT extends SkitMySqlIntegrationTestBase {

    private AnnotationConfigApplicationContext context;
    private SkitCallbackRoutingService routingService;
    private SkitAdCallbackRouteRegistryMigrationMapper migrationMapper;

    @BeforeAll
    void openContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, this::dataSource);
        context.register(SkitCallbackRouteRegistryMySqlIT.RegistryConfiguration.class);
        context.refresh();
        routingService = context.getBean(SkitCallbackRoutingService.class);
        migrationMapper = context.getBean(SkitAdCallbackRouteRegistryMigrationMapper.class);
    }

    @AfterAll
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void providerRegistryOwnerRejectsHistoricalLegacyCollisionInEveryEarlyPhase() {
        String rawKey = Base64.getUrlEncoder().withoutPadding().encodeToString(sequence(41));
        assertEquals(43, rawKey.length());
        byte[] keyHash = sha256(rawKey);
        insertTenantLegacyOwner(keyHash);
        insertProviderRegistryOwner(keyHash);

        assertEquals("DUAL_WRITE", migrationPhase());
        assertRewardRejected(rawKey);

        assertEquals(1, jdbc().update("UPDATE skit_ad_callback_route_registry_migration SET "
                + "migration_phase='BACKFILL',phase_revision=phase_revision+1,updated_at=CURRENT_TIMESTAMP "
                + "WHERE singleton_id=1 AND migration_phase='DUAL_WRITE'"));
        assertEquals("BACKFILL", migrationPhase());
        assertRewardRejected(rawKey);

        SkitAdCallbackRouteRegistryMigrationDO backfill = migrationMapper.selectSingleton();
        assertEquals(1, migrationMapper.startVerification(backfill.getPhaseRevision(),
                sha256("callback-registry-verification-seed-v1"), LocalDateTime.now()));
        assertEquals("VERIFY", migrationPhase());
        assertRewardRejected(rawKey);
    }

    private void assertRewardRejected(String rawKey) {
        assertThrows(SkitCallbackRouteRegistryService.CallbackRouteRejectedException.class,
                () -> routingService.resolveTenantReward(rawKey, LocalDateTime.now()));
    }

    private void insertTenantLegacyOwner(byte[] keyHash) {
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,app_key,status) "
                        + "VALUES (9402,9401,'TAKU','LEGACY_COLLISION','LEGACY_COLLISION',"
                        + "'LEGACY_COLLISION','',1)");
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) "
                        + "VALUES (9401,9402,1,?,b'1')",
                keyHash);
    }

    private void insertProviderRegistryOwner(byte[] keyHash) {
        jdbc().update("INSERT INTO skit_ad_provider_connection "
                + "(connection_code,provider,account_mode,external_account_ref_hash,state,"
                + "created_by_user_id,created_at,updated_by_user_id,updated_at) "
                + "VALUES ('provider-reward-collision','TAKU','SHARED_MASTER',"
                + "UNHEX(REPEAT('44',32)),'CONFIGURING',1,CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP)");
        long connectionId = jdbc().queryForObject("SELECT id FROM skit_ad_provider_connection "
                + "WHERE connection_code='provider-reward-collision'", Long.class);
        jdbc().update("INSERT INTO skit_ad_provider_callback_route "
                        + "(provider_connection_id,route_version,purpose,state,route_slot,"
                        + "created_by_user_id,created_at,updated_by_user_id,updated_at) "
                        + "VALUES (?,1,'GATE_TEST','DRAFT','INACTIVE',1,"
                        + "CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP)",
                connectionId);
        long routeId = jdbc().queryForObject("SELECT id FROM skit_ad_provider_callback_route "
                + "WHERE provider_connection_id=?", Long.class, connectionId);
        jdbc().update("INSERT INTO skit_ad_callback_route_registry "
                        + "(key_hash,route_type,provider_callback_route_id,registered_at) "
                        + "VALUES (?,'PROVIDER_CALLBACK_ROUTE',?,CURRENT_TIMESTAMP)",
                keyHash, routeId);
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

    private static byte[] sequence(int start) {
        byte[] result = new byte[32];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) (start + index);
        }
        return result;
    }
}
