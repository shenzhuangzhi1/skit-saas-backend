package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackClaimDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackInboxDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
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
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-MySQL proof for global SKIP LOCKED callback leasing and monotonic retries. */
class SkitAdCallbackDrainMySqlIT extends SkitMySqlIntegrationTestBase {

    private AnnotationConfigApplicationContext context;
    private CallbackDrainTransaction transactions;
    private SkitAdCallbackInboxMapper mapper;

    @BeforeAll
    void startDrainPersistenceBoundary() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, this::dataSource);
        context.register(DrainPersistenceConfiguration.class);
        context.refresh();
        transactions = context.getBean(CallbackDrainTransaction.class);
        mapper = context.getBean(SkitAdCallbackInboxMapper.class);
        assertTrue(AopUtils.isAopProxy(transactions));
    }

    @AfterAll
    void closeDrainPersistenceBoundary() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void twoWorkersLeaseTwoTenantsExactlyOnceEvenWhenOneTenantIsArchived() throws Exception {
        TenantFixture active = installFixture(98501L, 2);
        TenantFixture archived = installFixture(98502L, 2);
        jdbc().update("UPDATE system_tenant SET status=1,deleted=b'1' WHERE id=?", archived.tenantId);
        CountDownLatch selected = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        List<SkitAdCallbackClaimDO> first;
        List<SkitAdCallbackClaimDO> second;
        try {
            Future<List<SkitAdCallbackClaimDO>> firstFuture = workers.submit(() -> {
                await(start);
                return transactions.claim("drain-worker-a", 2, 120, selected);
            });
            Future<List<SkitAdCallbackClaimDO>> secondFuture = workers.submit(() -> {
                await(start);
                return transactions.claim("drain-worker-b", 2, 120, selected);
            });
            start.countDown();
            first = firstFuture.get(30, TimeUnit.SECONDS);
            second = secondFuture.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }

        List<SkitAdCallbackClaimDO> all = new ArrayList<>(first);
        all.addAll(second);
        assertEquals(4, all.size());
        Set<Long> claimedIds = new HashSet<>();
        Set<Long> claimedTenants = new HashSet<>();
        for (SkitAdCallbackClaimDO claim : all) {
            claimedIds.add(claim.getId());
            claimedTenants.add(claim.getTenantId());
        }
        assertEquals(4, claimedIds.size(), "SKIP LOCKED workers must not own the same inbox");
        assertEquals(new HashSet<>(java.util.Arrays.asList(active.tenantId, archived.tenantId)),
                claimedTenants, "archived tenants retain pre-existing callback processing rights");
        assertEquals(4, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_callback_inbox "
                        + "WHERE processing_status='PROCESSING' AND processing_attempt_count=1 "
                        + "AND lease_owner IN ('drain-worker-a','drain-worker-b')",
                Integer.class));
        assertTrue(transactions.claim("third-worker", 10, 120, null).isEmpty(),
                "an active lease cannot be double-claimed");
    }

    @Test
    void expiredLeaseIsRecoveredAndEachRetryIncrementsOnceBeforeDeadLetter() {
        TenantFixture fixture = installFixture(98503L, 1);
        long inboxId = fixture.inboxIds.get(0);
        assertEquals(1, transactions.claim("retry-worker-1", 1, 120, null).size());
        assertEquals(0, transactions.deadLetter(fixture, inboxId, "retry-worker-1", 3));
        assertEquals(1, transactions.retry(fixture, inboxId, "retry-worker-1", 3, 1, 2));
        waitUntilRetryIsDue(fixture, inboxId);
        assertEquals(1, transactions.claim("retry-worker-2", 1, 120, null).size());
        assertEquals(1, transactions.retry(fixture, inboxId, "retry-worker-2", 3, 1, 2));
        waitUntilRetryIsDue(fixture, inboxId);
        assertEquals(1, transactions.claim("retry-worker-3", 1, 120, null).size());
        assertEquals(1, transactions.deadLetter(fixture, inboxId, "retry-worker-3", 3));
        assertEquals(1, transactions.alertDeadLetter(fixture, inboxId));
        assertEquals(0, transactions.alertDeadLetter(fixture, inboxId));

        Map<String, Object> terminal = jdbc().queryForMap("SELECT processing_status,"
                        + "processing_attempt_count,error_code,lease_owner,lease_until,next_attempt_at,"
                        + "processed_at,dead_letter_alerted_at "
                        + "FROM skit_ad_callback_inbox WHERE tenant_id=? AND id=?",
                fixture.tenantId, inboxId);
        assertEquals("DEAD_LETTER", terminal.get("processing_status"));
        assertEquals(3, ((Number) terminal.get("processing_attempt_count")).intValue());
        assertEquals("CALLBACK_PROCESSOR_EXCEPTION", terminal.get("error_code"));
        assertEquals(null, terminal.get("lease_owner"));
        assertEquals(null, terminal.get("lease_until"));
        assertEquals(null, terminal.get("next_attempt_at"));
        assertNotNull(terminal.get("processed_at"));
        assertNotNull(terminal.get("dead_letter_alerted_at"));
    }

    @Test
    void aCrashedExpiredProcessingLeaseCanBeReclaimedButAFreshLeaseCannot() {
        TenantFixture fixture = installFixture(98504L, 1);
        long inboxId = fixture.inboxIds.get(0);
        jdbc().update("UPDATE skit_ad_callback_inbox SET processing_status='PROCESSING',"
                        + "lease_owner='crashed-worker',lease_until=DATE_SUB(NOW(),INTERVAL 5 SECOND),"
                        + "processing_attempt_count=1,updater='mysql-it' WHERE tenant_id=? AND id=?",
                fixture.tenantId, inboxId);

        List<SkitAdCallbackClaimDO> recovered = transactions.claim("recovery-worker", 1, 120, null);

        assertEquals(1, recovered.size());
        assertEquals(inboxId, recovered.get(0).getId());
        Map<String, Object> state = jdbc().queryForMap("SELECT processing_status,lease_owner,"
                        + "processing_attempt_count FROM skit_ad_callback_inbox WHERE tenant_id=? AND id=?",
                fixture.tenantId, inboxId);
        assertEquals("PROCESSING", state.get("processing_status"));
        assertEquals("recovery-worker", state.get("lease_owner"));
        assertEquals(2, ((Number) state.get("processing_attempt_count")).intValue());
        assertTrue(transactions.claim("racing-worker", 1, 120, null).isEmpty());
    }

    @Test
    void aRepeatedlyCrashedLeaseStopsAtTheConfiguredAttemptLimit() throws Exception {
        TenantFixture fixture = installFixture(98505L, 1);
        long inboxId = fixture.inboxIds.get(0);
        for (int attempt = 1; attempt <= 8; attempt++) {
            String owner = "crash-loop-" + attempt;
            assertEquals(1, transactions.claim(owner, 1, 1, null).size());
            if (attempt < 8) {
                assertEquals(1, transactions.retry(fixture, inboxId, owner, 99, 1, 1));
                waitUntilRetryIsDue(fixture, inboxId);
            }
        }
        waitUntilLeaseIsExpired(fixture, inboxId);

        assertEquals(1, transactions.exhaustExpired(fixture, inboxId, 8));
        assertEquals(1, transactions.alertDeadLetter(fixture, inboxId));
        assertEquals(0, transactions.alertDeadLetter(fixture, inboxId));

        Map<String, Object> terminal = jdbc().queryForMap("SELECT processing_status,"
                        + "processing_attempt_count,error_code,lease_owner,lease_until,processed_at,"
                        + "dead_letter_alerted_at "
                        + "FROM skit_ad_callback_inbox WHERE tenant_id=? AND id=?",
                fixture.tenantId, inboxId);
        assertEquals("DEAD_LETTER", terminal.get("processing_status"));
        assertEquals(8, ((Number) terminal.get("processing_attempt_count")).intValue());
        assertEquals("CALLBACK_LEASE_EXHAUSTED", terminal.get("error_code"));
        assertEquals(null, terminal.get("lease_owner"));
        assertEquals(null, terminal.get("lease_until"));
        assertNotNull(terminal.get("processed_at"));
        assertNotNull(terminal.get("dead_letter_alerted_at"));
    }

    @Test
    void databaseClockOwnsTerminalLeaseChecksEvenWhenJvmTimezoneDiffers() {
        TenantFixture fixture = installFixture(98506L, 3);
        TimeZone original = TimeZone.getDefault();
        long succeededId;
        long rejectedId;
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            List<SkitAdCallbackClaimDO> claims =
                    transactions.claim("terminal-worker", 2, 120, null);
            assertEquals(2, claims.size());
            succeededId = claims.get(0).getId();
            rejectedId = claims.get(1).getId();
            assertEquals(1, transactions.succeed(
                    fixture, succeededId, "terminal-worker"));
            assertEquals(1, transactions.reject(
                    fixture, rejectedId, "terminal-worker", "DETERMINISTIC_REJECTION"));

            long expiredId = fixture.inboxIds.get(2);
            jdbc().update("UPDATE skit_ad_callback_inbox SET processing_status='PROCESSING',"
                            + "lease_owner='expired-worker',"
                            + "lease_until=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND),"
                            + "processing_attempt_count=1,updater='mysql-it' "
                            + "WHERE tenant_id=? AND ad_account_id=? AND id=?",
                    fixture.tenantId, fixture.accountId, expiredId);
            assertEquals(0, transactions.succeed(fixture, expiredId, "expired-worker"));
            assertEquals(0, transactions.reject(
                    fixture, expiredId, "expired-worker", "MUST_NOT_ACK_EXPIRED_LEASE"));
            assertEquals(1, transactions.exhaustExpired(fixture, expiredId, 1));
        } finally {
            TimeZone.setDefault(original);
        }

        Map<String, Object> succeeded = jdbc().queryForMap(
                "SELECT processing_status,processed_at,lease_owner FROM skit_ad_callback_inbox "
                        + "WHERE tenant_id=? AND id=?", fixture.tenantId, succeededId);
        assertEquals("SUCCEEDED", succeeded.get("processing_status"));
        assertNotNull(succeeded.get("processed_at"));
        assertEquals(null, succeeded.get("lease_owner"));
        Map<String, Object> rejected = jdbc().queryForMap(
                "SELECT processing_status,error_code,processed_at,lease_owner "
                        + "FROM skit_ad_callback_inbox WHERE tenant_id=? AND id=?",
                fixture.tenantId, rejectedId);
        assertEquals("REJECTED", rejected.get("processing_status"));
        assertEquals("DETERMINISTIC_REJECTION", rejected.get("error_code"));
        assertNotNull(rejected.get("processed_at"));
        assertEquals(null, rejected.get("lease_owner"));
    }

    private TenantFixture installFixture(long tenantId, int inboxCount) {
        long base = tenantId * 100;
        long memberId = base + 1;
        long accountId = base + 10;
        long planId = base + 20;
        long snapshotId = base + 30;
        long sessionId = base + 40;
        long dramaId = base + 50;
        jdbc().update("INSERT INTO system_tenant (id,name,package_id,status,expire_time) "
                        + "VALUES (?,?,0,0,'2099-01-01 00:00:00')",
                tenantId, "Drain tenant " + tenantId);
        jdbc().update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                        + "VALUES (?,?,?,?,?,?,1,0)",
                memberId, tenantId, Long.toString(memberId), "encoded", "member-" + memberId,
                "DRN" + memberId);
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,status) "
                        + "VALUES (?,?,'TAKU',?,?,?,0)", accountId, tenantId,
                "TAKU-" + tenantId, "account-" + tenantId, "app-" + tenantId);
        jdbc().update("INSERT INTO skit_commission_plan "
                        + "(id,tenant_id,version,status,published_time) VALUES (?,?,1,0,NOW())",
                planId, tenantId);
        jdbc().update("INSERT INTO skit_ad_policy_snapshot "
                        + "(id,tenant_id,plan_id,source_member_id,rule_version,snapshot_schema_version,"
                        + "snapshot_json,snapshot_hash,policy_snapshot_at) "
                        + "VALUES (?,?,?,?,1,1,'{}',?,NOW())",
                snapshotId, tenantId, planId, memberId, hash("snapshot-" + tenantId));
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) "
                        + "VALUES (?,?,1,?,b'1')",
                tenantId, accountId, hash("callback-key-" + tenantId));
        jdbc().update("INSERT INTO skit_ad_reward_secret_version "
                        + "(tenant_id,ad_account_id,secret_version,ciphertext,nonce,encryption_key_id,"
                        + "envelope_version,active) VALUES (?,?,1,?,?,?,1,b'1')",
                tenantId, accountId, hash("reward-secret-" + tenantId),
                firstTwelve(hash("reward-nonce-" + tenantId)), "mysql-it-key");
        LocalDateTime acceptUntil = currentSecond().plusHours(1);
        jdbc().update("INSERT INTO skit_ad_session "
                        + "(id,tenant_id,session_id,session_token_hash,session_token_key_version,protocol_version,"
                        + "member_id,ad_account_id,policy_snapshot_id,callback_key_version,reward_secret_version,"
                        + "provider,placement_id,scenario_id,business_type,drama_id,episode_from,episode_to,"
                        + "unlock_scope,active_scope_hash,pseudonymous_user_id,access_mode,client_lifecycle_status,"
                        + "reward_verification_status,entitlement_status,revenue_status,load_expires_at,"
                        + "reward_accept_until,provider_transaction_id,last_callback_sequence,version) "
                        + "VALUES (?,?,?,?,1,1,?,?,?,1,1,'TAKU',?,'drama_unlock','EPISODE_UNLOCK',?,1,1,?,?,?,"
                        + "'MEMBER_OAUTH','CREATED','PENDING','NONE','NONE',?,?,?,-1,0)",
                sessionId, tenantId, "drain-session-" + tenantId, hash("session-" + tenantId),
                memberId, accountId, snapshotId, "placement-" + tenantId, dramaId,
                "scope-" + tenantId, hash("scope-" + tenantId), "pseudo-" + tenantId,
                acceptUntil, acceptUntil, "provider-transaction-" + tenantId);
        List<Long> inboxIds = new ArrayList<>();
        for (int index = 0; index < inboxCount; index++) {
            SkitAdCallbackInboxDO inbox = new SkitAdCallbackInboxDO()
                    .setAdAccountId(accountId).setAdSessionId(sessionId)
                    .setCallbackKeyVersion(1).setRewardSecretVersion(1)
                    .setProvider("TAKU").setCallbackType("REWARD")
                    .setIdempotencyKey("drain-" + tenantId + "-" + index)
                    .setProviderTransactionId("transaction-" + tenantId + "-" + index)
                    .setSignedFieldMask(63L).setEvidenceProvenance("TAKU_SIGNED_REWARD")
                    .setCanonicalPayloadHash(hash("payload-" + tenantId + "-" + index))
                    .setAuthenticationLevel("SIGNED_CALLBACK").setSignatureStatus("VERIFIED")
                    .setDeliveryIntegrityStatus("CANONICAL").setProcessingStatus("PENDING")
                    .setProcessingAttemptCount(0).setReceivedAt(currentSecond())
                    .setIngressResponseCode(200);
            inbox.setTenantId(tenantId);
            assertEquals(1, mapper.insertOrGetCanonical(inbox));
            assertNotNull(inbox.getId());
            inboxIds.add(inbox.getId());
        }
        return new TenantFixture(tenantId, accountId, inboxIds);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("worker start barrier timed out");
        }
    }

    private void waitUntilRetryIsDue(TenantFixture fixture, long inboxId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer due = jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_callback_inbox "
                            + "WHERE tenant_id=? AND id=? AND processing_status='RETRY_WAIT' "
                            + "AND next_attempt_at<=CURRENT_TIMESTAMP",
                    Integer.class, fixture.tenantId, inboxId);
            if (due != null && due == 1) {
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("retry due wait interrupted", interrupted);
            }
        }
        throw new IllegalStateException("callback retry did not become due");
    }

    private void waitUntilLeaseIsExpired(TenantFixture fixture, long inboxId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer expired = jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_callback_inbox "
                            + "WHERE tenant_id=? AND id=? AND processing_status='PROCESSING' "
                            + "AND lease_until<=CURRENT_TIMESTAMP",
                    Integer.class, fixture.tenantId, inboxId);
            if (expired != null && expired == 1) {
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("lease expiry wait interrupted", interrupted);
            }
        }
        throw new IllegalStateException("callback lease did not expire");
    }

    private static LocalDateTime currentSecond() {
        return LocalDateTime.now().withNano(0);
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
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class TenantFixture {
        private final long tenantId;
        private final long accountId;
        private final List<Long> inboxIds;

        private TenantFixture(long tenantId, long accountId, List<Long> inboxIds) {
            this.tenantId = tenantId;
            this.accountId = accountId;
            this.inboxIds = inboxIds;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class DrainPersistenceConfiguration {

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
            MapperFactoryBean<SkitAdCallbackInboxMapper> factory =
                    new MapperFactoryBean<>(SkitAdCallbackInboxMapper.class);
            factory.setSqlSessionFactory(sqlSessionFactory);
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        CallbackDrainTransaction callbackDrainTransaction(SkitAdCallbackInboxMapper mapper) {
            return new CallbackDrainTransaction(mapper);
        }
    }

    static class CallbackDrainTransaction {
        private final SkitAdCallbackInboxMapper mapper;

        CallbackDrainTransaction(SkitAdCallbackInboxMapper mapper) {
            this.mapper = mapper;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
                timeout = 15, rollbackFor = Exception.class)
        public List<SkitAdCallbackClaimDO> claim(String owner, int limit, int leaseSeconds,
                                                  CountDownLatch selected) {
            return TenantUtils.executeIgnore(() -> {
                List<SkitAdCallbackClaimDO> candidates = mapper.selectReadyClaimsForUpdate(limit);
                if (selected != null) {
                    selected.countDown();
                    await(selected);
                }
                List<SkitAdCallbackClaimDO> claimed = new ArrayList<>();
                for (SkitAdCallbackClaimDO candidate : candidates) {
                    int updated = mapper.claimForProcessingCas(candidate.getTenantId(),
                            candidate.getAdAccountId(), candidate.getId(), owner, leaseSeconds);
                    if (updated == 1) {
                        claimed.add(candidate);
                    }
                }
                return claimed;
            });
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
                timeout = 15, rollbackFor = Exception.class)
        public int retry(TenantFixture fixture, long inboxId, String owner, int maxAttempts,
                         int baseBackoffSeconds, int maxBackoffSeconds) {
            return mapper.markRetryWaitCas(fixture.tenantId, fixture.accountId, inboxId, owner,
                    "CALLBACK_PROCESSOR_EXCEPTION", maxAttempts,
                    baseBackoffSeconds, maxBackoffSeconds);
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
                timeout = 15, rollbackFor = Exception.class)
        public int deadLetter(TenantFixture fixture, long inboxId, String owner, int maxAttempts) {
            return mapper.markDeadLetterCas(fixture.tenantId, fixture.accountId, inboxId, owner,
                    "CALLBACK_PROCESSOR_EXCEPTION", maxAttempts);
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
                timeout = 15, rollbackFor = Exception.class)
        public int exhaustExpired(TenantFixture fixture, long inboxId, int maxAttempts) {
            return mapper.markExpiredProcessingDeadLetterCas(fixture.tenantId, fixture.accountId,
                    inboxId, "CALLBACK_LEASE_EXHAUSTED", maxAttempts);
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
                timeout = 15, rollbackFor = Exception.class)
        public int alertDeadLetter(TenantFixture fixture, long inboxId) {
            return mapper.markDeadLetterAlertedCas(
                    fixture.tenantId, fixture.accountId, inboxId);
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
                timeout = 15, rollbackFor = Exception.class)
        public int succeed(TenantFixture fixture, long inboxId, String owner) {
            SkitAdCallbackInboxDO active = mapper.selectActiveClaimForUpdate(
                    fixture.tenantId, fixture.accountId, inboxId, owner);
            return active == null ? 0 : mapper.markSucceededCas(
                    fixture.tenantId, fixture.accountId, inboxId, owner);
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
                timeout = 15, rollbackFor = Exception.class)
        public int reject(TenantFixture fixture, long inboxId, String owner, String errorCode) {
            SkitAdCallbackInboxDO active = mapper.selectActiveClaimForUpdate(
                    fixture.tenantId, fixture.accountId, inboxId, owner);
            return active == null ? 0 : mapper.markRejectedCas(
                    fixture.tenantId, fixture.accountId, inboxId, owner, errorCode);
        }
    }

}
