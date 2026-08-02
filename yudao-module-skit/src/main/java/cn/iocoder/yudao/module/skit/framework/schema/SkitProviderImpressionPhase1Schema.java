package cn.iocoder.yudao.module.skit.framework.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Additive, global schema description for phase-1 account-level provider callback capture.
 *
 * <p>This class deliberately exposes package-safe data descriptions instead of the initializer's
 * private {@code SchemaStep} type. It owns no execution behavior, so the published advertising
 * baseline and its migration manifests remain untouched.</p>
 */
final class SkitProviderImpressionPhase1Schema {

    private static final String TABLE_OPTIONS =
            " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private SkitProviderImpressionPhase1Schema() {
    }

    static List<Step> steps() {
        List<Step> steps = new ArrayList<>();
        steps.add(Step.createTable(providerConnectionTable()));
        steps.add(Step.createTable(providerCallbackRouteTable()));
        steps.add(Step.createTable(callbackRouteRegistryTable()));
        steps.add(Step.createTable(callbackRouteRegistryMigrationTable()));
        steps.add(Step.createTable(providerImpressionInboxTable()));
        steps.add(Step.createTable(providerCallbackAttemptTable()));
        steps.add(Step.foreignKey("skit_provider_impression_inbox",
                "fk_provider_impression_inbox_canonical_attempt",
                "`provider_connection_id`,`id`,`canonical_attempt_id`",
                "skit_provider_callback_attempt", "`provider_connection_id`,`inbox_id`,`id`"));
        steps.add(Step.foreignKey("skit_ad_provider_callback_route",
                "fk_provider_callback_route_registry", "`id`,`callback_route_registry_id`",
                "skit_ad_callback_route_registry", "`provider_callback_route_id`,`id`"));
        steps.add(Step.foreignKey("skit_ad_provider_connection",
                "fk_provider_connection_active_route", "`id`,`active_callback_route_id`",
                "skit_ad_provider_callback_route", "`provider_connection_id`,`id`"));
        steps.add(Step.createTable(platformProviderCommandAuditTable()));
        steps.add(Step.update("seed-callback-route-registry-migration",
                "INSERT IGNORE INTO `skit_ad_callback_route_registry_migration` "
                        + "(`singleton_id`,`migration_phase`,`phase_revision`,`last_callback_key_id`,"
                        + "`started_at`,`created_at`,`updated_at`) "
                        + "VALUES (1,'DUAL_WRITE',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"));
        steps.add(Step.trigger("skit_ad_provider_connection",
                "trg_provider_connection_lifecycle_immutable", "UPDATE",
                providerConnectionLifecycleAction()));
        steps.add(Step.trigger("skit_ad_provider_callback_route",
                "trg_provider_callback_route_no_self_supersedes", "INSERT",
                "BEGIN IF NEW.`supersedes_callback_route_id` IS NOT NULL AND NEW.`id`<>0 "
                        + "AND NEW.`supersedes_callback_route_id`=NEW.`id` THEN "
                        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'provider callback route cannot supersede itself'; "
                        + "END IF; END"));
        steps.add(Step.trigger("skit_ad_provider_callback_route",
                "trg_provider_callback_route_lifecycle_immutable", "UPDATE",
                "BEGIN IF NOT (NEW.`provider_connection_id` <=> OLD.`provider_connection_id`) "
                        + "OR NOT (NEW.`route_version` <=> OLD.`route_version`) "
                        + "OR NOT (NEW.`purpose` <=> OLD.`purpose`) "
                        + "OR NOT (NEW.`supersedes_callback_route_id` <=> OLD.`supersedes_callback_route_id`) "
                        + "OR NEW.`supersedes_callback_route_id`=OLD.`id` "
                        + "OR NOT (NEW.`state`=OLD.`state` OR "
                        + "(OLD.`state`='DRAFT' AND NEW.`state`='ISSUED') OR "
                        + "(OLD.`state`='ISSUED' AND NEW.`state` IN ('SUBMITTED','BLOCKED','ABANDONED')) OR "
                        + "(OLD.`state`='SUBMITTED' AND NEW.`state` IN ('ACTIVE','BLOCKED')) OR "
                        + "(OLD.`state`='ACTIVE' AND NEW.`state` IN ('BLOCKED','RETIRED')) OR "
                        + "(OLD.`state`='BLOCKED' AND NEW.`state`='RETIRED')) "
                        + "OR (OLD.`state` <> 'DRAFT' AND NOT ("
                        + "NEW.`callback_route_registry_id` <=> OLD.`callback_route_registry_id` AND "
                        + "NEW.`callback_key_fingerprint` <=> OLD.`callback_key_fingerprint` AND "
                        + "NEW.`canonical_origin` <=> OLD.`canonical_origin` AND "
                        + "NEW.`callback_path_version` <=> OLD.`callback_path_version` AND "
                        + "NEW.`callback_template_version` <=> OLD.`callback_template_version` AND "
                        + "NEW.`callback_origin_fingerprint` <=> OLD.`callback_origin_fingerprint` AND "
                        + "NEW.`callback_contract_fingerprint` <=> OLD.`callback_contract_fingerprint` AND "
                        + "NEW.`issued_at` <=> OLD.`issued_at` AND "
                        + "NEW.`issued_by_user_id` <=> OLD.`issued_by_user_id`)) "
                        + "OR (OLD.`submitted_at` IS NOT NULL AND NOT ("
                        + "NEW.`submission_ticket` <=> OLD.`submission_ticket` AND "
                        + "NEW.`submission_reference` <=> OLD.`submission_reference` AND "
                        + "NEW.`submission_recipient` <=> OLD.`submission_recipient` AND "
                        + "NEW.`submitted_by_user_id` <=> OLD.`submitted_by_user_id` AND "
                        + "NEW.`submitted_at` <=> OLD.`submitted_at`)) "
                        + "OR (OLD.`activated_at` IS NOT NULL "
                        + "AND NOT (NEW.`activated_at` <=> OLD.`activated_at`)) "
                        + "OR (OLD.`blocked_at` IS NOT NULL AND NOT (NEW.`blocked_at` <=> OLD.`blocked_at`)) "
                        + "OR (OLD.`abandoned_at` IS NOT NULL "
                        + "AND NOT (NEW.`abandoned_at` <=> OLD.`abandoned_at`)) "
                        + "OR (OLD.`retired_at` IS NOT NULL AND NOT (NEW.`retired_at` <=> OLD.`retired_at`)) "
                        + "OR NOT ((NEW.`accept_until` <=> OLD.`accept_until`) OR "
                        + "(OLD.`accept_until` IS NULL AND NEW.`accept_until` IS NOT NULL)) "
                        + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'provider callback route lifecycle is immutable'; "
                        + "END IF; END"));
        steps.add(Step.trigger("skit_ad_callback_route_registry_migration",
                "trg_callback_route_registry_migration_monotonic", "UPDATE",
                "BEGIN IF NEW.`singleton_id` <> OLD.`singleton_id` "
                        + "OR NEW.`last_callback_key_id` < OLD.`last_callback_key_id` "
                        + "OR NEW.`phase_revision` <> OLD.`phase_revision` + 1 "
                        + "OR NOT (NEW.`started_at` <=> OLD.`started_at`) "
                        + "OR OLD.`migration_phase`='ENFORCED' "
                        + "OR NOT (NEW.`migration_phase`=OLD.`migration_phase` OR "
                        + "(OLD.`migration_phase`='DUAL_WRITE' AND NEW.`migration_phase`='BACKFILL') OR "
                        + "(OLD.`migration_phase`='BACKFILL' AND NEW.`migration_phase`='VERIFY') OR "
                        + "(OLD.`migration_phase`='VERIFY' AND NEW.`migration_phase`='SHADOW_READ') OR "
                        + "(OLD.`migration_phase`='SHADOW_READ' AND NEW.`migration_phase`='HASH_FIRST') OR "
                        + "(OLD.`migration_phase`='HASH_FIRST' AND NEW.`migration_phase`='ENFORCED')) "
                        + "OR (OLD.`blocked_at` IS NOT NULL AND NEW.`migration_phase`<>OLD.`migration_phase`) "
                        + "OR (OLD.`verified_at` IS NOT NULL AND NOT ("
                        + "NEW.`expected_row_count` <=> OLD.`expected_row_count` AND "
                        + "NEW.`verified_row_count` <=> OLD.`verified_row_count` AND "
                        + "NEW.`verification_mismatch_count` <=> OLD.`verification_mismatch_count` AND "
                        + "NEW.`verification_hash` <=> OLD.`verification_hash` AND "
                        + "NEW.`verified_at` <=> OLD.`verified_at`)) "
                        + "OR (OLD.`migration_phase`='VERIFY' AND NEW.`migration_phase`='SHADOW_READ' "
                        + "AND NOT (NEW.`expected_row_count`=NEW.`verified_row_count` "
                        + "AND NEW.`verification_mismatch_count`=0 "
                        + "AND NEW.`verification_hash` IS NOT NULL AND NEW.`verified_at` IS NOT NULL)) "
                        + "OR (NEW.`migration_phase`='ENFORCED' AND NEW.`completed_at` IS NULL) "
                        + "OR (NEW.`migration_phase`<>'ENFORCED' AND NEW.`completed_at` IS NOT NULL) "
                        + "OR (OLD.`completed_at` IS NOT NULL "
                        + "AND NOT (NEW.`completed_at` <=> OLD.`completed_at`)) "
                        + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'callback registry migration state is monotonic'; "
                        + "END IF; END"));
        steps.add(Step.trigger("skit_ad_callback_route_registry_migration",
                "trg_callback_route_registry_migration_no_delete", "DELETE",
                "BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'callback registry migration state cannot be deleted'; END"));
        steps.add(Step.trigger("skit_ad_callback_route_registry",
                "trg_callback_route_registry_immutable", "UPDATE",
                "BEGIN IF NOT (NEW.`key_hash` <=> OLD.`key_hash`) "
                        + "OR NOT (NEW.`route_type` <=> OLD.`route_type`) "
                        + "OR NOT (NEW.`provider_callback_route_id` <=> OLD.`provider_callback_route_id`) "
                        + "OR NOT (NEW.`tenant_callback_key_id` <=> OLD.`tenant_callback_key_id`) "
                        + "OR NOT (NEW.`registered_at` <=> OLD.`registered_at`) "
                        + "OR NOT ((NEW.`tombstoned_at` <=> OLD.`tombstoned_at`) OR "
                        + "(OLD.`tombstoned_at` IS NULL AND NEW.`tombstoned_at` IS NOT NULL)) "
                        + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'callback route registry is immutable'; "
                        + "END IF; END"));
        steps.add(Step.trigger("skit_ad_callback_route_registry",
                "trg_callback_route_registry_no_delete", "DELETE",
                "BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'callback route registry cannot be deleted'; END"));
        steps.add(Step.trigger("skit_platform_provider_command_audit",
                "trg_platform_provider_command_audit_immutable", "UPDATE",
                "BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'platform provider command audit is append only'; END"));
        steps.add(Step.trigger("skit_platform_provider_command_audit",
                "trg_platform_provider_command_audit_no_delete", "DELETE",
                "BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'platform provider command audit cannot be deleted'; END"));
        steps.add(Step.trigger("skit_provider_impression_inbox",
                "trg_provider_impression_inbox_monotonic", "UPDATE", inboxMonotonicAction()));
        steps.add(Step.trigger("skit_provider_callback_attempt",
                "trg_provider_callback_attempt_immutable", "UPDATE",
                providerCallbackAttemptImmutableAction()));
        steps.add(Step.trigger("skit_provider_callback_attempt",
                "trg_provider_callback_attempt_no_delete", "DELETE",
                "BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'provider callback attempts cannot be deleted'; END"));
        return Collections.unmodifiableList(steps);
    }

    private static String providerConnectionLifecycleAction() {
        return "BEGIN IF NOT (NEW.`id` <=> OLD.`id`) "
                + "OR NOT (NEW.`connection_code` <=> OLD.`connection_code`) "
                + "OR NOT (NEW.`provider` <=> OLD.`provider`) "
                + "OR NOT (NEW.`account_mode` <=> OLD.`account_mode`) "
                + "OR NOT (NEW.`owner_tenant_id` <=> OLD.`owner_tenant_id`) "
                + "OR NOT (NEW.`owner_ad_account_id` <=> OLD.`owner_ad_account_id`) "
                + "OR NOT (NEW.`external_account_ref_hash` <=> OLD.`external_account_ref_hash`) "
                + "OR NOT (NEW.`created_by_user_id` <=> OLD.`created_by_user_id`) "
                + "OR NOT (NEW.`created_at` <=> OLD.`created_at`) "
                + "OR NOT (NEW.`state`=OLD.`state` OR "
                + "(OLD.`state`='CONFIGURING' AND NEW.`state` IN ('ACTIVE','BLOCKED','RETIRED')) OR "
                + "(OLD.`state`='ACTIVE' AND NEW.`state` IN ('MIGRATING','BLOCKED','RETIRED')) OR "
                + "(OLD.`state`='MIGRATING' AND NEW.`state` IN ('ACTIVE','BLOCKED','RETIRED')) OR "
                + "(OLD.`state`='BLOCKED' AND NEW.`state`='RETIRED')) "
                + "OR (OLD.`activated_at` IS NOT NULL "
                + "AND NOT (NEW.`activated_at` <=> OLD.`activated_at`)) "
                + "OR (OLD.`blocked_at` IS NOT NULL "
                + "AND NOT (NEW.`blocked_at` <=> OLD.`blocked_at`)) "
                + "OR (OLD.`retired_at` IS NOT NULL "
                + "AND NOT (NEW.`retired_at` <=> OLD.`retired_at`)) "
                + "THEN SIGNAL SQLSTATE '45000' "
                + "SET MESSAGE_TEXT = 'provider connection lifecycle is immutable'; END IF; END";
    }

    private static String inboxMonotonicAction() {
        return "BEGIN IF NOT (NEW.`provider_connection_id` <=> OLD.`provider_connection_id` "
                + "AND NEW.`dedupe_scheme` <=> OLD.`dedupe_scheme` "
                + "AND NEW.`dedupe_key_hash` <=> OLD.`dedupe_key_hash` "
                + "AND NEW.`provider_request_id_lexical` <=> OLD.`provider_request_id_lexical` "
                + "AND NEW.`adsource_id_lexical` <=> OLD.`adsource_id_lexical` "
                + "AND NEW.`material_integrity_hash` <=> OLD.`material_integrity_hash` "
                + "AND NEW.`authentication_level` <=> OLD.`authentication_level` "
                + "AND NEW.`first_received_at` <=> OLD.`first_received_at`) "
                + "OR NOT ((NEW.`canonical_attempt_id` <=> OLD.`canonical_attempt_id`) OR "
                + "(OLD.`canonical_attempt_id` IS NULL AND NEW.`canonical_attempt_id` IS NOT NULL)) "
                + "OR NEW.`processing_attempt_count` < OLD.`processing_attempt_count` "
                + "OR NEW.`last_received_at` < OLD.`last_received_at` "
                + "OR NOT (((NEW.`integrity_status` <=> OLD.`integrity_status`) "
                + "AND NEW.`integrity_revision`=OLD.`integrity_revision` "
                + "AND (NEW.`integrity_conflict_at` <=> OLD.`integrity_conflict_at`)) OR "
                + "(OLD.`integrity_status`='CANONICAL' AND NEW.`integrity_status`='PAYLOAD_CONFLICT' "
                + "AND NEW.`integrity_revision`=OLD.`integrity_revision`+1 "
                + "AND OLD.`integrity_conflict_at` IS NULL AND NEW.`integrity_conflict_at` IS NOT NULL) OR "
                + "(OLD.`integrity_status`='PAYLOAD_CONFLICT' "
                + "AND NEW.`integrity_status`='PAYLOAD_CONFLICT' "
                + "AND (NEW.`integrity_revision`=OLD.`integrity_revision` OR "
                + "NEW.`integrity_revision`=OLD.`integrity_revision`+1) "
                + "AND NEW.`integrity_conflict_at` <=> OLD.`integrity_conflict_at`)) "
                + "OR (NEW.`dedupe_scheme`='FALLBACK_WIRE_V1' "
                + "AND NEW.`processing_status`<>'QUARANTINED') "
                + "OR (OLD.`processing_status` IN ('SUCCEEDED','QUARANTINED','DEAD_LETTER') "
                + "AND NEW.`processing_status`<>OLD.`processing_status`) "
                + "OR NOT (NEW.`processing_status`=OLD.`processing_status` OR "
                + "(OLD.`processing_status`='PENDING' AND NEW.`processing_status` IN ('PROCESSING','QUARANTINED')) OR "
                + "(OLD.`processing_status`='PROCESSING' AND NEW.`processing_status` "
                + "IN ('RETRY_WAIT','SUCCEEDED','QUARANTINED','DEAD_LETTER')) OR "
                + "(OLD.`processing_status`='RETRY_WAIT' AND NEW.`processing_status`='PROCESSING')) "
                + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'provider impression inbox is monotonic'; "
                + "END IF; END";
    }

    private static String providerCallbackAttemptImmutableAction() {
        return "BEGIN DECLARE eligible_inbox_count INT DEFAULT 0; "
                + "IF NOT (NEW.`id` <=> OLD.`id` AND "
                + "NEW.`correlation_id` <=> OLD.`correlation_id` AND "
                + "NEW.`provider_connection_id` <=> OLD.`provider_connection_id` AND "
                + "NEW.`inbox_id` <=> OLD.`inbox_id` AND "
                + "NEW.`dedupe_scheme` <=> OLD.`dedupe_scheme` AND "
                + "NEW.`wire_payload_hash` <=> OLD.`wire_payload_hash` AND "
                + "NEW.`material_integrity_hash` <=> OLD.`material_integrity_hash` AND "
                + "NEW.`delivery_integrity_status` <=> OLD.`delivery_integrity_status` AND "
                + "NEW.`response_decision` <=> OLD.`response_decision` AND "
                + "NEW.`wire_size_bytes` <=> OLD.`wire_size_bytes` AND "
                + "NEW.`parameter_count` <=> OLD.`parameter_count` AND "
                + "NEW.`remote_address_hash` <=> OLD.`remote_address_hash` AND "
                + "NEW.`user_agent_hash` <=> OLD.`user_agent_hash` AND "
                + "NEW.`request_header_fingerprint` <=> OLD.`request_header_fingerprint` AND "
                + "NEW.`trace_id` <=> OLD.`trace_id` AND "
                + "NEW.`received_at` <=> OLD.`received_at`) THEN "
                + "SIGNAL SQLSTATE '45000' "
                + "SET MESSAGE_TEXT = 'provider callback attempt identity is immutable'; END IF; "
                + "IF NOT (NEW.`payload_ciphertext` <=> OLD.`payload_ciphertext` AND "
                + "NEW.`payload_nonce` <=> OLD.`payload_nonce` AND "
                + "NEW.`payload_key_id` <=> OLD.`payload_key_id` AND "
                + "NEW.`payload_purpose` <=> OLD.`payload_purpose` AND "
                + "NEW.`payload_envelope_version` <=> OLD.`payload_envelope_version` AND "
                + "NEW.`payload_expires_at` <=> OLD.`payload_expires_at` AND "
                + "NEW.`payload_purged_at` <=> OLD.`payload_purged_at`) THEN "
                + "IF NOT (OLD.`payload_ciphertext` IS NOT NULL "
                + "AND OLD.`payload_nonce` IS NOT NULL AND OLD.`payload_key_id` IS NOT NULL "
                + "AND OLD.`payload_purpose` IS NOT NULL "
                + "AND OLD.`payload_envelope_version` IS NOT NULL "
                + "AND OLD.`payload_expires_at` IS NOT NULL AND OLD.`payload_purged_at` IS NULL "
                + "AND NEW.`payload_ciphertext` IS NULL AND NEW.`payload_nonce` IS NULL "
                + "AND NEW.`payload_key_id` IS NULL AND NEW.`payload_purpose` IS NULL "
                + "AND NEW.`payload_envelope_version` IS NULL "
                + "AND NEW.`payload_expires_at` IS NULL AND NEW.`payload_purged_at` IS NOT NULL "
                + "AND OLD.`payload_expires_at`<=NEW.`payload_purged_at` "
                + "AND NEW.`payload_purged_at`<=CURRENT_TIMESTAMP) THEN "
                + "SIGNAL SQLSTATE '45000' "
                + "SET MESSAGE_TEXT = 'provider callback attempt is not purge eligible'; END IF; "
                + "SELECT COUNT(*) INTO eligible_inbox_count "
                + "FROM `skit_provider_impression_inbox` WHERE `provider_connection_id`=OLD.`provider_connection_id` "
                + "AND `id`=OLD.`inbox_id` AND ((`processing_status` IN ('SUCCEEDED','QUARANTINED') "
                + "AND `processed_at` IS NOT NULL) OR (`processing_status`='DEAD_LETTER' "
                + "AND `processed_at` IS NOT NULL AND `dead_letter_alerted_at` IS NOT NULL)); "
                + "IF eligible_inbox_count<>1 THEN SIGNAL SQLSTATE '45000' "
                + "SET MESSAGE_TEXT = 'provider callback attempt inbox is not purge eligible'; END IF; "
                + "END IF; END";
    }

    private static String providerConnectionTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_provider_connection` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,"
                + "`connection_code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                + "`provider` varchar(16) NOT NULL,`account_mode` varchar(32) NOT NULL,"
                + "`owner_tenant_id` bigint DEFAULT NULL,`owner_ad_account_id` bigint DEFAULT NULL,"
                + "`external_account_ref_hash` binary(32) NOT NULL,"
                + "`active_callback_route_id` bigint DEFAULT NULL,"
                + "`state` varchar(32) NOT NULL DEFAULT 'CONFIGURING',"
                + "`non_terminal_shared_master_slot` varchar(64) GENERATED ALWAYS AS "
                + "(CASE WHEN `provider`='TAKU' AND `account_mode`='SHARED_MASTER' "
                + "AND `state`<>'RETIRED' THEN 'TAKU:SHARED_MASTER' ELSE NULL END) STORED,"
                + "`activated_at` datetime DEFAULT NULL,`blocked_at` datetime DEFAULT NULL,"
                + "`retired_at` datetime DEFAULT NULL,"
                + "`created_by_user_id` bigint NOT NULL,`created_at` datetime NOT NULL,"
                + "`updated_by_user_id` bigint NOT NULL,`updated_at` datetime NOT NULL,"
                + "PRIMARY KEY (`id`),"
                + "UNIQUE KEY `uk_provider_connection_code` (`connection_code`),"
                + "UNIQUE KEY `uk_provider_connection_external_account` "
                + "(`provider`,`external_account_ref_hash`),"
                + "UNIQUE KEY `uk_provider_connection_active_route` (`active_callback_route_id`),"
                + "UNIQUE KEY `uk_provider_connection_shared_master` (`non_terminal_shared_master_slot`),"
                + "KEY `idx_provider_connection_state` (`provider`,`account_mode`,`state`,`id`),"
                + "CONSTRAINT `ck_provider_connection_provider` CHECK (`provider`='TAKU'),"
                + "CONSTRAINT `ck_provider_connection_mode` CHECK "
                + "(`account_mode` IN ('SHARED_MASTER','TENANT_OWNED')),"
                + "CONSTRAINT `ck_provider_connection_owner` CHECK "
                + "((`account_mode`='SHARED_MASTER' AND `owner_tenant_id` IS NULL "
                + "AND `owner_ad_account_id` IS NULL) OR (`account_mode`='TENANT_OWNED' "
                + "AND `owner_tenant_id` IS NOT NULL)),"
                + "CONSTRAINT `ck_provider_connection_state` CHECK "
                + "(`state` IN ('CONFIGURING','ACTIVE','MIGRATING','BLOCKED','RETIRED')),"
                + "CONSTRAINT `ck_provider_connection_lifecycle` CHECK "
                + "((`state`='CONFIGURING' AND `active_callback_route_id` IS NULL "
                + "AND `activated_at` IS NULL AND `blocked_at` IS NULL AND `retired_at` IS NULL) OR "
                + "(`state` IN ('ACTIVE','MIGRATING') AND `active_callback_route_id` IS NOT NULL "
                + "AND `activated_at` IS NOT NULL AND `blocked_at` IS NULL AND `retired_at` IS NULL) OR "
                + "(`state`='BLOCKED' AND `active_callback_route_id` IS NULL "
                + "AND `blocked_at` IS NOT NULL AND `retired_at` IS NULL) OR "
                + "(`state`='RETIRED' AND `active_callback_route_id` IS NULL AND `retired_at` IS NOT NULL)))"
                + TABLE_OPTIONS + " COMMENT='Skit 阶段 1 全局 provider connection'";
    }

    private static String providerCallbackRouteTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_provider_callback_route` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`provider_connection_id` bigint NOT NULL,"
                + "`route_version` int NOT NULL,`callback_route_registry_id` bigint DEFAULT NULL,"
                + "`supersedes_callback_route_id` bigint DEFAULT NULL,"
                + "`callback_key_fingerprint` char(16) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,"
                + "`canonical_origin` varchar(255) DEFAULT NULL,`callback_path_version` smallint DEFAULT NULL,"
                + "`callback_template_version` smallint DEFAULT NULL,"
                + "`callback_origin_fingerprint` binary(32) DEFAULT NULL,"
                + "`callback_contract_fingerprint` binary(32) DEFAULT NULL,"
                + "`purpose` varchar(16) NOT NULL,`state` varchar(16) NOT NULL DEFAULT 'DRAFT',"
                + "`route_slot` varchar(32) NOT NULL DEFAULT 'INACTIVE',"
                + "`occupied_route_slot` varchar(32) GENERATED ALWAYS AS (CASE WHEN "
                + "`route_slot`<>'INACTIVE' AND `state` NOT IN ('ABANDONED','RETIRED') "
                + "THEN `route_slot` ELSE NULL END) STORED,"
                + "`issued_at` datetime DEFAULT NULL,`issued_by_user_id` bigint DEFAULT NULL,"
                + "`submission_ticket` varchar(128) DEFAULT NULL,"
                + "`submission_reference` varchar(128) DEFAULT NULL,"
                + "`submission_recipient` varchar(255) DEFAULT NULL,"
                + "`submitted_by_user_id` bigint DEFAULT NULL,`submitted_at` datetime DEFAULT NULL,"
                + "`activated_at` datetime DEFAULT NULL,`accept_until` datetime DEFAULT NULL,"
                + "`blocked_at` datetime DEFAULT NULL,`abandoned_at` datetime DEFAULT NULL,"
                + "`retired_at` datetime DEFAULT NULL,"
                + "`created_by_user_id` bigint NOT NULL,`created_at` datetime NOT NULL,"
                + "`updated_by_user_id` bigint NOT NULL,`updated_at` datetime NOT NULL,"
                + "PRIMARY KEY (`id`),"
                + "UNIQUE KEY `uk_provider_callback_route_connection_id` (`provider_connection_id`,`id`),"
                + "UNIQUE KEY `uk_provider_callback_route_version` (`provider_connection_id`,`route_version`),"
                + "UNIQUE KEY `uk_provider_callback_route_registry` (`callback_route_registry_id`),"
                + "UNIQUE KEY `uk_provider_callback_route_supersedes` (`supersedes_callback_route_id`),"
                + "UNIQUE KEY `uk_provider_callback_route_slot` (`provider_connection_id`,`occupied_route_slot`),"
                + "KEY `idx_provider_callback_route_state` (`provider_connection_id`,`state`,`id`),"
                + "CONSTRAINT `fk_provider_callback_route_connection` FOREIGN KEY (`provider_connection_id`) "
                + "REFERENCES `skit_ad_provider_connection` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_provider_callback_route_supersedes` "
                + "FOREIGN KEY (`provider_connection_id`,`supersedes_callback_route_id`) "
                + "REFERENCES `skit_ad_provider_callback_route` (`provider_connection_id`,`id`) "
                + "ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_provider_callback_route_version` CHECK (`route_version`>0),"
                + "CONSTRAINT `ck_provider_callback_route_purpose` CHECK "
                + "(`purpose` IN ('GATE_TEST','PRODUCTION')),"
                + "CONSTRAINT `ck_provider_callback_route_state` CHECK "
                + "(`state` IN ('DRAFT','ISSUED','SUBMITTED','ACTIVE','BLOCKED','ABANDONED','RETIRED')),"
                + "CONSTRAINT `ck_provider_callback_route_slot` CHECK "
                + "(`route_slot` IN ('PRIMARY_ACCEPTING','MIGRATION_TARGET','INACTIVE')),"
                + "CONSTRAINT `ck_provider_callback_route_supersedes` CHECK "
                + "(`supersedes_callback_route_id` IS NULL OR "
                + "`purpose`='PRODUCTION'),"
                + "CONSTRAINT `ck_provider_callback_route_identity` CHECK "
                + "((`state`='DRAFT' AND `callback_route_registry_id` IS NULL "
                + "AND `callback_key_fingerprint` IS NULL AND `canonical_origin` IS NULL "
                + "AND `callback_path_version` IS NULL AND `callback_template_version` IS NULL "
                + "AND `callback_origin_fingerprint` IS NULL "
                + "AND `callback_contract_fingerprint` IS NULL "
                + "AND `issued_at` IS NULL AND `issued_by_user_id` IS NULL) OR "
                + "(`state`<>'DRAFT' AND `callback_route_registry_id` IS NOT NULL "
                + "AND REGEXP_LIKE(`callback_key_fingerprint`,'^[0-9a-f]{16}$') "
                + "AND CHAR_LENGTH(TRIM(`canonical_origin`)) BETWEEN 1 AND 255 "
                + "AND `callback_path_version`>0 AND `callback_template_version`>0 "
                + "AND `callback_origin_fingerprint` IS NOT NULL "
                + "AND `callback_contract_fingerprint` IS NOT NULL "
                + "AND `issued_at` IS NOT NULL AND `issued_by_user_id` IS NOT NULL)),"
                + "CONSTRAINT `ck_provider_callback_route_submission` CHECK "
                + "((`submission_ticket` IS NULL AND `submission_reference` IS NULL "
                + "AND `submission_recipient` IS NULL AND `submitted_by_user_id` IS NULL "
                + "AND `submitted_at` IS NULL) OR (`submission_ticket` IS NOT NULL "
                + "AND CHAR_LENGTH(TRIM(`submission_ticket`)) BETWEEN 1 AND 128 "
                + "AND `submission_reference` IS NOT NULL "
                + "AND CHAR_LENGTH(TRIM(`submission_reference`)) BETWEEN 1 AND 128 "
                + "AND `submission_recipient` IS NOT NULL "
                + "AND CHAR_LENGTH(TRIM(`submission_recipient`)) BETWEEN 1 AND 255 "
                + "AND `submitted_by_user_id` IS NOT NULL AND `submitted_at` IS NOT NULL)),"
                + "CONSTRAINT `ck_provider_callback_route_lifecycle` CHECK "
                + "((`state`='DRAFT' AND `route_slot`='INACTIVE' AND `submitted_at` IS NULL "
                + "AND `activated_at` IS NULL AND `blocked_at` IS NULL AND `abandoned_at` IS NULL "
                + "AND `retired_at` IS NULL) OR (`state`='ISSUED' "
                + "AND `route_slot` IN ('PRIMARY_ACCEPTING','MIGRATION_TARGET','INACTIVE') "
                + "AND `submitted_at` IS NULL "
                + "AND `activated_at` IS NULL AND `blocked_at` IS NULL AND `abandoned_at` IS NULL "
                + "AND `retired_at` IS NULL) OR (`state`='SUBMITTED' AND `purpose`='PRODUCTION' "
                + "AND `route_slot` IN ('PRIMARY_ACCEPTING','MIGRATION_TARGET') "
                + "AND `submitted_at` IS NOT NULL "
                + "AND `activated_at` IS NULL AND `blocked_at` IS NULL AND `abandoned_at` IS NULL "
                + "AND `retired_at` IS NULL) OR (`state`='ACTIVE' AND `purpose`='PRODUCTION' "
                + "AND `route_slot`='PRIMARY_ACCEPTING' AND `submitted_at` IS NOT NULL "
                + "AND `activated_at` IS NOT NULL AND `blocked_at` IS NULL AND `abandoned_at` IS NULL "
                + "AND `retired_at` IS NULL) OR (`state`='BLOCKED' AND `route_slot`='INACTIVE' "
                + "AND `blocked_at` IS NOT NULL AND `abandoned_at` IS NULL AND `retired_at` IS NULL) OR "
                + "(`state`='ABANDONED' AND `route_slot`='INACTIVE' AND `submitted_at` IS NULL "
                + "AND `activated_at` IS NULL AND `blocked_at` IS NULL AND `abandoned_at` IS NOT NULL "
                + "AND `retired_at` IS NULL) OR (`state`='RETIRED' AND `route_slot`='INACTIVE' "
                + "AND `abandoned_at` IS NULL AND `retired_at` IS NOT NULL)),"
                + "CONSTRAINT `ck_provider_callback_route_gate_state` CHECK "
                + "(`purpose`<>'GATE_TEST' OR `state` NOT IN ('SUBMITTED','ACTIVE')))"
                + TABLE_OPTIONS + " COMMENT='Skit 阶段 1 全局 provider callback route'";
    }

    private static String callbackRouteRegistryTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_callback_route_registry` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`key_hash` binary(32) NOT NULL,"
                + "`route_type` varchar(32) NOT NULL,`provider_callback_route_id` bigint DEFAULT NULL,"
                + "`tenant_callback_key_id` bigint DEFAULT NULL,`registered_at` datetime NOT NULL,"
                + "`tombstoned_at` datetime DEFAULT NULL,"
                + "PRIMARY KEY (`id`),"
                + "UNIQUE KEY `uk_callback_route_registry_key_hash` (`key_hash`),"
                + "UNIQUE KEY `uk_callback_route_registry_provider_route` (`provider_callback_route_id`),"
                + "UNIQUE KEY `uk_callback_route_registry_provider_route_id` "
                + "(`provider_callback_route_id`,`id`),"
                + "UNIQUE KEY `uk_callback_route_registry_tenant_key` (`tenant_callback_key_id`),"
                + "CONSTRAINT `fk_callback_route_registry_provider_route` "
                + "FOREIGN KEY (`provider_callback_route_id`) REFERENCES `skit_ad_provider_callback_route` (`id`) "
                + "ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_callback_route_registry_tenant_key` FOREIGN KEY (`tenant_callback_key_id`) "
                + "REFERENCES `skit_ad_callback_key` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_callback_route_registry_route_type` CHECK "
                + "(`route_type` IN ('PROVIDER_CALLBACK_ROUTE','TENANT_CALLBACK_KEY')),"
                + "CONSTRAINT `ck_callback_route_registry_route_xor` CHECK "
                + "((`route_type`='PROVIDER_CALLBACK_ROUTE' AND `provider_callback_route_id` IS NOT NULL "
                + "AND `tenant_callback_key_id` IS NULL) OR (`route_type`='TENANT_CALLBACK_KEY' "
                + "AND `provider_callback_route_id` IS NULL AND `tenant_callback_key_id` IS NOT NULL)))"
                + TABLE_OPTIONS + " COMMENT='Skit 阶段 1 全局 callback route registry'";
    }

    private static String callbackRouteRegistryMigrationTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_callback_route_registry_migration` ("
                + "`singleton_id` tinyint NOT NULL,"
                + "`migration_phase` varchar(16) NOT NULL DEFAULT 'DUAL_WRITE',"
                + "`phase_revision` bigint NOT NULL DEFAULT 0,"
                + "`last_callback_key_id` bigint NOT NULL DEFAULT 0,`last_batch_size` int NOT NULL DEFAULT 0,"
                + "`expected_row_count` bigint DEFAULT NULL,`verified_row_count` bigint DEFAULT NULL,"
                + "`verification_mismatch_count` bigint DEFAULT NULL,"
                + "`verification_hash` binary(32) DEFAULT NULL,`verified_at` datetime DEFAULT NULL,"
                + "`blocked_reason_hash` binary(32) DEFAULT NULL,`blocked_at` datetime DEFAULT NULL,"
                + "`started_at` datetime NOT NULL,`completed_at` datetime DEFAULT NULL,"
                + "`created_at` datetime NOT NULL,"
                + "`updated_at` datetime NOT NULL,PRIMARY KEY (`singleton_id`),"
                + "CONSTRAINT `ck_callback_route_registry_migration_singleton` CHECK (`singleton_id`=1),"
                + "CONSTRAINT `ck_callback_route_registry_migration_phase` CHECK "
                + "(`migration_phase` IN "
                + "('DUAL_WRITE','BACKFILL','VERIFY','SHADOW_READ','HASH_FIRST','ENFORCED')),"
                + "CONSTRAINT `ck_callback_route_registry_migration_revision` CHECK (`phase_revision`>=0),"
                + "CONSTRAINT `ck_callback_route_registry_migration_cursor` CHECK "
                + "(`last_callback_key_id`>=0 AND `last_batch_size`>=0),"
                + "CONSTRAINT `ck_callback_route_registry_migration_verification` CHECK "
                + "((`expected_row_count` IS NULL AND `verified_row_count` IS NULL "
                + "AND `verification_mismatch_count` IS NULL AND `verification_hash` IS NULL "
                + "AND `verified_at` IS NULL) OR (`expected_row_count`>=0 AND `verified_row_count`>=0 "
                + "AND `verification_mismatch_count`>=0 AND `verification_hash` IS NOT NULL "
                + "AND `verified_at` IS NOT NULL)),"
                + "CONSTRAINT `ck_callback_route_registry_migration_blocked` CHECK "
                + "((`blocked_reason_hash` IS NULL AND `blocked_at` IS NULL) OR "
                + "(`blocked_reason_hash` IS NOT NULL AND `blocked_at` IS NOT NULL)),"
                + "CONSTRAINT `ck_callback_route_registry_migration_time` CHECK "
                + "((`migration_phase`<>'ENFORCED' AND `completed_at` IS NULL) OR "
                + "(`migration_phase`='ENFORCED' AND `completed_at` IS NOT NULL "
                + "AND `completed_at`>=`started_at`)))"
                + TABLE_OPTIONS + " COMMENT='Skit 阶段 1 全局 registry migration state'";
    }

    private static String providerImpressionInboxTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_provider_impression_inbox` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`provider_connection_id` bigint NOT NULL,"
                + "`dedupe_scheme` varchar(32) NOT NULL,`dedupe_key_hash` binary(32) NOT NULL,"
                + "`canonical_attempt_id` bigint DEFAULT NULL,"
                + "`provider_request_id_lexical` varchar(512) CHARACTER SET utf8mb4 "
                + "COLLATE utf8mb4_bin DEFAULT NULL,"
                + "`adsource_id_lexical` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,"
                + "`material_integrity_hash` binary(32) DEFAULT NULL,"
                + "`authentication_level` varchar(32) NOT NULL,"
                + "`integrity_status` varchar(32) NOT NULL DEFAULT 'CANONICAL',"
                + "`integrity_revision` bigint NOT NULL DEFAULT 0,`integrity_conflict_at` datetime DEFAULT NULL,"
                + "`processing_status` varchar(32) NOT NULL DEFAULT 'PENDING',"
                + "`quarantine_reason` varchar(64) DEFAULT NULL,"
                + "`lease_owner` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,"
                + "`lease_until` datetime DEFAULT NULL,`processing_attempt_count` int NOT NULL DEFAULT 0,"
                + "`next_attempt_at` datetime DEFAULT NULL,"
                + "`first_received_at` datetime NOT NULL,`last_received_at` datetime NOT NULL,"
                + "`processed_at` datetime DEFAULT NULL,`dead_letter_alerted_at` datetime DEFAULT NULL,"
                + "PRIMARY KEY (`id`),"
                + "UNIQUE KEY `uk_provider_impression_inbox_connection_id` "
                + "(`provider_connection_id`,`id`,`dedupe_scheme`),"
                + "UNIQUE KEY `uk_provider_impression_inbox_dedupe` "
                + "(`provider_connection_id`,`dedupe_scheme`,`dedupe_key_hash`),"
                + "UNIQUE KEY `uk_provider_impression_inbox_canonical_attempt` (`canonical_attempt_id`),"
                + "KEY `idx_provider_impression_inbox_ready` "
                + "(`processing_status`,`next_attempt_at`,`id`),"
                + "KEY `idx_provider_impression_inbox_recovery` "
                + "(`processing_status`,`lease_until`,`id`),"
                + "KEY `idx_provider_impression_inbox_connection_status` "
                + "(`provider_connection_id`,`processing_status`,`last_received_at`,`id`),"
                + "CONSTRAINT `fk_provider_impression_inbox_connection` FOREIGN KEY (`provider_connection_id`) "
                + "REFERENCES `skit_ad_provider_connection` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_provider_impression_inbox_dedupe_scheme` CHECK "
                + "(`dedupe_scheme` IN ('OFFICIAL_V1','FALLBACK_WIRE_V1')),"
                + "CONSTRAINT `ck_provider_impression_inbox_dedupe_material` CHECK "
                + "((`dedupe_scheme`='OFFICIAL_V1' "
                + "AND `provider_request_id_lexical` IS NOT NULL "
                + "AND OCTET_LENGTH(`provider_request_id_lexical`) BETWEEN 1 AND 512 "
                + "AND `adsource_id_lexical` IS NOT NULL "
                + "AND CHAR_LENGTH(`adsource_id_lexical`) BETWEEN 1 AND 19 "
                + "AND REGEXP_LIKE(`adsource_id_lexical`,'^0*[1-9][0-9]*$') "
                + "AND `material_integrity_hash` IS NOT NULL) OR "
                + "(`dedupe_scheme`='FALLBACK_WIRE_V1' AND NOT ("
                + "`provider_request_id_lexical` IS NOT NULL "
                + "AND OCTET_LENGTH(`provider_request_id_lexical`) BETWEEN 1 AND 512 "
                + "AND `adsource_id_lexical` IS NOT NULL "
                + "AND CHAR_LENGTH(`adsource_id_lexical`) BETWEEN 1 AND 19 "
                + "AND REGEXP_LIKE(`adsource_id_lexical`,'^0*[1-9][0-9]*$')))),"
                + "CONSTRAINT `ck_provider_impression_inbox_authentication` CHECK "
                + "(`authentication_level`='UNSIGNED_PROVIDER_OBSERVATION'),"
                + "CONSTRAINT `ck_provider_impression_inbox_integrity` CHECK "
                + "((`integrity_status`='CANONICAL' AND `integrity_revision`=0 "
                + "AND `integrity_conflict_at` IS NULL) OR "
                + "(`integrity_status`='PAYLOAD_CONFLICT' AND `integrity_revision`>0 "
                + "AND `integrity_conflict_at` IS NOT NULL)),"
                + "CONSTRAINT `ck_provider_impression_inbox_fallback_quarantine` CHECK "
                + "(`dedupe_scheme`<>'FALLBACK_WIRE_V1' OR (`processing_status`='QUARANTINED' "
                + "AND `quarantine_reason` IS NOT NULL)),"
                + "CONSTRAINT `ck_provider_impression_inbox_processing` CHECK "
                + "((`processing_status`='PENDING' AND `lease_owner` IS NULL AND `lease_until` IS NULL "
                + "AND `next_attempt_at` IS NULL AND `processed_at` IS NULL) OR "
                + "(`processing_status`='PROCESSING' AND `lease_owner` IS NOT NULL "
                + "AND `lease_until` IS NOT NULL AND `next_attempt_at` IS NULL "
                + "AND `processed_at` IS NULL) OR (`processing_status`='RETRY_WAIT' "
                + "AND `lease_owner` IS NULL AND `lease_until` IS NULL "
                + "AND `next_attempt_at` IS NOT NULL AND `processed_at` IS NULL) OR "
                + "(`processing_status`='SUCCEEDED' AND `lease_owner` IS NULL AND `lease_until` IS NULL "
                + "AND `next_attempt_at` IS NULL AND `processed_at` IS NOT NULL) OR "
                + "(`processing_status`='QUARANTINED' AND `lease_owner` IS NULL "
                + "AND `lease_until` IS NULL AND `next_attempt_at` IS NULL "
                + "AND `quarantine_reason` IS NOT NULL) OR (`processing_status`='DEAD_LETTER' "
                + "AND `lease_owner` IS NULL AND `lease_until` IS NULL "
                + "AND `next_attempt_at` IS NULL AND `processed_at` IS NOT NULL)),"
                + "CONSTRAINT `ck_provider_impression_inbox_counts` CHECK "
                + "(`processing_attempt_count`>=0),"
                + "CONSTRAINT `ck_provider_impression_inbox_time` CHECK "
                + "(`last_received_at`>=`first_received_at` "
                + "AND (`dead_letter_alerted_at` IS NULL OR `processed_at` IS NOT NULL)))"
                + TABLE_OPTIONS + " COMMENT='Skit 阶段 1 全局 provider impression inbox'";
    }

    private static String providerCallbackAttemptTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_provider_callback_attempt` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`correlation_id` binary(16) NOT NULL,"
                + "`provider_connection_id` bigint NOT NULL,`inbox_id` bigint NOT NULL,"
                + "`dedupe_scheme` varchar(32) NOT NULL,"
                + "`wire_payload_hash` binary(32) NOT NULL,`material_integrity_hash` binary(32) DEFAULT NULL,"
                + "`delivery_integrity_status` varchar(32) NOT NULL,`response_decision` varchar(32) NOT NULL,"
                + "`payload_ciphertext` mediumblob DEFAULT NULL,`payload_nonce` binary(12) DEFAULT NULL,"
                + "`payload_key_id` varchar(64) DEFAULT NULL,`payload_purpose` varchar(64) DEFAULT NULL,"
                + "`payload_envelope_version` smallint DEFAULT NULL,`payload_expires_at` datetime DEFAULT NULL,"
                + "`payload_purged_at` datetime DEFAULT NULL,`wire_size_bytes` int NOT NULL,"
                + "`parameter_count` int NOT NULL,`remote_address_hash` binary(32) NOT NULL,"
                + "`user_agent_hash` binary(32) DEFAULT NULL,`request_header_fingerprint` binary(32) NOT NULL,"
                + "`trace_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                + "`received_at` datetime NOT NULL,"
                + "PRIMARY KEY (`id`),UNIQUE KEY `uk_provider_callback_attempt_correlation` (`correlation_id`),"
                + "UNIQUE KEY `uk_provider_callback_attempt_connection_inbox_id` "
                + "(`provider_connection_id`,`inbox_id`,`id`),"
                + "UNIQUE KEY `uk_provider_callback_attempt_trace` (`trace_id`),"
                + "KEY `idx_provider_callback_attempt_inbox` "
                + "(`provider_connection_id`,`inbox_id`,`dedupe_scheme`,`received_at`,`id`),"
                + "KEY `idx_provider_callback_attempt_expiry` (`payload_expires_at`,`id`),"
                + "CONSTRAINT `fk_provider_callback_attempt_connection` FOREIGN KEY (`provider_connection_id`) "
                + "REFERENCES `skit_ad_provider_connection` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_provider_callback_attempt_inbox` "
                + "FOREIGN KEY (`provider_connection_id`,`inbox_id`,`dedupe_scheme`) "
                + "REFERENCES `skit_provider_impression_inbox` "
                + "(`provider_connection_id`,`id`,`dedupe_scheme`) "
                + "ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_provider_callback_attempt_delivery_integrity` CHECK "
                + "((`dedupe_scheme`='OFFICIAL_V1' AND `delivery_integrity_status` IN "
                + "('CANONICAL','EQUIVALENT_DUPLICATE','PAYLOAD_CONFLICT') "
                + "AND `material_integrity_hash` IS NOT NULL) OR "
                + "(`dedupe_scheme`='FALLBACK_WIRE_V1' "
                + "AND `delivery_integrity_status`='FALLBACK_QUARANTINED')),"
                + "CONSTRAINT `ck_provider_callback_attempt_response` CHECK "
                + "(`response_decision`='ACK_200'),"
                + "CONSTRAINT `ck_provider_callback_attempt_payload_retention` CHECK "
                + "((`payload_ciphertext` IS NOT NULL AND `payload_nonce` IS NOT NULL "
                + "AND `payload_key_id` IS NOT NULL AND CHAR_LENGTH(TRIM(`payload_key_id`))>0 "
                + "AND `payload_purpose` IS NOT NULL AND CHAR_LENGTH(TRIM(`payload_purpose`))>0 "
                + "AND `payload_envelope_version`>0 AND `payload_expires_at` IS NOT NULL "
                + "AND `payload_purged_at` IS NULL) OR (`payload_ciphertext` IS NULL "
                + "AND `payload_nonce` IS NULL AND `payload_key_id` IS NULL "
                + "AND `payload_purpose` IS NULL AND `payload_envelope_version` IS NULL "
                + "AND `payload_expires_at` IS NULL AND `payload_purged_at` IS NOT NULL)),"
                + "CONSTRAINT `ck_provider_callback_attempt_boundary` CHECK "
                + "(`wire_size_bytes` BETWEEN 0 AND 32768 AND `parameter_count` BETWEEN 0 AND 64),"
                + "CONSTRAINT `ck_provider_callback_attempt_expiry` CHECK "
                + "(`payload_expires_at` IS NULL OR `payload_expires_at`>`received_at`),"
                + "CONSTRAINT `ck_provider_callback_attempt_safe_audit` CHECK "
                + "(CHAR_LENGTH(TRIM(`trace_id`)) BETWEEN 1 AND 64))"
                + TABLE_OPTIONS + " COMMENT='Skit 阶段 1 全局 provider callback attempt'";
    }

    private static String platformProviderCommandAuditTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_platform_provider_command_audit` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`actor_user_id` bigint NOT NULL,"
                + "`original_login_tenant_id` bigint NOT NULL,`action` varchar(64) NOT NULL,"
                + "`provider_connection_id` bigint DEFAULT NULL,`provider_callback_route_id` bigint DEFAULT NULL,"
                + "`callback_route_registry_id` bigint DEFAULT NULL,`reason` varchar(500) NOT NULL,"
                + "`reauthenticated_at` datetime NOT NULL,`request_fingerprint` binary(32) NOT NULL,"
                + "`before_state_hash` binary(32) DEFAULT NULL,`after_state_hash` binary(32) DEFAULT NULL,"
                + "`trace_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                + "`result_status` varchar(16) NOT NULL,`result_code` varchar(64) NOT NULL,"
                + "`occurred_at` datetime NOT NULL,PRIMARY KEY (`id`),"
                + "UNIQUE KEY `uk_platform_provider_command_audit_trace` (`trace_id`),"
                + "KEY `idx_platform_provider_command_audit_actor` (`actor_user_id`,`occurred_at`,`id`),"
                + "KEY `idx_platform_provider_command_audit_connection` "
                + "(`provider_connection_id`,`occurred_at`,`id`),"
                + "CONSTRAINT `fk_platform_provider_command_audit_connection` FOREIGN KEY (`provider_connection_id`) "
                + "REFERENCES `skit_ad_provider_connection` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_platform_provider_command_audit_route` FOREIGN KEY (`provider_callback_route_id`) "
                + "REFERENCES `skit_ad_provider_callback_route` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_platform_provider_command_audit_registry` "
                + "FOREIGN KEY (`callback_route_registry_id`) REFERENCES `skit_ad_callback_route_registry` (`id`) "
                + "ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_platform_provider_command_audit_resource` CHECK "
                + "(`provider_connection_id` IS NOT NULL OR `provider_callback_route_id` IS NOT NULL "
                + "OR `callback_route_registry_id` IS NOT NULL),"
                + "CONSTRAINT `ck_platform_provider_command_audit_reason` CHECK "
                + "(CHAR_LENGTH(TRIM(`reason`)) BETWEEN 10 AND 500),"
                + "CONSTRAINT `ck_platform_provider_command_audit_result` CHECK "
                + "(`result_status` IN ('SUCCEEDED','REJECTED','FAILED')))"
                + TABLE_OPTIONS + " COMMENT='Skit 阶段 1 全局平台 provider 命令审计'";
    }

    static final class Step {

        enum Kind {
            CREATE_TABLE,
            UPDATE,
            FOREIGN_KEY,
            TRIGGER
        }

        private final Kind kind;
        private final String manifestEntry;
        private final String sql;
        private final String table;
        private final String name;
        private final String event;
        private final String action;
        private final String columns;
        private final String referencedTable;
        private final String referencedColumns;

        private Step(Kind kind, String manifestEntry, String sql, String table, String name,
                     String event, String action, String columns, String referencedTable,
                     String referencedColumns) {
            this.kind = kind;
            this.manifestEntry = manifestEntry;
            this.sql = sql;
            this.table = table;
            this.name = name;
            this.event = event;
            this.action = action;
            this.columns = columns;
            this.referencedTable = referencedTable;
            this.referencedColumns = referencedColumns;
        }

        static Step createTable(String sql) {
            return new Step(Kind.CREATE_TABLE, "create-table:" + sql, sql,
                    null, null, null, null, null, null, null);
        }

        static Step update(String operation, String sql) {
            return new Step(Kind.UPDATE, operation + ":" + sql, sql,
                    null, null, null, null, null, null, null);
        }

        static Step foreignKey(String table, String name, String columns,
                               String referencedTable, String referencedColumns) {
            String sql = "ALTER TABLE `" + table + "` ADD CONSTRAINT `" + name + "` FOREIGN KEY ("
                    + columns + ") REFERENCES `" + referencedTable + "` (" + referencedColumns
                    + ") ON UPDATE RESTRICT ON DELETE RESTRICT";
            return new Step(Kind.FOREIGN_KEY, "add-foreign-key-if-missing:" + sql, sql,
                    table, name, null, null, columns, referencedTable, referencedColumns);
        }

        static Step trigger(String table, String name, String event, String action) {
            String sql = "CREATE TRIGGER IF NOT EXISTS `" + name + "` BEFORE " + event
                    + " ON `" + table + "` FOR EACH ROW " + action;
            return new Step(Kind.TRIGGER, "ensure-trigger:" + sql, sql,
                    table, name, event, action, null, null, null);
        }

        Kind getKind() {
            return kind;
        }

        String getManifestEntry() {
            return manifestEntry;
        }

        String getSql() {
            return sql;
        }

        String getTable() {
            return table;
        }

        String getName() {
            return name;
        }

        String getEvent() {
            return event;
        }

        String getAction() {
            return action;
        }

        String getColumns() {
            return columns;
        }

        String getReferencedTable() {
            return referencedTable;
        }

        String getReferencedColumns() {
            return referencedColumns;
        }

    }

}
