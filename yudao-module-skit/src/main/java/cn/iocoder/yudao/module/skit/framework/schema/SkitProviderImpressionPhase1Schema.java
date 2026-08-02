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
                "fk_provider_impression_inbox_canonical_attempt", "`canonical_attempt_id`",
                "skit_provider_callback_attempt", "`id`"));
        steps.add(Step.createTable(platformProviderCommandAuditTable()));
        steps.add(Step.update("seed-callback-route-registry-migration",
                "INSERT IGNORE INTO `skit_ad_callback_route_registry_migration` "
                        + "(`singleton_id`,`migration_state`,`last_callback_key_id`,`created_at`,`updated_at`) "
                        + "VALUES (1,'PENDING',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"));
        steps.add(Step.trigger("skit_ad_provider_callback_route",
                "trg_provider_callback_route_purpose_immutable", "UPDATE",
                "BEGIN IF NOT (NEW.`purpose` <=> OLD.`purpose`) THEN "
                        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'provider callback route purpose is immutable'; "
                        + "END IF; END"));
        steps.add(Step.trigger("skit_ad_callback_route_registry_migration",
                "trg_callback_route_registry_migration_monotonic", "UPDATE",
                "BEGIN IF NEW.`singleton_id` <> OLD.`singleton_id` "
                        + "OR NEW.`last_callback_key_id` < OLD.`last_callback_key_id` "
                        + "OR (OLD.`migration_state` = 'COMPLETED' AND NEW.`migration_state` <> 'COMPLETED') "
                        + "OR (OLD.`started_at` IS NOT NULL AND NOT (NEW.`started_at` <=> OLD.`started_at`)) "
                        + "OR (OLD.`completed_at` IS NOT NULL AND NOT (NEW.`completed_at` <=> OLD.`completed_at`)) "
                        + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'callback registry migration state is monotonic'; "
                        + "END IF; END"));
        steps.add(Step.trigger("skit_ad_callback_route_registry_migration",
                "trg_callback_route_registry_migration_no_delete", "DELETE",
                "BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'callback registry migration state cannot be deleted'; END"));
        steps.add(Step.trigger("skit_platform_provider_command_audit",
                "trg_platform_provider_command_audit_immutable", "UPDATE",
                "BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'platform provider command audit is append only'; END"));
        steps.add(Step.trigger("skit_platform_provider_command_audit",
                "trg_platform_provider_command_audit_no_delete", "DELETE",
                "BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'platform provider command audit cannot be deleted'; END"));
        return Collections.unmodifiableList(steps);
    }

    private static String providerConnectionTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_provider_connection` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,"
                + "`connection_code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                + "`provider` varchar(16) NOT NULL,`account_mode` varchar(32) NOT NULL,"
                + "`owner_tenant_id` bigint DEFAULT NULL,`owner_ad_account_id` bigint DEFAULT NULL,"
                + "`state` varchar(32) NOT NULL DEFAULT 'CONFIGURING',"
                + "`non_terminal_shared_master_slot` varchar(64) GENERATED ALWAYS AS "
                + "(CASE WHEN `provider`='TAKU' AND `account_mode`='SHARED_MASTER' "
                + "AND `state`<>'RETIRED' THEN 'TAKU:SHARED_MASTER' ELSE NULL END) STORED,"
                + "`created_by_user_id` bigint NOT NULL,`created_at` datetime NOT NULL,"
                + "`updated_by_user_id` bigint NOT NULL,`updated_at` datetime NOT NULL,"
                + "PRIMARY KEY (`id`),"
                + "UNIQUE KEY `uk_provider_connection_code` (`connection_code`),"
                + "UNIQUE KEY `uk_provider_connection_shared_master` (`non_terminal_shared_master_slot`),"
                + "KEY `idx_provider_connection_state` (`provider`,`account_mode`,`state`,`id`),"
                + "CONSTRAINT `ck_provider_connection_provider` CHECK (`provider`='TAKU'),"
                + "CONSTRAINT `ck_provider_connection_mode` CHECK "
                + "(`account_mode` IN ('SHARED_MASTER','TENANT_OWNED')),"
                + "CONSTRAINT `ck_provider_connection_owner` CHECK "
                + "((`account_mode`='SHARED_MASTER' AND `owner_tenant_id` IS NULL "
                + "AND `owner_ad_account_id` IS NULL) OR (`account_mode`='TENANT_OWNED' "
                + "AND `owner_tenant_id` IS NOT NULL AND `owner_ad_account_id` IS NOT NULL)),"
                + "CONSTRAINT `ck_provider_connection_state` CHECK "
                + "(`state` IN ('CONFIGURING','ACTIVE','MIGRATING','BLOCKED','RETIRED')))"
                + TABLE_OPTIONS + " COMMENT='Skit 阶段 1 全局 provider connection'";
    }

    private static String providerCallbackRouteTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_provider_callback_route` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`provider_connection_id` bigint NOT NULL,"
                + "`route_version` int NOT NULL,`key_hash` binary(32) DEFAULT NULL,"
                + "`purpose` varchar(16) NOT NULL,`state` varchar(16) NOT NULL DEFAULT 'DRAFT',"
                + "`route_slot` varchar(32) NOT NULL DEFAULT 'INACTIVE',"
                + "`occupied_route_slot` varchar(32) GENERATED ALWAYS AS (CASE WHEN "
                + "`route_slot`<>'INACTIVE' AND `state` NOT IN ('ABANDONED','RETIRED') "
                + "THEN `route_slot` ELSE NULL END) STORED,"
                + "`issued_at` datetime DEFAULT NULL,`submitted_at` datetime DEFAULT NULL,"
                + "`activated_at` datetime DEFAULT NULL,`accept_until` datetime DEFAULT NULL,"
                + "`blocked_at` datetime DEFAULT NULL,`retired_at` datetime DEFAULT NULL,"
                + "`created_by_user_id` bigint NOT NULL,`created_at` datetime NOT NULL,"
                + "`updated_by_user_id` bigint NOT NULL,`updated_at` datetime NOT NULL,"
                + "PRIMARY KEY (`id`),"
                + "UNIQUE KEY `uk_provider_callback_route_version` (`provider_connection_id`,`route_version`),"
                + "UNIQUE KEY `uk_provider_callback_route_key_hash` (`key_hash`),"
                + "UNIQUE KEY `uk_provider_callback_route_slot` (`provider_connection_id`,`occupied_route_slot`),"
                + "KEY `idx_provider_callback_route_state` (`provider_connection_id`,`state`,`id`),"
                + "CONSTRAINT `fk_provider_callback_route_connection` FOREIGN KEY (`provider_connection_id`) "
                + "REFERENCES `skit_ad_provider_connection` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_provider_callback_route_version` CHECK (`route_version`>0),"
                + "CONSTRAINT `ck_provider_callback_route_purpose` CHECK "
                + "(`purpose` IN ('GATE_TEST','PRODUCTION')),"
                + "CONSTRAINT `ck_provider_callback_route_state` CHECK "
                + "(`state` IN ('DRAFT','ISSUED','SUBMITTED','ACTIVE','BLOCKED','ABANDONED','RETIRED')),"
                + "CONSTRAINT `ck_provider_callback_route_slot` CHECK "
                + "(`route_slot` IN ('PRIMARY_ACCEPTING','MIGRATION_TARGET','INACTIVE')),"
                + "CONSTRAINT `ck_provider_callback_route_key_state` CHECK "
                + "((`state`='DRAFT' AND `key_hash` IS NULL AND `issued_at` IS NULL) OR "
                + "(`state`<>'DRAFT' AND `key_hash` IS NOT NULL AND `issued_at` IS NOT NULL)),"
                + "CONSTRAINT `ck_provider_callback_route_gate_state` CHECK "
                + "(`purpose`<>'GATE_TEST' OR `state` NOT IN ('SUBMITTED','ACTIVE')))"
                + TABLE_OPTIONS + " COMMENT='Skit 阶段 1 全局 provider callback route'";
    }

    private static String callbackRouteRegistryTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_callback_route_registry` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`key_hash` binary(32) NOT NULL,"
                + "`owner_type` varchar(32) NOT NULL,`provider_callback_route_id` bigint DEFAULT NULL,"
                + "`tenant_callback_key_id` bigint DEFAULT NULL,`registered_at` datetime NOT NULL,"
                + "PRIMARY KEY (`id`),"
                + "UNIQUE KEY `uk_callback_route_registry_key_hash` (`key_hash`),"
                + "UNIQUE KEY `uk_callback_route_registry_provider_route` (`provider_callback_route_id`),"
                + "UNIQUE KEY `uk_callback_route_registry_tenant_key` (`tenant_callback_key_id`),"
                + "CONSTRAINT `fk_callback_route_registry_provider_route` "
                + "FOREIGN KEY (`provider_callback_route_id`) REFERENCES `skit_ad_provider_callback_route` (`id`) "
                + "ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_callback_route_registry_tenant_key` FOREIGN KEY (`tenant_callback_key_id`) "
                + "REFERENCES `skit_ad_callback_key` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_callback_route_registry_owner_type` CHECK "
                + "(`owner_type` IN ('PROVIDER_CALLBACK_ROUTE','TENANT_CALLBACK_KEY')),"
                + "CONSTRAINT `ck_callback_route_registry_owner_xor` CHECK "
                + "((`owner_type`='PROVIDER_CALLBACK_ROUTE' AND `provider_callback_route_id` IS NOT NULL "
                + "AND `tenant_callback_key_id` IS NULL) OR (`owner_type`='TENANT_CALLBACK_KEY' "
                + "AND `provider_callback_route_id` IS NULL AND `tenant_callback_key_id` IS NOT NULL)))"
                + TABLE_OPTIONS + " COMMENT='Skit 阶段 1 全局 callback route registry'";
    }

    private static String callbackRouteRegistryMigrationTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_ad_callback_route_registry_migration` ("
                + "`singleton_id` tinyint NOT NULL,`migration_state` varchar(16) NOT NULL DEFAULT 'PENDING',"
                + "`last_callback_key_id` bigint NOT NULL DEFAULT 0,`last_batch_size` int NOT NULL DEFAULT 0,"
                + "`started_at` datetime DEFAULT NULL,`completed_at` datetime DEFAULT NULL,"
                + "`last_error_hash` binary(32) DEFAULT NULL,`created_at` datetime NOT NULL,"
                + "`updated_at` datetime NOT NULL,PRIMARY KEY (`singleton_id`),"
                + "CONSTRAINT `ck_callback_route_registry_migration_singleton` CHECK (`singleton_id`=1),"
                + "CONSTRAINT `ck_callback_route_registry_migration_state` CHECK "
                + "(`migration_state` IN ('PENDING','RUNNING','COMPLETED','BLOCKED')),"
                + "CONSTRAINT `ck_callback_route_registry_migration_cursor` CHECK "
                + "(`last_callback_key_id`>=0 AND `last_batch_size`>=0),"
                + "CONSTRAINT `ck_callback_route_registry_migration_time` CHECK "
                + "((`migration_state`='PENDING' AND `started_at` IS NULL AND `completed_at` IS NULL) OR "
                + "(`migration_state` IN ('RUNNING','BLOCKED') AND `started_at` IS NOT NULL "
                + "AND `completed_at` IS NULL) OR (`migration_state`='COMPLETED' "
                + "AND `started_at` IS NOT NULL AND `completed_at` IS NOT NULL)))"
                + TABLE_OPTIONS + " COMMENT='Skit 阶段 1 全局 registry migration state'";
    }

    private static String providerImpressionInboxTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_provider_impression_inbox` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`provider_connection_id` bigint NOT NULL,"
                + "`dedupe_scheme` varchar(32) NOT NULL,`dedupe_key_hash` binary(32) NOT NULL,"
                + "`canonical_attempt_id` bigint DEFAULT NULL,"
                + "`capture_status` varchar(16) NOT NULL DEFAULT 'CAPTURED',"
                + "`quarantine_reason` varchar(64) DEFAULT NULL,`delivery_count` int NOT NULL DEFAULT 1,"
                + "`first_received_at` datetime NOT NULL,`last_received_at` datetime NOT NULL,"
                + "PRIMARY KEY (`id`),"
                + "UNIQUE KEY `uk_provider_impression_inbox_dedupe` "
                + "(`provider_connection_id`,`dedupe_scheme`,`dedupe_key_hash`),"
                + "UNIQUE KEY `uk_provider_impression_inbox_canonical_attempt` (`canonical_attempt_id`),"
                + "KEY `idx_provider_impression_inbox_status` "
                + "(`provider_connection_id`,`capture_status`,`last_received_at`,`id`),"
                + "CONSTRAINT `fk_provider_impression_inbox_connection` FOREIGN KEY (`provider_connection_id`) "
                + "REFERENCES `skit_ad_provider_connection` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_provider_impression_inbox_dedupe_scheme` CHECK "
                + "(`dedupe_scheme` IN ('TAKU_REQ_ADSOURCE','WIRE_HASH_FALLBACK')),"
                + "CONSTRAINT `ck_provider_impression_inbox_status` CHECK "
                + "((`capture_status`='CAPTURED' AND `quarantine_reason` IS NULL) OR "
                + "(`capture_status`='QUARANTINED' AND `quarantine_reason` IS NOT NULL)),"
                + "CONSTRAINT `ck_provider_impression_inbox_delivery_count` CHECK (`delivery_count`>0),"
                + "CONSTRAINT `ck_provider_impression_inbox_time` CHECK "
                + "(`last_received_at`>=`first_received_at`))"
                + TABLE_OPTIONS + " COMMENT='Skit 阶段 1 全局 provider impression inbox'";
    }

    private static String providerCallbackAttemptTable() {
        return "CREATE TABLE IF NOT EXISTS `skit_provider_callback_attempt` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`correlation_id` binary(16) NOT NULL,"
                + "`provider_connection_id` bigint NOT NULL,`inbox_id` bigint NOT NULL,"
                + "`wire_hash` binary(32) NOT NULL,`payload_ciphertext` mediumblob NOT NULL,"
                + "`payload_nonce` binary(12) NOT NULL,`payload_key_id` varchar(64) NOT NULL,"
                + "`payload_purpose` varchar(64) NOT NULL,`payload_envelope_version` smallint NOT NULL,"
                + "`payload_expires_at` datetime NOT NULL,`wire_size_bytes` int NOT NULL,"
                + "`parameter_count` int NOT NULL,`received_at` datetime NOT NULL,"
                + "PRIMARY KEY (`id`),UNIQUE KEY `uk_provider_callback_attempt_correlation` (`correlation_id`),"
                + "KEY `idx_provider_callback_attempt_inbox` (`inbox_id`,`received_at`,`id`),"
                + "KEY `idx_provider_callback_attempt_expiry` (`payload_expires_at`,`id`),"
                + "CONSTRAINT `fk_provider_callback_attempt_connection` FOREIGN KEY (`provider_connection_id`) "
                + "REFERENCES `skit_ad_provider_connection` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `fk_provider_callback_attempt_inbox` FOREIGN KEY (`inbox_id`) "
                + "REFERENCES `skit_provider_impression_inbox` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                + "CONSTRAINT `ck_provider_callback_attempt_envelope` CHECK "
                + "(`payload_envelope_version`>0 AND CHAR_LENGTH(TRIM(`payload_key_id`))>0 "
                + "AND CHAR_LENGTH(TRIM(`payload_purpose`))>0),"
                + "CONSTRAINT `ck_provider_callback_attempt_boundary` CHECK "
                + "(`wire_size_bytes` BETWEEN 0 AND 32768 AND `parameter_count` BETWEEN 0 AND 64),"
                + "CONSTRAINT `ck_provider_callback_attempt_expiry` CHECK "
                + "(`payload_expires_at`>`received_at`))"
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
