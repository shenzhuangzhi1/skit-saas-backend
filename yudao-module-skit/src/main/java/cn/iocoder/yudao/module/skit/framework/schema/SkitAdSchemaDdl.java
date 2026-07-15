package cn.iocoder.yudao.module.skit.framework.schema;

import java.util.Arrays;
import java.util.List;

/**
 * Canonical clean-table DDL for the verified advertising/revenue foundation.
 *
 * <p>The statements intentionally include every tenant candidate key and
 * compound foreign key so a clean install and a legacy upgrade converge on
 * the same database-enforced tenant boundary.</p>
 */
final class SkitAdSchemaDdl {

    static final int VERSION = 2026071401;

    private SkitAdSchemaDdl() {
    }

    static List<String> tableNames() {
        return Arrays.asList(
                "skit_ad_callback_key",
                "skit_ad_reward_secret_version",
                "skit_ad_policy_snapshot",
                "skit_ad_session",
                "skit_ad_client_event",
                "skit_ad_callback_edge_attempt",
                "skit_ad_callback_inbox",
                "skit_ad_callback_attempt",
                "skit_ad_network_capability",
                "skit_content_entitlement",
                "skit_entitlement_grant",
                "skit_native_player_grant",
                "skit_ad_report_pull",
                "skit_ad_reconciliation_bucket",
                "skit_ad_reconciliation_revision",
                "skit_tenant_ad_capability",
                "skit_invite_code_registry");
    }

    static List<String> createTableStatements() {
        return Arrays.asList(
                createCallbackKeyTable(),
                createRewardSecretVersionTable(),
                createPolicySnapshotTable(),
                createAdSessionTable(),
                createAdClientEventTable(),
                createCallbackEdgeAttemptTable(),
                createCallbackInboxTable(),
                createCallbackAttemptTable(),
                createNetworkCapabilityTable(),
                createContentEntitlementTable(),
                createEntitlementGrantTable(),
                createNativePlayerGrantTable(),
                createReportPullTable(),
                createReconciliationBucketTable(),
                createReconciliationRevisionTable(),
                createTenantCapabilityTable(),
                createInviteCodeRegistryTable());
    }

    private static String createCallbackKeyTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_callback_key` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,"
                + "`ad_account_id` bigint NOT NULL,`key_version` int NOT NULL,"
                + "`callback_key_hash` binary(32) NOT NULL,`active` bit(1) NOT NULL DEFAULT b'1',"
                + "`accept_until` datetime DEFAULT NULL,`revoked_at` datetime DEFAULT NULL,"
                + "`active_account_id` bigint GENERATED ALWAYS AS (CASE WHEN `active` = b'1' "
                + "AND `revoked_at` IS NULL THEN `ad_account_id` ELSE NULL END) STORED,"
                + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_callback_key_tenant_id` (`tenant_id`,`id`),"
                + "UNIQUE KEY `uk_skit_callback_key_version` (`tenant_id`,`ad_account_id`,`key_version`),"
                + "UNIQUE KEY `uk_skit_callback_key_hash` (`callback_key_hash`),"
                + "UNIQUE KEY `uk_skit_callback_key_active` (`tenant_id`,`active_account_id`),"
                + "CONSTRAINT `fk_skit_callback_key_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) "
                + "REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_skit_callback_key_version` CHECK (`key_version` > 0))" + tableOptions();
    }

    private static String createRewardSecretVersionTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_reward_secret_version` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,"
                + "`ad_account_id` bigint NOT NULL,`secret_version` int NOT NULL,"
                + "`ciphertext` varbinary(4096) NOT NULL,`nonce` binary(12) NOT NULL,"
                + "`encryption_key_id` varchar(64) NOT NULL,`envelope_version` smallint NOT NULL DEFAULT 1,"
                + "`active` bit(1) NOT NULL DEFAULT b'1',`accept_until` datetime DEFAULT NULL,"
                + "`revoked_at` datetime DEFAULT NULL,"
                + "`active_account_id` bigint GENERATED ALWAYS AS (CASE WHEN `active` = b'1' "
                + "AND `revoked_at` IS NULL THEN `ad_account_id` ELSE NULL END) STORED,"
                + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_reward_secret_tenant_id` (`tenant_id`,`id`),"
                + "UNIQUE KEY `uk_skit_reward_secret_version` (`tenant_id`,`ad_account_id`,`secret_version`),"
                + "UNIQUE KEY `uk_skit_reward_secret_active` (`tenant_id`,`active_account_id`),"
                + "CONSTRAINT `fk_skit_reward_secret_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) "
                + "REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_skit_reward_secret_version` CHECK (`secret_version` > 0),"
                + "CONSTRAINT `ck_skit_reward_secret_envelope` CHECK (`envelope_version` > 0))" + tableOptions();
    }

    private static String createPolicySnapshotTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_policy_snapshot` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`plan_id` bigint NOT NULL,"
                + "`source_member_id` bigint NOT NULL,`rule_version` int NOT NULL,"
                + "`snapshot_schema_version` smallint NOT NULL DEFAULT 1,`snapshot_json` longtext NOT NULL,"
                + "`snapshot_hash` binary(32) NOT NULL,`policy_snapshot_at` datetime NOT NULL,"
                + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_policy_snapshot_tenant_id` (`tenant_id`,`id`),"
                + "KEY `idx_skit_policy_snapshot_plan` (`tenant_id`,`plan_id`,`rule_version`),"
                + "CONSTRAINT `fk_skit_policy_snapshot_plan` FOREIGN KEY (`tenant_id`,`plan_id`) "
                + "REFERENCES `skit_commission_plan` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_skit_policy_snapshot_member` FOREIGN KEY (`tenant_id`,`source_member_id`) "
                + "REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_skit_policy_snapshot_version` CHECK (`rule_version` > 0 "
                + "AND `snapshot_schema_version` > 0))" + tableOptions();
    }

    private static String createAdSessionTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_session` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,"
                + "`session_id` varchar(64) NOT NULL,`session_token_hash` binary(32) NOT NULL,"
                + "`protocol_version` smallint NOT NULL DEFAULT 1,"
                + "`member_id` bigint NOT NULL,`ad_account_id` bigint NOT NULL,`policy_snapshot_id` bigint NOT NULL,"
                + "`callback_key_version` int NOT NULL,`reward_secret_version` int NOT NULL,"
                + "`provider` varchar(16) NOT NULL,`placement_id` varchar(128) NOT NULL,"
                + "`scenario_id` varchar(64) NOT NULL,`business_type` varchar(32) NOT NULL,"
                + "`drama_id` bigint NOT NULL,`episode_from` int NOT NULL,`episode_to` int NOT NULL,"
                + "`unlock_scope` varchar(512) NOT NULL,`active_scope_hash` binary(32) DEFAULT NULL,"
                + "`pseudonymous_user_id` varchar(128) NOT NULL,"
                + "`client_lifecycle_status` varchar(32) NOT NULL DEFAULT 'CREATED',"
                + "`reward_verification_status` varchar(32) NOT NULL DEFAULT 'PENDING',"
                + "`entitlement_status` varchar(32) NOT NULL DEFAULT 'NONE',"
                + "`revenue_status` varchar(32) NOT NULL DEFAULT 'NONE',"
                + "`load_expires_at` datetime NOT NULL,`reward_accept_until` datetime NOT NULL,"
                + "`reward_verified_at` datetime DEFAULT NULL,`entitled_at` datetime DEFAULT NULL,"
                + "`sdk_request_id` varchar(128) DEFAULT NULL,`provider_show_id` varchar(128) DEFAULT NULL,"
                + "`provider_transaction_id` varchar(128) DEFAULT NULL,`network_firm_id` int DEFAULT NULL,"
                + "`adsource_id` varchar(128) DEFAULT NULL,`last_callback_sequence` int NOT NULL DEFAULT -1,"
                + "`last_client_event` varchar(32) DEFAULT NULL,`failure_reason` varchar(128) DEFAULT NULL,"
                + "`version` int NOT NULL DEFAULT 0," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_ad_session_tenant_id` (`tenant_id`,`id`),"
                + "UNIQUE KEY `uk_skit_ad_session_public` (`session_id`),"
                + "UNIQUE KEY `uk_skit_ad_session_token` (`session_token_hash`),"
                + "UNIQUE KEY `uk_skit_ad_session_active_scope` (`tenant_id`,`member_id`,`active_scope_hash`),"
                + "UNIQUE KEY `uk_skit_ad_session_transaction` (`tenant_id`,`ad_account_id`,`provider_transaction_id`),"
                + "UNIQUE KEY `uk_skit_ad_session_show` (`tenant_id`,`ad_account_id`,`provider_show_id`),"
                + "CONSTRAINT `fk_skit_ad_session_member` FOREIGN KEY (`tenant_id`,`member_id`) "
                + "REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_skit_ad_session_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) "
                + "REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_skit_ad_session_snapshot` FOREIGN KEY (`tenant_id`,`policy_snapshot_id`) "
                + "REFERENCES `skit_ad_policy_snapshot` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_skit_ad_session_callback_key` FOREIGN KEY "
                + "(`tenant_id`,`ad_account_id`,`callback_key_version`) REFERENCES `skit_ad_callback_key` "
                + "(`tenant_id`,`ad_account_id`,`key_version`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_skit_ad_session_reward_secret` FOREIGN KEY "
                + "(`tenant_id`,`ad_account_id`,`reward_secret_version`) REFERENCES `skit_ad_reward_secret_version` "
                + "(`tenant_id`,`ad_account_id`,`secret_version`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_skit_ad_session_episode` CHECK (`episode_from` > 0 AND `episode_to` >= `episode_from`),"
                + "CONSTRAINT `ck_skit_ad_session_protocol` CHECK (`protocol_version` > 0 "
                + "AND `last_callback_sequence` >= -1),"
                + "CONSTRAINT `ck_skit_ad_session_window` CHECK (`reward_accept_until` >= `load_expires_at`),"
                + "CONSTRAINT `ck_skit_ad_session_client_status` CHECK (`client_lifecycle_status` IN "
                + "('CREATED','LOADING','SHOWN','CLIENT_REWARDED','CLOSED','FAILED','LOAD_EXPIRED')),"
                + "CONSTRAINT `ck_skit_ad_session_reward_status` CHECK (`reward_verification_status` IN "
                + "('PENDING','SIGNED_VERIFIED','REJECTED','VERIFY_TIMEOUT')),"
                + "CONSTRAINT `ck_skit_ad_session_entitlement` CHECK (`entitlement_status` IN "
                + "('NONE','GRANTED','SECURITY_REVOKED')),"
                + "CONSTRAINT `ck_skit_ad_session_revenue` CHECK (`revenue_status` IN "
                + "('NONE','IMPRESSION_PENDING_REWARD','FROZEN','RECONCILING','RECONCILED','SUSPENSE')))"
                + tableOptions();
    }

    private static String createAdClientEventTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_client_event` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_session_id` bigint NOT NULL,"
                + "`protocol_version` smallint NOT NULL,`client_event_id` varchar(128) NOT NULL,"
                + "`callback_sequence` int NOT NULL,`event_type` varchar(32) NOT NULL,"
                + "`native_state` varchar(32) NOT NULL,`sdk_request_id` varchar(128) DEFAULT NULL,"
                + "`provider_show_id` varchar(128) DEFAULT NULL,`network_firm_id` int DEFAULT NULL,"
                + "`adsource_id` varchar(128) DEFAULT NULL,`client_reward_observed` bit(1) NOT NULL DEFAULT b'0',"
                + "`closed` bit(1) NOT NULL DEFAULT b'0',"
                + "`payload_hash` binary(32) NOT NULL,`occurred_at` datetime NOT NULL," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_client_event_tenant_id` (`tenant_id`,`id`),"
                + "UNIQUE KEY `uk_skit_client_event_idem` (`tenant_id`,`ad_session_id`,`client_event_id`),"
                + "UNIQUE KEY `uk_skit_client_event_sequence` (`tenant_id`,`ad_session_id`,`callback_sequence`),"
                + "CONSTRAINT `fk_skit_client_event_session` FOREIGN KEY (`tenant_id`,`ad_session_id`) "
                + "REFERENCES `skit_ad_session` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_skit_client_event_sequence` CHECK (`protocol_version` > 0 "
                + "AND `callback_sequence` >= 0))" + tableOptions();
    }

    private static String createCallbackEdgeAttemptTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_callback_edge_attempt` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint DEFAULT NULL,"
                + "`ad_account_id` bigint DEFAULT NULL,`callback_key_hash` binary(32) NOT NULL,"
                + "`provider` varchar(16) NOT NULL,`callback_type` varchar(32) NOT NULL,"
                + "`client_ip_hash` binary(32) DEFAULT NULL,`request_method` varchar(16) NOT NULL,"
                + "`result_code` varchar(32) NOT NULL,`received_at` datetime NOT NULL," + auditColumns()
                + ",PRIMARY KEY (`id`),KEY `idx_skit_edge_attempt_hash_time` (`callback_key_hash`,`received_at`),"
                + "KEY `idx_skit_edge_attempt_tenant_time` (`tenant_id`,`received_at`),"
                + "CONSTRAINT `fk_skit_edge_attempt_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) "
                + "REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_skit_edge_attempt_route_pair` CHECK ((`tenant_id` IS NULL AND `ad_account_id` IS NULL) "
                + "OR (`tenant_id` IS NOT NULL AND `ad_account_id` IS NOT NULL)))" + tableOptions();
    }

    private static String createCallbackInboxTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_callback_inbox` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_account_id` bigint NOT NULL,"
                + "`ad_session_id` bigint DEFAULT NULL,`callback_key_version` int DEFAULT NULL,"
                + "`reward_secret_version` int DEFAULT NULL,`provider` varchar(16) NOT NULL,"
                + "`callback_type` varchar(32) NOT NULL,`idempotency_key` varchar(255) NOT NULL,"
                + "`provider_user_id` varchar(128) DEFAULT NULL,`extra_data_hash` binary(32) DEFAULT NULL,"
                + "`provider_transaction_id` varchar(128) DEFAULT NULL,`provider_show_id` varchar(128) DEFAULT NULL,"
                + "`provider_request_id` varchar(128) DEFAULT NULL,`placement_id` varchar(128) DEFAULT NULL,"
                + "`adsource_id` varchar(128) DEFAULT NULL,`network_firm_id` int DEFAULT NULL,"
                + "`source_currency` char(3) DEFAULT NULL,`source_amount_units` bigint DEFAULT NULL,"
                + "`amount_scale` tinyint DEFAULT NULL,`signed_field_mask` bigint NOT NULL DEFAULT 0,"
                + "`evidence_provenance` varchar(32) NOT NULL DEFAULT 'UNCLASSIFIED',"
                + "`canonical_payload_hash` binary(32) NOT NULL,`authentication_level` varchar(32) NOT NULL,"
                + "`signature_status` varchar(32) NOT NULL,"
                + "`delivery_integrity_status` varchar(32) NOT NULL DEFAULT 'CANONICAL',"
                + "`integrity_conflict_at` datetime DEFAULT NULL,"
                + "`processing_status` varchar(32) NOT NULL DEFAULT 'PENDING',"
                + "`payload_ciphertext` mediumblob DEFAULT NULL,`payload_nonce` binary(12) DEFAULT NULL,"
                + "`payload_key_id` varchar(64) DEFAULT NULL,`payload_envelope_version` smallint DEFAULT NULL,"
                + "`payload_expires_at` datetime DEFAULT NULL,"
                + "`error_code` varchar(64) DEFAULT NULL,`lease_owner` varchar(64) DEFAULT NULL,"
                + "`lease_until` datetime DEFAULT NULL,`processing_attempt_count` int NOT NULL DEFAULT 0,"
                + "`next_attempt_at` datetime DEFAULT NULL,`received_at` datetime NOT NULL,"
                + "`processed_at` datetime DEFAULT NULL,`dead_letter_alerted_at` datetime DEFAULT NULL,"
                + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_callback_inbox_tenant_id` (`tenant_id`,`id`),"
                + "UNIQUE KEY `uk_skit_callback_inbox_idem` "
                + "(`tenant_id`,`ad_account_id`,`callback_type`,`idempotency_key`),"
                + "KEY `idx_skit_callback_inbox_ready` (`processing_status`,`next_attempt_at`,`id`),"
                + "KEY `idx_skit_callback_inbox_recovery` (`processing_status`,`lease_until`,`id`),"
                + "CONSTRAINT `fk_skit_callback_inbox_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) "
                + "REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_skit_callback_inbox_session` FOREIGN KEY (`tenant_id`,`ad_session_id`) "
                + "REFERENCES `skit_ad_session` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_skit_callback_inbox_key` FOREIGN KEY "
                + "(`tenant_id`,`ad_account_id`,`callback_key_version`) REFERENCES `skit_ad_callback_key` "
                + "(`tenant_id`,`ad_account_id`,`key_version`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_skit_callback_inbox_secret` FOREIGN KEY "
                + "(`tenant_id`,`ad_account_id`,`reward_secret_version`) REFERENCES `skit_ad_reward_secret_version` "
                + "(`tenant_id`,`ad_account_id`,`secret_version`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_skit_callback_inbox_processing_attempts` CHECK (`processing_attempt_count` >= 0),"
                + "CONSTRAINT `ck_skit_callback_inbox_delivery_integrity` CHECK "
                + "((`delivery_integrity_status` = 'CANONICAL' AND `integrity_conflict_at` IS NULL) OR "
                + "(`delivery_integrity_status` = 'PAYLOAD_CONFLICT' AND `integrity_conflict_at` IS NOT NULL)),"
                + "CONSTRAINT `ck_skit_callback_inbox_processing_state` CHECK "
                + "((`processing_status` = 'PENDING' AND `lease_owner` IS NULL AND `lease_until` IS NULL "
                + "AND `next_attempt_at` IS NULL AND `processed_at` IS NULL) OR "
                + "(`processing_status` = 'PROCESSING' AND `lease_owner` IS NOT NULL AND `lease_until` IS NOT NULL "
                + "AND `next_attempt_at` IS NULL AND `processed_at` IS NULL) OR "
                + "(`processing_status` = 'RETRY_WAIT' AND `lease_owner` IS NULL AND `lease_until` IS NULL "
                + "AND `next_attempt_at` IS NOT NULL AND `processed_at` IS NULL) OR "
                + "(`processing_status` IN ('SUCCEEDED','REJECTED','DEAD_LETTER') AND `lease_owner` IS NULL "
                + "AND `lease_until` IS NULL AND `next_attempt_at` IS NULL AND `processed_at` IS NOT NULL)),"
                + "CONSTRAINT `ck_skit_callback_inbox_money` CHECK ((`source_currency` IS NULL "
                + "AND `source_amount_units` IS NULL AND `amount_scale` IS NULL) OR "
                + "(REGEXP_LIKE(`source_currency`,'^[A-Z]{3}$') AND `source_amount_units` >= 0 "
                + "AND `amount_scale` BETWEEN 0 AND 18)),"
                + "CONSTRAINT `ck_skit_callback_inbox_payload` CHECK ((`payload_ciphertext` IS NULL "
                + "AND `payload_nonce` IS NULL AND `payload_key_id` IS NULL AND `payload_envelope_version` IS NULL "
                + "AND `payload_expires_at` IS NULL) OR (`payload_ciphertext` IS NOT NULL AND `payload_nonce` IS NOT NULL "
                + "AND `payload_key_id` IS NOT NULL AND `payload_envelope_version` > 0 "
                + "AND `payload_expires_at` IS NOT NULL)))" + tableOptions();
    }

    private static String createCallbackAttemptTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_callback_attempt` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`callback_inbox_id` bigint NOT NULL,"
                + "`ad_account_id` bigint NOT NULL,`ad_session_id` bigint DEFAULT NULL,`attempt_no` int NOT NULL,"
                + "`payload_hash` binary(32) NOT NULL,`result_code` varchar(32) NOT NULL,"
                + "`received_at` datetime NOT NULL," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_callback_attempt_tenant_id` (`tenant_id`,`id`),"
                + "UNIQUE KEY `uk_skit_callback_attempt_no` (`tenant_id`,`callback_inbox_id`,`attempt_no`),"
                + "CONSTRAINT `fk_skit_callback_attempt_inbox` FOREIGN KEY (`tenant_id`,`callback_inbox_id`) "
                + "REFERENCES `skit_ad_callback_inbox` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_skit_callback_attempt_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) "
                + "REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_skit_callback_attempt_session` FOREIGN KEY (`tenant_id`,`ad_session_id`) "
                + "REFERENCES `skit_ad_session` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_skit_callback_attempt_no` CHECK (`attempt_no` > 0))" + tableOptions();
    }

    private static String createNetworkCapabilityTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_network_capability` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_account_id` bigint NOT NULL,"
                + "`network_firm_id` int NOT NULL,`reward_authority` varchar(32) NOT NULL,"
                + "`supports_user_id` bit(1) NOT NULL DEFAULT b'0',`supports_custom_data` bit(1) NOT NULL DEFAULT b'0',"
                + "`supports_stable_transaction` bit(1) NOT NULL DEFAULT b'0',"
                + "`supports_impression_revenue` bit(1) NOT NULL DEFAULT b'0',"
                + "`supports_reporting` bit(1) NOT NULL DEFAULT b'0',`enabled` bit(1) NOT NULL DEFAULT b'0',"
                + "`verified_at` datetime DEFAULT NULL," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_network_cap_tenant_id` (`tenant_id`,`id`),"
                + "UNIQUE KEY `uk_skit_network_cap_firm` (`tenant_id`,`ad_account_id`,`network_firm_id`),"
                + "CONSTRAINT `fk_skit_network_cap_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) "
                + "REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_skit_network_cap_firm` CHECK (`network_firm_id` > 0))" + tableOptions();
    }

    private static String createContentEntitlementTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_content_entitlement` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`member_id` bigint NOT NULL,"
                + "`drama_id` bigint NOT NULL,`episode_no` int NOT NULL,`status` varchar(32) NOT NULL DEFAULT 'GRANTED',"
                + "`granted_at` datetime NOT NULL,`version` int NOT NULL DEFAULT 0," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_entitlement_tenant_id` (`tenant_id`,`id`),"
                + "UNIQUE KEY `uk_skit_entitlement_episode` (`tenant_id`,`member_id`,`drama_id`,`episode_no`),"
                + "CONSTRAINT `fk_skit_entitlement_member` FOREIGN KEY (`tenant_id`,`member_id`) "
                + "REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_skit_entitlement_episode` CHECK (`episode_no` > 0),"
                + "CONSTRAINT `ck_skit_entitlement_status` CHECK (`status` IN ('GRANTED','SECURITY_REVOKED')))"
                + tableOptions();
    }

    private static String createEntitlementGrantTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_entitlement_grant` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_session_id` bigint NOT NULL,"
                + "`entitlement_id` bigint DEFAULT NULL,`member_id` bigint NOT NULL,`drama_id` bigint NOT NULL,"
                + "`episode_no` int NOT NULL,`provider_transaction_id` varchar(128) NOT NULL,"
                + "`grant_result` varchar(32) NOT NULL,`granted_at` datetime NOT NULL," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_grant_tenant_id` (`tenant_id`,`id`),"
                + "UNIQUE KEY `uk_skit_grant_session_episode` (`tenant_id`,`ad_session_id`,`episode_no`),"
                + "CONSTRAINT `fk_skit_grant_session` FOREIGN KEY (`tenant_id`,`ad_session_id`) "
                + "REFERENCES `skit_ad_session` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_skit_grant_entitlement` FOREIGN KEY (`tenant_id`,`entitlement_id`) "
                + "REFERENCES `skit_content_entitlement` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_skit_grant_member` FOREIGN KEY (`tenant_id`,`member_id`) "
                + "REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_skit_grant_episode` CHECK (`episode_no` > 0),"
                + "CONSTRAINT `ck_skit_grant_result` CHECK (`grant_result` IN ('CREATED','ALREADY_OWNED')))"
                + tableOptions();
    }

    private static String createNativePlayerGrantTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_native_player_grant` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`member_id` bigint NOT NULL,"
                + "`drama_id` bigint NOT NULL,`grant_token_hash` binary(32) NOT NULL,"
                + "`status` varchar(16) NOT NULL DEFAULT 'ACTIVE',`expires_at` datetime NOT NULL,"
                + "`revoked_at` datetime DEFAULT NULL," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_player_grant_tenant_id` (`tenant_id`,`id`),"
                + "UNIQUE KEY `uk_skit_player_grant_token` (`grant_token_hash`),"
                + "KEY `idx_skit_player_grant_scope` (`tenant_id`,`member_id`,`drama_id`,`expires_at`),"
                + "CONSTRAINT `fk_skit_player_grant_member` FOREIGN KEY (`tenant_id`,`member_id`) "
                + "REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_skit_player_grant_status` CHECK (`status` IN ('ACTIVE','EXPIRED','REVOKED')))"
                + tableOptions();
    }

    private static String createReportPullTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_report_pull` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_account_id` bigint NOT NULL,"
                + "`provider` varchar(16) NOT NULL,`range_start` datetime NOT NULL,`range_end` datetime NOT NULL,"
                + "`response_hash` binary(32) NOT NULL,`status` varchar(32) NOT NULL,"
                + "`response_ciphertext` mediumblob DEFAULT NULL,`response_nonce` binary(12) DEFAULT NULL,"
                + "`response_key_id` varchar(64) DEFAULT NULL,`response_envelope_version` smallint DEFAULT NULL,"
                + "`response_expires_at` datetime DEFAULT NULL,`pulled_at` datetime NOT NULL,"
                + "`error_code` varchar(64) DEFAULT NULL," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_report_pull_tenant_id` (`tenant_id`,`id`),"
                + "UNIQUE KEY `uk_skit_report_pull_response` "
                + "(`tenant_id`,`ad_account_id`,`range_start`,`range_end`,`response_hash`),"
                + "CONSTRAINT `fk_skit_report_pull_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) "
                + "REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_skit_report_pull_range` CHECK (`range_end` > `range_start`),"
                + "CONSTRAINT `ck_skit_report_pull_response` CHECK ((`response_ciphertext` IS NULL "
                + "AND `response_nonce` IS NULL AND `response_key_id` IS NULL AND `response_envelope_version` IS NULL "
                + "AND `response_expires_at` IS NULL) OR (`response_ciphertext` IS NOT NULL "
                + "AND `response_nonce` IS NOT NULL AND `response_key_id` IS NOT NULL "
                + "AND `response_envelope_version` > 0 AND `response_expires_at` IS NOT NULL)))" + tableOptions();
    }

    private static String createReconciliationBucketTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_reconciliation_bucket` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_account_id` bigint NOT NULL,"
                + "`bucket_key` char(64) NOT NULL,`report_date` date NOT NULL,`report_timezone` varchar(64) NOT NULL,"
                + "`placement_id` varchar(128) NOT NULL,`network_firm_id` int NOT NULL,"
                + "`adsource_id` varchar(128) NOT NULL,`currency` char(3) NOT NULL,`amount_scale` tinyint NOT NULL,"
                + "`estimate_units` bigint NOT NULL DEFAULT 0,`report_actual_units` bigint NOT NULL DEFAULT 0,"
                + "`report_impressions` bigint NOT NULL DEFAULT 0,`matched_impressions` bigint NOT NULL DEFAULT 0,"
                + "`status` varchar(32) NOT NULL DEFAULT 'OPEN'," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_recon_bucket_tenant_id` (`tenant_id`,`id`),"
                + "UNIQUE KEY `uk_skit_recon_bucket_identity` "
                + "(`tenant_id`,`ad_account_id`,`bucket_key`,`report_date`,`report_timezone`,`placement_id`,"
                + "`network_firm_id`,`adsource_id`,`currency`),"
                + "CONSTRAINT `fk_skit_recon_bucket_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) "
                + "REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_skit_recon_bucket_currency` "
                + "CHECK (REGEXP_LIKE(`currency`,'^[A-Z]{3}$')),"
                + "CONSTRAINT `ck_skit_recon_bucket_scale` CHECK (`amount_scale` BETWEEN 0 AND 18),"
                + "CONSTRAINT `ck_skit_recon_bucket_amounts` CHECK (`estimate_units` >= 0 AND "
                + "`report_actual_units` >= 0 AND `report_impressions` >= 0 AND `matched_impressions` >= 0))"
                + tableOptions();
    }

    private static String createReconciliationRevisionTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_reconciliation_revision` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_account_id` bigint NOT NULL,"
                + "`reconciliation_bucket_id` bigint NOT NULL,`report_pull_id` bigint DEFAULT NULL,"
                + "`bucket_key` char(64) NOT NULL,`report_date` date NOT NULL,`revision_hash` binary(32) NOT NULL,"
                + "`revision_no` int NOT NULL,`target_actual_units` bigint NOT NULL,"
                + "`unmatched_actual_units` bigint NOT NULL DEFAULT 0,`amount_scale` tinyint NOT NULL,"
                + "`currency` char(3) NOT NULL,`final_revision` bit(1) NOT NULL DEFAULT b'0',"
                + "`reconciled_at` datetime NOT NULL," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_recon_revision_tenant_id` (`tenant_id`,`id`),"
                + "UNIQUE KEY `uk_skit_recon_revision_hash` "
                + "(`tenant_id`,`ad_account_id`,`bucket_key`,`report_date`,`revision_hash`),"
                + "UNIQUE KEY `uk_skit_recon_revision_no` (`tenant_id`,`reconciliation_bucket_id`,`revision_no`),"
                + "CONSTRAINT `fk_skit_recon_revision_bucket` FOREIGN KEY "
                + "(`tenant_id`,`reconciliation_bucket_id`) REFERENCES `skit_ad_reconciliation_bucket` "
                + "(`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_skit_recon_revision_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) "
                + "REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_skit_recon_revision_pull` FOREIGN KEY (`tenant_id`,`report_pull_id`) "
                + "REFERENCES `skit_ad_report_pull` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_skit_recon_revision_no` CHECK (`revision_no` >= 0),"
                + "CONSTRAINT `ck_skit_recon_revision_amounts` CHECK (`target_actual_units` >= 0 "
                + "AND `unmatched_actual_units` >= 0),"
                + "CONSTRAINT `ck_skit_recon_revision_scale` CHECK (`amount_scale` BETWEEN 0 AND 18),"
                + "CONSTRAINT `ck_skit_recon_revision_currency` "
                + "CHECK (REGEXP_LIKE(`currency`,'^[A-Z]{3}$')))"
                + tableOptions();
    }

    private static String createTenantCapabilityTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_tenant_ad_capability` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_account_id` bigint DEFAULT NULL,"
                + "`rollout_state` varchar(32) NOT NULL DEFAULT 'OFF',`min_native_version` varchar(32) DEFAULT '',"
                + "`readiness_version` int NOT NULL DEFAULT 0,`enforced_at` datetime DEFAULT NULL,"
                + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_tenant_capability_tenant_id` (`tenant_id`,`id`),"
                + "UNIQUE KEY `uk_skit_tenant_capability_singleton` (`tenant_id`),"
                + "CONSTRAINT `fk_skit_tenant_capability_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) "
                + "REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_skit_tenant_capability_state` CHECK (`rollout_state` IN "
                + "('OFF','SHADOW_TEST_USERS','ENFORCED')))" + tableOptions();
    }

    private static String createInviteCodeRegistryTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_invite_code_registry` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`code` varchar(32) NOT NULL,"
                + "`normalized_code` varchar(32) GENERATED ALWAYS AS (UPPER(TRIM(`code`))) STORED,"
                + "`owner_type` varchar(16) NOT NULL,`agent_id` bigint DEFAULT NULL,`member_id` bigint DEFAULT NULL,"
                + "`status` varchar(16) NOT NULL DEFAULT 'ACTIVE',`rotated_at` datetime DEFAULT NULL,"
                + "`active_agent_id` bigint GENERATED ALWAYS AS (CASE WHEN `status` = 'ACTIVE' "
                + "THEN `agent_id` ELSE NULL END) STORED,"
                + "`active_member_id` bigint GENERATED ALWAYS AS (CASE WHEN `status` = 'ACTIVE' "
                + "THEN `member_id` ELSE NULL END) STORED,"
                + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_invite_registry_tenant_id` (`tenant_id`,`id`),"
                + "UNIQUE KEY `uk_skit_invite_registry_code` (`normalized_code`),"
                + "UNIQUE KEY `uk_skit_invite_registry_agent` (`tenant_id`,`active_agent_id`),"
                + "UNIQUE KEY `uk_skit_invite_registry_member` (`tenant_id`,`active_member_id`),"
                + "CONSTRAINT `fk_skit_invite_registry_agent` FOREIGN KEY (`tenant_id`,`agent_id`) "
                + "REFERENCES `skit_agent` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_skit_invite_registry_member` FOREIGN KEY (`tenant_id`,`member_id`) "
                + "REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_skit_invite_registry_owner` CHECK ((`owner_type` = 'AGENT' AND `agent_id` IS NOT NULL "
                + "AND `member_id` IS NULL) OR (`owner_type` = 'MEMBER' AND `member_id` IS NOT NULL "
                + "AND `agent_id` IS NULL)),"
                + "CONSTRAINT `ck_skit_invite_registry_status` CHECK (`status` IN ('ACTIVE','DISABLED','ROTATED')))"
                + tableOptions();
    }

    private static String auditColumns() {
        return "`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP "
                + "ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0'";
    }

    private static String tableOptions() {
        return " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    }

}
