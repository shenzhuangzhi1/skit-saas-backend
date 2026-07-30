package cn.iocoder.yudao.module.skit.integration;

import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitAdCallbackInboxDO;
import cn.iocoder.yudao.module.skit.dal.dataobject.ad.SkitPangleRewardAttestationDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackInboxMapper;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitPangleRewardAttestationMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-MySQL proof for the Pangle reward-attestation persistence boundary.
 *
 * <p>The production schema initializer creates the schema before this class starts its real
 * MyBatis mappers. The tests therefore exercise MySQL 8 constraints, trigger bodies and
 * compare-and-set SQL instead of H2-compatible substitutes.</p>
 */
class SkitPangleRewardCallbackMySqlIT extends SkitMySqlIntegrationTestBase {

    private static final int CONCURRENCY = 12;

    private AnnotationConfigApplicationContext context;
    private SkitAdCallbackInboxMapper inboxMapper;
    private SkitPangleRewardAttestationMapper attestationMapper;

    @BeforeAll
    void startPanglePersistenceBoundary() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, this::dataSource);
        context.register(PanglePersistenceConfiguration.class);
        context.refresh();
        inboxMapper = context.getBean(SkitAdCallbackInboxMapper.class);
        attestationMapper = context.getBean(SkitPangleRewardAttestationMapper.class);
    }

    @AfterAll
    void closePanglePersistenceBoundary() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void productionInitializerInstallsPangleColumnsTableIndexesForeignKeysChecksAndTriggers() {
        assertEquals(1, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_schema_migration WHERE version=2026073001",
                Integer.class));
        assertEquals(3, jdbc().queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='skit_ad_session' "
                        + "AND COLUMN_NAME IN ('pangle_ad_account_id','pangle_reward_secret_version',"
                        + "'pangle_reward_placement_id')",
                Integer.class));
        assertEquals(1, jdbc().queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='skit_pangle_reward_attestation'",
                Integer.class));
        assertEquals(4, jdbc().queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='skit_pangle_reward_attestation' "
                        + "AND INDEX_NAME IN ('PRIMARY','uk_skit_pangle_attestation_tenant_id',"
                        + "'uk_skit_pangle_attestation_transaction',"
                        + "'uk_skit_pangle_attestation_session') AND SEQ_IN_INDEX=1",
                Integer.class));
        assertEquals(5, jdbc().queryForObject(
                "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='skit_pangle_reward_attestation'",
                Integer.class));
        assertEquals(3, jdbc().queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='skit_pangle_reward_attestation' "
                        + "AND CONSTRAINT_TYPE='CHECK'",
                Integer.class));
        assertEquals(3, jdbc().queryForObject(
                "SELECT COUNT(*) FROM information_schema.TRIGGERS "
                        + "WHERE TRIGGER_SCHEMA=DATABASE() "
                        + "AND TRIGGER_NAME IN ('trg_skit_pangle_attestation_immutable',"
                        + "'trg_skit_pangle_attestation_no_delete',"
                        + "'trg_skit_callback_inbox_monotonic')",
                Integer.class));
    }

    @Test
    void exactFirm15AttestationWakesRetryWaitInboxOnce() {
        TenantFixture fixture = installTenantFixture(
                98601L, currentSecond().minusMinutes(1), currentSecond().plusMinutes(20));
        InboxFixture inbox = prepareRetryWaitThroughProductionCas(
                fixture, fixture.primarySessionId, "wake-exact", currentSecond().plusMinutes(20));
        assertEquals(1, attestationMapper.insert(attestation(
                fixture, fixture.primarySessionId, "pangle-wake-exact")));

        assertEquals(1, inboxMapper.wakePangleAttestationPendingRewardCas(
                fixture.tenantId, fixture.takuAccountId, fixture.primarySessionId, 1));
        assertEquals(0, inboxMapper.wakePangleAttestationPendingRewardCas(
                fixture.tenantId, fixture.takuAccountId, fixture.primarySessionId, 1));

        Map<String, Object> state = inboxState(fixture.tenantId, inbox.inboxId);
        assertEquals("PENDING", state.get("processing_status"));
        assertEquals(null, state.get("error_code"));
        assertEquals(null, state.get("next_attempt_at"));
        assertEquals("pangle-attestation", state.get("updater"));
        assertEquals(1, ((Number) state.get("processing_attempt_count")).intValue());
    }

    @Test
    void expiredFirm15ProcessingLeaseRecoversToThePangleWaitStateAndCanStillBeWoken() {
        TenantFixture fixture = installTenantFixture(
                98610L, currentSecond().minusMinutes(1), currentSecond().plusMinutes(20));
        InboxFixture inbox = insertPendingLinkedInbox(
                fixture, fixture.primarySessionId, "expired-processing-recovery",
                currentSecond(), currentSecond().plusMinutes(20));
        assertEquals(1, inboxMapper.claimForProcessingCas(
                fixture.tenantId, fixture.takuAccountId, inbox.inboxId,
                "pangle-crashed-worker", 0));

        assertEquals(1, inboxMapper.recoverExpiredPanglePrerequisiteRetryWaitCas(
                fixture.tenantId, fixture.takuAccountId, inbox.inboxId, 0, 0));
        Map<String, Object> waiting = inboxState(fixture.tenantId, inbox.inboxId);
        assertEquals("RETRY_WAIT", waiting.get("processing_status"));
        assertEquals("PANGLE_ATTESTATION_PENDING", waiting.get("error_code"));
        assertEquals(null, waiting.get("lease_owner"));
        assertEquals(null, waiting.get("lease_until"));

        assertEquals(1, attestationMapper.insert(attestation(
                fixture, fixture.primarySessionId, "pangle-expired-processing-recovery")));
        assertEquals(1, inboxMapper.wakePangleAttestationPendingRewardCas(
                fixture.tenantId, fixture.takuAccountId, fixture.primarySessionId, 1));
        assertEquals("PENDING", inboxState(
                fixture.tenantId, inbox.inboxId).get("processing_status"));
    }

    @Test
    void wakeCasRejectsWrongTenantAccountSessionAndCallbackVersionBeforeExactScopeWins() {
        TenantFixture target = installTenantFixture(
                98602L, currentSecond().minusMinutes(1), currentSecond().plusMinutes(20));
        long otherSessionId = insertAdditionalSession(
                target, 2, currentSecond().minusMinutes(1), currentSecond().plusMinutes(20));
        TenantFixture otherTenant = installTenantFixture(
                98603L, currentSecond().minusMinutes(1), currentSecond().plusMinutes(20));
        InboxFixture inbox = prepareRetryWaitThroughProductionCas(
                target, target.primarySessionId, "wake-scope", currentSecond().plusMinutes(20));
        assertEquals(1, attestationMapper.insert(attestation(
                target, target.primarySessionId, "pangle-wake-scope")));

        assertEquals(0, inboxMapper.wakePangleAttestationPendingRewardCas(
                otherTenant.tenantId, target.takuAccountId, target.primarySessionId, 1));
        assertEquals(0, inboxMapper.wakePangleAttestationPendingRewardCas(
                target.tenantId, target.pangleAccountId, target.primarySessionId, 1));
        assertEquals(0, inboxMapper.wakePangleAttestationPendingRewardCas(
                target.tenantId, target.takuAccountId, otherSessionId, 1));
        assertEquals(0, inboxMapper.wakePangleAttestationPendingRewardCas(
                target.tenantId, target.takuAccountId, target.primarySessionId, 2));
        assertEquals("RETRY_WAIT", inboxState(
                target.tenantId, inbox.inboxId).get("processing_status"));

        assertEquals(1, inboxMapper.wakePangleAttestationPendingRewardCas(
                target.tenantId, target.takuAccountId, target.primarySessionId, 1));
        assertEquals("PENDING", inboxState(
                target.tenantId, inbox.inboxId).get("processing_status"));
    }

    @Test
    void wakeCasLeavesTerminalExpiredSessionAndExpiredPayloadUntouched() throws Exception {
        TenantFixture terminal = installTenantFixture(
                98604L, currentSecond().minusMinutes(1), currentSecond().plusMinutes(20));
        InboxFixture terminalInbox = prepareRetryWaitThroughProductionCas(
                terminal, terminal.primarySessionId, "terminal", currentSecond().plusMinutes(20));
        assertEquals(1, attestationMapper.insert(attestation(
                terminal, terminal.primarySessionId, "pangle-terminal")));
        assertEquals(1, inboxMapper.claimForProcessingCas(
                terminal.tenantId, terminal.takuAccountId, terminalInbox.inboxId,
                "pangle-terminal-it", 60));
        assertEquals(1, inboxMapper.markSucceededCas(
                terminal.tenantId, terminal.takuAccountId, terminalInbox.inboxId,
                "pangle-terminal-it"));

        LocalDateTime expiryBoundary = currentSecond().plusSeconds(4);
        TenantFixture expiredSession = installTenantFixture(
                98605L, currentSecond().minusMinutes(1), expiryBoundary);
        InboxFixture expiredSessionInbox = prepareRetryWaitThroughProductionCas(
                expiredSession, expiredSession.primarySessionId, "expired-session",
                currentSecond().plusMinutes(20));
        assertEquals(1, attestationMapper.insert(attestation(
                expiredSession, expiredSession.primarySessionId, "pangle-expired-session")));

        TenantFixture expiredPayload = installTenantFixture(
                98606L, currentSecond().minusMinutes(1), currentSecond().plusMinutes(20));
        InboxFixture expiredPayloadInbox = prepareRetryWaitThroughProductionCas(
                expiredPayload, expiredPayload.primarySessionId, "expired-payload",
                expiryBoundary);
        assertEquals(1, attestationMapper.insert(attestation(
                expiredPayload, expiredPayload.primarySessionId, "pangle-expired-payload")));
        waitUntilDatabaseTimeIsAfter(expiryBoundary);

        assertEquals(0, inboxMapper.wakePangleAttestationPendingRewardCas(
                terminal.tenantId, terminal.takuAccountId, terminal.primarySessionId, 1));
        assertEquals(0, inboxMapper.wakePangleAttestationPendingRewardCas(
                expiredSession.tenantId, expiredSession.takuAccountId,
                expiredSession.primarySessionId, 1));
        assertEquals(0, inboxMapper.wakePangleAttestationPendingRewardCas(
                expiredPayload.tenantId, expiredPayload.takuAccountId,
                expiredPayload.primarySessionId, 1));

        assertEquals("SUCCEEDED", inboxState(
                terminal.tenantId, terminalInbox.inboxId).get("processing_status"));
        assertEquals("RETRY_WAIT", inboxState(
                expiredSession.tenantId, expiredSessionInbox.inboxId).get("processing_status"));
        assertEquals("RETRY_WAIT", inboxState(
                expiredPayload.tenantId, expiredPayloadInbox.inboxId).get("processing_status"));
    }

    @Test
    void concurrentSameTransactionAcrossSessionsProducesAtMostOneAttestation() throws Exception {
        TenantFixture fixture = installTenantFixture(
                98607L, currentSecond().minusMinutes(1), currentSecond().plusMinutes(20));
        List<Long> sessionIds = new ArrayList<>();
        sessionIds.add(fixture.primarySessionId);
        for (int index = 2; index <= CONCURRENCY; index++) {
            sessionIds.add(insertAdditionalSession(
                    fixture, index, currentSecond().minusMinutes(1), currentSecond().plusMinutes(20)));
        }

        ConcurrentInsertResult result = insertConcurrently(sessionIds, index ->
                attestation(fixture, sessionIds.get(index), "shared-pangle-transaction"));

        assertEquals(1, result.inserted);
        assertEquals(CONCURRENCY - 1, result.duplicates);
        assertEquals(1, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_pangle_reward_attestation "
                        + "WHERE tenant_id=? AND pangle_ad_account_id=? "
                        + "AND provider_transaction_id='shared-pangle-transaction'",
                Integer.class, fixture.tenantId, fixture.pangleAccountId));
    }

    @Test
    void concurrentDifferentTransactionsForOneSessionProducesAtMostOneAttestation() throws Exception {
        TenantFixture fixture = installTenantFixture(
                98608L, currentSecond().minusMinutes(1), currentSecond().plusMinutes(20));
        List<Long> sameSession = new ArrayList<>();
        for (int index = 0; index < CONCURRENCY; index++) {
            sameSession.add(fixture.primarySessionId);
        }

        ConcurrentInsertResult result = insertConcurrently(sameSession, index ->
                attestation(fixture, fixture.primarySessionId,
                        "pangle-session-transaction-" + index));

        assertEquals(1, result.inserted);
        assertEquals(CONCURRENCY - 1, result.duplicates);
        assertEquals(1, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_pangle_reward_attestation "
                        + "WHERE tenant_id=? AND ad_session_id=?",
                Integer.class, fixture.tenantId, fixture.primarySessionId));
    }

    @Test
    void attestationUpdateAndDeleteAreRejectedByImmutableTriggers() {
        TenantFixture fixture = installTenantFixture(
                98609L, currentSecond().minusMinutes(1), currentSecond().plusMinutes(20));
        SkitPangleRewardAttestationDO candidate =
                attestation(fixture, fixture.primarySessionId, "pangle-immutable");
        assertEquals(1, attestationMapper.insert(candidate));
        assertNotNull(candidate.getId());

        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE skit_pangle_reward_attestation SET reward_name='tampered' "
                        + "WHERE tenant_id=? AND id=?",
                fixture.tenantId, candidate.getId()));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "DELETE FROM skit_pangle_reward_attestation WHERE tenant_id=? AND id=?",
                fixture.tenantId, candidate.getId()));

        Map<String, Object> stored = jdbc().queryForMap(
                "SELECT reward_name,canonical_payload_hash FROM skit_pangle_reward_attestation "
                        + "WHERE tenant_id=? AND id=?",
                fixture.tenantId, candidate.getId());
        assertEquals("积分", stored.get("reward_name"));
        assertArrayEquals(candidate.getCanonicalPayloadHash(),
                (byte[]) stored.get("canonical_payload_hash"));
        assertEquals(1, jdbc().queryForObject(
                "SELECT COUNT(*) FROM skit_pangle_reward_attestation "
                        + "WHERE tenant_id=? AND id=?",
                Integer.class, fixture.tenantId, candidate.getId()));
    }

    private InboxFixture prepareRetryWaitThroughProductionCas(
            TenantFixture fixture, long sessionId, String suffix, LocalDateTime payloadExpiresAt) {
        InboxFixture inbox = insertPendingLinkedInbox(
                fixture, sessionId, suffix, currentSecond(), payloadExpiresAt);
        assertEquals(1, inboxMapper.claimForProcessingCas(
                fixture.tenantId, fixture.takuAccountId, inbox.inboxId,
                "pangle-mysql-it", 60));
        assertEquals(1, inboxMapper.markPanglePrerequisiteRetryWaitCas(
                fixture.tenantId, fixture.takuAccountId, inbox.inboxId,
                "pangle-mysql-it", 0, 0));
        Map<String, Object> state = inboxState(fixture.tenantId, inbox.inboxId);
        assertEquals("RETRY_WAIT", state.get("processing_status"));
        assertEquals("PANGLE_ATTESTATION_PENDING", state.get("error_code"));
        assertNotNull(state.get("next_attempt_at"));
        return inbox;
    }

    private InboxFixture insertPendingLinkedInbox(
            TenantFixture fixture, long sessionId, String suffix,
            LocalDateTime receivedAt, LocalDateTime payloadExpiresAt) {
        SkitAdCallbackInboxDO row = new SkitAdCallbackInboxDO()
                .setAdAccountId(fixture.takuAccountId)
                .setAdSessionId(sessionId)
                .setCallbackKeyVersion(1)
                .setRewardSecretVersion(1)
                .setProvider("TAKU")
                .setCallbackType("REWARD")
                .setIdempotencyKey("taku-" + suffix)
                .setProviderUserId(fixture.pseudonymousUserId(sessionId))
                .setExtraDataHash(fixture.extraDataHash(sessionId))
                .setProviderTransactionId("taku-" + suffix)
                .setProviderShowId("show-" + suffix)
                .setPlacementId("taku-placement")
                .setAdsourceId("pangle-adsource")
                .setNetworkFirmId(15)
                .setSignedFieldMask(0x3fL)
                .setEvidenceProvenance("SIGNED_ILRD")
                .setCanonicalPayloadHash(hash("taku-payload-" + suffix))
                .setAuthenticationLevel("SIGNED_REWARD")
                .setSignatureStatus("VALID")
                .setDeliveryIntegrityStatus("CANONICAL")
                .setProcessingStatus("PENDING")
                .setPayloadCiphertext(hash("encrypted-payload-" + suffix))
                .setPayloadNonce(firstTwelve(hash("payload-nonce-" + suffix)))
                .setPayloadKeyId("pangle-mysql-it")
                .setPayloadEnvelopeVersion(1)
                .setPayloadExpiresAt(payloadExpiresAt)
                .setProcessingAttemptCount(0)
                .setReceivedAt(receivedAt)
                .setIngressResponseCode(200);
        row.setTenantId(fixture.tenantId);
        assertEquals(1, inboxMapper.insertOrGetCanonical(row));
        assertNotNull(row.getId());
        assertEquals(1, jdbc().update(
                "UPDATE skit_ad_session SET reward_callback_inbox_id=?,"
                        + "reward_callback_received_at=?,version=version+1,"
                        + "updater='pangle-mysql-it',update_time=? "
                        + "WHERE tenant_id=? AND id=? AND ad_account_id=? "
                        + "AND reward_callback_inbox_id IS NULL "
                        + "AND reward_callback_received_at IS NULL",
                row.getId(), receivedAt, receivedAt, fixture.tenantId,
                sessionId, fixture.takuAccountId));
        return new InboxFixture(row.getId());
    }

    private TenantFixture installTenantFixture(
            long tenantId, LocalDateTime loadExpiresAt, LocalDateTime rewardAcceptUntil) {
        long base = tenantId * 100;
        long memberId = base + 1;
        long takuAccountId = base + 10;
        long pangleAccountId = base + 11;
        long planId = base + 20;
        long snapshotId = base + 30;
        long primarySessionId = base + 40;
        long dramaId = base + 50;
        jdbc().update("INSERT INTO system_tenant (id,name,package_id,status,expire_time) "
                        + "VALUES (?, ?, 0, 0, '2099-01-01 00:00:00')",
                tenantId, "Pangle callback tenant " + tenantId);
        jdbc().update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                        + "VALUES (?,?,?,?,?,?,1,0)",
                memberId, tenantId, Long.toString(memberId), "encoded-password",
                "member-" + memberId, "PCI" + memberId);
        insertAccount(tenantId, takuAccountId, "TAKU");
        insertAccount(tenantId, pangleAccountId, "PANGLE");
        jdbc().update("INSERT INTO skit_commission_plan "
                        + "(id,tenant_id,version,status,published_time) VALUES (?,?,1,0,NOW())",
                planId, tenantId);
        jdbc().update("INSERT INTO skit_ad_policy_snapshot "
                        + "(id,tenant_id,plan_id,source_member_id,rule_version,snapshot_schema_version,"
                        + "snapshot_json,snapshot_hash,policy_snapshot_at) "
                        + "VALUES (?,?,?,?,1,1,'{}',?,NOW())",
                snapshotId, tenantId, planId, memberId, hash("snapshot-" + tenantId));
        insertCallbackKey(tenantId, takuAccountId);
        insertRewardSecret(tenantId, takuAccountId, "taku");
        insertRewardSecret(tenantId, pangleAccountId, "pangle");
        TenantFixture fixture = new TenantFixture(
                tenantId, memberId, takuAccountId, pangleAccountId,
                planId, snapshotId, primarySessionId, dramaId);
        insertSession(fixture, primarySessionId, 1, loadExpiresAt, rewardAcceptUntil);
        return fixture;
    }

    private long insertAdditionalSession(
            TenantFixture fixture, int index,
            LocalDateTime loadExpiresAt, LocalDateTime rewardAcceptUntil) {
        long sessionId = fixture.primarySessionId + index;
        insertSession(fixture, sessionId, index, loadExpiresAt, rewardAcceptUntil);
        return sessionId;
    }

    private void insertSession(
            TenantFixture fixture, long sessionId, int index,
            LocalDateTime loadExpiresAt, LocalDateTime rewardAcceptUntil) {
        jdbc().update("INSERT INTO skit_ad_session "
                        + "(id,tenant_id,session_id,session_token_hash,session_token_key_version,"
                        + "protocol_version,member_id,ad_account_id,policy_snapshot_id,"
                        + "callback_key_version,reward_secret_version,pangle_ad_account_id,"
                        + "pangle_reward_secret_version,pangle_reward_placement_id,provider,"
                        + "placement_id,scenario_id,business_type,drama_id,episode_from,episode_to,"
                        + "unlock_scope,active_scope_hash,pseudonymous_user_id,access_mode,"
                        + "client_lifecycle_status,reward_verification_status,entitlement_status,"
                        + "revenue_status,load_expires_at,reward_accept_until,last_callback_sequence,version) "
                        + "VALUES (?,?,?,?,1,1,?,?,?,1,1,?,1,?,'TAKU','taku-placement',"
                        + "'drama_unlock','EPISODE_UNLOCK',?,1,1,?,?,?,'MEMBER_OAUTH',"
                        + "'CREATED','PENDING','NONE','NONE',?,?,-1,0)",
                sessionId, fixture.tenantId, "pangle-session-" + fixture.tenantId + "-" + index,
                hash("session-token-" + fixture.tenantId + "-" + index),
                fixture.memberId, fixture.takuAccountId, fixture.snapshotId,
                fixture.pangleAccountId, fixture.panglePlacementId,
                fixture.dramaId, "scope-" + fixture.tenantId + "-" + index,
                hash("active-scope-" + fixture.tenantId + "-" + index),
                fixture.pseudonymousUserId(sessionId), loadExpiresAt, rewardAcceptUntil);
    }

    private void insertAccount(long tenantId, long accountId, String provider) {
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,status) "
                        + "VALUES (?,?,?,?,?,?,0)",
                accountId, tenantId, provider, provider + "-" + tenantId,
                provider.toLowerCase() + "-account-" + tenantId,
                provider.toLowerCase() + "-app-" + tenantId);
    }

    private void insertCallbackKey(long tenantId, long accountId) {
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) "
                        + "VALUES (?,?,1,?,b'1')",
                tenantId, accountId, hash("callback-key-" + tenantId));
    }

    private void insertRewardSecret(long tenantId, long accountId, String provider) {
        jdbc().update("INSERT INTO skit_ad_reward_secret_version "
                        + "(tenant_id,ad_account_id,secret_version,ciphertext,nonce,encryption_key_id,"
                        + "envelope_version,active) VALUES (?,?,1,?,?,?,1,b'1')",
                tenantId, accountId, hash("reward-secret-" + provider + "-" + tenantId),
                firstTwelve(hash("reward-nonce-" + provider + "-" + tenantId)),
                "pangle-mysql-it");
    }

    private SkitPangleRewardAttestationDO attestation(
            TenantFixture fixture, long sessionId, String transactionId) {
        SkitPangleRewardAttestationDO row = new SkitPangleRewardAttestationDO()
                .setTakuAdAccountId(fixture.takuAccountId)
                .setPangleAdAccountId(fixture.pangleAccountId)
                .setAdSessionId(sessionId)
                .setCallbackKeyVersion(1)
                .setPangleRewardSecretVersion(1)
                .setPangleRewardPlacementId(fixture.panglePlacementId)
                .setProvider("PANGLE")
                .setProviderTransactionId(transactionId)
                .setProviderUserId(fixture.pseudonymousUserId(sessionId))
                .setExtraDataHash(fixture.extraDataHash(sessionId))
                .setRewardName("积分")
                .setRewardAmount(1)
                .setCanonicalPayloadHash(hash("pangle-payload-" + transactionId))
                .setCredentialFingerprint(hash("credential-" + fixture.tenantId))
                .setReceivedAt(currentSecond());
        row.setTenantId(fixture.tenantId);
        return row;
    }

    private ConcurrentInsertResult insertConcurrently(
            List<Long> sessionIds, AttestationFactory factory) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(sessionIds.size());
        CountDownLatch ready = new CountDownLatch(sessionIds.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < sessionIds.size(); index++) {
                final int taskIndex = index;
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("attestation start barrier was not released");
                    }
                    try {
                        return attestationMapper.insert(factory.create(taskIndex)) == 1;
                    } catch (DuplicateKeyException duplicate) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS),
                    "attestation workers did not become ready");
            start.countDown();
            int inserted = 0;
            int duplicates = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    inserted++;
                } else {
                    duplicates++;
                }
            }
            return new ConcurrentInsertResult(inserted, duplicates);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Map<String, Object> inboxState(long tenantId, long inboxId) {
        return jdbc().queryForMap(
                "SELECT processing_status,error_code,next_attempt_at,processing_attempt_count,updater "
                        + "FROM skit_ad_callback_inbox WHERE tenant_id=? AND id=?",
                tenantId, inboxId);
    }

    private void waitUntilDatabaseTimeIsAfter(LocalDateTime boundary) throws Exception {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadlineNanos) {
            Integer expired = jdbc().queryForObject(
                    "SELECT CASE WHEN CURRENT_TIMESTAMP>? THEN 1 ELSE 0 END",
                    Integer.class, boundary);
            if (expired != null && expired == 1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("database time did not cross the expected expiry boundary");
    }

    private LocalDateTime currentSecond() {
        return jdbc().queryForObject("SELECT CURRENT_TIMESTAMP", LocalDateTime.class);
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
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PanglePersistenceConfiguration {

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
        MapperFactoryBean<SkitPangleRewardAttestationMapper> pangleAttestationMapperFactory(
                SqlSessionFactory sqlSessionFactory) {
            return mapperFactory(SkitPangleRewardAttestationMapper.class, sqlSessionFactory);
        }

        private static <T> MapperFactoryBean<T> mapperFactory(
                Class<T> mapperType, SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<T> factory = new MapperFactoryBean<>(mapperType);
            factory.setSqlSessionFactory(sqlSessionFactory);
            return factory;
        }
    }

    @FunctionalInterface
    private interface AttestationFactory {
        SkitPangleRewardAttestationDO create(int index);
    }

    private static final class ConcurrentInsertResult {
        private final int inserted;
        private final int duplicates;

        private ConcurrentInsertResult(int inserted, int duplicates) {
            this.inserted = inserted;
            this.duplicates = duplicates;
        }
    }

    private static final class InboxFixture {
        private final long inboxId;

        private InboxFixture(long inboxId) {
            this.inboxId = inboxId;
        }
    }

    private static final class TenantFixture {
        private final long tenantId;
        private final long memberId;
        private final long takuAccountId;
        private final long pangleAccountId;
        private final long planId;
        private final long snapshotId;
        private final long primarySessionId;
        private final long dramaId;
        private final String panglePlacementId;

        private TenantFixture(
                long tenantId, long memberId, long takuAccountId, long pangleAccountId,
                long planId, long snapshotId, long primarySessionId, long dramaId) {
            this.tenantId = tenantId;
            this.memberId = memberId;
            this.takuAccountId = takuAccountId;
            this.pangleAccountId = pangleAccountId;
            this.planId = planId;
            this.snapshotId = snapshotId;
            this.primarySessionId = primarySessionId;
            this.dramaId = dramaId;
            this.panglePlacementId = "pangle-reward-placement-" + tenantId;
        }

        private String pseudonymousUserId(long sessionId) {
            return "pangle-user-" + sessionId;
        }

        private byte[] extraDataHash(long sessionId) {
            return hash("pangle-extra-" + sessionId);
        }
    }

}
