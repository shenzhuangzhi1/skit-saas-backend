package cn.iocoder.yudao.module.skit.service.commission;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackEdgeAttemptMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdSessionMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitAdRevenueEventMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.revenue.SkitCommissionLedgerMapper;
import cn.iocoder.yudao.module.skit.integration.SkitMySqlIntegrationTestBase;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitAdCallbackEvidenceRetentionService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitAdCallbackEvidenceRetentionServiceImpl;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitAdRewardVerificationExpiryService;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitAdRewardVerificationExpiryServiceImpl;
import cn.iocoder.yudao.module.skit.service.ad.callback.SkitAdRewardReceiptResolutionService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-MySQL proof for reward expiry and callback-evidence retention. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SkitAdExpiryRetentionMySqlIT extends SkitMySqlIntegrationTestBase {

    private AnnotationConfigApplicationContext context;
    private SkitAdRewardVerificationExpiryService expiryService;
    private SkitAdCallbackEvidenceRetentionService retentionService;
    private SkitAdSessionMapper sessionMapper;
    private FixturePolicySnapshotService snapshotService;
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    void startPersistenceBoundary() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, this::dataSource);
        context.register(PersistenceConfiguration.class);
        context.refresh();
        expiryService = context.getBean(SkitAdRewardVerificationExpiryService.class);
        retentionService = context.getBean(SkitAdCallbackEvidenceRetentionService.class);
        sessionMapper = context.getBean(SkitAdSessionMapper.class);
        snapshotService = context.getBean(FixturePolicySnapshotService.class);
        transactionManager = context.getBean(PlatformTransactionManager.class);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @AfterAll
    void closePersistenceBoundary() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @Order(1)
    void databaseTimeExpiresAnImpressionAcrossJvmTimezoneAndCreatesOnlyAgentLedger() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
        try {
            assertNotEquals(0, TimeZone.getDefault().getRawOffset());
            assertEquals(0, jdbc().queryForObject(
                    "SELECT TIMESTAMPDIFF(HOUR,UTC_TIMESTAMP(),CURRENT_TIMESTAMP())", Integer.class));
            SessionFixture fixture = installExpiredSession(98701L, true, "IMPRESSION_PENDING_REWARD");

            assertTrue(expiryService.sweepOnce() >= 1);

            Map<String, Object> session = sessionState(fixture);
            assertEquals("VERIFY_TIMEOUT", session.get("reward_verification_status"));
            assertEquals("FROZEN", session.get("revenue_status"));
            assertNull(session.get("active_scope_hash"));
            assertEquals("VERIFY_TIMEOUT", session.get("active_scope_release_reason"));
            Map<String, Object> event = jdbc().queryForMap(
                    "SELECT reward_qualification_status,reconciliation_status,version "
                            + "FROM skit_ad_revenue_event WHERE tenant_id=? AND id=?",
                    fixture.tenantId, fixture.eventId);
            assertEquals("NON_REWARDED", event.get("reward_qualification_status"));
            assertEquals("FROZEN", event.get("reconciliation_status"));
            assertEquals(1, ((Number) event.get("version")).intValue());
            Map<String, Object> ledger = jdbc().queryForMap(
                    "SELECT beneficiary_type,beneficiary_member_id,level_no,amount_units,"
                            + "gross_amount_units,balance_bucket,entry_type FROM skit_commission_ledger "
                            + "WHERE tenant_id=? AND event_id=?",
                    fixture.tenantId, fixture.eventId);
            assertEquals(2, ((Number) ledger.get("beneficiary_type")).intValue());
            assertEquals(0L, ((Number) ledger.get("beneficiary_member_id")).longValue());
            assertEquals(-1, ((Number) ledger.get("level_no")).intValue());
            assertEquals(1000L, ((Number) ledger.get("amount_units")).longValue());
            assertEquals(1000L, ((Number) ledger.get("gross_amount_units")).longValue());
            assertEquals("FROZEN", ledger.get("balance_bucket"));
            assertEquals("ESTIMATE", ledger.get("entry_type"));
            assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM skit_commission_ledger "
                            + "WHERE tenant_id=? AND event_id=? AND beneficiary_type=1",
                    Integer.class, fixture.tenantId, fixture.eventId));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    @Order(2)
    void noImpressionOnlyTimesOutAndNeverFabricatesRevenueOrLedger() {
        SessionFixture fixture = installExpiredSession(98702L, false, "NONE");

        expiryService.sweepOnce();

        Map<String, Object> session = sessionState(fixture);
        assertEquals("VERIFY_TIMEOUT", session.get("reward_verification_status"));
        assertEquals("NONE", session.get("revenue_status"));
        assertEquals(0, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_ad_revenue_event WHERE tenant_id=? AND ad_session_id=?",
                Integer.class, fixture.tenantId, fixture.sessionId));
        assertEquals(0, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_commission_ledger WHERE tenant_id=?",
                Integer.class, fixture.tenantId));
    }

    @Test
    @Order(3)
    void receiptAndTimeoutRaceHasExactlyOneWinnerAndPreservesTheReceiptFact() throws Exception {
        SessionFixture fixture = installExpiredSession(98703L, false, "NONE");
        long rewardInboxId = fixture.base + 20;
        insertPendingSignedRewardInbox(rewardInboxId, fixture);
        LocalDateTime receivedAt = jdbc().queryForObject(
                "SELECT received_at FROM skit_ad_callback_inbox WHERE tenant_id=? AND id=?",
                LocalDateTime.class, fixture.tenantId, rewardInboxId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        int swept;
        int received;
        try {
            Future<Integer> sweep = workers.submit(() -> {
                awaitStart(ready, start);
                return expiryService.sweepOnce();
            });
            Future<Integer> receipt = workers.submit(() -> {
                awaitStart(ready, start);
                AtomicInteger result = new AtomicInteger();
                TenantUtils.execute(fixture.tenantId, (Runnable) () ->
                        result.set(new TransactionTemplate(transactionManager).execute(status ->
                                sessionMapper.markRewardCallbackReceivedCas(
                                        fixture.tenantId, fixture.sessionId, fixture.accountId,
                                        rewardInboxId, receivedAt))));
                return result.get();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            swept = sweep.get(30, TimeUnit.SECONDS);
            received = receipt.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }

        Map<String, Object> session = sessionState(fixture);
        boolean timeoutWon = "VERIFY_TIMEOUT".equals(session.get("reward_verification_status"));
        assertEquals(1, (timeoutWon ? 1 : 0) + received);
        if (timeoutWon) {
            assertTrue(swept >= 1);
            assertNull(session.get("reward_callback_inbox_id"));
        } else {
            assertEquals("PENDING", session.get("reward_verification_status"));
            assertEquals(rewardInboxId,
                    ((Number) session.get("reward_callback_inbox_id")).longValue());
            assertNotNull(session.get("reward_callback_received_at"));
            assertNotNull(session.get("active_scope_hash"));
        }
        assertEquals(1, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_ad_callback_inbox WHERE tenant_id=? AND id=?",
                Integer.class, fixture.tenantId, rewardInboxId));
    }

    @Test
    @Order(4)
    void signedAndRejectedFactsAreIrreversibleWhileTwoTenantsExpireIndependently() {
        SessionFixture signed = installExpiredSession(98704L, false, "NONE");
        SessionFixture rejected = installExpiredSession(98705L, false, "NONE");
        SessionFixture tenantA = installExpiredSession(98706L, false, "NONE");
        SessionFixture tenantB = installExpiredSession(98707L, false, "NONE");
        jdbc().update("UPDATE skit_ad_session SET reward_verification_status='SIGNED_VERIFIED',"
                        + "entitlement_status='GRANTED',reward_verified_at=CURRENT_TIMESTAMP,"
                        + "entitled_at=CURRENT_TIMESTAMP,active_scope_hash=NULL,"
                        + "active_scope_released_at=CURRENT_TIMESTAMP,"
                        + "active_scope_release_reason='ENTITLEMENT_GRANTED' WHERE tenant_id=? AND id=?",
                signed.tenantId, signed.sessionId);
        jdbc().update("UPDATE skit_ad_session SET reward_verification_status='REJECTED',"
                        + "active_scope_hash=NULL,active_scope_released_at=CURRENT_TIMESTAMP,"
                        + "active_scope_release_reason='REWARD_REJECTED' WHERE tenant_id=? AND id=?",
                rejected.tenantId, rejected.sessionId);

        expiryService.sweepOnce();

        assertEquals("SIGNED_VERIFIED", sessionState(signed).get("reward_verification_status"));
        assertEquals("REJECTED", sessionState(rejected).get("reward_verification_status"));
        assertEquals("VERIFY_TIMEOUT", sessionState(tenantA).get("reward_verification_status"));
        assertEquals("VERIFY_TIMEOUT", sessionState(tenantB).get("reward_verification_status"));
        assertEquals(tenantA.tenantId, jdbc().queryForObject(
                "SELECT tenant_id FROM skit_ad_session WHERE id=?", Long.class, tenantA.sessionId));
        assertEquals(tenantB.tenantId, jdbc().queryForObject(
                "SELECT tenant_id FROM skit_ad_session WHERE id=?", Long.class, tenantB.sessionId));
    }

    @Test
    @Order(5)
    void scheduledSweepCompensatesDeadLetterReceiptAndSuspendsItsPendingImpression() {
        SessionFixture fixture = installExpiredSession(98709L, true, "IMPRESSION_PENDING_REWARD");
        long rewardInboxId = fixture.base + 20;
        insertPendingSignedRewardInbox(rewardInboxId, fixture);
        LocalDateTime receivedAt = jdbc().queryForObject(
                "SELECT received_at FROM skit_ad_callback_inbox WHERE tenant_id=? AND id=?",
                LocalDateTime.class, fixture.tenantId, rewardInboxId);
        assertEquals(1, TenantUtils.execute(fixture.tenantId,
                () -> sessionMapper.markRewardCallbackReceivedCas(
                        fixture.tenantId, fixture.sessionId, fixture.accountId,
                        rewardInboxId, receivedAt)));
        SkitAdCallbackInboxMapper inboxMapper = context.getBean(SkitAdCallbackInboxMapper.class);
        assertEquals(1, TenantUtils.execute(fixture.tenantId,
                () -> inboxMapper.claimForProcessingCas(fixture.tenantId, fixture.accountId,
                        rewardInboxId, "expiry-dead-letter", 60)));
        assertEquals(1, TenantUtils.execute(fixture.tenantId,
                () -> inboxMapper.markDeadLetterCas(fixture.tenantId, fixture.accountId,
                        rewardInboxId, "expiry-dead-letter", "PROCESSING_RETRIES_EXHAUSTED", 1)));
        assertEquals(1, TenantUtils.execute(fixture.tenantId,
                () -> inboxMapper.markPayloadConflict(fixture.tenantId, fixture.accountId,
                        rewardInboxId, LocalDateTime.now().withNano(0))));
        Map<String, Object> pending = jdbc().queryForMap(
                "SELECT member_id,callback_key_version,reward_secret_version,drama_id,episode_from,"
                        + "active_scope_hash,version FROM skit_ad_session WHERE tenant_id=? AND id=?",
                fixture.tenantId, fixture.sessionId);
        assertEquals(0, TenantUtils.execute(fixture.tenantId,
                () -> sessionMapper.markTerminalRewardReceiptRejectedAndReleaseScopeCas(
                        fixture.tenantId, fixture.sessionId, fixture.accountId, fixture.memberId,
                        rewardInboxId, receivedAt,
                        ((Number) pending.get("callback_key_version")).intValue(),
                        ((Number) pending.get("reward_secret_version")).intValue(),
                        ((Number) pending.get("drama_id")).longValue(),
                        ((Number) pending.get("episode_from")).intValue(),
                        (byte[]) pending.get("active_scope_hash"),
                        ((Number) pending.get("version")).intValue(), "DEAD_LETTER",
                        "CALLBACK_DEAD_LETTER", LocalDateTime.now().withNano(0))),
                "PAYLOAD_CONFLICT cannot be compensated with the canonical dead-letter reason");

        assertTrue(expiryService.sweepOnce() >= 1);

        Map<String, Object> session = jdbc().queryForMap(
                "SELECT reward_verification_status,entitlement_status,revenue_status,"
                        + "active_scope_hash,active_scope_released_at,active_scope_release_reason,"
                        + "failure_reason FROM skit_ad_session WHERE tenant_id=? AND id=?",
                fixture.tenantId, fixture.sessionId);
        assertEquals("REJECTED", session.get("reward_verification_status"));
        assertEquals("NONE", session.get("entitlement_status"));
        assertEquals("SUSPENSE", session.get("revenue_status"));
        assertNull(session.get("active_scope_hash"));
        assertNotNull(session.get("active_scope_released_at"));
        assertEquals("REWARD_REJECTED", session.get("active_scope_release_reason"));
        assertEquals("CALLBACK_PAYLOAD_CONFLICT", session.get("failure_reason"));
        Map<String, Object> event = jdbc().queryForMap(
                "SELECT reward_qualification_status,reconciliation_status,version "
                        + "FROM skit_ad_revenue_event WHERE tenant_id=? AND id=?",
                fixture.tenantId, fixture.eventId);
        assertEquals("NON_REWARDED", event.get("reward_qualification_status"));
        assertEquals("SUSPENSE", event.get("reconciliation_status"));
        assertEquals(1, ((Number) event.get("version")).intValue());
        assertEquals(0, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_commission_ledger WHERE tenant_id=? AND event_id=?",
                Integer.class, fixture.tenantId, fixture.eventId));
    }

    @Test
    @Order(6)
    void retentionErasesOnlyExpiredTerminalPayloadAndDeletesOnlyOldAttemptsAndEdges() {
        long tenantId = 98708L;
        AccountFixture account = installAccountOnly(tenantId);
        long terminalExpired = account.base + 30;
        long pendingExpired = account.base + 31;
        long terminalRecent = account.base + 32;
        insertInbox(terminalExpired, tenantId, account.accountId, null,
                "IMPRESSION", "terminal-expired", "SUCCEEDED", true, 91, -1);
        insertInbox(pendingExpired, tenantId, account.accountId, null,
                "IMPRESSION", "pending-expired", "PENDING", true, 91, -1);
        insertInbox(terminalRecent, tenantId, account.accountId, null,
                "IMPRESSION", "terminal-recent", "SUCCEEDED", true, 89, 1);
        insertAttempt(account.base + 40, tenantId, account.accountId, terminalExpired, 181);
        insertAttempt(account.base + 41, tenantId, account.accountId, terminalExpired, 179);
        insertEdge(account.base + 50, tenantId, account.accountId, 181);
        insertEdge(account.base + 51, tenantId, account.accountId, 179);
        insertEdge(account.base + 52, null, null, 181);
        insertEdge(account.base + 53, null, null, 179);
        int inboxFactsBefore = count("skit_ad_callback_inbox");
        int sessionFactsBefore = count("skit_ad_session");
        int revenueFactsBefore = count("skit_ad_revenue_event");
        int ledgerFactsBefore = count("skit_commission_ledger");

        SkitAdCallbackEvidenceRetentionService.RetentionResult result = retentionService.runOnce();

        assertTrue(result.getErasedPayloadCount() >= 1);
        assertEquals(1, result.getDeletedAttemptCount());
        assertEquals(2, result.getDeletedEdgeAttemptCount());
        assertNull(jdbc().queryForObject("SELECT payload_ciphertext FROM skit_ad_callback_inbox "
                        + "WHERE tenant_id=? AND id=?", byte[].class, tenantId, terminalExpired));
        assertNotNull(jdbc().queryForObject("SELECT payload_ciphertext FROM skit_ad_callback_inbox "
                        + "WHERE tenant_id=? AND id=?", byte[].class, tenantId, pendingExpired));
        assertNotNull(jdbc().queryForObject("SELECT payload_ciphertext FROM skit_ad_callback_inbox "
                        + "WHERE tenant_id=? AND id=?", byte[].class, tenantId, terminalRecent));
        assertEquals(0, exists("skit_ad_callback_attempt", account.base + 40));
        assertEquals(1, exists("skit_ad_callback_attempt", account.base + 41));
        assertEquals(0, exists("skit_ad_callback_edge_attempt", account.base + 50));
        assertEquals(1, exists("skit_ad_callback_edge_attempt", account.base + 51));
        assertEquals(0, exists("skit_ad_callback_edge_attempt", account.base + 52));
        assertEquals(1, exists("skit_ad_callback_edge_attempt", account.base + 53));
        assertEquals(inboxFactsBefore, count("skit_ad_callback_inbox"));
        assertEquals(sessionFactsBefore, count("skit_ad_session"));
        assertEquals(revenueFactsBefore, count("skit_ad_revenue_event"));
        assertEquals(ledgerFactsBefore, count("skit_commission_ledger"));
    }

    private SessionFixture installExpiredSession(long tenantId, boolean impression, String revenueStatus) {
        AccountFixture account = installAccountOnly(tenantId);
        long memberId = account.base + 1;
        long planId = account.base + 3;
        long snapshotId = account.base + 4;
        long sessionId = account.base + 5;
        long inboxId = impression ? account.base + 6 : 0L;
        long eventId = impression ? account.base + 7 : 0L;
        long dramaId = account.base + 8;
        jdbc().update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                        + "VALUES (?,?,?,?,?,?,1,0)", memberId, tenantId, Long.toString(memberId),
                "encoded", "member-" + memberId, "ERT" + memberId);
        jdbc().update("INSERT INTO skit_commission_plan "
                        + "(id,tenant_id,version,status,published_time) VALUES (?,?,1,0,CURRENT_TIMESTAMP)",
                planId, tenantId);
        jdbc().update("INSERT INTO skit_ad_policy_snapshot "
                        + "(id,tenant_id,plan_id,source_member_id,rule_version,snapshot_schema_version,"
                        + "snapshot_json,snapshot_hash,policy_snapshot_at) "
                        + "VALUES (?,?,?,?,1,1,'{}',?,CURRENT_TIMESTAMP)",
                snapshotId, tenantId, planId, memberId, hash("snapshot-" + tenantId));
        snapshotService.put(snapshotId, tenantId, planId, memberId);
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) "
                        + "VALUES (?,?,1,?,b'1')", tenantId, account.accountId,
                hash("callback-key-" + tenantId));
        jdbc().update("INSERT INTO skit_ad_reward_secret_version "
                        + "(tenant_id,ad_account_id,secret_version,ciphertext,nonce,encryption_key_id,"
                        + "envelope_version,active) VALUES (?,?,1,?,?,?,1,b'1')",
                tenantId, account.accountId, hash("secret-" + tenantId),
                firstTwelve(hash("nonce-" + tenantId)), "mysql-it-key");
        jdbc().update("INSERT INTO skit_ad_session "
                        + "(id,tenant_id,session_id,session_token_hash,session_token_key_version,protocol_version,"
                        + "member_id,ad_account_id,policy_snapshot_id,callback_key_version,reward_secret_version,"
                        + "provider,placement_id,scenario_id,business_type,drama_id,episode_from,episode_to,"
                        + "unlock_scope,active_scope_hash,pseudonymous_user_id,access_mode,client_lifecycle_status,"
                        + "reward_verification_status,entitlement_status,revenue_status,load_expires_at,"
                        + "reward_accept_until,last_callback_sequence,version) "
                        + "VALUES (?,?,?,?,1,1,?,?,?,1,1,'TAKU',?,'drama_unlock','EPISODE_UNLOCK',?,1,1,"
                        + "?,?,?,'MEMBER_OAUTH','CREATED','PENDING','NONE',?,"
                        + "DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 3 HOUR),"
                        + "DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 2 HOUR),-1,0)",
                sessionId, tenantId, "expiry-session-" + tenantId, hash("token-" + tenantId),
                memberId, account.accountId, snapshotId, "placement-" + tenantId, dramaId,
                "scope-" + tenantId, hash("scope-" + tenantId), "pseudo-" + tenantId, revenueStatus);
        if (impression) {
            insertInbox(inboxId, tenantId, account.accountId, sessionId,
                    "IMPRESSION", "impression-" + tenantId, "SUCCEEDED", false, 0, 0);
            jdbc().update("INSERT INTO skit_ad_revenue_event "
                            + "(id,tenant_id,ad_account_id,provider,placement_id,external_event_id,source_member_id,"
                            + "gross_amount,occurred_time,completed,mock,status,rule_version,ad_session_id,"
                            + "callback_inbox_id,policy_snapshot_id,source_type,provider_show_id,source_amount_units,"
                            + "estimated_amount_units,amount_scale,source_currency,match_status,"
                            + "source_verification_status,reward_qualification_status,reconciliation_status,"
                            + "payload_hash,version,legacy_unverified) VALUES "
                            + "(?,?,?,'TAKU',?,?,?,0.00100000,CURRENT_TIMESTAMP,b'0',b'0',1,1,?,?,?,"
                            + "'TAKU_IMPRESSION',?,1000,1000,6,'USD','MATCHED','UNSIGNED_OBSERVATION',"
                            + "'PENDING_REWARD','FROZEN',?,0,b'0')",
                    eventId, tenantId, account.accountId, "placement-" + tenantId,
                    "impression-event-" + tenantId, memberId, sessionId, inboxId, snapshotId,
                    "show-" + tenantId, hash("event-payload-" + tenantId));
        }
        return new SessionFixture(account.base, tenantId, account.accountId, memberId,
                snapshotId, sessionId, inboxId, eventId);
    }

    private AccountFixture installAccountOnly(long tenantId) {
        long base = tenantId * 100;
        long accountId = base + 2;
        jdbc().update("INSERT INTO system_tenant (id,name,package_id,status,expire_time) "
                        + "VALUES (?,?,0,0,'2099-01-01 00:00:00')",
                tenantId, "Expiry tenant " + tenantId);
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,status) "
                        + "VALUES (?,?,'TAKU',?,?,?,0)", accountId, tenantId,
                "TAKU-" + tenantId, "account-" + tenantId, "app-" + tenantId);
        return new AccountFixture(base, tenantId, accountId);
    }

    private void insertInbox(long id, long tenantId, long accountId, Long sessionId,
                             String callbackType, String idempotencyKey, String processingStatus,
                             boolean payload, int receivedDaysAgo, int payloadExpiresInDays) {
        String processed = "PENDING".equals(processingStatus) ? "NULL" : "CURRENT_TIMESTAMP";
        String payloadValues = payload
                ? "X'010203',UNHEX('000102030405060708090A0B'),'mysql-it-payload',1,"
                + (payloadExpiresInDays < 0
                ? "DATE_SUB(CURRENT_TIMESTAMP,INTERVAL " + (-payloadExpiresInDays) + " DAY)"
                : "DATE_ADD(CURRENT_TIMESTAMP,INTERVAL " + payloadExpiresInDays + " DAY)")
                : "NULL,NULL,NULL,NULL,NULL";
        jdbc().update("INSERT INTO skit_ad_callback_inbox "
                        + "(id,tenant_id,ad_account_id,ad_session_id,provider,callback_type,idempotency_key,"
                        + "canonical_payload_hash,authentication_level,signature_status,delivery_integrity_status,"
                        + "processing_status,payload_ciphertext,payload_nonce,payload_key_id,payload_envelope_version,"
                        + "payload_expires_at,processing_attempt_count,received_at,processed_at,ingress_response_code) "
                        + "VALUES (?,?,?,?,'TAKU',?,?,?,'UNSIGNED_OBSERVATION','NOT_APPLICABLE','CANONICAL',?,"
                        + payloadValues + ",?,DATE_SUB(CURRENT_TIMESTAMP,INTERVAL "
                        + receivedDaysAgo + " DAY)," + processed + ",200)",
                id, tenantId, accountId, sessionId, callbackType, idempotencyKey,
                hash("inbox-" + tenantId + "-" + id), processingStatus,
                "PENDING".equals(processingStatus) ? 0 : 1);
    }

    private void insertPendingSignedRewardInbox(long id, SessionFixture fixture) {
        jdbc().update("INSERT INTO skit_ad_callback_inbox "
                        + "(id,tenant_id,ad_account_id,ad_session_id,callback_key_version,"
                        + "reward_secret_version,provider,callback_type,idempotency_key,"
                        + "provider_transaction_id,provider_show_id,placement_id,adsource_id,network_firm_id,"
                        + "signed_field_mask,evidence_provenance,canonical_payload_hash,authentication_level,"
                        + "signature_status,delivery_integrity_status,processing_status,processing_attempt_count,"
                        + "received_at,ingress_response_code) SELECT ?,tenant_id,ad_account_id,id,"
                        + "callback_key_version,reward_secret_version,'TAKU','REWARD',?,?,'dead-letter-show',"
                        + "placement_id,'7',66,63,'SIGNED_ILRD',?,'SIGNED_REWARD','VALID','CANONICAL',"
                        + "'PENDING',0,DATE_SUB(reward_accept_until,INTERVAL 1 MINUTE),200 "
                        + "FROM skit_ad_session WHERE tenant_id=? AND id=?",
                id, "dead-letter-reward-" + fixture.tenantId,
                "dead-letter-transaction-" + fixture.tenantId,
                hash("dead-letter-payload-" + fixture.tenantId),
                fixture.tenantId, fixture.sessionId);
    }

    private void insertAttempt(long id, long tenantId, long accountId, long inboxId, int daysAgo) {
        jdbc().update("INSERT INTO skit_ad_callback_attempt "
                        + "(id,tenant_id,callback_inbox_id,ad_account_id,ad_session_id,attempt_no,"
                        + "payload_hash,result_code,received_at) VALUES (?,?,?,?,NULL,?,?,'CANONICAL',"
                        + "DATE_SUB(CURRENT_TIMESTAMP,INTERVAL " + daysAgo + " DAY))",
                id, tenantId, inboxId, accountId, daysAgo > 180 ? 1 : 2, hash("attempt-" + id));
    }

    private void insertEdge(long id, Long tenantId, Long accountId, int daysAgo) {
        jdbc().update("INSERT INTO skit_ad_callback_edge_attempt "
                        + "(id,tenant_id,ad_account_id,callback_key_hash,provider,callback_type,client_ip_hash,"
                        + "request_method,result_code,received_at) VALUES (?,?,?,?,'TAKU','IMPRESSION',?,"
                        + "'GET','ACCEPTED',DATE_SUB(CURRENT_TIMESTAMP,INTERVAL " + daysAgo + " DAY))",
                id, tenantId, accountId, hash("edge-key-" + id), hash("edge-ip-" + id));
    }

    private Map<String, Object> sessionState(SessionFixture fixture) {
        return jdbc().queryForMap("SELECT reward_verification_status,revenue_status,"
                        + "reward_callback_inbox_id,reward_callback_received_at,active_scope_hash,"
                        + "active_scope_release_reason FROM skit_ad_session WHERE tenant_id=? AND id=?",
                fixture.tenantId, fixture.sessionId);
    }

    private int count(String table) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class);
    }

    private int exists(String table, long id) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM `" + table + "` WHERE id=?",
                Integer.class, id);
    }

    private static void awaitStart(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("race start timed out");
        }
    }

    private static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] firstTwelve(byte[] value) {
        byte[] result = new byte[12];
        System.arraycopy(value, 0, result, 0, result.length);
        return result;
    }

    private static class AccountFixture {
        final long base;
        final long tenantId;
        final long accountId;

        private AccountFixture(long base, long tenantId, long accountId) {
            this.base = base;
            this.tenantId = tenantId;
            this.accountId = accountId;
        }
    }

    private static final class SessionFixture extends AccountFixture {
        private final long memberId;
        private final long snapshotId;
        private final long sessionId;
        private final long inboxId;
        private final long eventId;

        private SessionFixture(long base, long tenantId, long accountId, long memberId,
                               long snapshotId, long sessionId, long inboxId, long eventId) {
            super(base, tenantId, accountId);
            this.memberId = memberId;
            this.snapshotId = snapshotId;
            this.sessionId = sessionId;
            this.inboxId = inboxId;
            this.eventId = eventId;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class PersistenceConfiguration {

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
        MapperFactoryBean<SkitAdSessionMapper> adSessionMapperFactory(SqlSessionFactory factory) {
            return mapperFactory(SkitAdSessionMapper.class, factory);
        }

        @Bean
        MapperFactoryBean<SkitAdRevenueEventMapper> adRevenueEventMapperFactory(SqlSessionFactory factory) {
            return mapperFactory(SkitAdRevenueEventMapper.class, factory);
        }

        @Bean
        MapperFactoryBean<SkitCommissionLedgerMapper> commissionLedgerMapperFactory(SqlSessionFactory factory) {
            return mapperFactory(SkitCommissionLedgerMapper.class, factory);
        }

        @Bean
        MapperFactoryBean<SkitAdCallbackInboxMapper> callbackInboxMapperFactory(SqlSessionFactory factory) {
            return mapperFactory(SkitAdCallbackInboxMapper.class, factory);
        }

        @Bean
        MapperFactoryBean<SkitAdCallbackAttemptMapper> callbackAttemptMapperFactory(SqlSessionFactory factory) {
            return mapperFactory(SkitAdCallbackAttemptMapper.class, factory);
        }

        @Bean
        MapperFactoryBean<SkitAdCallbackEdgeAttemptMapper> callbackEdgeMapperFactory(SqlSessionFactory factory) {
            return mapperFactory(SkitAdCallbackEdgeAttemptMapper.class, factory);
        }

        private static <T> MapperFactoryBean<T> mapperFactory(Class<T> type, SqlSessionFactory factory) {
            MapperFactoryBean<T> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionFactory(factory);
            return bean;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        FixturePolicySnapshotService policySnapshotService() {
            return new FixturePolicySnapshotService();
        }

        @Bean
        SkitMoneyAllocator moneyAllocator() {
            return new SkitMoneyAllocator();
        }

        @Bean
        SkitFrozenCommissionProjectionService frozenCommissionProjectionService() {
            return new SkitFrozenCommissionProjectionServiceImpl();
        }

        @Bean
        SkitAdRewardReceiptResolutionService rewardReceiptResolutionService(
                SkitAdCallbackInboxMapper inboxMapper,
                SkitAdSessionMapper sessionMapper,
                SkitAdRevenueEventMapper eventMapper) {
            return new SkitAdRewardReceiptResolutionService(inboxMapper, sessionMapper, eventMapper);
        }

        @Bean
        SkitAdRewardVerificationExpiryService rewardExpiryService(
                SkitAdSessionMapper sessionMapper,
                SkitAdRevenueEventMapper eventMapper,
                SkitAdRewardReceiptResolutionService rewardReceiptResolutionService,
                SkitFrozenCommissionProjectionService projectionService,
                PlatformTransactionManager transactionManager) {
            return new SkitAdRewardVerificationExpiryServiceImpl(
                    sessionMapper, eventMapper, rewardReceiptResolutionService,
                    projectionService, transactionManager, 100);
        }

        @Bean
        SkitAdCallbackEvidenceRetentionService evidenceRetentionService(
                SkitAdCallbackInboxMapper inboxMapper,
                SkitAdCallbackAttemptMapper attemptMapper,
                SkitAdCallbackEdgeAttemptMapper edgeMapper,
                PlatformTransactionManager transactionManager) {
            return new SkitAdCallbackEvidenceRetentionServiceImpl(
                    inboxMapper, attemptMapper, edgeMapper, transactionManager, 200, 180);
        }
    }

    static final class FixturePolicySnapshotService implements SkitPolicySnapshotService {
        private final Map<Long, PolicySnapshot> snapshots = new ConcurrentHashMap<>();

        void put(long snapshotId, long tenantId, long planId, long memberId) {
            snapshots.put(snapshotId, new PolicySnapshot(snapshotId, tenantId, 0, planId,
                    memberId, 1, 1, "{}", hash("policy-" + snapshotId),
                    LocalDateTime.now().withNano(0), Collections.emptyList(),
                    Collections.emptyList(), 0, 0));
        }

        @Override
        public PolicySnapshot createSnapshot(Long sourceMemberId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PolicySnapshot getRequired(Long snapshotId) {
            PolicySnapshot snapshot = snapshots.get(snapshotId);
            if (snapshot == null) {
                throw new IllegalStateException("missing fixture policy snapshot");
            }
            return snapshot;
        }
    }
}
