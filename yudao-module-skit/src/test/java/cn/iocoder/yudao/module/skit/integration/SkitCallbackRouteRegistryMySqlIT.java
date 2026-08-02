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
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private PlatformTransactionManager transactionManager;
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
        transactionManager = context.getBean(PlatformTransactionManager.class);
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
    void historicalRotateAndRevokeBackfillOwnersBeforeRetireOrTombstone() throws Exception {
        long tenantId = 9101L;
        long accountId = 9102L;
        insertAccount(tenantId, accountId, "ATOMIC");

        String first = Base64.getUrlEncoder().withoutPadding().encodeToString(sequence(1));
        insertLegacyCallbackKey(tenantId, accountId, 1, first, true, null);
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

        long historicalTenant = 9103L;
        long historicalAccount = 9104L;
        insertAccount(historicalTenant, historicalAccount, "HISTORICAL_REVOKE");
        LocalDateTime stillAccepted = LocalDateTime.now().plusHours(1);
        insertLegacyCallbackKey(historicalTenant, historicalAccount, 1,
                Base64.getUrlEncoder().withoutPadding().encodeToString(sequence(61)),
                false, stillAccepted);
        insertLegacyCallbackKey(historicalTenant, historicalAccount, 2,
                Base64.getUrlEncoder().withoutPadding().encodeToString(sequence(93)),
                false, stillAccepted);
        insertLegacyCallbackKey(historicalTenant, historicalAccount, 3,
                Base64.getUrlEncoder().withoutPadding().encodeToString(sequence(125)),
                true, null);
        assertTrue(credentialService.revokeAllCallbackKeys(historicalTenant, historicalAccount));
        assertEquals(3, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_callback_route_registry r "
                        + "JOIN skit_ad_callback_key k ON k.id=r.tenant_callback_key_id "
                        + "WHERE k.tenant_id=? AND k.ad_account_id=? AND r.tombstoned_at=k.revoked_at",
                Integer.class, historicalTenant, historicalAccount));

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
    void committedVerificationBatchesRestartAfterGatedCredentialMutation() throws Exception {
        long tenantId = 9201L;
        long accountId = 9202L;
        insertAccount(tenantId, accountId, "CONCURRENT");
        insertLegacyBackfillFixture(205);
        assertEquals(1, jdbc().update("UPDATE skit_ad_callback_route_registry_migration SET "
                + "migration_phase='BACKFILL',phase_revision=phase_revision+1,updated_at=CURRENT_TIMESTAMP "
                + "WHERE singleton_id=1 AND migration_phase='DUAL_WRITE'"));

        int rotations = 20;
        ExecutorService executor = Executors.newFixedThreadPool(rotations);
        CountDownLatch ready = new CountDownLatch(rotations);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> issued = new ArrayList<>();
        try {
            for (int index = 0; index < rotations; index++) {
                issued.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(30, TimeUnit.SECONDS));
                    return credentialService.rotateCallbackKey(
                            tenantId, accountId, Duration.ofMinutes(30)).consumeCallbackKey();
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            start.countDown();
            Set<String> keys = new HashSet<>();
            for (Future<String> future : issued) {
                keys.add(future.get(60, TimeUnit.SECONDS));
            }
            assertEquals(rotations, keys.size());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        VerificationMutationBarrier barrier = context.getBean(VerificationMutationBarrier.class);
        barrier.arm();
        ExecutorService migrationExecutor = Executors.newSingleThreadExecutor();
        ExecutorService mutationExecutor = Executors.newSingleThreadExecutor(
                work -> new Thread(work, VerificationMutationBarrier.MUTATION_THREAD_NAME));
        Future<SkitCallbackRouteRegistryService.RegistryMigrationReport> migration =
                migrationExecutor.submit(registryService::backfillAndVerifyTenantKeys);
        Future<String> mutation = null;
        try {
            assertTrue(barrier.awaitSecondVerificationBatch(),
                    "second verification batch did not reach the transaction barrier");
            VerificationProgress firstProgress = verificationProgress();
            assertEquals("VERIFY", firstProgress.phase);
            assertEquals(200L, firstProgress.expectedCount);
            assertEquals(firstProgress.expectedCount, firstProgress.actualCount);
            assertEquals(0L, firstProgress.mismatchCount);

            long mutationTenant = 9211L;
            long mutationAccount = 9212L;
            insertAccount(mutationTenant, mutationAccount, "VERIFY_MUTATION");
            mutation = mutationExecutor.submit(() -> credentialService.rotateCallbackKey(
                    mutationTenant, mutationAccount, Duration.ofMinutes(30)).consumeCallbackKey());
            assertTrue(barrier.awaitMutationGateAttempt(),
                    "credential mutation did not queue on the verification gate");
            barrier.releaseSecondVerificationBatch();
            assertTrue(barrier.awaitMutationOwnsGate(),
                    "credential mutation did not acquire the gate after the second batch commit");
            VerificationProgress secondProgress = verificationProgress();
            assertEquals(firstProgress.runId, secondProgress.runId);
            assertTrue(secondProgress.cursor > firstProgress.cursor);
            assertTrue(secondProgress.expectedCount > firstProgress.expectedCount);
            assertEquals(secondProgress.expectedCount, secondProgress.actualCount);
            assertEquals(0L, secondProgress.mismatchCount);

            barrier.releaseMutationGate();
            assertTrue(barrier.awaitRestartedVerificationBatch(),
                    "verification did not restart from cursor zero after credential mutation");
            VerificationProgress restarted = verificationProgress();
            assertEquals(firstProgress.runId + 1, restarted.runId);
            assertEquals(0L, restarted.cursor);
            assertEquals(0L, restarted.expectedCount);
            assertEquals(0L, restarted.actualCount);
            assertEquals(0L, restarted.mismatchCount);
            assertTrue(restarted.snapshotEpoch > firstProgress.snapshotEpoch);
            barrier.releaseRestartedVerificationBatch();

            String mutationKey = mutation.get(60, TimeUnit.SECONDS);
            assertNotNull(mutationKey);
            SkitCallbackRouteRegistryService.RegistryMigrationReport completed =
                    migration.get(60, TimeUnit.SECONDS);
            assertEquals(SkitCallbackRouteRegistryService.MigrationPhase.SHADOW_READ,
                    completed.getPhase());
            assertEquals(completed, registryService.backfillAndVerifyTenantKeys());
            VerificationProgress finalProgress = verificationProgress();
            assertEquals(restarted.runId, finalProgress.runId);
            assertTrue(finalProgress.expectedCount > 200L);
            assertEquals(finalProgress.expectedCount, finalProgress.actualCount);
            assertEquals(0L, finalProgress.mismatchCount);
            assertEquals(jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_callback_key", Long.class),
                    jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_callback_route_registry "
                            + "WHERE route_type='TENANT_CALLBACK_KEY'", Long.class));

            String routable = issued.get(0).get(60, TimeUnit.SECONDS);
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
                    () -> routingService.resolveTenantReward(providerRawKey, LocalDateTime.now()));
        } finally {
            barrier.releaseAll();
            migrationExecutor.shutdownNow();
            mutationExecutor.shutdownNow();
        }
    }

    @Test
    @Order(3)
    void suspendedOuterMutationCannotAuthorizeRequiresNewRegistryCommit() {
        long tenantId = 9301L;
        long accountId = 9302L;
        insertAccount(tenantId, accountId, "TOKEN_SUSPEND");
        TenantContextHolder.setTenantId(tenantId);

        String innerRawKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sequence(157));
        byte[] innerHash = sha256(innerRawKey);
        insertLegacyCallbackKey(tenantId, accountId, 1, innerRawKey, false, null);
        SkitAdCallbackKeyDO innerCandidate = callbackKeyMapper.selectByHash(innerHash);
        assertNotNull(innerCandidate);
        SkitCallbackRouteRegistryService.TenantCallbackKeyRegistration innerRegistration =
                SkitCallbackRouteRegistryService.TenantCallbackKeyRegistration.fromLegacy(
                        innerCandidate, LocalDateTime.now().withNano(0));

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AtomicLong rolledBackLegacyId = new AtomicLong();
        byte[] outerHash = sha256("outer-transaction-callback-key");

        outer.executeWithoutResult(status -> {
            SkitCallbackRouteRegistryService.TenantKeyMutation mutation =
                    registryService.beginTenantKeyMutation(tenantId, accountId);
            SkitAdCallbackKeyDO outerLegacy = new SkitAdCallbackKeyDO()
                    .setAdAccountId(accountId).setKeyVersion(2)
                    .setCallbackKeyHash(outerHash).setActive(true);
            outerLegacy.setTenantId(tenantId);
            assertEquals(1, callbackKeyMapper.insert(outerLegacy));
            assertNotNull(outerLegacy.getId());
            rolledBackLegacyId.set(outerLegacy.getId());

            assertThrows(IllegalStateException.class, () -> requiresNew.executeWithoutResult(
                    innerStatus -> registryService.registerTenantKey(mutation, innerRegistration)));
            assertEquals(0, jdbc().queryForObject(
                    "SELECT COUNT(*) FROM `skit_ad_callback_route_registry` "
                            + "WHERE `tenant_callback_key_id`=?", Integer.class,
                    innerCandidate.getId()));

            registryService.registerTenantKey(mutation,
                    SkitCallbackRouteRegistryService.TenantCallbackKeyRegistration.fromLegacy(
                            outerLegacy, LocalDateTime.now().withNano(0)));
            assertEquals(1, jdbc().queryForObject(
                    "SELECT COUNT(*) FROM `skit_ad_callback_route_registry` "
                            + "WHERE `tenant_callback_key_id`=?", Integer.class,
                    outerLegacy.getId()));
            status.setRollbackOnly();
        });

        assertTrue(rolledBackLegacyId.get() > 0L);
        assertEquals(0, jdbc().queryForObject(
                "SELECT COUNT(*) FROM `skit_ad_callback_key` WHERE `id`=?",
                Integer.class, rolledBackLegacyId.get()));
        assertEquals(0, jdbc().queryForObject(
                "SELECT COUNT(*) FROM `skit_ad_callback_route_registry` "
                        + "WHERE `tenant_callback_key_id` IN (?,?)", Integer.class,
                innerCandidate.getId(), rolledBackLegacyId.get()));
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

    private void insertLegacyCallbackKey(long tenantId, long accountId, int version,
                                         String rawKey, boolean active,
                                         LocalDateTime acceptUntil) {
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active,accept_until) "
                        + "VALUES (?,?,?,?,?,?)",
                tenantId, accountId, version, sha256(rawKey), active, acceptUntil);
    }

    private VerificationProgress verificationProgress() {
        return jdbc().queryForObject("SELECT migration_phase,verification_run_id,"
                        + "verification_snapshot_epoch,verification_cursor_callback_key_id,"
                        + "verification_expected_progress_count,verification_actual_progress_count,"
                        + "verification_progress_mismatch_count "
                        + "FROM skit_ad_callback_route_registry_migration WHERE singleton_id=1",
                (resultSet, rowNumber) -> new VerificationProgress(
                        resultSet.getString("migration_phase"),
                        resultSet.getLong("verification_run_id"),
                        resultSet.getLong("verification_snapshot_epoch"),
                        resultSet.getLong("verification_cursor_callback_key_id"),
                        resultSet.getLong("verification_expected_progress_count"),
                        resultSet.getLong("verification_actual_progress_count"),
                        resultSet.getLong("verification_progress_mismatch_count")));
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

    private static final class VerificationProgress {
        private final String phase;
        private final long runId;
        private final long snapshotEpoch;
        private final long cursor;
        private final long expectedCount;
        private final long actualCount;
        private final long mismatchCount;

        private VerificationProgress(String phase, long runId, long snapshotEpoch,
                                     long cursor, long expectedCount, long actualCount,
                                     long mismatchCount) {
            this.phase = phase;
            this.runId = runId;
            this.snapshotEpoch = snapshotEpoch;
            this.cursor = cursor;
            this.expectedCount = expectedCount;
            this.actualCount = actualCount;
            this.mismatchCount = mismatchCount;
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static final class VerificationMutationBarrier implements Interceptor {

        static final String MUTATION_THREAD_NAME = "registry-verification-credential-mutation";
        private static final String VERIFICATION_SELECT =
                "SkitAdCallbackRouteRegistryMapper.selectVerificationPairsAfterId";
        private static final String SINGLETON_LOCK =
                "SkitAdCallbackRouteRegistryMigrationMapper.selectSingletonForUpdate";

        private final AtomicInteger verificationSelectCount = new AtomicInteger();
        private final CountDownLatch secondVerificationBatch = new CountDownLatch(1);
        private final CountDownLatch releaseSecondVerificationBatch = new CountDownLatch(1);
        private final CountDownLatch mutationGateAttempt = new CountDownLatch(1);
        private final CountDownLatch mutationOwnsGate = new CountDownLatch(1);
        private final CountDownLatch releaseMutationGate = new CountDownLatch(1);
        private final CountDownLatch restartedVerificationBatch = new CountDownLatch(1);
        private final CountDownLatch releaseRestartedVerificationBatch = new CountDownLatch(1);
        private volatile boolean armed;
        private volatile boolean mutationAttempted;

        void arm() {
            armed = true;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            String statementId = statement.getId();
            if (!armed) {
                return invocation.proceed();
            }
            if (statementId.endsWith(SINGLETON_LOCK)
                    && MUTATION_THREAD_NAME.equals(Thread.currentThread().getName())) {
                mutationAttempted = true;
                mutationGateAttempt.countDown();
                Object result = invocation.proceed();
                mutationOwnsGate.countDown();
                awaitRelease(releaseMutationGate, "credential mutation gate release");
                return result;
            }
            if (!statementId.endsWith(VERIFICATION_SELECT)) {
                return invocation.proceed();
            }
            long afterId = afterId(invocation.getArgs()[1]);
            int selectNumber = verificationSelectCount.incrementAndGet();
            if (selectNumber == 2) {
                secondVerificationBatch.countDown();
                awaitRelease(releaseSecondVerificationBatch, "second verification batch release");
            } else if (mutationAttempted && afterId == 0L) {
                restartedVerificationBatch.countDown();
                awaitRelease(releaseRestartedVerificationBatch,
                        "restarted verification batch release");
            }
            return invocation.proceed();
        }

        boolean awaitSecondVerificationBatch() throws InterruptedException {
            return secondVerificationBatch.await(30, TimeUnit.SECONDS);
        }

        boolean awaitMutationGateAttempt() throws InterruptedException {
            return mutationGateAttempt.await(30, TimeUnit.SECONDS);
        }

        boolean awaitMutationOwnsGate() throws InterruptedException {
            return mutationOwnsGate.await(30, TimeUnit.SECONDS);
        }

        boolean awaitRestartedVerificationBatch() throws InterruptedException {
            return restartedVerificationBatch.await(30, TimeUnit.SECONDS);
        }

        void releaseSecondVerificationBatch() {
            releaseSecondVerificationBatch.countDown();
        }

        void releaseMutationGate() {
            releaseMutationGate.countDown();
        }

        void releaseRestartedVerificationBatch() {
            releaseRestartedVerificationBatch.countDown();
        }

        void releaseAll() {
            releaseSecondVerificationBatch();
            releaseMutationGate();
            releaseRestartedVerificationBatch();
        }

        private static long afterId(Object parameter) {
            if (!(parameter instanceof Map)) {
                throw new IllegalStateException("Verification mapper parameters are unavailable");
            }
            Object value = ((Map<?, ?>) parameter).get("afterId");
            if (!(value instanceof Number)) {
                throw new IllegalStateException("Verification cursor parameter is unavailable");
            }
            return ((Number) value).longValue();
        }

        private static void awaitRelease(CountDownLatch latch, String boundary)
                throws InterruptedException {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for " + boundary);
            }
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
                                                       MybatisPlusInterceptor interceptor,
                                                       VerificationMutationBarrier barrier) {
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
            factory.setPlugins(interceptor, barrier);
            return factory;
        }

        @Bean
        VerificationMutationBarrier verificationMutationBarrier() {
            return new VerificationMutationBarrier();
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
