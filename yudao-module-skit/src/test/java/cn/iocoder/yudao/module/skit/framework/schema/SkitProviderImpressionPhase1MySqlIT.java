package cn.iocoder.yudao.module.skit.framework.schema;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkitProviderImpressionPhase1MySqlIT extends SkitPartialMigrationMySqlITBase {

    private static final int MIGRATION_VERSION = 2026080201;
    private static final List<String> TABLES = Arrays.asList(
            "skit_ad_provider_connection",
            "skit_ad_provider_callback_route",
            "skit_ad_callback_route_registry",
            "skit_ad_callback_route_registry_migration",
            "skit_provider_impression_inbox",
            "skit_provider_callback_attempt",
            "skit_platform_provider_command_audit");

    @BeforeAll
    void installPhase1Migration() throws Exception {
        runThrough(MIGRATION_VERSION);
    }

    @Test
    void freshDatabaseInstallsExactGlobalCaptureSchemaAndMigrationOnce() throws Exception {
        for (String table : TABLES) {
            assertEquals(1, tableCount(table), "missing table " + table);
            assertEquals(0, columnCount(table, "tenant_id"),
                    table + " must not acquire tenant ownership");
        }
        assertEquals("0:provider_connection_id,dedupe_scheme,dedupe_key_hash",
                indexDefinition("skit_provider_impression_inbox",
                        "uk_provider_impression_inbox_dedupe"));
        assertEquals("binary(32):NO", columnDefinition(
                "skit_provider_impression_inbox", "dedupe_key_hash"));
        assertEquals("binary(12):YES", columnDefinition(
                "skit_provider_callback_attempt", "payload_nonce"));
        assertEquals(0, columnCount("skit_ad_provider_callback_route", "key_hash"));
        assertEquals("char(16):YES", columnDefinition(
                "skit_ad_provider_callback_route", "callback_key_fingerprint"));
        assertEquals("binary(32):YES", columnDefinition(
                "skit_provider_impression_inbox", "material_integrity_hash"));
        assertEquals("binary(32):YES", columnDefinition(
                "skit_provider_callback_attempt", "material_integrity_hash"));
        assertEquals(0, columnCount("skit_provider_impression_inbox", "delivery_count"));
        assertEquals("varchar(32):NO", columnDefinition(
                "skit_provider_callback_attempt", "dedupe_scheme"));
        assertEquals("provider_connection_id,id,canonical_attempt_id->skit_provider_callback_attempt"
                        + "(provider_connection_id,inbox_id,id):RESTRICT:RESTRICT",
                foreignKeyDefinition("skit_provider_impression_inbox",
                        "fk_provider_impression_inbox_canonical_attempt"));
        assertEquals("provider_connection_id->skit_ad_provider_connection(id):RESTRICT:RESTRICT",
                foreignKeyDefinition("skit_provider_callback_attempt",
                        "fk_provider_callback_attempt_connection"));
        assertEquals("provider_connection_id,inbox_id,dedupe_scheme->skit_provider_impression_inbox"
                        + "(provider_connection_id,id,dedupe_scheme):RESTRICT:RESTRICT",
                foreignKeyDefinition("skit_provider_callback_attempt",
                        "fk_provider_callback_attempt_inbox"));
        assertEquals("id,active_callback_route_id->skit_ad_provider_callback_route"
                        + "(provider_connection_id,id):RESTRICT:RESTRICT",
                foreignKeyDefinition("skit_ad_provider_connection",
                        "fk_provider_connection_active_route"));
        assertEquals("id,callback_route_registry_id->skit_ad_callback_route_registry"
                        + "(provider_callback_route_id,id):RESTRICT:RESTRICT",
                foreignKeyDefinition("skit_ad_provider_callback_route",
                        "fk_provider_callback_route_registry"));
        assertEquals("provider_connection_id,supersedes_callback_route_id->"
                        + "skit_ad_provider_callback_route(provider_connection_id,id):RESTRICT:RESTRICT",
                foreignKeyDefinition("skit_ad_provider_callback_route",
                        "fk_provider_callback_route_supersedes"));
        assertNotNull(checkDefinition("skit_ad_provider_callback_route",
                "ck_provider_callback_route_purpose"));
        assertNotNull(checkDefinition("skit_ad_callback_route_registry",
                "ck_callback_route_registry_route_xor"));
        assertNotNull(checkDefinition("skit_provider_callback_attempt",
                "ck_provider_callback_attempt_payload_retention"));
        assertNotNull(triggerDefinition("trg_provider_connection_lifecycle_immutable"));
        assertNotNull(triggerDefinition("trg_provider_callback_route_lifecycle_immutable"));
        assertNotNull(triggerDefinition("trg_callback_route_registry_immutable"));
        assertNotNull(triggerDefinition("trg_callback_route_registry_no_delete"));
        assertNotNull(triggerDefinition("trg_callback_route_registry_migration_monotonic"));
        assertNotNull(triggerDefinition("trg_provider_impression_inbox_monotonic"));
        assertNotNull(triggerDefinition("trg_platform_provider_command_audit_immutable"));
        assertNotNull(triggerDefinition("trg_platform_provider_command_audit_no_delete"));
        assertEquals(1, jdbc().queryForObject(
                "SELECT COUNT(*) FROM `skit_ad_callback_route_registry_migration` WHERE `singleton_id`=1 "
                        + "AND `migration_phase`='DUAL_WRITE' AND `last_callback_key_id`=0", Integer.class));

        String storedChecksum = jdbc().queryForObject(
                "SELECT `checksum` FROM `skit_schema_migration` WHERE `version`=?",
                String.class, MIGRATION_VERSION);
        assertEquals(migration(MIGRATION_VERSION).getChecksum(), storedChecksum);
        runThrough(MIGRATION_VERSION);
        assertEquals(1, migrationCount(MIGRATION_VERSION));
        assertEquals(storedChecksum, jdbc().queryForObject(
                "SELECT `checksum` FROM `skit_schema_migration` WHERE `version`=?",
                String.class, MIGRATION_VERSION));
    }

    @Test
    void registryCutoverRejectsSkipsAndRequiresVerificationBeforeShadowRead() {
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE `skit_ad_callback_route_registry_migration` SET `migration_phase`='VERIFY',"
                        + "`phase_revision`=`phase_revision`+1 WHERE `singleton_id`=1"));

        assertEquals(1, jdbc().update(
                "UPDATE `skit_ad_callback_route_registry_migration` SET `migration_phase`='BACKFILL',"
                        + "`phase_revision`=`phase_revision`+1 WHERE `singleton_id`=1"));
        assertEquals(1, jdbc().update(
                "UPDATE `skit_ad_callback_route_registry_migration` SET "
                        + "`blocked_reason_hash`=UNHEX(REPEAT('11',32)),"
                        + "`blocked_at`=CURRENT_TIMESTAMP,`phase_revision`=`phase_revision`+1 "
                        + "WHERE `singleton_id`=1"));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE `skit_ad_callback_route_registry_migration` SET `migration_phase`='VERIFY',"
                        + "`phase_revision`=`phase_revision`+1 WHERE `singleton_id`=1"));
        assertEquals(1, jdbc().update(
                "UPDATE `skit_ad_callback_route_registry_migration` SET `migration_phase`='BACKFILL',"
                        + "`blocked_reason_hash`=NULL,`blocked_at`=NULL,"
                        + "`phase_revision`=`phase_revision`+1 WHERE `singleton_id`=1"));
        assertEquals(1, jdbc().update(
                "UPDATE `skit_ad_callback_route_registry_migration` SET `migration_phase`='VERIFY',"
                        + "`phase_revision`=`phase_revision`+1 WHERE `singleton_id`=1"));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE `skit_ad_callback_route_registry_migration` SET `migration_phase`='SHADOW_READ',"
                        + "`phase_revision`=`phase_revision`+1 WHERE `singleton_id`=1"));
        assertEquals(1, jdbc().update(
                "UPDATE `skit_ad_callback_route_registry_migration` SET "
                        + "`expected_row_count`=10,`verified_row_count`=10,"
                        + "`verification_mismatch_count`=0,"
                        + "`verification_hash`=UNHEX(REPEAT('22',32)),`verified_at`=CURRENT_TIMESTAMP,"
                        + "`phase_revision`=`phase_revision`+1 WHERE `singleton_id`=1"));
        assertEquals(1, jdbc().update(
                "UPDATE `skit_ad_callback_route_registry_migration` SET `migration_phase`='SHADOW_READ',"
                        + "`phase_revision`=`phase_revision`+1 WHERE `singleton_id`=1"));
    }

    @Test
    void routeLifecycleRejectsMissingSubmissionEvidenceAndPurposeMutation() {
        long connectionId = insertConnection("route-contract");
        long issuedRouteId = insertIssuedRoute(connectionId, 1, "PRODUCTION", "PRIMARY_ACCEPTING");
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE `skit_ad_provider_callback_route` SET `state`='SUBMITTED' WHERE `id`=?",
                issuedRouteId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE `skit_ad_provider_callback_route` SET `state`='ACTIVE',"
                        + "`activated_at`=CURRENT_TIMESTAMP WHERE `id`=?", issuedRouteId));

        assertEquals(1, jdbc().update(
                "INSERT INTO `skit_ad_provider_callback_route` "
                        + "(`provider_connection_id`,`route_version`,`purpose`,`state`,`route_slot`,"
                        + "`created_by_user_id`,`created_at`,`updated_by_user_id`,`updated_at`) "
                        + "VALUES (?,2,'GATE_TEST','DRAFT','INACTIVE',7,CURRENT_TIMESTAMP,7,CURRENT_TIMESTAMP)",
                connectionId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE `skit_ad_provider_callback_route` SET `purpose`='PRODUCTION' "
                        + "WHERE `provider_connection_id`=? AND `route_version`=2", connectionId));

        long selfSupersedingRouteId = issuedRouteId + 1_000;
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "INSERT INTO `skit_ad_provider_callback_route` "
                        + "(`id`,`provider_connection_id`,`route_version`,`purpose`,`state`,`route_slot`,"
                        + "`supersedes_callback_route_id`,`created_by_user_id`,`created_at`,"
                        + "`updated_by_user_id`,`updated_at`) VALUES (?,?,3,'PRODUCTION','DRAFT','INACTIVE',"
                        + "?,7,CURRENT_TIMESTAMP,7,CURRENT_TIMESTAMP)",
                selfSupersedingRouteId, connectionId, selfSupersedingRouteId));

        long otherConnectionId = insertConnection("route-other");
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "INSERT INTO `skit_ad_provider_callback_route` "
                        + "(`provider_connection_id`,`route_version`,`purpose`,`state`,`route_slot`,"
                        + "`supersedes_callback_route_id`,`created_by_user_id`,`created_at`,"
                        + "`updated_by_user_id`,`updated_at`) VALUES (?,1,'PRODUCTION','DRAFT','INACTIVE',"
                        + "?,7,CURRENT_TIMESTAMP,7,CURRENT_TIMESTAMP)",
                otherConnectionId, issuedRouteId));

        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE `skit_ad_callback_route_registry` SET `key_hash`=UNHEX(REPEAT('ef',32)) "
                        + "WHERE `provider_callback_route_id`=?", issuedRouteId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "DELETE FROM `skit_ad_callback_route_registry` WHERE `provider_callback_route_id`=?",
                issuedRouteId));
    }

    @Test
    void providerConnectionLifecycleRejectsReopenAndIdentityMutation() {
        long blockedConnectionId = insertConnection("connection-blocked");
        assertEquals(1, jdbc().update(
                "UPDATE `skit_ad_provider_connection` SET `state`='BLOCKED',"
                        + "`blocked_at`=CURRENT_TIMESTAMP WHERE `id`=?", blockedConnectionId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE `skit_ad_provider_connection` SET `state`='CONFIGURING',"
                        + "`blocked_at`=NULL WHERE `id`=?", blockedConnectionId));

        long retiredConnectionId = insertConnection("connection-retired");
        assertEquals(1, jdbc().update(
                "UPDATE `skit_ad_provider_connection` SET `state`='RETIRED',"
                        + "`retired_at`=CURRENT_TIMESTAMP WHERE `id`=?", retiredConnectionId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE `skit_ad_provider_connection` SET `state`='CONFIGURING',"
                        + "`retired_at`=NULL WHERE `id`=?", retiredConnectionId));

        long identityConnectionId = insertConnection("connection-identity");
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE `skit_ad_provider_connection` SET `connection_code`="
                        + "CONCAT(`connection_code`,'-rewritten') WHERE `id`=?", identityConnectionId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE `skit_ad_provider_connection` SET `external_account_ref_hash`="
                        + "UNHEX(SHA2('rewritten-external-account',256)) WHERE `id`=?",
                identityConnectionId));
    }

    @Test
    void inboxRejectsInvalidSchemeMaterialAndMakesFallbackQuarantinePermanent() {
        long connectionId = insertConnection("inbox-contract");
        assertThrows(DataAccessException.class, () -> jdbc().update(
                inboxInsertSql(), connectionId, "OFFICIAL_V1", null, "42", "PENDING", null, null));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                inboxInsertSql(), connectionId, "FALLBACK_WIRE_V1", "valid", "42",
                "PENDING", null, null));

        jdbc().update(inboxInsertSql(), connectionId, "FALLBACK_WIRE_V1", null, "42",
                "QUARANTINED", "FALLBACK_WIRE_KEY", null);
        Long fallbackId = jdbc().queryForObject("SELECT MAX(`id`) FROM `skit_provider_impression_inbox`",
                Long.class);
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE `skit_provider_impression_inbox` SET `processing_status`='PROCESSING',"
                        + "`quarantine_reason`=NULL,`processed_at`=NULL,`lease_owner`='worker',"
                        + "`lease_until`=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 MINUTE) WHERE `id`=?",
                fallbackId));
    }

    @Test
    void inboxConflictIsMonotonicAndAttemptRowsAreTheDeliveryLedger() {
        long connectionId = insertConnection("integrity-contract");
        jdbc().update(inboxInsertSql(), connectionId, "OFFICIAL_V1", "req-1", "42",
                "PENDING", null, null);
        Long inboxId = jdbc().queryForObject("SELECT MAX(`id`) FROM `skit_provider_impression_inbox`",
                Long.class);
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE `skit_provider_impression_inbox` SET `integrity_status`='PAYLOAD_CONFLICT',"
                        + "`integrity_revision`=2,`integrity_conflict_at`=CURRENT_TIMESTAMP WHERE `id`=?",
                inboxId));

        assertEquals(1, jdbc().update(
                attemptInsertSql("DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 7 DAY)"),
                connectionId, inboxId, "OFFICIAL_V1", "PAYLOAD_CONFLICT", "ACK_200"));
        assertEquals(1, jdbc().update(
                "UPDATE `skit_provider_impression_inbox` SET `integrity_status`='PAYLOAD_CONFLICT',"
                        + "`integrity_revision`=1,`integrity_conflict_at`=CURRENT_TIMESTAMP WHERE `id`=?",
                inboxId));
        assertEquals(1, jdbc().update(
                attemptInsertSql("DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 7 DAY)"),
                connectionId, inboxId, "OFFICIAL_V1", "PAYLOAD_CONFLICT", "ACK_200"));
        assertEquals(1, jdbc().update(
                "UPDATE `skit_provider_impression_inbox` SET `integrity_revision`=2 WHERE `id`=?",
                inboxId));
        assertEquals(1, jdbc().queryForObject(
                "SELECT COUNT(*) FROM `skit_provider_impression_inbox` WHERE `id`=?",
                Integer.class, inboxId));
        assertEquals(2, jdbc().queryForObject(
                "SELECT COUNT(*) FROM `skit_provider_callback_attempt` WHERE `inbox_id`=?",
                Integer.class, inboxId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE `skit_provider_impression_inbox` SET `integrity_revision`=4 WHERE `id`=?",
                inboxId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE `skit_provider_impression_inbox` SET `integrity_status`='CANONICAL',"
                        + "`integrity_revision`=3,`integrity_conflict_at`=NULL WHERE `id`=?", inboxId));

        assertThrows(DataAccessException.class, () -> jdbc().update(
                attemptInsertSql("DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 7 DAY)"),
                connectionId, inboxId, "OFFICIAL_V1", "PAYLOAD_CONFLICT", "REJECT_602"));

        long otherConnectionId = insertConnection("integrity-other");
        assertThrows(DataAccessException.class, () -> jdbc().update(
                attemptInsertSql("DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 7 DAY)"),
                otherConnectionId, inboxId, "OFFICIAL_V1", "CANONICAL", "ACK_200"));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                attemptInsertSql("DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 7 DAY)"),
                connectionId, inboxId, "FALLBACK_WIRE_V1", "FALLBACK_QUARANTINED", "ACK_200"));

        jdbc().update(inboxInsertSql(), connectionId, "FALLBACK_WIRE_V1", null, "42",
                "QUARANTINED", "FALLBACK_WIRE_KEY", null);
        Long fallbackInboxId = jdbc().queryForObject(
                "SELECT `id` FROM `skit_provider_impression_inbox` WHERE `provider_connection_id`=? "
                        + "AND `dedupe_scheme`='FALLBACK_WIRE_V1'", Long.class, connectionId);
        assertThrows(DataAccessException.class, () -> jdbc().update(
                attemptInsertSql("DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 7 DAY)"),
                connectionId, fallbackInboxId, "OFFICIAL_V1", "CANONICAL", "ACK_200"));

        Long attemptId = jdbc().queryForObject(
                "SELECT MIN(`id`) FROM `skit_provider_callback_attempt` WHERE `inbox_id`=?",
                Long.class, inboxId);
        assertEquals(1, jdbc().update(
                "UPDATE `skit_provider_impression_inbox` SET `canonical_attempt_id`=? WHERE `id`=?",
                attemptId, inboxId));
        assertThrows(DataAccessException.class, () -> jdbc().update(
                "UPDATE `skit_provider_callback_attempt` SET `wire_payload_hash`=UNHEX(REPEAT('01',32)) "
                        + "WHERE `id`=?", attemptId));
    }

    @Test
    void payloadPurgeRequiresExpiryAndProcessedTerminalInboxEvidence() {
        long pendingConnectionId = insertConnection("purge-pending");
        jdbc().update(inboxInsertSql(), pendingConnectionId, "OFFICIAL_V1", "req-pending", "42",
                "PENDING", null, null);
        Long pendingInboxId = jdbc().queryForObject(
                "SELECT MAX(`id`) FROM `skit_provider_impression_inbox`", Long.class);
        assertEquals(1, jdbc().update(attemptInsertSql(
                        "DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 DAY)"),
                pendingConnectionId, pendingInboxId, "OFFICIAL_V1", "CANONICAL", "ACK_200"));
        Long pendingAttemptId = jdbc().queryForObject(
                "SELECT MAX(`id`) FROM `skit_provider_callback_attempt`", Long.class);
        assertThrows(DataAccessException.class, () -> purgeAttempt(pendingAttemptId));

        long fallbackConnectionId = insertConnection("purge-fallback");
        jdbc().update(inboxInsertSql(), fallbackConnectionId, "FALLBACK_WIRE_V1", null, "42",
                "QUARANTINED", "FALLBACK_WIRE_KEY", null);
        Long fallbackInboxId = jdbc().queryForObject(
                "SELECT MAX(`id`) FROM `skit_provider_impression_inbox`", Long.class);
        assertEquals(1, jdbc().update(attemptInsertSql(
                        "DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 DAY)",
                        "DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 2 DAY)"),
                fallbackConnectionId, fallbackInboxId, "FALLBACK_WIRE_V1",
                "FALLBACK_QUARANTINED", "ACK_200"));
        Long fallbackAttemptId = jdbc().queryForObject(
                "SELECT MAX(`id`) FROM `skit_provider_callback_attempt`", Long.class);
        assertThrows(DataAccessException.class, () -> purgeAttempt(fallbackAttemptId));

        long succeededConnectionId = insertConnection("purge-succeeded");
        jdbc().update(inboxInsertSql(), succeededConnectionId, "OFFICIAL_V1", "req-succeeded", "42",
                "SUCCEEDED", null, new Timestamp(System.currentTimeMillis()));
        Long succeededInboxId = jdbc().queryForObject(
                "SELECT MAX(`id`) FROM `skit_provider_impression_inbox`", Long.class);
        assertEquals(1, jdbc().update(attemptInsertSql(
                        "DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 DAY)"),
                succeededConnectionId, succeededInboxId, "OFFICIAL_V1", "CANONICAL", "ACK_200"));
        Long futureAttemptId = jdbc().queryForObject(
                "SELECT MAX(`id`) FROM `skit_provider_callback_attempt`", Long.class);
        assertThrows(DataAccessException.class, () -> purgeAttempt(futureAttemptId));

        assertEquals(1, jdbc().update(attemptInsertSql(
                        "DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 DAY)",
                        "DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 2 DAY)"),
                succeededConnectionId, succeededInboxId, "OFFICIAL_V1", "CANONICAL", "ACK_200"));
        Long expiredAttemptId = jdbc().queryForObject(
                "SELECT MAX(`id`) FROM `skit_provider_callback_attempt`", Long.class);
        assertEquals(1, purgeAttempt(expiredAttemptId));
    }

    private long insertConnection(String suffix) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc().update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO `skit_ad_provider_connection` "
                            + "(`connection_code`,`provider`,`account_mode`,`external_account_ref_hash`,`state`,"
                            + "`owner_tenant_id`,`created_by_user_id`,`created_at`,`updated_by_user_id`,`updated_at`) "
                            + "VALUES (?,'TAKU','TENANT_OWNED',UNHEX(SHA2(?,256)),'CONFIGURING',1001,"
                            + "7,CURRENT_TIMESTAMP,7,CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "connection-" + suffix);
            statement.setString(2, "external-" + suffix);
            return statement;
        }, keyHolder);
        return generatedKey(keyHolder);
    }

    private long insertIssuedRoute(long connectionId, int version, String purpose, String slot) {
        KeyHolder routeKeyHolder = new GeneratedKeyHolder();
        jdbc().update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO `skit_ad_provider_callback_route` "
                            + "(`provider_connection_id`,`route_version`,`purpose`,`state`,`route_slot`,"
                            + "`created_by_user_id`,`created_at`,`updated_by_user_id`,`updated_at`) "
                            + "VALUES (?,? ,?,'DRAFT','INACTIVE',7,CURRENT_TIMESTAMP,7,CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, connectionId);
            statement.setInt(2, version);
            statement.setString(3, purpose);
            return statement;
        }, routeKeyHolder);
        long routeId = generatedKey(routeKeyHolder);
        KeyHolder registryKeyHolder = new GeneratedKeyHolder();
        jdbc().update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO `skit_ad_callback_route_registry` "
                            + "(`key_hash`,`route_type`,`provider_callback_route_id`,`registered_at`) "
                            + "VALUES (UNHEX(SHA2(?,256)),'PROVIDER_CALLBACK_ROUTE',?,CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "route-key-" + routeId);
            statement.setLong(2, routeId);
            return statement;
        }, registryKeyHolder);
        long registryId = generatedKey(registryKeyHolder);
        assertEquals(1, jdbc().update("UPDATE `skit_ad_provider_callback_route` SET `state`='ISSUED',"
                        + "`route_slot`=?,`callback_route_registry_id`=?,"
                        + "`callback_key_fingerprint`='0123456789abcdef',"
                        + "`canonical_origin`='https://callback.example.com',"
                        + "`callback_path_version`=1,`callback_template_version`=1,"
                        + "`callback_origin_fingerprint`=UNHEX(REPEAT('22',32)),"
                        + "`callback_contract_fingerprint`=UNHEX(REPEAT('33',32)),"
                        + "`issued_at`=CURRENT_TIMESTAMP,`issued_by_user_id`=7 WHERE `id`=?",
                slot, registryId, routeId));
        return routeId;
    }

    private long generatedKey(KeyHolder keyHolder) {
        assertNotNull(keyHolder.getKey());
        return keyHolder.getKey().longValue();
    }

    private String inboxInsertSql() {
        return "INSERT INTO `skit_provider_impression_inbox` "
                + "(`provider_connection_id`,`dedupe_scheme`,`dedupe_key_hash`,"
                + "`provider_request_id_lexical`,`adsource_id_lexical`,`material_integrity_hash`,"
                + "`authentication_level`,`integrity_status`,`integrity_revision`,`processing_status`,"
                + "`quarantine_reason`,`processing_attempt_count`,`first_received_at`,"
                + "`last_received_at`,`processed_at`) VALUES (?, ?,UNHEX(REPEAT('aa',32)),?,?,"
                + "UNHEX(REPEAT('bb',32)),'UNSIGNED_PROVIDER_OBSERVATION','CANONICAL',0,?,?,0,"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)";
    }

    private String attemptInsertSql(String payloadExpiresAtExpression) {
        return attemptInsertSql(payloadExpiresAtExpression, "CURRENT_TIMESTAMP");
    }

    private String attemptInsertSql(String payloadExpiresAtExpression, String receivedAtExpression) {
        return "INSERT INTO `skit_provider_callback_attempt` "
                + "(`correlation_id`,`provider_connection_id`,`inbox_id`,`dedupe_scheme`,`wire_payload_hash`,"
                + "`material_integrity_hash`,`delivery_integrity_status`,`response_decision`,"
                + "`payload_ciphertext`,`payload_nonce`,`payload_key_id`,`payload_purpose`,"
                + "`payload_envelope_version`,`payload_expires_at`,`wire_size_bytes`,`parameter_count`,"
                + "`remote_address_hash`,`user_agent_hash`,`request_header_fingerprint`,`trace_id`,"
                + "`received_at`) VALUES (UUID_TO_BIN(UUID()),?,?,?,UNHEX(REPEAT('44',32)),"
                + "UNHEX(REPEAT('55',32)),?,?,X'01',UNHEX(REPEAT('66',12)),"
                + "'provider-capture','TAKU_IMPRESSION_CAPTURE',1,"
                + payloadExpiresAtExpression + ",128,2,UNHEX(REPEAT('77',32)),"
                + "UNHEX(REPEAT('88',32)),UNHEX(REPEAT('99',32)),REPLACE(UUID(),'-',''),"
                + receivedAtExpression + ")";
    }

    private int purgeAttempt(long attemptId) {
        return jdbc().update(
                "UPDATE `skit_provider_callback_attempt` SET `payload_ciphertext`=NULL,"
                        + "`payload_nonce`=NULL,`payload_key_id`=NULL,`payload_purpose`=NULL,"
                        + "`payload_envelope_version`=NULL,`payload_expires_at`=NULL,"
                        + "`payload_purged_at`=CURRENT_TIMESTAMP WHERE `id`=?", attemptId);
    }

    private String columnDefinition(String table, String column) {
        return jdbc().queryForObject("SELECT CONCAT(`COLUMN_TYPE`,':',`IS_NULLABLE`) "
                        + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME=? AND COLUMN_NAME=?",
                String.class, table, column);
    }

    private String indexDefinition(String table, String index) {
        return jdbc().queryForObject("SELECT CONCAT(MIN(`NON_UNIQUE`),':',"
                        + "GROUP_CONCAT(`COLUMN_NAME` ORDER BY `SEQ_IN_INDEX` SEPARATOR ',')) "
                        + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME=? AND INDEX_NAME=?",
                String.class, table, index);
    }

    private String foreignKeyDefinition(String table, String constraint) {
        return jdbc().queryForObject("SELECT CONCAT(GROUP_CONCAT(`k`.`COLUMN_NAME` "
                        + "ORDER BY `k`.`ORDINAL_POSITION` SEPARATOR ','),'->',"
                        + "MIN(`k`.`REFERENCED_TABLE_NAME`),'(',GROUP_CONCAT(`k`.`REFERENCED_COLUMN_NAME` "
                        + "ORDER BY `k`.`ORDINAL_POSITION` SEPARATOR ','),'):',MIN(`r`.`UPDATE_RULE`),':',"
                        + "MIN(`r`.`DELETE_RULE`)) FROM information_schema.KEY_COLUMN_USAGE `k` "
                        + "JOIN information_schema.REFERENTIAL_CONSTRAINTS `r` "
                        + "ON `r`.`CONSTRAINT_SCHEMA`=`k`.`CONSTRAINT_SCHEMA` "
                        + "AND `r`.`CONSTRAINT_NAME`=`k`.`CONSTRAINT_NAME` "
                        + "AND `r`.`TABLE_NAME`=`k`.`TABLE_NAME` WHERE `k`.`TABLE_SCHEMA`=DATABASE() "
                        + "AND `k`.`TABLE_NAME`=? AND `k`.`CONSTRAINT_NAME`=? "
                        + "AND `k`.`REFERENCED_TABLE_NAME` IS NOT NULL",
                String.class, table, constraint);
    }

    private String checkDefinition(String table, String constraint) {
        return jdbc().queryForObject("SELECT `cc`.`CHECK_CLAUSE` FROM "
                        + "information_schema.TABLE_CONSTRAINTS `tc` "
                        + "JOIN information_schema.CHECK_CONSTRAINTS `cc` "
                        + "ON `cc`.`CONSTRAINT_SCHEMA`=`tc`.`CONSTRAINT_SCHEMA` "
                        + "AND `cc`.`CONSTRAINT_NAME`=`tc`.`CONSTRAINT_NAME` "
                        + "WHERE `tc`.`TABLE_SCHEMA`=DATABASE() AND `tc`.`TABLE_NAME`=? "
                        + "AND `tc`.`CONSTRAINT_NAME`=? AND `tc`.`CONSTRAINT_TYPE`='CHECK'",
                String.class, table, constraint);
    }

    private String triggerDefinition(String trigger) {
        return jdbc().queryForObject("SELECT `ACTION_STATEMENT` FROM information_schema.TRIGGERS "
                        + "WHERE `TRIGGER_SCHEMA`=DATABASE() AND `TRIGGER_NAME`=?",
                String.class, trigger);
    }

}
