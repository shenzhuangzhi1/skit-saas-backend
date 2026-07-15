package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackAttemptDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackInboxDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdSessionDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the callback inbox persistence boundary against production MySQL DDL and mapper SQL.
 *
 * <p>The fixture intentionally starts only MyBatis and Spring transaction management. It does not
 * boot the application or replace production SQL with JDBC copies, so generated-key, row-lock,
 * compare-and-set and compound-foreign-key behavior all come from the real persistence layer.</p>
 */
class SkitAdCallbackInboxMySqlIT extends SkitMySqlIntegrationTestBase {

    private AnnotationConfigApplicationContext context;
    private CallbackPersistenceTransaction persistence;
    private SkitAdCallbackInboxMapper inboxMapper;
    private SkitAdCallbackAttemptMapper attemptMapper;

    @BeforeAll
    void startCallbackPersistenceBoundary() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, this::dataSource);
        context.register(RealCallbackPersistenceConfiguration.class);
        context.refresh();
        persistence = context.getBean(CallbackPersistenceTransaction.class);
        inboxMapper = context.getBean(SkitAdCallbackInboxMapper.class);
        attemptMapper = context.getBean(SkitAdCallbackAttemptMapper.class);
        assertTrue(AopUtils.isAopProxy(persistence),
                "callback writes must execute through the real transaction proxy");
    }

    @AfterAll
    void closeCallbackPersistenceBoundary() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void twentyIdenticalConcurrentDeliveriesProduceOneInboxTwentyOrderedAttemptsAndOneReceipt() throws Exception {
        Fixture fixture = installFixture(98401L, "concurrent-session", futureMinute(20));
        Delivery delivery = delivery(fixture, "concurrent-transaction", "canonical-payload");

        List<DeliveryResult> results = concurrentDeliveries(20, delivery);

        Set<Long> inboxIds = new HashSet<>();
        int canonicalCount = 0;
        int duplicateCount = 0;
        int markerCount = 0;
        for (DeliveryResult result : results) {
            assertNotNull(result.inboxId);
            inboxIds.add(result.inboxId);
            if (DeliveryOutcome.CANONICAL == result.outcome) {
                canonicalCount++;
            } else if (DeliveryOutcome.DUPLICATE == result.outcome) {
                duplicateCount++;
            }
            if (result.receiptInserted) {
                markerCount++;
            }
        }
        assertEquals(1, inboxIds.size());
        assertEquals(1, canonicalCount);
        assertEquals(19, duplicateCount);
        assertEquals(1, markerCount);
        Long inboxId = inboxIds.iterator().next();
        assertEquals(1, count("skit_ad_callback_inbox", fixture.tenantId));
        assertEquals(20, count("skit_ad_callback_attempt", fixture.tenantId));
        assertEquals(20, jdbc().queryForObject("SELECT COUNT(DISTINCT attempt_no) "
                        + "FROM skit_ad_callback_attempt WHERE tenant_id=? AND callback_inbox_id=?",
                Integer.class, fixture.tenantId, inboxId));
        Map<String, Object> sequence = jdbc().queryForMap("SELECT MIN(attempt_no) AS first_attempt,"
                        + "MAX(attempt_no) AS last_attempt,SUM(attempt_no) AS attempt_sum "
                        + "FROM skit_ad_callback_attempt WHERE tenant_id=? AND callback_inbox_id=?",
                fixture.tenantId, inboxId);
        assertEquals(1, ((Number) sequence.get("first_attempt")).intValue());
        assertEquals(20, ((Number) sequence.get("last_attempt")).intValue());
        assertEquals(210, ((Number) sequence.get("attempt_sum")).intValue());
        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_session "
                        + "WHERE tenant_id=? AND id=? AND reward_callback_inbox_id=? "
                        + "AND reward_callback_received_at IS NOT NULL",
                Integer.class, fixture.tenantId, fixture.sessionRowId, inboxId));
    }

    @Test
    void sameIdempotencyWithDifferentPayloadFreezesCanonicalInboxAsConflict() {
        Fixture fixture = installFixture(98402L, "conflict-session", futureMinute(20));
        Delivery canonical = delivery(fixture, "conflict-transaction", "first-payload");
        Delivery conflicting = delivery(fixture, "conflict-transaction", "different-payload")
                .receivedOneSecondAfter(canonical.receivedAt);

        DeliveryResult accepted = persistence.receive(canonical);
        DeliveryResult rejected = persistence.receive(conflicting);

        assertEquals(DeliveryOutcome.CANONICAL, accepted.outcome);
        assertEquals(DeliveryOutcome.CONFLICT, rejected.outcome);
        assertEquals(accepted.inboxId, rejected.inboxId);
        Map<String, Object> inbox = jdbc().queryForMap("SELECT delivery_integrity_status,integrity_conflict_at "
                        + "FROM skit_ad_callback_inbox WHERE tenant_id=? AND id=?",
                fixture.tenantId, accepted.inboxId);
        assertEquals("PAYLOAD_CONFLICT", inbox.get("delivery_integrity_status"));
        assertNotNull(inbox.get("integrity_conflict_at"));
        List<String> attemptResults = jdbc().queryForList("SELECT result_code "
                        + "FROM skit_ad_callback_attempt WHERE tenant_id=? AND callback_inbox_id=? "
                        + "ORDER BY attempt_no",
                String.class, fixture.tenantId, accepted.inboxId);
        assertEquals(java.util.Arrays.asList("CANONICAL", "PAYLOAD_CONFLICT"), attemptResults);
        assertEquals(accepted.inboxId, jdbc().queryForObject("SELECT reward_callback_inbox_id "
                        + "FROM skit_ad_session WHERE tenant_id=? AND id=?",
                Long.class, fixture.tenantId, fixture.sessionRowId));
    }

    @Test
    void sameProviderIdentifiersRemainIsolatedAcrossTenants() throws Exception {
        Fixture tenantA = installFixture(98403L, "tenant-a-session", futureMinute(20));
        Fixture tenantB = installFixture(98404L, "tenant-b-session", futureMinute(20));
        Delivery deliveryA = delivery(tenantA, "shared-provider-transaction", "shared-payload");
        Delivery deliveryB = delivery(tenantB, "shared-provider-transaction", "shared-payload");

        List<DeliveryResult> results = concurrentDeliveries(deliveryA, deliveryB);

        assertEquals(2, results.size());
        assertEquals(DeliveryOutcome.CANONICAL, results.get(0).outcome);
        assertEquals(DeliveryOutcome.CANONICAL, results.get(1).outcome);
        assertTrue(!results.get(0).inboxId.equals(results.get(1).inboxId));
        assertEquals(2, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_callback_inbox "
                        + "WHERE callback_type='REWARD' AND idempotency_key='shared-provider-transaction'",
                Integer.class));
        assertTenantOwnsReceipt(tenantA);
        assertTenantOwnsReceipt(tenantB);
    }

    @Test
    void receiptMarkerAndVerifyTimeoutCasCannotBothWin() throws Exception {
        LocalDateTime acceptUntil = currentSecond().minusMinutes(1);
        Fixture fixture = installFixture(98405L, "timeout-race-session", acceptUntil);
        Delivery delivery = delivery(fixture, "timeout-race-transaction", "in-window-payload")
                .receivedAt(acceptUntil.minusSeconds(1));
        LocalDateTime timeoutAt = acceptUntil.plusMinutes(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        DeliveryResult delivered;
        int timedOut;
        try {
            Future<DeliveryResult> deliveryFuture = workers.submit(() -> {
                awaitStart(ready, start);
                return persistence.receive(delivery);
            });
            Future<Integer> timeoutFuture = workers.submit(() -> {
                awaitStart(ready, start);
                return persistence.timeout(fixture.tenantId, fixture.sessionRowId,
                        fixture.memberId, 0, timeoutAt);
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS), "race workers did not become ready");
            start.countDown();
            delivered = deliveryFuture.get(30, TimeUnit.SECONDS);
            timedOut = timeoutFuture.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }

        int receiptWon = delivered.outcome == DeliveryOutcome.CANONICAL ? 1 : 0;
        assertEquals(1, receiptWon + timedOut, "receipt and timeout must be mutually exclusive");
        Map<String, Object> session = jdbc().queryForMap("SELECT reward_verification_status,"
                        + "reward_callback_inbox_id,reward_callback_received_at,active_scope_hash,"
                        + "active_scope_released_at,active_scope_release_reason,version "
                        + "FROM skit_ad_session WHERE tenant_id=? AND id=?",
                fixture.tenantId, fixture.sessionRowId);
        if (receiptWon == 1) {
            assertEquals(0, timedOut);
            assertEquals("PENDING", session.get("reward_verification_status"));
            assertNotNull(session.get("reward_callback_inbox_id"));
            assertNotNull(session.get("reward_callback_received_at"));
            assertNotNull(session.get("active_scope_hash"));
            assertNull(session.get("active_scope_released_at"));
            assertNull(session.get("active_scope_release_reason"));
            assertEquals(1, count("skit_ad_callback_inbox", fixture.tenantId));
            assertEquals(1, count("skit_ad_callback_attempt", fixture.tenantId));
        } else {
            assertEquals(DeliveryOutcome.REJECTED, delivered.outcome);
            assertEquals(1, timedOut);
            assertEquals("VERIFY_TIMEOUT", session.get("reward_verification_status"));
            assertNull(session.get("reward_callback_inbox_id"));
            assertNull(session.get("reward_callback_received_at"));
            assertNull(session.get("active_scope_hash"));
            assertNotNull(session.get("active_scope_released_at"));
            assertEquals("VERIFY_TIMEOUT", session.get("active_scope_release_reason"));
            assertEquals(0, count("skit_ad_callback_inbox", fixture.tenantId));
            assertEquals(0, count("skit_ad_callback_attempt", fixture.tenantId));
        }
        assertEquals(1, ((Number) session.get("version")).intValue());
    }

    @Test
    void compoundForeignKeysRejectForgedTenantAccountAndSessionEnvelopes() {
        Fixture tenantA = installFixture(98406L, "binding-session-a", futureMinute(20));
        Fixture tenantB = installFixture(98407L, "binding-session-b", futureMinute(20));
        long otherAccountId = insertAdditionalAccount(tenantA);

        Delivery valid = delivery(tenantA, "binding-transaction", "binding-payload");
        SkitAdCallbackInboxDO wrongAccount = valid.toInbox().setAdAccountId(otherAccountId);
        assertThrows(DataAccessException.class, () -> inboxMapper.insertOrGetCanonical(wrongAccount));

        SkitAdCallbackInboxDO wrongTenantSession = valid.toInbox().setAdSessionId(tenantB.sessionRowId);
        assertThrows(DataAccessException.class, () -> inboxMapper.insertOrGetCanonical(wrongTenantSession));

        InsertResolution canonical = persistence.insertCanonicalOnly(valid);
        SkitAdCallbackAttemptDO forgedAttempt = new SkitAdCallbackAttemptDO()
                .setCallbackInboxId(canonical.inboxId).setAdAccountId(otherAccountId)
                .setAdSessionId(tenantA.sessionRowId).setAttemptNo(1)
                .setPayloadHash(hash("forged-attempt")).setResultCode("FORGED")
                .setReceivedAt(valid.receivedAt);
        forgedAttempt.setTenantId(tenantA.tenantId);
        assertThrows(DataAccessException.class, () -> attemptMapper.insert(forgedAttempt));
        assertEquals(1, count("skit_ad_callback_inbox", tenantA.tenantId));
        assertEquals(0, count("skit_ad_callback_attempt", tenantA.tenantId));
    }

    @Test
    void duplicateInsertReturnsCanonicalIdThroughLastInsertId() {
        Fixture fixture = installFixture(98408L, "generated-key-session", futureMinute(20));
        Delivery delivery = delivery(fixture, "generated-key-transaction", "generated-key-payload");

        InsertResolution first = persistence.insertCanonicalOnly(delivery);
        InsertResolution duplicate = persistence.insertCanonicalOnly(delivery);

        assertNotNull(first.inboxId);
        assertTrue(first.inboxId > 0);
        assertEquals(first.inboxId, duplicate.inboxId,
                "ON DUPLICATE KEY must expose the existing row through LAST_INSERT_ID");
        assertEquals(1, count("skit_ad_callback_inbox", fixture.tenantId));
        assertArrayEquals(delivery.payloadHash, jdbc().queryForObject(
                "SELECT canonical_payload_hash FROM skit_ad_callback_inbox WHERE tenant_id=? AND id=?",
                byte[].class, fixture.tenantId, first.inboxId));
    }

    private List<DeliveryResult> concurrentDeliveries(int concurrency, Delivery delivery) throws Exception {
        List<Delivery> deliveries = new ArrayList<>(concurrency);
        for (int index = 0; index < concurrency; index++) {
            deliveries.add(delivery);
        }
        return concurrentDeliveries(deliveries);
    }

    private List<DeliveryResult> concurrentDeliveries(Delivery first, Delivery second) throws Exception {
        List<Delivery> deliveries = new ArrayList<>(2);
        deliveries.add(first);
        deliveries.add(second);
        return concurrentDeliveries(deliveries);
    }

    private List<DeliveryResult> concurrentDeliveries(List<Delivery> deliveries) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(deliveries.size());
        CountDownLatch ready = new CountDownLatch(deliveries.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<DeliveryResult>> futures = new ArrayList<>();
        try {
            for (Delivery delivery : deliveries) {
                futures.add(workers.submit(() -> {
                    awaitStart(ready, start);
                    return persistence.receive(delivery);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "delivery workers did not become ready");
            start.countDown();
            List<DeliveryResult> results = new ArrayList<>(deliveries.size());
            for (Future<DeliveryResult> future : futures) {
                results.add(future.get(45, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void awaitStart(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("start barrier was not released");
        }
    }

    private Fixture installFixture(long tenantId, String publicSessionId, LocalDateTime rewardAcceptUntil) {
        long base = tenantId * 100;
        long memberId = base + 1;
        long accountId = base + 10;
        long planId = base + 20;
        long snapshotId = base + 30;
        long sessionRowId = base + 40;
        long dramaId = base + 50;
        jdbc().update("INSERT INTO system_tenant (id,name,package_id,status,expire_time) "
                        + "VALUES (?, ?, 0, 0, '2099-01-01 00:00:00')",
                tenantId, "Callback inbox tenant " + tenantId);
        jdbc().update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                        + "VALUES (?,?,?,?,?,?,1,0)",
                memberId, tenantId, Long.toString(memberId), "encoded-password",
                "member-" + memberId, "CBI" + memberId);
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,status) "
                        + "VALUES (?,?,'TAKU',?,?,?,0)",
                accountId, tenantId, "TAKU-" + tenantId, "account-" + tenantId,
                "app-" + tenantId);
        jdbc().update("INSERT INTO skit_ad_network_capability "
                        + "(tenant_id,ad_account_id,network_firm_id,reward_authority,supports_user_id,"
                        + "supports_custom_data,supports_stable_transaction,supports_impression_revenue,"
                        + "supports_reporting,enabled,verified_at) "
                        + "VALUES (?,?,66,'SIGNED_REWARD',b'1',b'1',b'1',b'1',b'1',b'1',NOW())",
                tenantId, accountId);
        jdbc().update("INSERT INTO skit_commission_plan "
                        + "(id,tenant_id,version,status,published_time) VALUES (?,?,1,0,NOW())",
                planId, tenantId);
        jdbc().update("INSERT INTO skit_ad_policy_snapshot "
                        + "(id,tenant_id,plan_id,source_member_id,rule_version,snapshot_schema_version,"
                        + "snapshot_json,snapshot_hash,policy_snapshot_at) "
                        + "VALUES (?,?,?,?,1,1,'{}',?,NOW())",
                snapshotId, tenantId, planId, memberId, hash("snapshot-" + tenantId));
        insertCredentialVersions(tenantId, accountId, "primary");
        jdbc().update("INSERT INTO skit_ad_session "
                        + "(id,tenant_id,session_id,session_token_hash,session_token_key_version,protocol_version,"
                        + "member_id,ad_account_id,policy_snapshot_id,callback_key_version,reward_secret_version,"
                        + "provider,placement_id,scenario_id,business_type,drama_id,episode_from,episode_to,"
                        + "unlock_scope,active_scope_hash,pseudonymous_user_id,access_mode,client_lifecycle_status,"
                        + "reward_verification_status,entitlement_status,revenue_status,load_expires_at,"
                        + "reward_accept_until,provider_transaction_id,last_callback_sequence,version) "
                        + "VALUES (?,?,?,?,1,1,?,?,?,1,1,'TAKU',?,'drama_unlock','EPISODE_UNLOCK',?,1,1,?,?,?,"
                        + "'MEMBER_OAUTH','CREATED','PENDING','NONE','NONE',?,?,?, -1,0)",
                sessionRowId, tenantId, publicSessionId, hash("session-token-" + tenantId),
                memberId, accountId, snapshotId, "placement-" + tenantId, dramaId,
                "scope-" + tenantId, hash("active-scope-" + tenantId), "pseudo-" + tenantId,
                rewardAcceptUntil, rewardAcceptUntil, "provider-transaction-" + tenantId);
        return new Fixture(tenantId, memberId, accountId, sessionRowId, publicSessionId,
                "placement-" + tenantId, "pseudo-" + tenantId);
    }

    private long insertAdditionalAccount(Fixture fixture) {
        long accountId = fixture.accountId + 1;
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,status) "
                        + "VALUES (?,?,'PANGLE',?,?,?,0)",
                accountId, fixture.tenantId, "other-" + fixture.tenantId,
                "other-account-" + fixture.tenantId, "other-app-" + fixture.tenantId);
        insertCredentialVersions(fixture.tenantId, accountId, "other");
        return accountId;
    }

    private void insertCredentialVersions(long tenantId, long accountId, String suffix) {
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) "
                        + "VALUES (?,?,1,?,b'1')",
                tenantId, accountId, hash("callback-key-" + suffix + tenantId));
        jdbc().update("INSERT INTO skit_ad_reward_secret_version "
                        + "(tenant_id,ad_account_id,secret_version,ciphertext,nonce,encryption_key_id,"
                        + "envelope_version,active) VALUES (?,?,1,?,?,?,1,b'1')",
                tenantId, accountId, hash("reward-secret-" + suffix + tenantId),
                firstTwelve(hash("reward-nonce-" + suffix + tenantId)), "mysql-it-key");
    }

    private Delivery delivery(Fixture fixture, String idempotencyKey, String payload) {
        return new Delivery(fixture.tenantId, fixture.accountId, fixture.sessionRowId,
                fixture.publicSessionId, fixture.placementId, fixture.pseudonymousUserId,
                idempotencyKey, hash(payload), currentSecond());
    }

    private void assertTenantOwnsReceipt(Fixture fixture) {
        Map<String, Object> row = jdbc().queryForMap("SELECT s.tenant_id AS session_tenant,"
                        + "i.tenant_id AS inbox_tenant,s.ad_account_id AS session_account,"
                        + "i.ad_account_id AS inbox_account,s.id AS session_id,i.ad_session_id AS inbox_session "
                        + "FROM skit_ad_session s JOIN skit_ad_callback_inbox i "
                        + "ON i.tenant_id=s.tenant_id AND i.id=s.reward_callback_inbox_id "
                        + "WHERE s.tenant_id=? AND s.id=?",
                fixture.tenantId, fixture.sessionRowId);
        assertEquals(fixture.tenantId, ((Number) row.get("session_tenant")).longValue());
        assertEquals(fixture.tenantId, ((Number) row.get("inbox_tenant")).longValue());
        assertEquals(fixture.accountId, ((Number) row.get("session_account")).longValue());
        assertEquals(fixture.accountId, ((Number) row.get("inbox_account")).longValue());
        assertEquals(fixture.sessionRowId, ((Number) row.get("session_id")).longValue());
        assertEquals(fixture.sessionRowId, ((Number) row.get("inbox_session")).longValue());
    }

    private int count(String table, long tenantId) {
        if (!"skit_ad_callback_inbox".equals(table) && !"skit_ad_callback_attempt".equals(table)) {
            throw new IllegalArgumentException("unexpected callback table");
        }
        return jdbc().queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?",
                Integer.class, tenantId);
    }

    private static LocalDateTime currentSecond() {
        return LocalDateTime.now().withNano(0);
    }

    private static LocalDateTime futureMinute(long minutes) {
        return currentSecond().plusMinutes(minutes);
    }

    private static byte[] firstTwelve(byte[] value) {
        byte[] result = new byte[12];
        System.arraycopy(value, 0, result, 0, result.length);
        return result;
    }

    private static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class RealCallbackPersistenceConfiguration {

        @Bean
        MybatisSqlSessionFactoryBean sqlSessionFactory(DataSource dataSource) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            return factory;
        }

        @Bean
        MapperFactoryBean<SkitAdCallbackInboxMapper> callbackInboxMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitAdCallbackInboxMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<SkitAdCallbackAttemptMapper> callbackAttemptMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitAdCallbackAttemptMapper.class, sqlSessionFactory);
        }

        @Bean
        MapperFactoryBean<SkitAdSessionMapper> adSessionMapperFactory(SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitAdSessionMapper.class, sqlSessionFactory);
        }

        private static <T> MapperFactoryBean<T> mapperFactory(
                Class<T> mapperType, SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<T> factory = new MapperFactoryBean<>(mapperType);
            factory.setSqlSessionFactory(sqlSessionFactory);
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        CallbackPersistenceTransaction callbackPersistenceTransaction(
                SkitAdCallbackInboxMapper inboxMapper,
                SkitAdCallbackAttemptMapper attemptMapper,
                SkitAdSessionMapper sessionMapper) {
            return new CallbackPersistenceTransaction(inboxMapper, attemptMapper, sessionMapper);
        }
    }

    static class CallbackPersistenceTransaction {

        private final SkitAdCallbackInboxMapper inboxMapper;
        private final SkitAdCallbackAttemptMapper attemptMapper;
        private final SkitAdSessionMapper sessionMapper;

        CallbackPersistenceTransaction(SkitAdCallbackInboxMapper inboxMapper,
                                       SkitAdCallbackAttemptMapper attemptMapper,
                                       SkitAdSessionMapper sessionMapper) {
            this.inboxMapper = inboxMapper;
            this.attemptMapper = attemptMapper;
            this.sessionMapper = sessionMapper;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
                timeout = 15, rollbackFor = Exception.class)
        public DeliveryResult receive(Delivery delivery) {
            SkitAdSessionDO session = sessionMapper.selectByAccountAndSessionIdForUpdate(
                    delivery.tenantId, delivery.accountId, delivery.publicSessionId);
            if (session == null) {
                return new DeliveryResult(DeliveryOutcome.REJECTED, null, false);
            }
            if (session.getRewardCallbackInboxId() != null) {
                if (!"PENDING".equals(session.getRewardVerificationStatus())
                        && !"SIGNED_VERIFIED".equals(session.getRewardVerificationStatus())) {
                    return new DeliveryResult(DeliveryOutcome.REJECTED, null, false);
                }
                SkitAdCallbackInboxDO canonical = inboxMapper.selectByTenantAccountAndIdForUpdate(
                        delivery.tenantId, delivery.accountId, session.getRewardCallbackInboxId());
                requireCanonicalScope(canonical, delivery);
                if (!sameHash(delivery.payloadHash, canonical.getCanonicalPayloadHash())) {
                    markConflict(canonical, delivery);
                    appendAttempt(canonical, delivery.payloadHash, "PAYLOAD_CONFLICT", delivery.receivedAt);
                    return new DeliveryResult(DeliveryOutcome.CONFLICT, canonical.getId(), false);
                }
                appendAttempt(canonical, delivery.payloadHash, "DUPLICATE", delivery.receivedAt);
                return new DeliveryResult(DeliveryOutcome.DUPLICATE, canonical.getId(), false);
            }
            if (!"PENDING".equals(session.getRewardVerificationStatus())
                    || delivery.receivedAt.isAfter(session.getRewardAcceptUntil())) {
                return new DeliveryResult(DeliveryOutcome.REJECTED, null, false);
            }

            InsertResolution inserted = insertCanonicalInternal(delivery);
            SkitAdCallbackInboxDO canonical = inserted.canonical;
            if (!sameHash(delivery.payloadHash, canonical.getCanonicalPayloadHash())) {
                markConflict(canonical, delivery);
                appendAttempt(canonical, delivery.payloadHash, "PAYLOAD_CONFLICT", delivery.receivedAt);
                return new DeliveryResult(DeliveryOutcome.CONFLICT, canonical.getId(), false);
            }
            appendAttempt(canonical, delivery.payloadHash,
                    inserted.affected == 1 ? "CANONICAL" : "DUPLICATE", delivery.receivedAt);
            int marked = sessionMapper.markRewardCallbackReceivedCas(delivery.tenantId,
                    delivery.sessionRowId, delivery.accountId, canonical.getId(), delivery.receivedAt);
            if (marked != 1) {
                throw new IllegalStateException("callback receipt CAS did not change exactly one session");
            }
            return new DeliveryResult(inserted.affected == 1
                    ? DeliveryOutcome.CANONICAL : DeliveryOutcome.DUPLICATE, canonical.getId(), true);
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
                timeout = 15, rollbackFor = Exception.class)
        public InsertResolution insertCanonicalOnly(Delivery delivery) {
            return insertCanonicalInternal(delivery);
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
                timeout = 15, rollbackFor = Exception.class)
        public int timeout(long tenantId, long sessionId, long memberId,
                           int expectedVersion, LocalDateTime authoritativeNow) {
            return sessionMapper.markRewardVerifyTimeoutAndReleaseScopeCas(
                    tenantId, sessionId, memberId, expectedVersion, authoritativeNow);
        }

        private InsertResolution insertCanonicalInternal(Delivery delivery) {
            SkitAdCallbackInboxDO candidate = delivery.toInbox();
            int affected = inboxMapper.insertOrGetCanonical(candidate);
            if (affected < 0 || candidate.getId() == null || candidate.getId() <= 0) {
                throw new IllegalStateException("canonical callback id was not returned");
            }
            SkitAdCallbackInboxDO canonical = inboxMapper.selectByTenantAccountAndIdForUpdate(
                    delivery.tenantId, delivery.accountId, candidate.getId());
            requireCanonicalScope(canonical, delivery);
            return new InsertResolution(candidate.getId(), affected, canonical);
        }

        private void markConflict(SkitAdCallbackInboxDO canonical, Delivery delivery) {
            if ("CANONICAL".equals(canonical.getDeliveryIntegrityStatus())) {
                int changed = inboxMapper.markPayloadConflict(delivery.tenantId,
                        delivery.accountId, canonical.getId(), delivery.receivedAt);
                if (changed != 1) {
                    throw new IllegalStateException("payload conflict did not transition exactly once");
                }
            }
        }

        private void appendAttempt(SkitAdCallbackInboxDO inbox, byte[] payloadHash,
                                   String resultCode, LocalDateTime receivedAt) {
            Integer maximum = attemptMapper.selectMaxAttemptNo(inbox.getTenantId(), inbox.getId());
            int nextAttempt = Math.addExact(maximum == null ? 0 : maximum, 1);
            SkitAdCallbackAttemptDO attempt = new SkitAdCallbackAttemptDO()
                    .setCallbackInboxId(inbox.getId()).setAdAccountId(inbox.getAdAccountId())
                    .setAdSessionId(inbox.getAdSessionId()).setAttemptNo(nextAttempt)
                    .setPayloadHash(payloadHash).setResultCode(resultCode).setReceivedAt(receivedAt);
            attempt.setTenantId(inbox.getTenantId());
            if (attemptMapper.insert(attempt) != 1) {
                throw new IllegalStateException("callback attempt was not appended exactly once");
            }
        }

        private static void requireCanonicalScope(SkitAdCallbackInboxDO row, Delivery delivery) {
            if (row == null || !delivery.tenantIdEquals(row.getTenantId())
                    || !delivery.accountIdEquals(row.getAdAccountId())
                    || !delivery.sessionIdEquals(row.getAdSessionId())
                    || !"TAKU".equals(row.getProvider()) || !"REWARD".equals(row.getCallbackType())
                    || !delivery.idempotencyKey.equals(row.getIdempotencyKey())) {
                throw new IllegalStateException("canonical callback escaped its tenant envelope");
            }
        }

        private static boolean sameHash(byte[] first, byte[] second) {
            return first != null && second != null && MessageDigest.isEqual(first, second);
        }
    }

    private enum DeliveryOutcome {
        CANONICAL,
        DUPLICATE,
        CONFLICT,
        REJECTED
    }

    private static final class DeliveryResult {
        private final DeliveryOutcome outcome;
        private final Long inboxId;
        private final boolean receiptInserted;

        private DeliveryResult(DeliveryOutcome outcome, Long inboxId, boolean receiptInserted) {
            this.outcome = outcome;
            this.inboxId = inboxId;
            this.receiptInserted = receiptInserted;
        }
    }

    private static final class InsertResolution {
        private final Long inboxId;
        private final int affected;
        private final SkitAdCallbackInboxDO canonical;

        private InsertResolution(Long inboxId, int affected, SkitAdCallbackInboxDO canonical) {
            this.inboxId = inboxId;
            this.affected = affected;
            this.canonical = canonical;
        }
    }

    private static final class Delivery {
        private final long tenantId;
        private final long accountId;
        private final long sessionRowId;
        private final String publicSessionId;
        private final String placementId;
        private final String pseudonymousUserId;
        private final String idempotencyKey;
        private final byte[] payloadHash;
        private final LocalDateTime receivedAt;

        private Delivery(long tenantId, long accountId, long sessionRowId,
                         String publicSessionId, String placementId, String pseudonymousUserId,
                         String idempotencyKey, byte[] payloadHash, LocalDateTime receivedAt) {
            this.tenantId = tenantId;
            this.accountId = accountId;
            this.sessionRowId = sessionRowId;
            this.publicSessionId = publicSessionId;
            this.placementId = placementId;
            this.pseudonymousUserId = pseudonymousUserId;
            this.idempotencyKey = idempotencyKey;
            this.payloadHash = payloadHash;
            this.receivedAt = receivedAt;
        }

        private Delivery receivedOneSecondAfter(LocalDateTime reference) {
            return receivedAt(reference.plusSeconds(1));
        }

        private Delivery receivedAt(LocalDateTime value) {
            return new Delivery(tenantId, accountId, sessionRowId, publicSessionId, placementId,
                    pseudonymousUserId, idempotencyKey, payloadHash, value);
        }

        private SkitAdCallbackInboxDO toInbox() {
            SkitAdCallbackInboxDO row = new SkitAdCallbackInboxDO()
                    .setAdAccountId(accountId).setAdSessionId(sessionRowId)
                    .setCallbackKeyVersion(1).setRewardSecretVersion(1)
                    .setProvider("TAKU").setCallbackType("REWARD")
                    .setIdempotencyKey(idempotencyKey).setProviderUserId(pseudonymousUserId)
                    .setExtraDataHash(hash("extra-" + idempotencyKey))
                    .setProviderTransactionId(idempotencyKey).setProviderShowId(idempotencyKey)
                    .setPlacementId(placementId).setAdsourceId("9001").setNetworkFirmId(66)
                    .setSignedFieldMask(0x3fL).setEvidenceProvenance("SIGNED_ILRD")
                    .setCanonicalPayloadHash(payloadHash).setAuthenticationLevel("SIGNED_REWARD")
                    .setSignatureStatus("VALID").setDeliveryIntegrityStatus("CANONICAL")
                    .setProcessingStatus("PENDING").setProcessingAttemptCount(0)
                    .setReceivedAt(receivedAt).setIngressResponseCode(200);
            row.setTenantId(tenantId);
            return row;
        }

        private boolean tenantIdEquals(Long value) {
            return value != null && value == tenantId;
        }

        private boolean accountIdEquals(Long value) {
            return value != null && value == accountId;
        }

        private boolean sessionIdEquals(Long value) {
            return value != null && value == sessionRowId;
        }
    }

    private static final class Fixture {
        private final long tenantId;
        private final long memberId;
        private final long accountId;
        private final long sessionRowId;
        private final String publicSessionId;
        private final String placementId;
        private final String pseudonymousUserId;

        private Fixture(long tenantId, long memberId, long accountId, long sessionRowId,
                        String publicSessionId, String placementId, String pseudonymousUserId) {
            this.tenantId = tenantId;
            this.memberId = memberId;
            this.accountId = accountId;
            this.sessionRowId = sessionRowId;
            this.publicSessionId = publicSessionId;
            this.placementId = placementId;
            this.pseudonymousUserId = pseudonymousUserId;
        }
    }

}
