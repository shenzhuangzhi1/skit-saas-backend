package cn.iocoder.yudao.module.skit.integration;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkitAdSessionSchemaHardeningMySqlIT extends SkitMySqlIntegrationTestBase {

    private static final String TASK_2_CHECKSUM =
            "64e450e4b8048a00b0ce7fbbe9f4b162ec519b5cd3f2c83d12470d92fe72fdbf";
    private static final String POLICY_IMMUTABILITY_CHECKSUM =
            "11f815a76c7f15bacfc9d9a29a60121f6736ec18bbd712a02a0190ff69c8c18d";

    @Test
    void migrationIsAdditiveStableAndInstallsTheTask5Shape() {
        Map<String, Object> task2 = jdbc().queryForMap(
                "SELECT description,checksum FROM skit_schema_migration WHERE version=2026071401");
        Map<String, Object> policyImmutability = jdbc().queryForMap(
                "SELECT description,checksum FROM skit_schema_migration WHERE version=2026071402");
        Map<String, Object> task5 = jdbc().queryForMap(
                "SELECT description,checksum FROM skit_schema_migration WHERE version=2026071403");

        assertEquals(TASK_2_CHECKSUM, task2.get("checksum"));
        assertEquals(POLICY_IMMUTABILITY_CHECKSUM, policyImmutability.get("checksum"));
        assertEquals("harden Task 5 ad session and entitlement bindings", task5.get("description"));
        assertEquals(64, String.valueOf(task5.get("checksum")).length());

        assertColumn("skit_ad_session", "session_token_key_version", "int", "NO", "1");
        assertColumn("skit_ad_session", "access_mode", "varchar(32)", "NO", "MEMBER_OAUTH");
        assertColumn("skit_ad_session", "native_player_grant_id", "bigint", "YES", null);
        assertColumn("skit_ad_session", "active_scope_released_at", "datetime", "YES", null);
        assertColumn("skit_ad_session", "active_scope_release_reason", "varchar(32)", "YES", null);
        assertColumn("skit_native_player_grant", "version", "int", "NO", "0");
        assertColumn("skit_entitlement_grant", "entitlement_id", "bigint", "NO", null);

        assertExactIndex("skit_ad_session", "uk_skit_ad_session_grant_scope", true,
                "tenant_id,id,member_id,drama_id,episode_from,provider_transaction_id");
        assertExactIndex("skit_native_player_grant", "uk_skit_player_grant_scope_id", true,
                "tenant_id,id,member_id,drama_id");
        assertExactIndex("skit_native_player_grant", "idx_skit_player_grant_lookup", false,
                "tenant_id,member_id,drama_id,status,expires_at,id");
        assertExactIndex("skit_content_entitlement", "uk_skit_entitlement_grant_binding", true,
                "tenant_id,id,member_id,drama_id,episode_no");

        assertExactForeignKey("skit_ad_session", "fk_skit_ad_session_player_grant",
                "tenant_id,native_player_grant_id,member_id,drama_id", "skit_native_player_grant",
                "tenant_id,id,member_id,drama_id");
        assertExactForeignKey("skit_entitlement_grant", "fk_skit_grant_session_binding",
                "tenant_id,ad_session_id,member_id,drama_id,provider_transaction_id",
                "skit_ad_session", "tenant_id,id,member_id,drama_id,provider_transaction_id");
        assertExactForeignKey("skit_entitlement_grant", "fk_skit_grant_entitlement_binding",
                "tenant_id,entitlement_id,member_id,drama_id,episode_no", "skit_content_entitlement",
                "tenant_id,id,member_id,drama_id,episode_no");

        assertCheckExists("skit_ad_session", "ck_skit_ad_session_token_key_version");
        assertCheckExists("skit_ad_session", "ck_skit_ad_session_access_mode");
        assertCheckExists("skit_ad_session", "ck_skit_ad_session_scope_release_pair");
        assertCheckExists("skit_ad_session", "ck_skit_ad_session_scope_release_lifecycle");
        assertCheckExists("skit_native_player_grant", "ck_skit_player_grant_version");
        assertCheckExists("skit_native_player_grant", "ck_skit_player_grant_lifecycle");
    }

    @Test
    void maximumPositive32BitSessionTokenKeyVersionCanBeInserted() {
        Fixture fixture = installFixture(96011L, 43L);

        assertDoesNotThrow(() -> insertSession(fixture.baseId + 20, fixture, fixture.memberId,
                fixture.dramaId, "MEMBER_OAUTH", null, "max-key-version", null,
                "PENDING", "NONE", null, null, null, 2_147_483_647));
        assertEquals(2_147_483_647, jdbc().queryForObject(
                "SELECT session_token_key_version FROM skit_ad_session WHERE tenant_id=? AND id=?",
                Integer.class, fixture.tenantId, fixture.baseId + 20));
    }

    @Test
    void sessionAccessProofAndActiveScopeLifecycleAreDatabaseEnforced() {
        long tenantId = 96001L;
        Fixture fixture = installFixture(tenantId, 41L);
        long playerGrantId = fixture.baseId + 10;
        insertNativePlayerGrant(playerGrantId, fixture.tenantId, fixture.memberId, fixture.dramaId,
                "ACTIVE", null, 0);

        assertDoesNotThrow(() -> insertSession(fixture.baseId + 20, fixture, fixture.memberId, fixture.dramaId,
                "MEMBER_OAUTH", null, "active-oauth", null, "PENDING", "NONE", null, null));
        assertDoesNotThrow(() -> insertSession(fixture.baseId + 21, fixture, fixture.memberId, fixture.dramaId,
                "NATIVE_PLAYER_GRANT", playerGrantId, "active-native", null,
                "PENDING", "NONE", null, null));
        assertDoesNotThrow(() -> insertSession(fixture.baseId + 22, fixture, fixture.memberId, fixture.dramaId,
                "MEMBER_OAUTH", null, null, "VERIFY_TIMEOUT", "VERIFY_TIMEOUT", "NONE",
                LocalDateTime.now(), null));
        assertDoesNotThrow(() -> insertSession(fixture.baseId + 23, fixture, fixture.memberId, fixture.dramaId,
                "MEMBER_OAUTH", null, null, "ENTITLEMENT_GRANTED", "SIGNED_VERIFIED", "GRANTED",
                LocalDateTime.now(), LocalDateTime.now()));
        assertDoesNotThrow(() -> insertSession(fixture.baseId + 25, fixture, fixture.memberId, fixture.dramaId,
                "MEMBER_OAUTH", null, null, "REWARD_REJECTED", "REJECTED", "NONE",
                LocalDateTime.now(), null));
        assertDoesNotThrow(() -> insertSession(fixture.baseId + 26, fixture, fixture.memberId, fixture.dramaId,
                "MEMBER_OAUTH", null, null, "ENTITLEMENT_GRANTED", "SIGNED_VERIFIED", "SECURITY_REVOKED",
                LocalDateTime.now(), LocalDateTime.now()));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE skit_ad_session SET session_token_key_version=0 WHERE tenant_id=? AND id=?",
                fixture.tenantId, fixture.baseId + 20));

        assertThrows(DataAccessException.class,
                () -> insertSession(fixture.baseId + 30, fixture, fixture.memberId, fixture.dramaId,
                        "MEMBER_OAUTH", playerGrantId, "oauth-cannot-carry-grant", null,
                        "PENDING", "NONE", null, null));
        assertThrows(DataAccessException.class,
                () -> insertSession(fixture.baseId + 31, fixture, fixture.memberId, fixture.dramaId,
                        "NATIVE_PLAYER_GRANT", null, "native-requires-grant", null,
                        "PENDING", "NONE", null, null));
        assertThrows(DataAccessException.class,
                () -> insertSession(fixture.baseId + 32, fixture, fixture.memberId, fixture.dramaId,
                        "MEMBER_OAUTH", null, null, null, "PENDING", "NONE", null, null));
        assertThrows(DataAccessException.class,
                () -> insertSession(fixture.baseId + 33, fixture, fixture.memberId, fixture.dramaId,
                        "MEMBER_OAUTH", null, "still-active", "VERIFY_TIMEOUT", "VERIFY_TIMEOUT", "NONE",
                        LocalDateTime.now(), null));
        assertThrows(DataAccessException.class,
                () -> insertSession(fixture.baseId + 34, fixture, fixture.memberId, fixture.dramaId,
                        "MEMBER_OAUTH", null, null, "ENTITLEMENT_GRANTED", "PENDING", "NONE",
                        LocalDateTime.now(), null));
        assertThrows(DataAccessException.class,
                () -> insertSession(fixture.baseId + 37, fixture, fixture.memberId, fixture.dramaId,
                        "MEMBER_OAUTH", null, null, "REWARD_REJECTED", "PENDING", "NONE",
                        LocalDateTime.now(), null));
        assertThrows(DataAccessException.class,
                () -> insertSession(fixture.baseId + 38, fixture, fixture.memberId, fixture.dramaId,
                        "MEMBER_OAUTH", null, "timeout-awaiting-release", null, "VERIFY_TIMEOUT", "NONE",
                        null, null));
        assertThrows(DataAccessException.class,
                () -> insertSession(fixture.baseId + 39, fixture, fixture.memberId, fixture.dramaId,
                        "MEMBER_OAUTH", null, "reject-awaiting-release", null, "REJECTED", "NONE",
                        null, null));
        assertThrows(DataAccessException.class,
                () -> insertSession(fixture.baseId + 40, fixture, fixture.memberId, fixture.dramaId,
                        "MEMBER_OAUTH", null, "grant-awaiting-release", null,
                        "SIGNED_VERIFIED", "GRANTED", null, LocalDateTime.now()));

        long otherMemberId = fixture.memberId + 90;
        insertMember(tenantId, otherMemberId);
        assertThrows(DataAccessException.class,
                () -> insertSession(fixture.baseId + 35, fixture, otherMemberId, fixture.dramaId,
                        "NATIVE_PLAYER_GRANT", playerGrantId, "wrong-player-member", null,
                        "PENDING", "NONE", null, null));
        assertThrows(DataAccessException.class,
                () -> insertSession(fixture.baseId + 36, fixture, fixture.memberId, fixture.dramaId + 1,
                        "NATIVE_PLAYER_GRANT", playerGrantId, "wrong-player-drama", null,
                        "PENDING", "NONE", null, null));
    }

    @Test
    void nativePlayerGrantLifecycleIsCasReadyAndConsistent() {
        Fixture fixture = installFixture(96002L, 42L);

        assertDoesNotThrow(() -> insertNativePlayerGrant(fixture.baseId + 10, fixture.tenantId,
                fixture.memberId, fixture.dramaId, "ACTIVE", null, 0));
        assertDoesNotThrow(() -> insertNativePlayerGrant(fixture.baseId + 11, fixture.tenantId,
                fixture.memberId, fixture.dramaId, "EXPIRED", null, 1));
        assertDoesNotThrow(() -> insertNativePlayerGrant(fixture.baseId + 12, fixture.tenantId,
                fixture.memberId, fixture.dramaId, "REVOKED", LocalDateTime.now(), 1));

        assertThrows(DataAccessException.class, () -> insertNativePlayerGrant(fixture.baseId + 20,
                fixture.tenantId, fixture.memberId, fixture.dramaId, "ACTIVE", LocalDateTime.now(), 0));
        assertThrows(DataAccessException.class, () -> insertNativePlayerGrant(fixture.baseId + 21,
                fixture.tenantId, fixture.memberId, fixture.dramaId, "REVOKED", null, 0));
        assertThrows(DataAccessException.class, () -> insertNativePlayerGrant(fixture.baseId + 22,
                fixture.tenantId, fixture.memberId, fixture.dramaId, "ACTIVE", null, -1));
    }

    @Test
    void entitlementGrantCannotBindSameTenantWrongSessionOrEntitlementFacts() {
        Fixture fixture = installFixture(96003L, 43L);
        long otherMemberId = fixture.memberId + 100;
        insertMember(fixture.tenantId, otherMemberId);
        long correctEntitlementId = fixture.baseId + 50;
        long otherEntitlementId = fixture.baseId + 51;
        long episodeTwoEntitlementId = fixture.baseId + 52;
        insertEntitlement(correctEntitlementId, fixture.tenantId, fixture.memberId, fixture.dramaId, 1);
        insertEntitlement(otherEntitlementId, fixture.tenantId, otherMemberId, fixture.dramaId + 1, 2);
        insertEntitlement(episodeTwoEntitlementId, fixture.tenantId, fixture.memberId, fixture.dramaId, 2);

        long validSessionId = fixture.baseId + 60;
        insertSession(validSessionId, fixture, fixture.memberId, fixture.dramaId, "MEMBER_OAUTH", null,
                "grant-valid", null, "PENDING", "NONE", null, null, "tx-valid");
        assertDoesNotThrow(() -> insertGrant(fixture.baseId + 70, fixture.tenantId, validSessionId,
                correctEntitlementId, fixture.memberId, fixture.dramaId, 1, "tx-valid"));

        long nullEntitlementSession = fixture.baseId + 61;
        insertSession(nullEntitlementSession, fixture, fixture.memberId, fixture.dramaId, "MEMBER_OAUTH", null,
                "grant-null", null, "PENDING", "NONE", null, null, "tx-null");
        assertThrows(DataAccessException.class, () -> insertGrant(fixture.baseId + 71, fixture.tenantId,
                nullEntitlementSession, null, fixture.memberId, fixture.dramaId, 1, "tx-null"));

        long wrongEntitlementSession = fixture.baseId + 62;
        insertSession(wrongEntitlementSession, fixture, fixture.memberId, fixture.dramaId, "MEMBER_OAUTH", null,
                "grant-wrong-entitlement", null, "PENDING", "NONE", null, null,
                "tx-wrong-entitlement");
        assertThrows(DataAccessException.class, () -> insertGrant(fixture.baseId + 72, fixture.tenantId,
                wrongEntitlementSession, otherEntitlementId, fixture.memberId, fixture.dramaId, 1,
                "tx-wrong-entitlement"));

        long wrongSessionMember = fixture.baseId + 63;
        insertSession(wrongSessionMember, fixture, fixture.memberId, fixture.dramaId, "MEMBER_OAUTH", null,
                "grant-wrong-member", null, "PENDING", "NONE", null, null, "tx-wrong-member");
        assertThrows(DataAccessException.class, () -> insertGrant(fixture.baseId + 73, fixture.tenantId,
                wrongSessionMember, otherEntitlementId, otherMemberId, fixture.dramaId + 1, 2,
                "tx-wrong-member"));

        long wrongSessionTransaction = fixture.baseId + 64;
        insertSession(wrongSessionTransaction, fixture, fixture.memberId, fixture.dramaId, "MEMBER_OAUTH", null,
                "grant-wrong-transaction", null, "PENDING", "NONE", null, null,
                "tx-authoritative");
        assertThrows(DataAccessException.class, () -> insertGrant(fixture.baseId + 74, fixture.tenantId,
                wrongSessionTransaction, correctEntitlementId, fixture.memberId, fixture.dramaId, 1,
                "tx-forged"));

        long wrongSessionEpisode = fixture.baseId + 65;
        insertSession(wrongSessionEpisode, fixture, fixture.memberId, fixture.dramaId, "MEMBER_OAUTH", null,
                "grant-wrong-episode", null, "PENDING", "NONE", null, null,
                "tx-wrong-episode");
        assertThrows(DataAccessException.class, () -> insertGrant(fixture.baseId + 75, fixture.tenantId,
                wrongSessionEpisode, episodeTwoEntitlementId, fixture.memberId, fixture.dramaId, 2,
                "tx-wrong-episode"));
    }

    private Fixture installFixture(long tenantId, long dramaId) {
        long baseId = tenantId * 100;
        long memberId = baseId + 1;
        long accountId = baseId + 2;
        long planId = baseId + 3;
        long snapshotId = baseId + 4;
        insertMember(tenantId, memberId);
        jdbc().update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,app_key,status) "
                        + "VALUES (?,?,\'TAKU\',\'Task5\',?,?,\'\',1)",
                accountId, tenantId, "account-" + tenantId, "app-" + tenantId);
        jdbc().update("INSERT INTO skit_commission_plan "
                        + "(id,tenant_id,version,status,published_time) VALUES (?,?,1,0,CURRENT_TIMESTAMP)",
                planId, tenantId);
        jdbc().update("INSERT INTO skit_ad_policy_snapshot "
                        + "(id,tenant_id,plan_id,source_member_id,rule_version,snapshot_schema_version,"
                        + "snapshot_json,snapshot_hash,policy_snapshot_at) "
                        + "VALUES (?,?,?,?,1,1,\'{}\',UNHEX(SHA2(?,256)),CURRENT_TIMESTAMP)",
                snapshotId, tenantId, planId, memberId, "snapshot-" + tenantId);
        jdbc().update("INSERT INTO skit_ad_callback_key "
                        + "(tenant_id,ad_account_id,key_version,callback_key_hash,active) "
                        + "VALUES (?,?,1,UNHEX(SHA2(?,256)),b\'1\')",
                tenantId, accountId, "callback-" + tenantId);
        jdbc().update("INSERT INTO skit_ad_reward_secret_version "
                        + "(tenant_id,ad_account_id,secret_version,ciphertext,nonce,encryption_key_id,"
                        + "envelope_version,active) VALUES (?,?,1,UNHEX(SHA2(?,256)),UNHEX(REPEAT(\'11\',12)),"
                        + "\'test-key\',1,b\'1\')",
                tenantId, accountId, "secret-" + tenantId);
        return new Fixture(tenantId, baseId, memberId, accountId, snapshotId, dramaId);
    }

    private void insertMember(long tenantId, long memberId) {
        jdbc().update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) "
                        + "VALUES (?,?,?,?,?,?,1,0)",
                memberId, tenantId, Long.toString(memberId), "encoded", "member-" + memberId,
                "T5" + memberId);
    }

    private void insertNativePlayerGrant(long id, long tenantId, long memberId, long dramaId,
                                         String status, LocalDateTime revokedAt, int version) {
        jdbc().update("INSERT INTO skit_native_player_grant "
                        + "(id,tenant_id,member_id,drama_id,grant_token_hash,status,expires_at,revoked_at,version) "
                        + "VALUES (?,?,?,?,UNHEX(SHA2(?,256)),?,DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 30 MINUTE),?,?)",
                id, tenantId, memberId, dramaId, "player-" + id, status, revokedAt, version);
    }

    private void insertSession(long id, Fixture fixture, long memberId, long dramaId, String accessMode,
                               Long playerGrantId, String activeScopeSeed, String releaseReason,
                               String rewardStatus, String entitlementStatus, LocalDateTime releasedAt,
                               LocalDateTime entitledAt) {
        insertSession(id, fixture, memberId, dramaId, accessMode, playerGrantId, activeScopeSeed, releaseReason,
                rewardStatus, entitlementStatus, releasedAt, entitledAt, null);
    }

    private void insertSession(long id, Fixture fixture, long memberId, long dramaId, String accessMode,
                               Long playerGrantId, String activeScopeSeed, String releaseReason,
                               String rewardStatus, String entitlementStatus, LocalDateTime releasedAt,
                               LocalDateTime entitledAt, String providerTransactionId) {
        insertSession(id, fixture, memberId, dramaId, accessMode, playerGrantId, activeScopeSeed, releaseReason,
                rewardStatus, entitlementStatus, releasedAt, entitledAt, providerTransactionId, 1);
    }

    private void insertSession(long id, Fixture fixture, long memberId, long dramaId, String accessMode,
                               Long playerGrantId, String activeScopeSeed, String releaseReason,
                               String rewardStatus, String entitlementStatus, LocalDateTime releasedAt,
                               LocalDateTime entitledAt, String providerTransactionId,
                               int sessionTokenKeyVersion) {
        jdbc().update("INSERT INTO skit_ad_session "
                        + "(id,tenant_id,session_id,session_token_hash,session_token_key_version,protocol_version,"
                        + "member_id,ad_account_id,policy_snapshot_id,callback_key_version,reward_secret_version,"
                        + "access_mode,native_player_grant_id,provider,placement_id,scenario_id,business_type,"
                        + "drama_id,episode_from,episode_to,unlock_scope,active_scope_hash,active_scope_released_at,"
                        + "active_scope_release_reason,pseudonymous_user_id,reward_verification_status,"
                        + "entitlement_status,load_expires_at,reward_accept_until,entitled_at,"
                        + "provider_transaction_id,version) "
                        + "VALUES (?,?,?,UNHEX(SHA2(?,256)),?,1,?,?,?,?,1,?,?,?,\'placement\',\'drama_unlock\',"
                        + "\'DRAMA_EPISODE_UNLOCK\',?,1,1,?,UNHEX(SHA2(?,256)),?,?,?,?,"
                        + "?,CURRENT_TIMESTAMP,DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 20 MINUTE),?,?,0)",
                id, fixture.tenantId, "session-" + id, "session-token-" + id, sessionTokenKeyVersion,
                memberId, fixture.accountId, fixture.snapshotId, 1, accessMode, playerGrantId, "TAKU",
                dramaId, "v1|DRAMA_EPISODE_UNLOCK|PANGLE_DJX|" + dramaId + "|1|1", activeScopeSeed,
                releasedAt, releaseReason, "pseudo-" + memberId, rewardStatus, entitlementStatus, entitledAt,
                providerTransactionId);
    }

    private void insertEntitlement(long id, long tenantId, long memberId, long dramaId, int episodeNo) {
        jdbc().update("INSERT INTO skit_content_entitlement "
                        + "(id,tenant_id,member_id,drama_id,episode_no,status,granted_at) "
                        + "VALUES (?,?,?,?,?,\'GRANTED\',CURRENT_TIMESTAMP)",
                id, tenantId, memberId, dramaId, episodeNo);
    }

    private void insertGrant(long id, long tenantId, long sessionId, Long entitlementId, long memberId,
                             long dramaId, int episodeNo, String providerTransactionId) {
        jdbc().update("INSERT INTO skit_entitlement_grant "
                        + "(id,tenant_id,ad_session_id,entitlement_id,member_id,drama_id,episode_no,"
                        + "provider_transaction_id,grant_result,granted_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,\'CREATED\',CURRENT_TIMESTAMP)",
                id, tenantId, sessionId, entitlementId, memberId, dramaId, episodeNo, providerTransactionId);
    }

    private void assertColumn(String table, String column, String type, String nullable, String defaultValue) {
        Map<String, Object> definition = jdbc().queryForMap("SELECT COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT "
                        + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME=? AND COLUMN_NAME=?",
                table, column);
        assertEquals(type, String.valueOf(definition.get("COLUMN_TYPE")).toLowerCase());
        assertEquals(nullable, definition.get("IS_NULLABLE"));
        assertEquals(defaultValue, definition.get("COLUMN_DEFAULT"));
    }

    private void assertExactIndex(String table, String index, boolean unique, String columns) {
        String actual = jdbc().queryForObject("SELECT CONCAT(MIN(NON_UNIQUE),\':\',"
                        + "GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR \',\')) "
                        + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME=? AND INDEX_NAME=?",
                String.class, table, index);
        assertEquals((unique ? "0:" : "1:") + columns, actual, table + "." + index);
    }

    private void assertExactForeignKey(String table, String constraint, String columns,
                                       String parentTable, String parentColumns) {
        String actual = jdbc().queryForObject("SELECT CONCAT("
                        + "GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR \',\'),\'->\',"
                        + "MIN(k.REFERENCED_TABLE_NAME),\'(\',GROUP_CONCAT(k.REFERENCED_COLUMN_NAME "
                        + "ORDER BY k.ORDINAL_POSITION SEPARATOR \',\'),\'):\',MIN(r.UPDATE_RULE),\':\',"
                        + "MIN(r.DELETE_RULE)) FROM information_schema.KEY_COLUMN_USAGE k "
                        + "JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
                        + "ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME "
                        + "AND r.TABLE_NAME=k.TABLE_NAME WHERE k.TABLE_SCHEMA=DATABASE() AND k.TABLE_NAME=? "
                        + "AND k.CONSTRAINT_NAME=? AND k.REFERENCED_TABLE_NAME IS NOT NULL",
                String.class, table, constraint);
        assertEquals(columns + "->" + parentTable + "(" + parentColumns + "):RESTRICT:RESTRICT", actual,
                table + "." + constraint);
    }

    private void assertCheckExists(String table, String constraint) {
        Integer count = jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME=? "
                        + "AND CONSTRAINT_TYPE=\'CHECK\'",
                Integer.class, table, constraint);
        assertEquals(1, count, table + "." + constraint);
    }

    private static final class Fixture {

        private final long tenantId;
        private final long baseId;
        private final long memberId;
        private final long accountId;
        private final long snapshotId;
        private final long dramaId;

        private Fixture(long tenantId, long baseId, long memberId, long accountId, long snapshotId, long dramaId) {
            this.tenantId = tenantId;
            this.baseId = baseId;
            this.memberId = memberId;
            this.accountId = accountId;
            this.snapshotId = snapshotId;
            this.dramaId = dramaId;
        }

    }

}
