package cn.iocoder.yudao.module.skit.integration;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdCallbackSchemaHardeningMySqlIT extends SkitMySqlIntegrationTestBase {

    private static final String TASK_2_CHECKSUM =
            "64e450e4b8048a00b0ce7fbbe9f4b162ec519b5cd3f2c83d12470d92fe72fdbf";
    private static final String POLICY_IMMUTABILITY_CHECKSUM =
            "11f815a76c7f15bacfc9d9a29a60121f6736ec18bbd712a02a0190ff69c8c18d";
    private static final String TASK_5_CHECKSUM =
            "88b57c75266fc10a56dfef4ce17fedfd0683bb63e4e80c1f6cd9353698355892";
    private static final String TASK_7_CHECKSUM =
            "8940c5da3ef12d1ec8bbeefe54ec4e08c87743b97bebc52825c76f2eadf29ea3";

    @Test
    void migrationIsAdditiveAndInstallsReceiptAndCompoundBindingShape() {
        assertEquals(TASK_2_CHECKSUM, migrationChecksum(2026071401));
        assertEquals(POLICY_IMMUTABILITY_CHECKSUM, migrationChecksum(2026071402));
        assertEquals(TASK_5_CHECKSUM, migrationChecksum(2026071403));
        Map<String, Object> task7 = jdbc().queryForMap(
                "SELECT description,checksum FROM skit_schema_migration WHERE version=2026071404");
        assertEquals("bind callback receipts and verified advertising facts", task7.get("description"));
        assertEquals(TASK_7_CHECKSUM, task7.get("checksum"));

        assertColumn("skit_ad_session", "reward_callback_inbox_id", "bigint", "YES", null);
        assertColumn("skit_ad_session", "reward_callback_received_at", "datetime", "YES", null);
        assertColumn("skit_ad_callback_inbox", "ingress_response_code", "smallint", "YES", null);
        assertGeneratedColumn("skit_ad_callback_inbox", "ad_session_ref_id", "bigint");
        assertGeneratedColumn("skit_ad_callback_attempt", "ad_session_ref_id", "bigint");
        assertGeneratedColumn("skit_ad_revenue_event", "ad_session_ref_id", "bigint");

        assertExactIndex("skit_ad_session", "uk_skit_ad_session_account_binding", true,
                "tenant_id,id,ad_account_id");
        assertExactIndex("skit_ad_session", "uk_skit_ad_session_revenue_binding", true,
                "tenant_id,id,ad_account_id,member_id,policy_snapshot_id");
        assertExactIndex("skit_ad_session", "uk_skit_ad_session_grant_envelope", true,
                "tenant_id,id,member_id,drama_id,provider_transaction_id");
        assertExactIndex("skit_ad_callback_inbox", "uk_skit_callback_inbox_attempt_binding", true,
                "tenant_id,id,ad_account_id,ad_session_ref_id");
        assertExactIndex("skit_ad_revenue_event", "uk_skit_revenue_event_snapshot_binding", true,
                "tenant_id,id,policy_snapshot_id");
        assertExactIndex("skit_ad_callback_inbox", "idx_skit_callback_inbox_payload_expiry", false,
                "payload_expires_at,id");
        assertExactIndex("skit_ad_callback_attempt", "idx_skit_callback_attempt_retention", false,
                "received_at,id");
        assertExactIndex("skit_ad_callback_edge_attempt", "idx_skit_callback_edge_retention", false,
                "received_at,id");
        assertExactIndex("skit_ad_network_capability", "idx_skit_network_cap_readiness", false,
                "tenant_id,ad_account_id,network_firm_id,enabled,reward_authority,verified_at");

        assertExactForeignKey("skit_ad_callback_inbox", "fk_skit_callback_inbox_session_account",
                "tenant_id,ad_session_id,ad_account_id", "skit_ad_session",
                "tenant_id,id,ad_account_id");
        assertExactForeignKey("skit_ad_callback_attempt", "fk_skit_callback_attempt_inbox_binding",
                "tenant_id,callback_inbox_id,ad_account_id,ad_session_ref_id", "skit_ad_callback_inbox",
                "tenant_id,id,ad_account_id,ad_session_ref_id");
        assertExactForeignKey("skit_ad_session", "fk_skit_ad_session_reward_callback_receipt",
                "tenant_id,reward_callback_inbox_id,ad_account_id,id", "skit_ad_callback_inbox",
                "tenant_id,id,ad_account_id,ad_session_ref_id");
        assertExactForeignKey("skit_ad_revenue_event", "fk_skit_revenue_session_binding",
                "tenant_id,ad_session_id,ad_account_id,source_member_id,policy_snapshot_id", "skit_ad_session",
                "tenant_id,id,ad_account_id,member_id,policy_snapshot_id");
        assertExactForeignKey("skit_ad_revenue_event", "fk_skit_revenue_inbox_binding",
                "tenant_id,callback_inbox_id,ad_account_id,ad_session_ref_id", "skit_ad_callback_inbox",
                "tenant_id,id,ad_account_id,ad_session_ref_id");
        assertExactForeignKey("skit_commission_ledger", "fk_skit_ledger_event_snapshot",
                "tenant_id,event_id,policy_snapshot_id", "skit_ad_revenue_event",
                "tenant_id,id,policy_snapshot_id");
        assertExactForeignKey("skit_entitlement_grant", "fk_skit_grant_session_binding",
                "tenant_id,ad_session_id,member_id,drama_id,provider_transaction_id", "skit_ad_session",
                "tenant_id,id,member_id,drama_id,provider_transaction_id");

        assertCheckExists("skit_ad_session", "ck_skit_ad_session_reward_callback_receipt");
        assertCheckExists("skit_ad_callback_inbox", "ck_skit_callback_inbox_response_code");
        assertCheckExists("skit_ad_revenue_event", "ck_skit_revenue_verified_binding");
        assertCheckExists("skit_commission_ledger", "ck_skit_ledger_verified_snapshot");
        assertCheckExists("skit_ad_network_capability", "ck_skit_network_cap_reward_authority");
        assertCheckExists("skit_ad_network_capability", "ck_skit_network_cap_signed_readiness");
        assertCheckExists("skit_ad_callback_inbox", "ck_skit_callback_inbox_processing_error");
        assertCheckExists("skit_ad_callback_inbox", "ck_skit_callback_inbox_dead_letter_alert");

        assertTrigger("trg_skit_ledger_immutable", "skit_commission_ledger", "UPDATE");
        assertTrigger("trg_skit_ledger_no_delete", "skit_commission_ledger", "DELETE");
        assertTrigger("trg_skit_entitlement_grant_immutable", "skit_entitlement_grant", "UPDATE");
        assertTrigger("trg_skit_entitlement_grant_no_delete", "skit_entitlement_grant", "DELETE");
        assertTrigger("trg_skit_callback_inbox_monotonic", "skit_ad_callback_inbox", "UPDATE");
        assertTrigger("trg_skit_callback_inbox_no_delete", "skit_ad_callback_inbox", "DELETE");
        assertTrigger("trg_skit_callback_attempt_immutable", "skit_ad_callback_attempt", "UPDATE");
        assertTrigger("trg_skit_callback_edge_attempt_immutable", "skit_ad_callback_edge_attempt", "UPDATE");
        assertTrigger("trg_skit_entitlement_grant_session_range", "skit_entitlement_grant", "INSERT");
    }

    @Test
    void receiptMarkerIsPairedWindowBoundAndReferencesTheExactSessionInbox() {
        Fixture fixture = installFixture(98101L);
        long inboxOne = fixture.base + 50;
        long inboxTwo = fixture.base + 51;
        insertInbox(inboxOne, fixture.tenantId, fixture.accountOne, fixture.sessionOne,
                "receipt-one", false);
        insertInbox(inboxTwo, fixture.tenantId, fixture.accountTwo, fixture.sessionTwo,
                "receipt-two", false);

        assertDoesNotThrow(() -> jdbc().update("UPDATE skit_ad_session SET "
                        + "reward_callback_inbox_id=?,reward_callback_received_at="
                        + "DATE_SUB(reward_accept_until,INTERVAL 1 SECOND) WHERE tenant_id=? AND id=?",
                inboxOne, fixture.tenantId, fixture.sessionOne));

        assertThrows(DataAccessException.class, () -> jdbc().update("UPDATE skit_ad_session SET "
                        + "reward_callback_inbox_id=?,reward_callback_received_at="
                        + "DATE_SUB(reward_accept_until,INTERVAL 1 SECOND) WHERE tenant_id=? AND id=?",
                inboxTwo, fixture.tenantId, fixture.sessionOne));
        assertThrows(DataAccessException.class, () -> jdbc().update("UPDATE skit_ad_session SET "
                        + "reward_callback_inbox_id=NULL WHERE tenant_id=? AND id=?",
                fixture.tenantId, fixture.sessionOne));
        assertThrows(DataAccessException.class, () -> jdbc().update("UPDATE skit_ad_session SET "
                        + "reward_callback_received_at=DATE_ADD(reward_accept_until,INTERVAL 1 SECOND) "
                        + "WHERE tenant_id=? AND id=?",
                fixture.tenantId, fixture.sessionOne));
    }

    @Test
    void callbackRevenueAndLedgerRejectSameTenantCrossAccountForgery() {
        Fixture fixture = installFixture(98201L);
        long inboxOne = fixture.base + 50;
        long inboxTwo = fixture.base + 51;
        insertInbox(inboxOne, fixture.tenantId, fixture.accountOne, fixture.sessionOne,
                "binding-one", false);
        insertInbox(inboxTwo, fixture.tenantId, fixture.accountTwo, fixture.sessionTwo,
                "binding-two", false);

        assertThrows(DataAccessException.class, () -> insertInbox(fixture.base + 52, fixture.tenantId,
                fixture.accountTwo, fixture.sessionOne, "wrong-inbox-account", false));

        assertDoesNotThrow(() -> insertAttempt(fixture.base + 60, fixture.tenantId, inboxOne,
                fixture.accountOne, fixture.sessionOne, 1));
        assertThrows(DataAccessException.class, () -> insertAttempt(fixture.base + 61, fixture.tenantId,
                inboxOne, fixture.accountTwo, fixture.sessionTwo, 2));
        assertThrows(DataAccessException.class, () -> insertAttempt(fixture.base + 62, fixture.tenantId,
                inboxOne, fixture.accountOne, null, 3));

        long eventId = fixture.base + 70;
        assertDoesNotThrow(() -> insertVerifiedRevenue(eventId, fixture, fixture.accountOne,
                fixture.sessionOne, inboxOne, fixture.memberOne, fixture.snapshotOne, "valid-event"));
        assertThrows(DataAccessException.class, () -> insertVerifiedRevenue(fixture.base + 71, fixture,
                fixture.accountTwo, fixture.sessionOne, inboxOne, fixture.memberOne,
                fixture.snapshotOne, "wrong-event-account"));
        assertThrows(DataAccessException.class, () -> insertVerifiedRevenue(fixture.base + 72, fixture,
                fixture.accountOne, fixture.sessionOne, inboxTwo, fixture.memberOne,
                fixture.snapshotOne, "wrong-event-inbox"));
        assertThrows(DataAccessException.class, () -> insertVerifiedRevenue(fixture.base + 73, fixture,
                fixture.accountOne, fixture.sessionOne, inboxOne, fixture.memberTwo,
                fixture.snapshotOne, "wrong-event-member"));
        assertThrows(DataAccessException.class, () -> insertVerifiedRevenue(fixture.base + 74, fixture,
                fixture.accountOne, fixture.sessionOne, inboxOne, fixture.memberOne,
                fixture.snapshotTwo, "wrong-event-snapshot"));

        assertDoesNotThrow(() -> insertVerifiedLedger(fixture.base + 80, fixture.tenantId,
                eventId, fixture.snapshotOne));
        assertThrows(DataAccessException.class, () -> insertVerifiedLedger(fixture.base + 81,
                fixture.tenantId, eventId, fixture.snapshotTwo));
    }

    @Test
    void appendOnlyFactsRejectMutationWhileRetentionCanEraseOrDeleteOnlyItsAllowedRows() {
        Fixture fixture = installFixture(98301L);
        long inboxId = fixture.base + 50;
        insertInbox(inboxId, fixture.tenantId, fixture.accountOne, fixture.sessionOne,
                "immutable-inbox", true);
        long attemptId = fixture.base + 60;
        insertAttempt(attemptId, fixture.tenantId, inboxId, fixture.accountOne, fixture.sessionOne, 1);
        long edgeId = fixture.base + 61;
        jdbc().update("INSERT INTO skit_ad_callback_edge_attempt "
                        + "(id,tenant_id,ad_account_id,callback_key_hash,provider,callback_type,request_method,"
                        + "result_code,received_at) VALUES (?,?,?,?, 'TAKU','REWARD','GET','ROUTED',NOW())",
                edgeId, fixture.tenantId, fixture.accountOne, hash("edge-" + edgeId));

        long eventId = fixture.base + 70;
        insertVerifiedRevenue(eventId, fixture, fixture.accountOne, fixture.sessionOne, inboxId,
                fixture.memberOne, fixture.snapshotOne, "immutable-event");
        long ledgerId = fixture.base + 80;
        insertVerifiedLedger(ledgerId, fixture.tenantId, eventId, fixture.snapshotOne);

        long entitlementId = fixture.base + 90;
        jdbc().update("INSERT INTO skit_content_entitlement "
                        + "(id,tenant_id,member_id,drama_id,episode_no,status,granted_at,version) "
                        + "VALUES (?,?,?,?,1,'GRANTED',NOW(),0)",
                entitlementId, fixture.tenantId, fixture.memberOne, fixture.dramaOne);
        long grantId = fixture.base + 91;
        jdbc().update("INSERT INTO skit_entitlement_grant "
                        + "(id,tenant_id,ad_session_id,entitlement_id,member_id,drama_id,episode_no,"
                        + "provider_transaction_id,grant_result,granted_at) "
                        + "VALUES (?,?,?,?,?,?,1,?,'CREATED',NOW())",
                grantId, fixture.tenantId, fixture.sessionOne, entitlementId,
                fixture.memberOne, fixture.dramaOne, fixture.transactionOne);

        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE skit_commission_ledger SET amount_units=amount_units+1 WHERE tenant_id=? AND id=?",
                fixture.tenantId, ledgerId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "DELETE FROM skit_commission_ledger WHERE tenant_id=? AND id=?", fixture.tenantId, ledgerId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE skit_entitlement_grant SET grant_result='ALREADY_OWNED' WHERE tenant_id=? AND id=?",
                fixture.tenantId, grantId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "DELETE FROM skit_entitlement_grant WHERE tenant_id=? AND id=?", fixture.tenantId, grantId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE skit_ad_callback_inbox SET placement_id='forged' WHERE tenant_id=? AND id=?",
                fixture.tenantId, inboxId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "DELETE FROM skit_ad_callback_inbox WHERE tenant_id=? AND id=?", fixture.tenantId, inboxId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE skit_ad_callback_attempt SET result_code='FORGED' WHERE tenant_id=? AND id=?",
                fixture.tenantId, attemptId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE skit_ad_callback_edge_attempt SET result_code='FORGED' WHERE id=?", edgeId));

        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE skit_ad_callback_inbox SET payload_ciphertext=NULL,payload_nonce=NULL,"
                        + "payload_key_id=NULL,payload_envelope_version=NULL,payload_expires_at=NULL "
                        + "WHERE tenant_id=? AND id=?", fixture.tenantId, inboxId));
        assertEquals(1, jdbc().update("UPDATE skit_ad_callback_inbox SET processing_status='PROCESSING',"
                        + "lease_owner='retention-worker',lease_until=DATE_ADD(NOW(),INTERVAL 1 MINUTE),"
                        + "processing_attempt_count=1 WHERE tenant_id=? AND id=?",
                fixture.tenantId, inboxId));
        assertEquals(1, jdbc().update("UPDATE skit_ad_callback_inbox SET processing_status='SUCCEEDED',"
                        + "lease_owner=NULL,lease_until=NULL,processed_at=NOW() WHERE tenant_id=? AND id=?",
                fixture.tenantId, inboxId));
        assertEquals(1, jdbc().update("UPDATE skit_ad_callback_inbox SET payload_ciphertext=NULL,"
                        + "payload_nonce=NULL,payload_key_id=NULL,payload_envelope_version=NULL,"
                        + "payload_expires_at=NULL WHERE tenant_id=? AND id=?",
                fixture.tenantId, inboxId));
        assertEquals(1, jdbc().update("DELETE FROM skit_ad_callback_attempt WHERE tenant_id=? AND id=?",
                fixture.tenantId, attemptId));
        assertEquals(1, jdbc().update("DELETE FROM skit_ad_callback_edge_attempt WHERE id=?", edgeId));
    }

    @Test
    void callbackInboxProcessingStateIsMonotonicAndStillSupportsLeaseRetryAndDeadLetter() {
        Fixture fixture = installFixture(98351L);
        long inboxId = fixture.base + 50;
        insertInbox(inboxId, fixture.tenantId, fixture.accountOne, fixture.sessionOne,
                "state-machine", false);

        assertEquals(1, jdbc().update("UPDATE skit_ad_callback_inbox SET processing_status='PROCESSING',"
                        + "lease_owner='worker-a',lease_until=DATE_ADD(NOW(),INTERVAL 10 MINUTE),"
                        + "processing_attempt_count=1,error_code=NULL WHERE tenant_id=? AND id=?",
                fixture.tenantId, inboxId));
        assertThrows(DataAccessException.class, () -> jdbc().update("UPDATE skit_ad_callback_inbox SET "
                        + "processing_status='PENDING',lease_owner=NULL,lease_until=NULL,"
                        + "processing_attempt_count=0 WHERE tenant_id=? AND id=?",
                fixture.tenantId, inboxId));
        assertThrows(DataAccessException.class, () -> jdbc().update("UPDATE skit_ad_callback_inbox SET "
                        + "lease_owner='worker-b',lease_until=DATE_ADD(NOW(),INTERVAL 20 MINUTE),"
                        + "processing_attempt_count=2 WHERE tenant_id=? AND id=?",
                fixture.tenantId, inboxId));

        long recoveredInboxId = fixture.base + 51;
        insertInbox(recoveredInboxId, fixture.tenantId, fixture.accountOne, fixture.sessionOne,
                "expired-lease", false);
        assertEquals(1, jdbc().update("UPDATE skit_ad_callback_inbox SET processing_status='PROCESSING',"
                        + "lease_owner='crashed-worker',lease_until=DATE_SUB(NOW(),INTERVAL 1 SECOND),"
                        + "processing_attempt_count=1,error_code=NULL WHERE tenant_id=? AND id=?",
                fixture.tenantId, recoveredInboxId));
        assertThrows(DataAccessException.class, () -> jdbc().update("UPDATE skit_ad_callback_inbox SET "
                        + "lease_owner='skip-worker',lease_until=DATE_ADD(NOW(),INTERVAL 10 MINUTE),"
                        + "processing_attempt_count=3 WHERE tenant_id=? AND id=?",
                fixture.tenantId, recoveredInboxId));
        assertEquals(1, jdbc().update("UPDATE skit_ad_callback_inbox SET lease_owner='recovery-worker',"
                        + "lease_until=DATE_ADD(NOW(),INTERVAL 10 MINUTE),processing_attempt_count=2 "
                        + "WHERE tenant_id=? AND id=?",
                fixture.tenantId, recoveredInboxId));
        assertThrows(DataAccessException.class, () -> jdbc().update("UPDATE skit_ad_callback_inbox SET "
                        + "processing_status='RETRY_WAIT',error_code='TRANSIENT',lease_owner=NULL,"
                        + "lease_until=NULL,next_attempt_at=DATE_ADD(NOW(),INTERVAL 1 MINUTE),"
                        + "processing_attempt_count=1 WHERE tenant_id=? AND id=?",
                fixture.tenantId, recoveredInboxId));
        assertEquals(1, jdbc().update("UPDATE skit_ad_callback_inbox SET processing_status='RETRY_WAIT',"
                        + "error_code='TRANSIENT',lease_owner=NULL,lease_until=NULL,"
                        + "next_attempt_at=DATE_ADD(NOW(),INTERVAL 1 MINUTE) WHERE tenant_id=? AND id=?",
                fixture.tenantId, recoveredInboxId));
        assertEquals(1, jdbc().update("UPDATE skit_ad_callback_inbox SET processing_status='PROCESSING',"
                        + "error_code=NULL,lease_owner='retry-worker',"
                        + "lease_until=DATE_ADD(NOW(),INTERVAL 10 MINUTE),next_attempt_at=NULL,"
                        + "processing_attempt_count=3 WHERE tenant_id=? AND id=?",
                fixture.tenantId, recoveredInboxId));
        assertEquals(1, jdbc().update("UPDATE skit_ad_callback_inbox SET processing_status='SUCCEEDED',"
                        + "lease_owner=NULL,lease_until=NULL,processed_at=NOW() WHERE tenant_id=? AND id=?",
                fixture.tenantId, recoveredInboxId));
        assertThrows(DataAccessException.class, () -> jdbc().update("UPDATE skit_ad_callback_inbox SET "
                        + "processing_status='PENDING',processed_at=NULL WHERE tenant_id=? AND id=?",
                fixture.tenantId, recoveredInboxId));

        long deadLetterInboxId = fixture.base + 52;
        insertInbox(deadLetterInboxId, fixture.tenantId, fixture.accountOne, fixture.sessionOne,
                "dead-letter", false);
        assertEquals(1, jdbc().update("UPDATE skit_ad_callback_inbox SET processing_status='PROCESSING',"
                        + "lease_owner='dead-worker',lease_until=DATE_ADD(NOW(),INTERVAL 1 MINUTE),"
                        + "processing_attempt_count=1 WHERE tenant_id=? AND id=?",
                fixture.tenantId, deadLetterInboxId));
        assertEquals(1, jdbc().update("UPDATE skit_ad_callback_inbox SET processing_status='DEAD_LETTER',"
                        + "error_code='MAX_ATTEMPTS',lease_owner=NULL,lease_until=NULL,processed_at=NOW() "
                        + "WHERE tenant_id=? AND id=?",
                fixture.tenantId, deadLetterInboxId));
        assertEquals(1, jdbc().update("UPDATE skit_ad_callback_inbox SET dead_letter_alerted_at=NOW() "
                        + "WHERE tenant_id=? AND id=?",
                fixture.tenantId, deadLetterInboxId));
        assertThrows(DataAccessException.class, () -> jdbc().update("UPDATE skit_ad_callback_inbox SET "
                        + "dead_letter_alerted_at=DATE_ADD(dead_letter_alerted_at,INTERVAL 1 SECOND) "
                        + "WHERE tenant_id=? AND id=?",
                fixture.tenantId, deadLetterInboxId));
        assertThrows(DataAccessException.class, () -> jdbc().update("UPDATE skit_ad_callback_inbox SET "
                        + "processing_status='RETRY_WAIT',processed_at=NULL,next_attempt_at=NOW(),"
                        + "dead_letter_alerted_at=NULL WHERE tenant_id=? AND id=?",
                fixture.tenantId, deadLetterInboxId));
    }

    @Test
    void entitlementGrantAcceptsEveryEpisodeInsideSessionRangeAndRejectsOutsideRange() {
        Fixture fixture = installFixture(98361L);
        assertEquals(1, jdbc().update("UPDATE skit_ad_session SET episode_to=3 WHERE tenant_id=? AND id=?",
                fixture.tenantId, fixture.sessionOne));

        for (int episode = 1; episode <= 3; episode++) {
            int grantedEpisode = episode;
            long entitlementId = fixture.base + 90 + episode;
            long grantId = fixture.base + 100 + episode;
            jdbc().update("INSERT INTO skit_content_entitlement "
                            + "(id,tenant_id,member_id,drama_id,episode_no,status,granted_at,version) "
                            + "VALUES (?,?,?,?,?,'GRANTED',NOW(),0)",
                    entitlementId, fixture.tenantId, fixture.memberOne, fixture.dramaOne, episode);
            assertDoesNotThrow(() -> jdbc().update("INSERT INTO skit_entitlement_grant "
                            + "(id,tenant_id,ad_session_id,entitlement_id,member_id,drama_id,episode_no,"
                            + "provider_transaction_id,grant_result,granted_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,'CREATED',NOW())",
                    grantId, fixture.tenantId, fixture.sessionOne, entitlementId, fixture.memberOne,
                    fixture.dramaOne, grantedEpisode, fixture.transactionOne));
        }
        assertEquals(3, jdbc().queryForObject("SELECT COUNT(*) FROM skit_entitlement_grant "
                        + "WHERE tenant_id=? AND ad_session_id=?",
                Integer.class, fixture.tenantId, fixture.sessionOne));

        long outsideEntitlementId = fixture.base + 99;
        jdbc().update("INSERT INTO skit_content_entitlement "
                        + "(id,tenant_id,member_id,drama_id,episode_no,status,granted_at,version) "
                        + "VALUES (?,?,?,?,4,'GRANTED',NOW(),0)",
                outsideEntitlementId, fixture.tenantId, fixture.memberOne, fixture.dramaOne);
        assertThrows(DataAccessException.class, () -> jdbc().update("INSERT INTO skit_entitlement_grant "
                        + "(id,tenant_id,ad_session_id,entitlement_id,member_id,drama_id,episode_no,"
                        + "provider_transaction_id,grant_result,granted_at) "
                        + "VALUES (?,?,?,?,?,?,4,?,'CREATED',NOW())",
                fixture.base + 109, fixture.tenantId, fixture.sessionOne, outsideEntitlementId,
                fixture.memberOne, fixture.dramaOne, fixture.transactionOne));
    }

    @Test
    void networkCapabilityAllowsOnlyDeclaredAuthoritiesAndRequiresEvidenceForSignedReward() {
        Fixture fixture = installFixture(98401L);

        assertThrows(DataAccessException.class, () -> insertNetworkCapability(fixture.base + 60,
                fixture, "FORGED_AUTHORITY", false, false, false, false));
        assertThrows(DataAccessException.class, () -> insertNetworkCapability(fixture.base + 61,
                fixture, "SIGNED_REWARD", true, true, false, true));
        assertDoesNotThrow(() -> insertNetworkCapability(fixture.base + 62,
                fixture, "UNSIGNED_PROVIDER_OBSERVATION", false, false, false, false));
        assertDoesNotThrow(() -> insertNetworkCapability(fixture.base + 63,
                fixture, "SIGNED_REWARD", true, true, true, true));
    }

    private String migrationChecksum(int version) {
        return jdbc().queryForObject("SELECT checksum FROM skit_schema_migration WHERE version=?",
                String.class, version);
    }

    private Fixture installFixture(long tenantId) {
        long base = tenantId * 100;
        long memberOne = base + 1;
        long memberTwo = base + 2;
        long accountOne = base + 10;
        long accountTwo = base + 11;
        long planOne = base + 20;
        long planTwo = base + 21;
        long snapshotOne = base + 30;
        long snapshotTwo = base + 31;
        long sessionOne = base + 40;
        long sessionTwo = base + 41;
        long dramaOne = base + 1000;
        long dramaTwo = base + 1001;
        String transactionOne = "tx-" + sessionOne;
        String transactionTwo = "tx-" + sessionTwo;

        jdbc().update("INSERT INTO system_tenant (id,name,package_id,status,expire_time) "
                        + "VALUES (?, ?, 0, 0, '2099-01-01 00:00:00')",
                tenantId, "Callback schema tenant " + tenantId);
        insertMember(tenantId, memberOne, "A");
        insertMember(tenantId, memberTwo, "B");
        insertAccount(tenantId, accountOne, "A");
        insertAccount(tenantId, accountTwo, "B");
        insertPlanAndSnapshot(tenantId, planOne, snapshotOne, memberOne, "A");
        insertPlanAndSnapshot(tenantId, planTwo, snapshotTwo, memberTwo, "B");
        insertCredentialVersions(tenantId, accountOne, "A");
        insertCredentialVersions(tenantId, accountTwo, "B");
        insertSession(sessionOne, tenantId, memberOne, accountOne, snapshotOne, dramaOne, transactionOne, "A");
        insertSession(sessionTwo, tenantId, memberTwo, accountTwo, snapshotTwo, dramaTwo, transactionTwo, "B");
        return new Fixture(base, tenantId, memberOne, memberTwo, accountOne, accountTwo,
                snapshotOne, snapshotTwo, sessionOne, sessionTwo, dramaOne, transactionOne);
    }

    private void insertMember(long tenantId, long memberId, String suffix) {
        jdbc().update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                        + "VALUES (?,?,?,?,?,?,1,0)",
                memberId, tenantId, Long.toString(memberId), "encoded", "member-" + suffix,
                "CB" + memberId);
    }

    private void insertAccount(long tenantId, long accountId, String suffix) {
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,status) "
                        + "VALUES (?,?,?,?,?,?,0)",
                accountId, tenantId, "A".equals(suffix) ? "TAKU" : "PANGLE",
                "callback-" + suffix, "account-" + accountId, "app-" + accountId);
    }

    private void insertPlanAndSnapshot(long tenantId, long planId, long snapshotId,
                                       long memberId, String suffix) {
        jdbc().update("INSERT INTO skit_commission_plan "
                        + "(id,tenant_id,version,status,published_time) VALUES (?,?,?,?,NOW())",
                planId, tenantId, "A".equals(suffix) ? 1 : 2, "A".equals(suffix) ? 0 : 1);
        jdbc().update("INSERT INTO skit_ad_policy_snapshot "
                        + "(id,tenant_id,plan_id,source_member_id,rule_version,snapshot_schema_version,"
                        + "snapshot_json,snapshot_hash,policy_snapshot_at) "
                        + "VALUES (?,?,?,?,1,1,'{}',?,NOW())",
                snapshotId, tenantId, planId, memberId, hash("snapshot-" + suffix + tenantId));
    }

    private void insertCredentialVersions(long tenantId, long accountId, String suffix) {
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) "
                        + "VALUES (?,?,1,?,b'1')",
                tenantId, accountId, hash("callback-key-" + suffix + tenantId));
        jdbc().update("INSERT INTO skit_ad_reward_secret_version "
                        + "(tenant_id,ad_account_id,secret_version,ciphertext,nonce,encryption_key_id,"
                        + "envelope_version,active) VALUES (?,?,1,?,?,?,1,b'1')",
                tenantId, accountId, hash("secret-" + suffix + tenantId),
                java.util.Arrays.copyOf(hash("nonce-" + suffix + tenantId), 12), "mysql-it-key");
    }

    private void insertSession(long id, long tenantId, long memberId, long accountId,
                               long snapshotId, long dramaId, String transactionId, String suffix) {
        jdbc().update("INSERT INTO skit_ad_session "
                        + "(id,tenant_id,session_id,session_token_hash,session_token_key_version,protocol_version,"
                        + "member_id,ad_account_id,policy_snapshot_id,callback_key_version,reward_secret_version,"
                        + "provider,placement_id,scenario_id,business_type,drama_id,episode_from,episode_to,"
                        + "unlock_scope,active_scope_hash,pseudonymous_user_id,access_mode,client_lifecycle_status,"
                        + "reward_verification_status,entitlement_status,revenue_status,load_expires_at,"
                        + "reward_accept_until,provider_transaction_id,last_callback_sequence,version) "
                        + "VALUES (?,?,?,?,1,1,?,?,?,1,1,'TAKU',?,'drama_unlock','EPISODE_UNLOCK',?,1,1,?,?,?,"
                        + "'MEMBER_OAUTH','CREATED','PENDING','NONE','NONE',DATE_ADD(NOW(),INTERVAL 5 MINUTE),"
                        + "DATE_ADD(NOW(),INTERVAL 20 MINUTE),?,-1,0)",
                id, tenantId, "session-" + suffix + id, hash("token-" + suffix + id), memberId, accountId,
                snapshotId, "placement-" + accountId, dramaId, "scope-" + id, hash("scope-" + id),
                "pseudo-" + memberId, transactionId);
    }

    private void insertInbox(long id, long tenantId, long accountId, Long sessionId,
                             String idempotencyKey, boolean expiredPayload) {
        String payloadColumns = expiredPayload
                ? ",payload_ciphertext,payload_nonce,payload_key_id,payload_envelope_version,payload_expires_at" : "";
        String payloadValues = expiredPayload
                ? ",UNHEX('0102'),UNHEX(REPEAT('11',12)),'payload-key',1,DATE_SUB(NOW(),INTERVAL 1 SECOND)" : "";
        jdbc().update("INSERT INTO skit_ad_callback_inbox "
                        + "(id,tenant_id,ad_account_id,ad_session_id,callback_key_version,reward_secret_version,"
                        + "provider,callback_type,idempotency_key,provider_transaction_id,placement_id,adsource_id,"
                        + "canonical_payload_hash,authentication_level,signature_status,ingress_response_code,"
                        + "received_at" + payloadColumns + ") VALUES (?,?,?,?,1,1,'TAKU','REWARD',?,?,?,'66',"
                        + "?,'SIGNED_REWARD','VALID',200,NOW()" + payloadValues + ")",
                id, tenantId, accountId, sessionId, idempotencyKey, "transaction-" + id,
                "placement-" + accountId, hash("payload-" + id));
    }

    private void insertAttempt(long id, long tenantId, long inboxId, long accountId,
                               Long sessionId, int attemptNo) {
        jdbc().update("INSERT INTO skit_ad_callback_attempt "
                        + "(id,tenant_id,callback_inbox_id,ad_account_id,ad_session_id,attempt_no,payload_hash,"
                        + "result_code,received_at) VALUES (?,?,?,?,?,?,?,'DURABLE',NOW())",
                id, tenantId, inboxId, accountId, sessionId, attemptNo, hash("attempt-" + id));
    }

    private void insertVerifiedRevenue(long id, Fixture fixture, long accountId, Long sessionId,
                                       long inboxId, long memberId, long snapshotId, String externalId) {
        jdbc().update("INSERT INTO skit_ad_revenue_event "
                        + "(id,tenant_id,ad_account_id,provider,placement_id,external_event_id,source_member_id,"
                        + "gross_amount,occurred_time,completed,mock,status,ad_session_id,callback_inbox_id,"
                        + "policy_snapshot_id,source_type,source_amount_units,estimated_amount_units,"
                        + "reconciled_amount_units,amount_scale,source_currency,match_status,"
                        + "source_verification_status,reward_qualification_status,reconciliation_status,"
                        + "version,legacy_unverified) VALUES (?,?,?,'TAKU',?,?,?,0.001,NOW(),b'1',b'0',0,?,?,?,"
                        + "'TAKU_IMPRESSION',1000,1000,0,6,'CNY','MATCHED','UNSIGNED_OBSERVATION',"
                        + "'PENDING_REWARD','FROZEN',0,b'0')",
                id, fixture.tenantId, accountId, "placement-" + accountId, externalId, memberId,
                sessionId, inboxId, snapshotId);
    }

    private void insertVerifiedLedger(long id, long tenantId, long eventId, long snapshotId) {
        jdbc().update("INSERT INTO skit_commission_ledger "
                        + "(id,tenant_id,event_id,beneficiary_type,beneficiary_member_id,level_no,gross_amount,"
                        + "rate_bps,amount,rule_version,status,entry_type,balance_bucket,currency,gross_amount_units,"
                        + "amount_units,amount_scale,policy_snapshot_id,revision_no,legacy_unverified) "
                        + "VALUES (?,?,?,2,0,0,0.001,10000,0.001,1,0,'ESTIMATE','FROZEN','CNY',1000,1000,6,?,0,b'0')",
                id, tenantId, eventId, snapshotId);
    }

    private void insertNetworkCapability(long id, Fixture fixture, String rewardAuthority,
                                         boolean supportsUserId, boolean supportsCustomData,
                                         boolean supportsStableTransaction, boolean verified) {
        jdbc().update("INSERT INTO skit_ad_network_capability "
                        + "(id,tenant_id,ad_account_id,network_firm_id,reward_authority,supports_user_id,"
                        + "supports_custom_data,supports_stable_transaction,enabled,verified_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,b'1',?)",
                id, fixture.tenantId, fixture.accountOne, Math.toIntExact(id % 100000), rewardAuthority,
                supportsUserId, supportsCustomData, supportsStableTransaction,
                verified ? java.time.LocalDateTime.now() : null);
    }

    private void assertColumn(String table, String column, String type, String nullable, String defaultValue) {
        Map<String, Object> actual = jdbc().queryForMap("SELECT COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT "
                        + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME=? AND COLUMN_NAME=?",
                table, column);
        assertEquals(type, String.valueOf(actual.get("COLUMN_TYPE")).toLowerCase());
        assertEquals(nullable, actual.get("IS_NULLABLE"));
        assertEquals(defaultValue, actual.get("COLUMN_DEFAULT"));
    }

    private void assertGeneratedColumn(String table, String column, String type) {
        Map<String, Object> actual = jdbc().queryForMap("SELECT COLUMN_TYPE,EXTRA,GENERATION_EXPRESSION "
                        + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME=? AND COLUMN_NAME=?",
                table, column);
        assertEquals(type, String.valueOf(actual.get("COLUMN_TYPE")).toLowerCase());
        assertTrue(String.valueOf(actual.get("EXTRA")).toUpperCase().contains("STORED GENERATED"));
        assertTrue(String.valueOf(actual.get("GENERATION_EXPRESSION")).contains("ad_session_id"));
    }

    private void assertExactIndex(String table, String index, boolean unique, String columns) {
        String actual = jdbc().queryForObject("SELECT CONCAT(MIN(NON_UNIQUE),':',"
                        + "GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')) "
                        + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME=? AND INDEX_NAME=?",
                String.class, table, index);
        assertEquals((unique ? "0:" : "1:") + columns, actual);
    }

    private void assertExactForeignKey(String table, String constraint, String columns,
                                       String referencedTable, String referencedColumns) {
        String actual = jdbc().queryForObject("SELECT CONCAT(GROUP_CONCAT(k.COLUMN_NAME "
                        + "ORDER BY k.ORDINAL_POSITION SEPARATOR ','),'->',MIN(k.REFERENCED_TABLE_NAME),'(',"
                        + "GROUP_CONCAT(k.REFERENCED_COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ','),')') "
                        + "FROM information_schema.KEY_COLUMN_USAGE k WHERE k.TABLE_SCHEMA=DATABASE() "
                        + "AND k.TABLE_NAME=? AND k.CONSTRAINT_NAME=? AND k.REFERENCED_TABLE_NAME IS NOT NULL",
                String.class, table, constraint);
        assertEquals(columns + "->" + referencedTable + "(" + referencedColumns + ")", actual);
    }

    private void assertCheckExists(String table, String constraint) {
        assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME=? "
                        + "AND CONSTRAINT_TYPE='CHECK'",
                Integer.class, table, constraint));
    }

    private void assertTrigger(String trigger, String table, String event) {
        Map<String, Object> actual = jdbc().queryForMap("SELECT EVENT_OBJECT_TABLE,EVENT_MANIPULATION "
                        + "FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME=?",
                trigger);
        assertEquals(table, actual.get("EVENT_OBJECT_TABLE"));
        assertEquals(event, actual.get("EVENT_MANIPULATION"));
    }

    private static byte[] hash(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class Fixture {
        private final long base;
        private final long tenantId;
        private final long memberOne;
        private final long memberTwo;
        private final long accountOne;
        private final long accountTwo;
        private final long snapshotOne;
        private final long snapshotTwo;
        private final long sessionOne;
        private final long sessionTwo;
        private final long dramaOne;
        private final String transactionOne;

        private Fixture(long base, long tenantId, long memberOne, long memberTwo,
                        long accountOne, long accountTwo, long snapshotOne, long snapshotTwo,
                        long sessionOne, long sessionTwo, long dramaOne, String transactionOne) {
            this.base = base;
            this.tenantId = tenantId;
            this.memberOne = memberOne;
            this.memberTwo = memberTwo;
            this.accountOne = accountOne;
            this.accountTwo = accountTwo;
            this.snapshotOne = snapshotOne;
            this.snapshotTwo = snapshotTwo;
            this.sessionOne = sessionOne;
            this.sessionTwo = sessionTwo;
            this.dramaOne = dramaOne;
            this.transactionOne = transactionOne;
        }
    }

}
