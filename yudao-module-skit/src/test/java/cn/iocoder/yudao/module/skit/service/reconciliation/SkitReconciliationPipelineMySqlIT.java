package cn.iocoder.yudao.module.skit.service.reconciliation;

import cn.iocoder.yudao.module.skit.dal.dataobject.revenue.SkitAdRevenueEventDO;
import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdAccountMapper;
import cn.iocoder.yudao.module.skit.integration.SkitMySqlIntegrationTestBase;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-MySQL proof for Task 10 append-only retries, tenant isolation, and archive routing. */
class SkitReconciliationPipelineMySqlIT extends SkitMySqlIntegrationTestBase {

    @Test
    void requestScopeIsPartOfPullIdempotencyAndImmutableFactsRemainTenantBound() {
        AccountFixture first = installAccount(99101L, null, false);
        AccountFixture second = installAccount(99102L, null, false);
        LocalDate reportDate = LocalDate.of(2026, 7, 12);
        byte[] responseHash = hash("same-empty-response");
        byte[] oldScope = hash("scope-v1");
        byte[] newScope = hash("scope-v2");

        long firstPull = insertPull(first, reportDate, oldScope, responseHash);
        assertTrue(hasUnsettledScope(first));
        long finalPull = insertPull(first, reportDate, oldScope, responseHash, true);
        assertFalse(hasUnsettledScope(first));
        long secondScopePull = insertPull(first, reportDate, newScope, responseHash);
        long otherTenantPull = insertPull(second, reportDate, oldScope, responseHash);

        assertEquals(4, jdbc().queryForObject("SELECT COUNT(*) FROM skit_ad_report_pull "
                        + "WHERE id IN (?,?,?,?)", Integer.class,
                firstPull, finalPull, secondScopePull, otherTenantPull));
        assertThrows(DataIntegrityViolationException.class,
                () -> insertPull(first, reportDate, oldScope, responseHash));
        assertImmutable("skit_ad_report_pull", firstPull);

        long bucketId = insertBucket(first, reportDate, hex(hash("tenant-one-bucket")),
                0L, 0L, false, 0L, 0L, "SUSPENSE");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc().update(
                "INSERT INTO skit_ad_reconciliation_revision "
                        + "(tenant_id,ad_account_id,reconciliation_bucket_id,report_pull_id,bucket_key,"
                        + "report_date,revision_hash,revision_no,target_actual_units,unmatched_actual_units,"
                        + "amount_scale,currency,final_revision,source_report_impressions,"
                        + "source_report_impressions_available,matched_event_count,status,reconciled_at) "
                        + "VALUES (?,?,?,?,?,?,?,1,0,0,6,'USD',b'1',0,b'0',0,'APPLIED',CURRENT_TIMESTAMP)",
                second.tenantId, second.accountId, bucketId, otherTenantPull,
                hex(hash("tenant-one-bucket")), reportDate, hash("cross-tenant-revision")));
    }

    @Test
    void lateEventAppendsANewPartialRetryRevisionWhileExactReplayStaysIdempotent() {
        AccountFixture account = installAccount(99103L, null, false);
        LocalDate reportDate = LocalDate.of(2026, 7, 12);
        EventFixture first = installVerifiedEvent(account, 1,
                LocalDateTime.of(2026, 7, 12, 8, 0));
        byte[] requestHash = hash("late-event-scope");
        long pullId = insertPull(account, reportDate, requestHash,
                hash("two-impressions-1000-units"), true);
        String bucketKey = hex(hash("late-event-bucket"));
        long bucketId = insertBucket(account, reportDate, bucketKey,
                100L, 1_000L, true, 500L, 500L, "PARTIAL");
        byte[] firstHash = revisionHash(account, bucketKey, reportDate, false, first);
        long firstRevision = insertRevision(account, bucketId, pullId, bucketKey, reportDate,
                firstHash, 1, 500L, 500L, false, 2L, 1L, "PARTIAL");
        linkEvent(first, bucketId, firstRevision, 500L, "RECONCILED");
        insertAllocation(account, bucketId, firstRevision, 1, first, 500L);
        long firstEventLink = insertEventLink(account, bucketId, firstRevision,
                1, first, "ATTRIBUTED", 500L);

        assertTrue(hasUnsettledScope(account));
        EventFixture late = installVerifiedEvent(account, 2,
                LocalDateTime.of(2026, 7, 12, 9, 0));
        byte[] secondHash = revisionHash(account, bucketKey, reportDate, true, first, late);
        assertFalse(Arrays.equals(firstHash, secondHash));
        assertArrayEquals(secondHash,
                revisionHash(account, bucketKey, reportDate, true, late, first));
        long secondRevision = insertRevision(account, bucketId, pullId, bucketKey, reportDate,
                secondHash, 2, 1_000L, 0L, true, 2L, 2L, "APPLIED");
        linkEvent(first, bucketId, secondRevision, 500L, "RECONCILED");
        linkEvent(late, bucketId, secondRevision, 500L, "RECONCILED");
        insertEventLink(account, bucketId, secondRevision,
                2, first, "ATTRIBUTED", 500L);
        long lateEventLink = insertEventLink(account, bucketId, secondRevision,
                2, late, "ATTRIBUTED", 500L);
        insertAllocation(account, bucketId, secondRevision, 2, first, 500L);
        long immutableAllocation = insertAllocation(
                account, bucketId, secondRevision, 2, late, 500L);

        assertFalse(hasUnsettledScope(account));
        assertThrows(DataIntegrityViolationException.class, () -> insertRevision(account,
                bucketId, pullId, bucketKey, reportDate, secondHash, 3,
                1_000L, 0L, true, 2L, 2L, "APPLIED"));
        assertImmutable("skit_ad_reconciliation_revision", firstRevision);
        assertImmutable("skit_ad_reconciliation_revision", secondRevision);
        assertImmutable("skit_ad_reconciliation_allocation", immutableAllocation);
        assertImmutable("skit_ad_reconciliation_event_link", firstEventLink);
        assertImmutable("skit_ad_reconciliation_event_link", lateEventLink);
        assertEquals(2, jdbc().queryForObject("SELECT COUNT(*) FROM "
                        + "skit_ad_reconciliation_event_link WHERE tenant_id=? AND event_id=?",
                Integer.class, account.tenantId, first.eventId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc().update(
                "INSERT INTO skit_ad_reconciliation_allocation "
                        + "(tenant_id,reconciliation_bucket_id,reconciliation_revision_id,revision_no,"
                        + "event_id,beneficiary_type,beneficiary_member_id,level_no,policy_snapshot_id,"
                        + "currency,amount_scale,cumulative_target_units) "
                        + "VALUES (?,?,?,?,?,2,0,-1,?,'USD',6,500)",
                account.tenantId + 1, bucketId, secondRevision, 2,
                late.eventId, late.snapshotId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc().update(
                "INSERT INTO skit_ad_reconciliation_event_link "
                        + "(tenant_id,reconciliation_bucket_id,reconciliation_revision_id,revision_no,"
                        + "event_id,policy_snapshot_id,association_status,actual_units) "
                        + "VALUES (?,?,?,?,?,?,'SUSPENSE',0)",
                account.tenantId + 1, bucketId, secondRevision, 2,
                late.eventId, late.snapshotId));
    }

    @Test
    void missingImpressionFactsAreExplicitAndCannotCarryFabricatedCounts() {
        AccountFixture account = installAccount(99104L, null, false);
        LocalDate reportDate = LocalDate.of(2026, 7, 12);
        String bucketKey = hex(hash("missing-impression-bucket"));
        long pullId = insertPull(account, reportDate, hash("missing-impression-scope"),
                hash("missing-impression-response"));
        long bucketId = insertBucket(account, reportDate, bucketKey,
                0L, 77L, false, 0L, 77L, "SUSPENSE");
        long revisionId = insertRevision(account, bucketId, pullId, bucketKey, reportDate,
                hash("missing-impression-revision"), 1, 0L, 77L,
                true, 0L, 0L, "SUSPENSE");

        assertEquals(0, jdbc().queryForObject("SELECT report_impressions "
                + "FROM skit_ad_reconciliation_bucket WHERE id=?", Integer.class, bucketId));
        assertEquals(0, jdbc().queryForObject("SELECT source_report_impressions "
                + "FROM skit_ad_reconciliation_revision WHERE id=?", Integer.class, revisionId));
        assertThrows(DataAccessException.class, () -> insertBucket(account, reportDate,
                hex(hash("fabricated-bucket-count")), 1L, 77L, false,
                0L, 77L, "SUSPENSE"));
        assertThrows(DataAccessException.class, () -> insertRevision(account,
                bucketId, pullId, bucketKey, reportDate, hash("fabricated-revision-count"),
                2, 0L, 77L, true, 1L, false, 0L, "SUSPENSE"));
    }

    @Test
    void globalSelectorIncludesOnlyActiveOrFiniteArchivedPendingRoutes() throws Exception {
        AccountFixture active = installAccount(99105L, null, false);
        AccountFixture archivedWithoutPending = installAccount(99106L,
                LocalDateTime.now().minusDays(1), true);
        AccountFixture archivedPending = installAccount(99107L,
                LocalDateTime.now().minusDays(1), true);
        AccountFixture archivedExpired = installAccount(99108L,
                LocalDateTime.now().minusDays(4), true);
        AccountFixture future = installAccount(99109L, null, false);
        AccountFixture disabledPending = installAccount(99110L, null, false);
        AccountFixture disabledWithoutPending = installAccount(99111L, null, false);
        AccountFixture disabledPendingEmptyWindow = installAccount(99112L, null, false);
        AccountFixture disabledFinalEmptyWindow = installAccount(99113L, null, false);
        installVerifiedEvent(archivedPending, 1, LocalDateTime.now().minusDays(2));
        installVerifiedEvent(archivedExpired, 1, LocalDateTime.now().minusDays(5));
        installVerifiedEvent(disabledPending, 1, LocalDateTime.now().minusDays(1));
        LocalDate emptyWindowDate = LocalDate.of(2026, 7, 12);
        byte[] emptyRequestHash = hash("disabled-empty-window-scope");
        byte[] emptyResponseHash = hash("disabled-empty-window-response");
        insertPull(disabledPendingEmptyWindow, emptyWindowDate,
                emptyRequestHash, emptyResponseHash);
        insertPull(disabledFinalEmptyWindow, emptyWindowDate,
                emptyRequestHash, emptyResponseHash);
        insertPull(disabledFinalEmptyWindow, emptyWindowDate,
                emptyRequestHash, emptyResponseHash, true);
        jdbc().update("UPDATE skit_ad_account SET status=1 WHERE id IN (?,?,?,?)",
                disabledPending.accountId, disabledWithoutPending.accountId,
                disabledPendingEmptyWindow.accountId, disabledFinalEmptyWindow.accountId);
        jdbc().update("UPDATE skit_ad_account SET report_next_allowed_at="
                + "DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 DAY) WHERE id=?", future.accountId);

        Set<Long> selected = selectGlobalDueAccountIds();

        assertTrue(selected.contains(active.accountId));
        assertTrue(selected.contains(archivedPending.accountId));
        assertTrue(selected.contains(disabledPending.accountId));
        assertTrue(selected.contains(disabledPendingEmptyWindow.accountId));
        assertFalse(selected.contains(archivedWithoutPending.accountId));
        assertFalse(selected.contains(archivedExpired.accountId));
        assertFalse(selected.contains(future.accountId));
        assertFalse(selected.contains(disabledWithoutPending.accountId));
        assertFalse(selected.contains(disabledFinalEmptyWindow.accountId));
    }

    private AccountFixture installAccount(long tenantId, LocalDateTime archivedAt,
                                          boolean tenantDisabled) {
        long base = tenantId * 100;
        long accountId = base + 2;
        jdbc().update("INSERT INTO system_tenant (id,name,package_id,status,expire_time) "
                        + "VALUES (?,?,0,?,'2099-01-01 00:00:00')",
                tenantId, "Reconciliation tenant " + tenantId, tenantDisabled ? 1 : 0);
        jdbc().update("INSERT INTO skit_agent "
                        + "(id,tenant_id,tenant_code,root_invite_code,status,archived_time) "
                        + "VALUES (?,?,?,?,?,?)",
                base + 1, tenantId, "REC" + tenantId, "RIV" + tenantId,
                tenantDisabled ? 1 : 0, archivedAt);
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,config_data,status,"
                        + "report_timezone,report_currency,report_amount_scale) "
                        + "VALUES (?,?,'TAKU',?,?,?,'{\"placementId\":\"placement\","
                        + "\"adFormat\":\"rewarded_video\"}',0,'UTC+8','USD',6)",
                accountId, tenantId, "TAKU-" + tenantId,
                "account-" + tenantId, "app-" + tenantId);
        jdbc().update("INSERT INTO skit_ad_reporting_credential_version "
                        + "(tenant_id,ad_account_id,credential_version,ciphertext,nonce,"
                        + "encryption_key_id,envelope_version,active) VALUES (?,?,1,?,?,?,1,b'1')",
                tenantId, accountId, hash("publisher-" + tenantId),
                firstTwelve(hash("publisher-nonce-" + tenantId)), "mysql-it-report-key");
        return new AccountFixture(base, tenantId, accountId);
    }

    private EventFixture installVerifiedEvent(AccountFixture account, int sequence,
                                              LocalDateTime occurredAt) {
        long memberId = account.base + 10;
        long planId = account.base + 20;
        long snapshotId = account.base + 30;
        if (sequence == 1) {
            jdbc().update("INSERT INTO skit_member "
                            + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                            + "VALUES (?,?,?,?,?,?,1,0)",
                    memberId, account.tenantId, Long.toString(memberId), "encoded",
                    "member-" + memberId, "REC" + memberId);
            jdbc().update("INSERT INTO skit_commission_plan "
                            + "(id,tenant_id,version,status,published_time) "
                            + "VALUES (?,?,1,0,CURRENT_TIMESTAMP)", planId, account.tenantId);
            jdbc().update("INSERT INTO skit_ad_policy_snapshot "
                            + "(id,tenant_id,plan_id,source_member_id,rule_version,"
                            + "snapshot_schema_version,snapshot_json,snapshot_hash,policy_snapshot_at) "
                            + "VALUES (?,?,?,?,1,1,'{}',?,CURRENT_TIMESTAMP)",
                    snapshotId, account.tenantId, planId, memberId,
                    hash("snapshot-" + account.tenantId));
            jdbc().update("INSERT INTO skit_ad_callback_key "
                            + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) "
                            + "VALUES (?,?,1,?,b'1')",
                    account.tenantId, account.accountId, hash("callback-" + account.tenantId));
            jdbc().update("INSERT INTO skit_ad_reward_secret_version "
                            + "(tenant_id,ad_account_id,secret_version,ciphertext,nonce,"
                            + "encryption_key_id,envelope_version,active) "
                            + "VALUES (?,?,1,?,?,?,1,b'1')",
                    account.tenantId, account.accountId, hash("reward-" + account.tenantId),
                    firstTwelve(hash("reward-nonce-" + account.tenantId)), "mysql-it-reward-key");
        }
        long sessionId = account.base + 40 + sequence;
        long inboxId = account.base + 50 + sequence;
        long eventId = account.base + 60 + sequence;
        jdbc().update("INSERT INTO skit_ad_session "
                        + "(id,tenant_id,session_id,session_token_hash,session_token_key_version,"
                        + "protocol_version,member_id,ad_account_id,policy_snapshot_id,callback_key_version,"
                        + "reward_secret_version,provider,placement_id,scenario_id,business_type,drama_id,"
                        + "episode_from,episode_to,unlock_scope,active_scope_hash,pseudonymous_user_id,"
                        + "access_mode,client_lifecycle_status,reward_verification_status,entitlement_status,"
                        + "revenue_status,load_expires_at,reward_accept_until,provider_transaction_id,"
                        + "last_callback_sequence,version) "
                        + "VALUES (?,?,?,?,1,1,?,?,?,1,1,'TAKU','placement','drama_unlock',"
                        + "'EPISODE_UNLOCK',?,1,1,?,?,?,'MEMBER_OAUTH','CREATED','PENDING','NONE',"
                        + "'FROZEN',DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 HOUR),"
                        + "DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 2 HOUR),?,-1,0)",
                sessionId, account.tenantId, "recon-session-" + account.tenantId + "-" + sequence,
                hash("session-token-" + account.tenantId + "-" + sequence),
                memberId, account.accountId, snapshotId, account.base + 70,
                "scope-" + account.tenantId + "-" + sequence,
                hash("scope-" + account.tenantId + "-" + sequence),
                "pseudo-" + account.tenantId + "-" + sequence,
                "transaction-" + account.tenantId + "-" + sequence);
        jdbc().update("INSERT INTO skit_ad_callback_inbox "
                        + "(id,tenant_id,ad_account_id,ad_session_id,callback_key_version,"
                        + "reward_secret_version,provider,callback_type,idempotency_key,provider_show_id,"
                        + "placement_id,adsource_id,network_firm_id,canonical_payload_hash,"
                        + "authentication_level,signature_status,delivery_integrity_status,processing_status,"
                        + "processing_attempt_count,received_at,processed_at,ingress_response_code) "
                        + "VALUES (?,?,?,?,1,1,'TAKU','IMPRESSION',?,?,'placement','adsource',7,?,"
                        + "'UNSIGNED_OBSERVATION','NOT_APPLICABLE','CANONICAL','SUCCEEDED',1,?,?,200)",
                inboxId, account.tenantId, account.accountId, sessionId,
                "recon-impression-" + account.tenantId + "-" + sequence,
                "show-" + account.tenantId + "-" + sequence,
                hash("inbox-" + account.tenantId + "-" + sequence), occurredAt, occurredAt);
        jdbc().update("INSERT INTO skit_ad_revenue_event "
                        + "(id,tenant_id,ad_account_id,provider,placement_id,external_event_id,"
                        + "source_member_id,gross_amount,occurred_time,completed,mock,status,rule_version,"
                        + "ad_session_id,callback_inbox_id,policy_snapshot_id,source_type,provider_show_id,"
                        + "adsource_id,source_amount_units,estimated_amount_units,reconciled_amount_units,"
                        + "amount_scale,source_currency,match_status,source_verification_status,"
                        + "reward_qualification_status,reconciliation_status,payload_hash,version,"
                        + "legacy_unverified) VALUES (?,?,?,'TAKU','placement',?,?,0.00010000,?,"
                        + "b'0',b'0',1,1,?,?,?,'TAKU_IMPRESSION',?,'adsource',100,100,0,6,'USD',"
                        + "'MATCHED','UNSIGNED_OBSERVATION','REWARDED','FROZEN',?,0,b'0')",
                eventId, account.tenantId, account.accountId,
                "recon-event-" + account.tenantId + "-" + sequence, memberId, occurredAt,
                sessionId, inboxId, snapshotId, "show-" + account.tenantId + "-" + sequence,
                hash("event-" + account.tenantId + "-" + sequence));
        return new EventFixture(eventId, memberId, snapshotId, inboxId);
    }

    private long insertPull(AccountFixture account, LocalDate reportDate,
                            byte[] requestHash, byte[] responseHash) {
        return insertPull(account, reportDate, requestHash, responseHash, false);
    }

    private long insertPull(AccountFixture account, LocalDate reportDate,
                            byte[] requestHash, byte[] responseHash, boolean finalWindow) {
        jdbc().update("INSERT INTO skit_ad_report_pull "
                        + "(tenant_id,ad_account_id,provider,range_start,range_end,report_date,"
                        + "report_timezone,currency,amount_scale,request_hash,credential_version,"
                        + "response_hash,status,final_window,pulled_at) VALUES (?,?, 'TAKU',?,?,?,"
                        + "'UTC+8','USD',6,?,1,?,'SUCCEEDED',?,CURRENT_TIMESTAMP)",
                account.tenantId, account.accountId, reportDate.atStartOfDay(),
                reportDate.plusDays(1).atStartOfDay(), reportDate, requestHash, responseHash,
                finalWindow);
        return jdbc().queryForObject("SELECT id FROM skit_ad_report_pull WHERE tenant_id=? "
                        + "AND ad_account_id=? AND report_date=? AND request_hash=? "
                        + "AND response_hash=? AND credential_version=1 AND final_window=?",
                Long.class, account.tenantId, account.accountId, reportDate,
                requestHash, responseHash, finalWindow);
    }

    private long insertBucket(AccountFixture account, LocalDate reportDate, String bucketKey,
                              long reportImpressions, long actualUnits,
                              boolean impressionsAvailable, long attributableUnits,
                              long suspenseUnits, String status) {
        jdbc().update("INSERT INTO skit_ad_reconciliation_bucket "
                        + "(tenant_id,ad_account_id,bucket_key,report_date,report_timezone,app_id,"
                        + "placement_id,ad_format,network_firm_id,network_account_id,adsource_id,currency,"
                        + "amount_scale,estimate_units,report_actual_units,report_impressions,"
                        + "report_impressions_available,matched_impressions,attributable_actual_units,"
                        + "suspense_units,status) VALUES (?,?,?,?,'UTC+8',?,'placement','rewarded_video',"
                        + "7,'network-account','adsource','USD',6,100,?,?,?,?,?,?,?)",
                account.tenantId, account.accountId, bucketKey, reportDate,
                "app-" + account.tenantId, actualUnits, reportImpressions,
                impressionsAvailable, attributableUnits > 0 ? 1L : 0L,
                attributableUnits, suspenseUnits, status);
        return jdbc().queryForObject("SELECT id FROM skit_ad_reconciliation_bucket "
                        + "WHERE tenant_id=? AND ad_account_id=? AND bucket_key=? AND report_date=?",
                Long.class, account.tenantId, account.accountId, bucketKey, reportDate);
    }

    private long insertRevision(AccountFixture account, long bucketId, long pullId,
                                String bucketKey, LocalDate reportDate, byte[] revisionHash,
                                int revisionNo, long targetUnits, long unmatchedUnits,
                                boolean finalRevision, long reportImpressions,
                                long matchedEvents, String status) {
        return insertRevision(account, bucketId, pullId, bucketKey, reportDate, revisionHash,
                revisionNo, targetUnits, unmatchedUnits, finalRevision, reportImpressions,
                reportImpressions > 0, matchedEvents, status);
    }

    private long insertRevision(AccountFixture account, long bucketId, long pullId,
                                String bucketKey, LocalDate reportDate, byte[] revisionHash,
                                int revisionNo, long targetUnits, long unmatchedUnits,
                                boolean finalRevision, long reportImpressions,
                                boolean impressionsAvailable, long matchedEvents, String status) {
        jdbc().update("INSERT INTO skit_ad_reconciliation_revision "
                        + "(tenant_id,ad_account_id,reconciliation_bucket_id,report_pull_id,bucket_key,"
                        + "report_date,revision_hash,revision_no,target_actual_units,unmatched_actual_units,"
                        + "amount_scale,currency,final_revision,source_report_impressions,"
                        + "source_report_impressions_available,matched_event_count,status,reconciled_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?, ?,6,'USD',?,?,?,?,?,CURRENT_TIMESTAMP)",
                account.tenantId, account.accountId, bucketId, pullId, bucketKey, reportDate,
                revisionHash, revisionNo, targetUnits, unmatchedUnits, finalRevision,
                reportImpressions, impressionsAvailable, matchedEvents, status);
        return jdbc().queryForObject("SELECT id FROM skit_ad_reconciliation_revision "
                        + "WHERE tenant_id=? AND reconciliation_bucket_id=? AND revision_no=?",
                Long.class, account.tenantId, bucketId, revisionNo);
    }

    private long insertAllocation(AccountFixture account, long bucketId, long revisionId,
                                  int revisionNo, EventFixture event, long units) {
        jdbc().update("INSERT INTO skit_ad_reconciliation_allocation "
                        + "(tenant_id,reconciliation_bucket_id,reconciliation_revision_id,revision_no,"
                        + "event_id,beneficiary_type,beneficiary_member_id,level_no,policy_snapshot_id,"
                        + "currency,amount_scale,cumulative_target_units) "
                        + "VALUES (?,?,?,?,?,2,0,-1,?,'USD',6,?)",
                account.tenantId, bucketId, revisionId, revisionNo,
                event.eventId, event.snapshotId, units);
        return jdbc().queryForObject("SELECT id FROM skit_ad_reconciliation_allocation "
                        + "WHERE tenant_id=? AND event_id=? AND reconciliation_revision_id=? "
                        + "AND beneficiary_type=2 AND beneficiary_member_id=0 AND level_no=-1",
                Long.class, account.tenantId, event.eventId, revisionId);
    }

    private long insertEventLink(AccountFixture account, long bucketId, long revisionId,
                                 int revisionNo, EventFixture event,
                                 String associationStatus, long actualUnits) {
        jdbc().update("INSERT INTO skit_ad_reconciliation_event_link "
                        + "(tenant_id,reconciliation_bucket_id,reconciliation_revision_id,revision_no,"
                        + "event_id,policy_snapshot_id,association_status,actual_units) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                account.tenantId, bucketId, revisionId, revisionNo,
                event.eventId, event.snapshotId, associationStatus, actualUnits);
        return jdbc().queryForObject("SELECT id FROM skit_ad_reconciliation_event_link "
                        + "WHERE tenant_id=? AND reconciliation_revision_id=? AND event_id=?",
                Long.class, account.tenantId, revisionId, event.eventId);
    }

    private void linkEvent(EventFixture event, long bucketId, long revisionId,
                           long units, String status) {
        jdbc().update("UPDATE skit_ad_revenue_event SET reconciliation_bucket_id=?,"
                        + "reconciliation_revision_id=?,reconciled_amount_units=?,"
                        + "source_verification_status='REPORT_CONFIRMED',reconciliation_status=?,"
                        + "reconciled_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=?",
                bucketId, revisionId, units, status, event.eventId);
    }

    private byte[] revisionHash(AccountFixture account, String bucketKey, LocalDate reportDate,
                                boolean finalRevision, EventFixture... events) {
        SkitAdRevenueEventDO[] rows = new SkitAdRevenueEventDO[events.length];
        for (int index = 0; index < events.length; index++) {
            EventFixture event = events[index];
            rows[index] = new SkitAdRevenueEventDO().setId(event.eventId)
                    .setEstimatedAmountUnits(100L).setRewardQualificationStatus("REWARDED")
                    .setSourceMemberId(event.memberId).setPolicySnapshotId(event.snapshotId)
                    .setCallbackInboxId(event.inboxId);
        }
        return SkitReconciliationRevisionHasher.hash(account.tenantId, account.accountId,
                bucketKey, reportDate, 1_000L, 2L, "USD", 6,
                finalRevision, true, Arrays.asList(rows));
    }

    private boolean hasUnsettledScope(AccountFixture account) {
        try {
            Method method = SkitAdAccountMapper.class.getMethod(
                    "hasUnsettledTakuReportScope", long.class, long.class);
            String sql = selectSql(method).replace("#{tenantId}", ":tenantId")
                    .replace("#{adAccountId}", ":adAccountId");
            Boolean result = new NamedParameterJdbcTemplate(dataSource()).queryForObject(sql,
                    new MapSqlParameterSource().addValue("tenantId", account.tenantId)
                            .addValue("adAccountId", account.accountId), Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Set<Long> selectGlobalDueAccountIds() throws Exception {
        Method method = SkitAdAccountMapper.class.getMethod("selectDueReportRoutes", int.class);
        String sql = selectSql(method).replace("#{limit}", "100");
        List<Map<String, Object>> rows = jdbc().queryForList(sql);
        Set<Long> result = new HashSet<>();
        for (Map<String, Object> row : rows) {
            result.add(((Number) row.get("id")).longValue());
        }
        return result;
    }

    private String selectSql(Method method) {
        Select select = method.getAnnotation(Select.class);
        if (select == null) {
            throw new IllegalStateException(method.getName() + " is not explicit SQL");
        }
        return String.join(" ", select.value());
    }

    private void assertImmutable(String table, long id) {
        assertThrows(DataAccessException.class,
                () -> jdbc().update("UPDATE `" + table + "` SET updater='mutated' WHERE id=?", id));
        assertThrows(DataAccessException.class,
                () -> jdbc().update("DELETE FROM `" + table + "` WHERE id=?", id));
    }

    private static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] firstTwelve(byte[] source) {
        return Arrays.copyOf(source, 12);
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }

    private static final class AccountFixture {
        private final long base;
        private final long tenantId;
        private final long accountId;

        private AccountFixture(long base, long tenantId, long accountId) {
            this.base = base;
            this.tenantId = tenantId;
            this.accountId = accountId;
        }
    }

    private static final class EventFixture {
        private final long eventId;
        private final long memberId;
        private final long snapshotId;
        private final long inboxId;

        private EventFixture(long eventId, long memberId, long snapshotId, long inboxId) {
            this.eventId = eventId;
            this.memberId = memberId;
            this.snapshotId = snapshotId;
            this.inboxId = inboxId;
        }
    }
}
