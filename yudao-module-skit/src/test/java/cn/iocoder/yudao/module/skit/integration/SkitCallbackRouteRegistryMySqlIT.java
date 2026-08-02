package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdAccountDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackKeyDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdRewardSecretVersionDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdCallbackRouteRegistryDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.provider.SkitAdCallbackRouteRegistryMigrationDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackKeyMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdRewardSecretVersionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdCallbackRouteRegistryMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.provider.SkitAdCallbackRouteRegistryMigrationMapper;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAdCredentialCryptoService;
import cn.iocoder.yudao.module.skit.framework.crypto.SkitAesGcmCredentialCryptoService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionService;
import cn.iocoder.yudao.module.skit.service.ad.SkitAdCredentialVersionServiceImpl;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRouteRegistryService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitCallbackRoutingService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SkitCallbackRouteRegistryMySqlIT extends SkitMySqlIntegrationTestBase {

    private static final byte[] TEST_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private AnnotationConfigApplicationContext context;
    private SkitAdCredentialVersionServiceImpl credentialService;
    private SkitCallbackRouteRegistryService registryService;
    private SkitCallbackRoutingService routingService;
    private SkitAdAccountMapper accountMapper;
    private SkitAdCallbackKeyMapper callbackKeyMapper;
    private SkitAdRewardSecretVersionMapper rewardSecretMapper;
    private String providerRawKey;

    @BeforeAll
    void openContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, this::dataSource);
        context.register(RegistryConfiguration.class);
        context.refresh();
        credentialService = context.getBean(SkitAdCredentialVersionServiceImpl.class);
        registryService = context.getBean(SkitCallbackRouteRegistryService.class);
        routingService = context.getBean(SkitCallbackRoutingService.class);
        accountMapper = context.getBean(SkitAdAccountMapper.class);
        callbackKeyMapper = context.getBean(SkitAdCallbackKeyMapper.class);
        rewardSecretMapper = context.getBean(SkitAdRewardSecretVersionMapper.class);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @AfterAll
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @Order(1)
    void tenantCreateRotateRevokeAndProviderCollisionRollbackAreAtomic() throws Exception {
        long tenantId = 9101L;
        long accountId = 9102L;
        insertAccount(tenantId, accountId, "ATOMIC");

        String first = credentialService.rotateCallbackKey(
                tenantId, accountId, Duration.ofMinutes(20)).consumeCallbackKey();
        String second = credentialService.rotateCallbackKey(
                tenantId, accountId, Duration.ofMinutes(20)).consumeCallbackKey();
        assertTenantRegistryOwner(first, tenantId, accountId, 1);
        assertTenantRegistryOwner(second, tenantId, accountId, 2);
        assertNull(jdbc().queryForObject("SELECT r.tombstoned_at FROM skit_ad_callback_route_registry r "
                        + "JOIN skit_ad_callback_key k ON k.id=r.tenant_callback_key_id "
                        + "WHERE k.tenant_id=? AND k.ad_account_id=? AND k.key_version=1",
                java.sql.Timestamp.class, tenantId, accountId));
        assertEquals(1, registryService.lookup(sha256(first), LocalDateTime.now())
                .getKeyVersion(), "retired grace key remains accepting and is not tombstoned");

        assertTrue(credentialService.revokeAllCallbackKeys(tenantId, accountId));
        assertEquals(2, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_callback_route_registry r "
                        + "JOIN skit_ad_callback_key k ON k.id=r.tenant_callback_key_id "
                        + "WHERE k.tenant_id=? AND k.ad_account_id=? AND r.tombstoned_at=k.revoked_at",
                Integer.class, tenantId, accountId));
        assertThrows(SkitCallbackRouteRegistryService.CallbackRouteRejectedException.class,
                () -> registryService.lookup(sha256(second), LocalDateTime.now().plusSeconds(1)));

        long collisionTenant = 9111L;
        long collisionAccount = 9112L;
        insertAccount(collisionTenant, collisionAccount, "COLLISION");
        byte[] collisionMaterial = sequence(17);
        providerRawKey = Base64.getUrlEncoder().withoutPadding().encodeToString(collisionMaterial);
        assertFalse(providerRawKey.startsWith("acct_"));
        insertProviderRegistryOwner(sha256(providerRawKey));
        SkitAdCredentialVersionServiceImpl deterministic = new SkitAdCredentialVersionServiceImpl(
                accountMapper, callbackKeyMapper, rewardSecretMapper,
                context.getBean(SkitAdCredentialCryptoService.class), registryService,
                Clock.systemUTC(), new FixedSecureRandom(collisionMaterial));

        assertThrows(IllegalStateException.class, () -> inTransaction(() ->
                deterministic.rotateCallbackKey(collisionTenant, collisionAccount, Duration.ZERO)));
        assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_callback_key "
                        + "WHERE tenant_id=? AND ad_account_id=?", Integer.class,
                collisionTenant, collisionAccount));
        assertEquals("PROVIDER_CALLBACK_ROUTE", jdbc().queryForObject(
                "SELECT route_type FROM skit_ad_callback_route_registry WHERE key_hash=?",
                String.class, sha256(providerRawKey)));
    }

    @Test
    @Order(2)
    void concurrentRotationAndCommittedBackfillReachReentrantEnforcedRegistry() throws Exception {
        long tenantId = 9201L;
        long accountId = 9202L;
        insertAccount(tenantId, accountId, "CONCURRENT");
        insertLegacyBackfillFixture(205);

        int rotations = 20;
        ExecutorService executor = Executors.newFixedThreadPool(rotations + 1);
        CountDownLatch ready = new CountDownLatch(rotations + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> issued = new ArrayList<>();
        Future<SkitCallbackRouteRegistryService.RegistryMigrationReport> migration;
        try {
            for (int index = 0; index < rotations; index++) {
                issued.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(30, TimeUnit.SECONDS));
                    return credentialService.rotateCallbackKey(
                            tenantId, accountId, Duration.ofMinutes(30)).consumeCallbackKey();
                }));
            }
            migration = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(30, TimeUnit.SECONDS));
                return registryService.backfillAndVerifyTenantKeys();
            });
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            start.countDown();
            Set<String> keys = new HashSet<>();
            for (Future<String> future : issued) {
                keys.add(future.get(60, TimeUnit.SECONDS));
            }
            assertEquals(rotations, keys.size());
            SkitCallbackRouteRegistryService.RegistryMigrationReport first =
                    migration.get(60, TimeUnit.SECONDS);
            assertEquals(SkitCallbackRouteRegistryService.MigrationPhase.SHADOW_READ,
                    first.getPhase());
            assertEquals(first, registryService.backfillAndVerifyTenantKeys());
            assertEquals(jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_callback_key", Long.class),
                    jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_callback_route_registry "
                            + "WHERE route_type='TENANT_CALLBACK_KEY'", Long.class));
            for (String raw : keys) {
                SkitCallbackRouteRegistryService.RouteLookup route = registryService.lookup(
                        sha256(raw), LocalDateTime.now());
                assertEquals(tenantId, route.getTenantId());
                assertEquals(accountId, route.getAdAccountId());
            }
            String routable = keys.iterator().next();
            assertEquals(tenantId, routingService.resolveTenantReward(
                    routable, LocalDateTime.now()).getTenantId());

            registryService.enableHashFirstReads();
            assertEquals("HASH_FIRST", migrationPhase());
            registryService.enableHashFirstReads();
            assertEquals("ENFORCED", migrationPhase());

            TenantContextHolder.setTenantId(999999L);
            assertEquals(tenantId, registryService.lookup(
                    sha256(routable), LocalDateTime.now()).getTenantId());
            assertEquals(999999L, TenantContextHolder.getTenantId());
            assertFalse(TenantContextHolder.isIgnore());
            assertThrows(SkitCallbackRouteRegistryService.CallbackRouteRejectedException.class,
                    () -> routingService.resolveTenantReward(
                            providerRawKey, LocalDateTime.now()));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private void assertTenantRegistryOwner(String rawKey, long tenantId,
                                           long accountId, int version) {
        SkitCallbackRouteRegistryService.RouteLookup lookup = registryService.lookup(
                sha256(rawKey), LocalDateTime.now());
        assertEquals(SkitCallbackRouteRegistryService.RouteType.TENANT_CALLBACK_KEY,
                lookup.getRouteType());
        assertEquals(tenantId, lookup.getTenantId());
        assertEquals(accountId, lookup.getAdAccountId());
        assertEquals(version, lookup.getKeyVersion());
    }

    private void insertProviderRegistryOwner(byte[] keyHash) {
        jdbc().update("INSERT INTO skit_ad_provider_connection "
                + "(connection_code,provider,account_mode,external_account_ref_hash,state,"
                + "created_by_user_id,created_at,updated_by_user_id,updated_at) "
                + "VALUES ('collision-provider','TAKU','SHARED_MASTER',UNHEX(REPEAT('11',32)),"
                + "'CONFIGURING',1,CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP)");
        long connectionId = jdbc().queryForObject("SELECT id FROM skit_ad_provider_connection "
                + "WHERE connection_code='collision-provider'", Long.class);
        jdbc().update("INSERT INTO skit_ad_provider_callback_route "
                + "(provider_connection_id,route_version,purpose,state,route_slot,"
                + "created_by_user_id,created_at,updated_by_user_id,updated_at) "
                + "VALUES (?,1,'GATE_TEST','DRAFT','INACTIVE',1,CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP)",
                connectionId);
        long routeId = jdbc().queryForObject("SELECT id FROM skit_ad_provider_callback_route "
                + "WHERE provider_connection_id=?", Long.class, connectionId);
        jdbc().update("INSERT INTO skit_ad_callback_route_registry "
                        + "(key_hash,route_type,provider_callback_route_id,registered_at) "
                        + "VALUES (?,'PROVIDER_CALLBACK_ROUTE',?,CURRENT_TIMESTAMP)",
                keyHash, routeId);
    }

    private void insertLegacyBackfillFixture(int count) {
        long tenantId = 9251L;
        long accountId = 9252L;
        insertAccount(tenantId, accountId, "BACKFILL");
        for (int version = 1; version <= count; version++) {
            jdbc().update("INSERT INTO skit_ad_callback_key "
                            + "(tenant_id,ad_account_id,key_version,callback_key_hash,active,accept_until) "
                            + "VALUES (?,?,?,?,b'0',CURRENT_TIMESTAMP)",
                    tenantId, accountId, version,
                    sha256("legacy-backfill-" + version));
        }
    }

    private String migrationPhase() {
        return jdbc().queryForObject("SELECT migration_phase FROM "
                + "skit_ad_callback_route_registry_migration WHERE singleton_id=1", String.class);
    }

    private void insertAccount(long tenantId, long accountId, String suffix) {
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,app_key,status) "
                        + "VALUES (?,?,'TAKU',?,?,?,'',1)",
                accountId, tenantId, suffix, suffix, suffix);
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

    private static final class FixedSecureRandom extends SecureRandom {
        private final byte[] value;

        private FixedSecureRandom(byte[] value) {
            this.value = value.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            System.arraycopy(value, 0, bytes, 0, bytes.length);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan(basePackages = {
            "cn.iocoder.yudao.module.skit.dal.mysql.ad",
            "cn.iocoder.yudao.module.skit.dal.mysql.provider"
    }, annotationClass = Mapper.class)
    static class RegistryConfiguration {

        @Bean
        TenantProperties tenantProperties() {
            return new TenantProperties();
        }

        @Bean
        MybatisPlusInterceptor mybatisPlusInterceptor(TenantProperties tenantProperties) {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                    new TenantDatabaseInterceptor(tenantProperties)));
            return interceptor;
        }

        @Bean
        MybatisSqlSessionFactoryBean sqlSessionFactory(DataSource dataSource,
                                                       MybatisPlusInterceptor interceptor) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration,
                    "callback-route-registry-mysql-it");
            TableInfoHelper.initTableInfo(assistant, SkitAdAccountDO.class);
            TableInfoHelper.initTableInfo(assistant, SkitAdCallbackKeyDO.class);
            TableInfoHelper.initTableInfo(assistant, SkitAdRewardSecretVersionDO.class);
            TableInfoHelper.initTableInfo(assistant, SkitAdCallbackRouteRegistryDO.class);
            TableInfoHelper.initTableInfo(assistant, SkitAdCallbackRouteRegistryMigrationDO.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        SkitAdCredentialCryptoService credentialCryptoService() {
            return new SkitAesGcmCredentialCryptoService("registry-it",
                    Collections.singletonMap("registry-it", TEST_KEY));
        }

        @Bean
        SkitCallbackRouteRegistryService registryService(
                SkitAdCallbackRouteRegistryMapper registryMapper,
                SkitAdCallbackRouteRegistryMigrationMapper migrationMapper,
                SkitAdCallbackKeyMapper callbackKeyMapper,
                MeterRegistry meterRegistry,
                PlatformTransactionManager transactionManager) {
            return new SkitCallbackRouteRegistryService(registryMapper, migrationMapper,
                    callbackKeyMapper, meterRegistry, transactionManager);
        }

        @Bean
        SkitAdCredentialVersionServiceImpl credentialService(
                SkitAdAccountMapper accountMapper,
                SkitAdCallbackKeyMapper callbackKeyMapper,
                SkitAdRewardSecretVersionMapper rewardSecretMapper,
                SkitAdCredentialCryptoService cryptoService,
                SkitCallbackRouteRegistryService registryService) {
            return new SkitAdCredentialVersionServiceImpl(accountMapper, callbackKeyMapper,
                    rewardSecretMapper, cryptoService, registryService);
        }

        @Bean
        SkitCallbackRoutingService routingService(SkitCallbackRouteRegistryService registryService) {
            return new SkitCallbackRoutingService(registryService);
        }
    }

}
