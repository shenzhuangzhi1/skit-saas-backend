package cn.iocoder.yudao.module.skit.framework.schema;

import cn.iocoder.yudao.framework.common.util.validation.ValidationUtils;
import cn.iocoder.yudao.module.skit.controller.admin.tenant.vo.SkitAgentCreateReqVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 短剧 SaaS 模块幂等建表及存量表补丁。
 */
@Component
@Slf4j
public class SkitSchemaInitializer implements ApplicationRunner {

    private static final String CREATE_MIGRATION_TABLE_SQL = "CREATE TABLE IF NOT EXISTS `skit_schema_migration` ("
            + "`version` int NOT NULL,`description` varchar(255) NOT NULL,`checksum` char(64) NOT NULL,"
            + "`installed_on` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY (`version`))"
            + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    private static final String SELECT_APPLIED_MIGRATIONS_SQL =
            "SELECT `version`, `checksum` FROM `skit_schema_migration` ORDER BY `version`";
    private static final String INSERT_APPLIED_MIGRATION_SQL = "INSERT INTO `skit_schema_migration` "
            + "(`version`, `description`, `checksum`, `installed_on`) VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
    private static final String MIGRATION_LOCK_NAME = "skit_schema_migration";
    private static final int MIGRATION_LOCK_TIMEOUT_SECONDS = 10;
    private static final String ACQUIRE_MIGRATION_LOCK_SQL = "SELECT GET_LOCK(?, ?)";
    private static final String RELEASE_MIGRATION_LOCK_SQL = "SELECT RELEASE_LOCK(?)";
    private static final String COLUMN_EXISTS_QUERY = "SELECT COUNT(*) FROM information_schema.COLUMNS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
    private static final String COLUMN_DEFINITION_QUERY = "SELECT `COLUMN_TYPE`,`IS_NULLABLE`,`COLUMN_DEFAULT`,"
            + "`EXTRA`,`GENERATION_EXPRESSION` FROM information_schema.COLUMNS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
    private static final String INDEX_EXISTS_QUERY = "SELECT COUNT(*) FROM information_schema.STATISTICS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?";
    private static final String TABLE_EXISTS_QUERY = "SELECT COUNT(*) FROM information_schema.TABLES "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
    private static final String INDEX_DEFINITION_QUERY = "SELECT CONCAT(MIN(`NON_UNIQUE`),':',"
            + "GROUP_CONCAT(`COLUMN_NAME` ORDER BY `SEQ_IN_INDEX` SEPARATOR ',')) "
            + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
            + "AND TABLE_NAME = ? AND INDEX_NAME = ?";
    private static final String FOREIGN_KEY_DEFINITION_QUERY = "SELECT CONCAT("
            + "GROUP_CONCAT(`k`.`COLUMN_NAME` ORDER BY `k`.`ORDINAL_POSITION` SEPARATOR ','),'->',"
            + "MIN(`k`.`REFERENCED_TABLE_NAME`),'(',GROUP_CONCAT(`k`.`REFERENCED_COLUMN_NAME` "
            + "ORDER BY `k`.`ORDINAL_POSITION` SEPARATOR ','),'):',MIN(`r`.`UPDATE_RULE`),':',"
            + "MIN(`r`.`DELETE_RULE`)) FROM information_schema.KEY_COLUMN_USAGE `k` "
            + "JOIN information_schema.REFERENTIAL_CONSTRAINTS `r` ON `r`.`CONSTRAINT_SCHEMA`=`k`.`CONSTRAINT_SCHEMA` "
            + "AND `r`.`CONSTRAINT_NAME`=`k`.`CONSTRAINT_NAME` AND `r`.`TABLE_NAME`=`k`.`TABLE_NAME` "
            + "WHERE `k`.`TABLE_SCHEMA`=DATABASE() AND `k`.`TABLE_NAME`=? AND `k`.`CONSTRAINT_NAME`=? "
            + "AND `k`.`REFERENCED_TABLE_NAME` IS NOT NULL";
    private static final String CHECK_DEFINITION_QUERY = "SELECT `cc`.`CHECK_CLAUSE` FROM "
            + "information_schema.TABLE_CONSTRAINTS `tc` JOIN information_schema.CHECK_CONSTRAINTS `cc` "
            + "ON `cc`.`CONSTRAINT_SCHEMA`=`tc`.`CONSTRAINT_SCHEMA` AND `cc`.`CONSTRAINT_NAME`=`tc`.`CONSTRAINT_NAME` "
            + "WHERE `tc`.`TABLE_SCHEMA`=DATABASE() AND `tc`.`TABLE_NAME`=? AND `tc`.`CONSTRAINT_NAME`=? "
            + "AND `tc`.`CONSTRAINT_TYPE`='CHECK'";
    private static final String TRIGGER_DEFINITION_QUERY = "SELECT CONCAT(`ACTION_TIMING`,':',"
            + "`EVENT_MANIPULATION`,':',`EVENT_OBJECT_TABLE`,':',`ACTION_STATEMENT`) FROM "
            + "information_schema.TRIGGERS WHERE `TRIGGER_SCHEMA`=DATABASE() AND `TRIGGER_NAME`=?";
    private static final String TASK_2_INVITE_COLLISIONS_QUERY = "SELECT `normalized_code`,"
            + "GROUP_CONCAT(`owner_descriptor` ORDER BY `owner_descriptor` SEPARATOR ',') AS `owners` FROM ("
            + "SELECT UPPER(TRIM(`root_invite_code`)) AS `normalized_code`,"
            + "CONCAT('AGENT:',`tenant_id`,':',`id`) AS `owner_descriptor` FROM `skit_agent` "
            + "WHERE `deleted`=b'0' UNION ALL SELECT UPPER(TRIM(`invite_code`)) AS `normalized_code`,"
            + "CONCAT('MEMBER:',`tenant_id`,':',`id`) AS `owner_descriptor` FROM `skit_member` "
            + "WHERE `deleted`=b'0') `invite_owners` GROUP BY `normalized_code` HAVING COUNT(*) > 1 "
            + "ORDER BY `normalized_code`";
    private static final long TASK_2_MONEY_SCALE_FACTOR = 100000000L;
    private static final List<Task2ColumnSpec> TASK_2_PARENT_COLUMN_SPECS = Collections.unmodifiableList(
            Arrays.asList(
                    new Task2ColumnSpec("skit_ad_revenue_event", "placement_id",
                            "varchar(128) NOT NULL",
                            "varchar(128) NOT NULL DEFAULT '' COMMENT '广告位编号' AFTER `provider`"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "ad_session_id", "bigint DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "callback_inbox_id", "bigint DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "policy_snapshot_id", "bigint DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "reconciliation_bucket_id",
                            "bigint DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "reconciliation_revision_id",
                            "bigint DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "source_type",
                            "varchar(32) NOT NULL DEFAULT 'LEGACY_CLIENT'"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "provider_transaction_id",
                            "varchar(128) DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "provider_show_id",
                            "varchar(128) DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "sdk_request_id",
                            "varchar(128) DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "adsource_id",
                            "varchar(128) DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "source_amount_units",
                            "bigint NOT NULL DEFAULT 0"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "estimated_amount_units",
                            "bigint NOT NULL DEFAULT 0"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "reconciled_amount_units",
                            "bigint NOT NULL DEFAULT 0"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "amount_scale",
                            "tinyint NOT NULL DEFAULT 8"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "source_currency",
                            "char(3) NOT NULL DEFAULT 'CNY'"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "match_status",
                            "varchar(32) NOT NULL DEFAULT 'LEGACY_UNMATCHED'"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "source_verification_status",
                            "varchar(32) NOT NULL DEFAULT 'LEGACY_UNVERIFIED'"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "reward_qualification_status",
                            "varchar(32) NOT NULL DEFAULT 'NOT_APPLICABLE'"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "reconciliation_status",
                            "varchar(32) NOT NULL DEFAULT 'NON_SETTLEABLE'"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "reconciled_at", "datetime DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "verified_at", "datetime DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "payload_hash", "binary(32) DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "version", "int NOT NULL DEFAULT 0"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "legacy_unverified",
                            "bit(1) NOT NULL DEFAULT b'1'"),
                    new Task2ColumnSpec("skit_commission_ledger", "entry_type",
                            "varchar(32) NOT NULL DEFAULT 'LEGACY_ESTIMATE'"),
                    new Task2ColumnSpec("skit_commission_ledger", "balance_bucket",
                            "varchar(32) NOT NULL DEFAULT 'NON_SETTLEABLE'"),
                    new Task2ColumnSpec("skit_commission_ledger", "currency",
                            "char(3) NOT NULL DEFAULT 'CNY'"),
                    new Task2ColumnSpec("skit_commission_ledger", "gross_amount_units",
                            "bigint NOT NULL DEFAULT 0"),
                    new Task2ColumnSpec("skit_commission_ledger", "amount_units",
                            "bigint NOT NULL DEFAULT 0"),
                    new Task2ColumnSpec("skit_commission_ledger", "amount_scale",
                            "tinyint NOT NULL DEFAULT 8"),
                    new Task2ColumnSpec("skit_commission_ledger", "reversal_of_id", "bigint DEFAULT NULL"),
                    new Task2ColumnSpec("skit_commission_ledger", "reconciliation_revision_id",
                            "bigint DEFAULT NULL"),
                    new Task2ColumnSpec("skit_commission_ledger", "policy_snapshot_id", "bigint DEFAULT NULL"),
                    new Task2ColumnSpec("skit_commission_ledger", "revision_no", "int NOT NULL DEFAULT 0"),
                    new Task2ColumnSpec("skit_commission_ledger", "legacy_unverified",
                            "bit(1) NOT NULL DEFAULT b'1'"),
                    new Task2ColumnSpec("skit_commission_ledger", "beneficiary_member_ref_id",
                            "bigint GENERATED ALWAYS AS (CASE WHEN `beneficiary_type` = 1 "
                                    + "THEN `beneficiary_member_id` ELSE NULL END) STORED")));
    private static final List<Task2ForeignKeySpec> TASK_2_PARENT_FOREIGN_KEY_SPECS = Collections.unmodifiableList(
            Arrays.asList(
                    new Task2ForeignKeySpec("skit_commission_rule", "fk_skit_commission_rule_plan",
                            "tenant_id,plan_id", "skit_commission_plan", "tenant_id,id"),
                    new Task2ForeignKeySpec("skit_member", "fk_skit_member_inviter",
                            "tenant_id,inviter_id", "skit_member", "tenant_id,id"),
                    new Task2ForeignKeySpec("skit_member_closure", "fk_skit_member_closure_ancestor",
                            "tenant_id,ancestor_id", "skit_member", "tenant_id,id"),
                    new Task2ForeignKeySpec("skit_member_closure", "fk_skit_member_closure_descendant",
                            "tenant_id,descendant_id", "skit_member", "tenant_id,id"),
                    new Task2ForeignKeySpec("skit_ad_revenue_event", "fk_skit_revenue_event_account",
                            "tenant_id,ad_account_id", "skit_ad_account", "tenant_id,id"),
                    new Task2ForeignKeySpec("skit_ad_revenue_event", "fk_skit_revenue_event_member",
                            "tenant_id,source_member_id", "skit_member", "tenant_id,id"),
                    new Task2ForeignKeySpec("skit_commission_ledger", "fk_skit_ledger_event",
                            "tenant_id,event_id", "skit_ad_revenue_event", "tenant_id,id"),
                    new Task2ForeignKeySpec("skit_commission_ledger", "fk_skit_ledger_beneficiary_member",
                            "tenant_id,beneficiary_member_ref_id", "skit_member", "tenant_id,id"),
                    new Task2ForeignKeySpec("skit_ad_revenue_event", "fk_skit_revenue_event_session",
                            "tenant_id,ad_session_id", "skit_ad_session", "tenant_id,id"),
                    new Task2ForeignKeySpec("skit_ad_revenue_event", "fk_skit_revenue_event_inbox",
                            "tenant_id,callback_inbox_id", "skit_ad_callback_inbox", "tenant_id,id"),
                    new Task2ForeignKeySpec("skit_ad_revenue_event", "fk_skit_revenue_event_snapshot",
                            "tenant_id,policy_snapshot_id", "skit_ad_policy_snapshot", "tenant_id,id"),
                    new Task2ForeignKeySpec("skit_ad_revenue_event", "fk_skit_revenue_event_bucket",
                            "tenant_id,reconciliation_bucket_id", "skit_ad_reconciliation_bucket", "tenant_id,id"),
                    new Task2ForeignKeySpec("skit_ad_revenue_event", "fk_skit_revenue_event_revision",
                            "tenant_id,reconciliation_revision_id", "skit_ad_reconciliation_revision", "tenant_id,id"),
                    new Task2ForeignKeySpec("skit_commission_ledger", "fk_skit_ledger_reversal",
                            "tenant_id,reversal_of_id", "skit_commission_ledger", "tenant_id,id"),
                    new Task2ForeignKeySpec("skit_commission_ledger", "fk_skit_ledger_revision",
                            "tenant_id,reconciliation_revision_id", "skit_ad_reconciliation_revision", "tenant_id,id"),
                    new Task2ForeignKeySpec("skit_commission_ledger", "fk_skit_ledger_snapshot",
                            "tenant_id,policy_snapshot_id", "skit_ad_policy_snapshot", "tenant_id,id")));
    private static final List<Task2CheckSpec> TASK_2_PARENT_CHECK_SPECS = Collections.unmodifiableList(
            Arrays.asList(
                    new Task2CheckSpec("skit_commission_rule", "ck_skit_commission_rule_rate",
                            "`rate_bps` BETWEEN 0 AND 10000"),
                    new Task2CheckSpec("skit_member_closure", "ck_skit_member_closure_distance",
                            "`distance` >= 0"),
                    new Task2CheckSpec("skit_ad_revenue_event", "ck_skit_revenue_currency",
                            "REGEXP_LIKE(`source_currency`,'^[A-Z]{3}$')"),
                    new Task2CheckSpec("skit_ad_revenue_event", "ck_skit_revenue_scale",
                            "`amount_scale` BETWEEN 0 AND 18"),
                    new Task2CheckSpec("skit_ad_revenue_event", "ck_skit_revenue_amounts",
                            "`source_amount_units` >= 0 AND `estimated_amount_units` >= 0 "
                                    + "AND `reconciled_amount_units` >= 0"),
                    new Task2CheckSpec("skit_ad_revenue_event", "ck_skit_revenue_legacy",
                            "`legacy_unverified` = b'0' OR (`source_type` = 'LEGACY_CLIENT' "
                                    + "AND `source_verification_status` = 'LEGACY_UNVERIFIED' "
                                    + "AND `reward_qualification_status` = 'NOT_APPLICABLE' "
                                    + "AND `reconciliation_status` = 'NON_SETTLEABLE')"),
                    new Task2CheckSpec("skit_ad_revenue_event", "ck_skit_revenue_reward_qualification",
                            "`reward_qualification_status` IN "
                                    + "('NOT_APPLICABLE','PENDING_REWARD','REWARDED','NON_REWARDED')"),
                    new Task2CheckSpec("skit_commission_ledger", "ck_skit_ledger_currency",
                            "REGEXP_LIKE(`currency`,'^[A-Z]{3}$')"),
                    new Task2CheckSpec("skit_commission_ledger", "ck_skit_ledger_scale",
                            "`amount_scale` BETWEEN 0 AND 18"),
                    new Task2CheckSpec("skit_commission_ledger", "ck_skit_ledger_rate",
                            "`rate_bps` BETWEEN 0 AND 10000"),
                    new Task2CheckSpec("skit_commission_ledger", "ck_skit_ledger_revision_no",
                            "`revision_no` >= 0"),
                    new Task2CheckSpec("skit_commission_ledger", "ck_skit_ledger_beneficiary",
                            "(`beneficiary_type` = 1 AND `beneficiary_member_id` > 0) OR "
                                    + "(`beneficiary_type` = 2 AND `beneficiary_member_id` = 0)"),
                    new Task2CheckSpec("skit_commission_ledger", "ck_skit_ledger_legacy",
                            "`legacy_unverified` = b'0' OR (`entry_type` = 'LEGACY_ESTIMATE' "
                                    + "AND `balance_bucket` = 'NON_SETTLEABLE' AND `revision_no` = 0)")));
    private static final List<Task2TriggerSpec> TASK_2_PARENT_TRIGGER_SPECS = Collections.unmodifiableList(
            Arrays.asList(
                    new Task2TriggerSpec("skit_ad_revenue_event", "trg_skit_revenue_legacy_immutable",
                            legacyImmutableAction("legacy revenue facts are immutable",
                                    "id", "tenant_id", "ad_account_id", "provider", "placement_id",
                                    "external_event_id", "source_member_id", "gross_amount", "occurred_time",
                                    "completed", "mock", "status", "rule_version", "raw_data", "ad_session_id",
                                    "callback_inbox_id", "policy_snapshot_id", "reconciliation_bucket_id",
                                    "reconciliation_revision_id", "source_type", "provider_transaction_id",
                                    "provider_show_id", "sdk_request_id", "adsource_id", "source_amount_units",
                                    "estimated_amount_units", "reconciled_amount_units", "amount_scale",
                                    "source_currency", "match_status", "source_verification_status",
                                    "reward_qualification_status", "reconciliation_status", "reconciled_at",
                                    "verified_at", "payload_hash", "version", "legacy_unverified", "creator",
                                    "create_time", "deleted")),
                    new Task2TriggerSpec("skit_commission_ledger", "trg_skit_ledger_legacy_immutable",
                            legacyImmutableAction("legacy ledger facts are immutable",
                                    "id", "tenant_id", "event_id", "beneficiary_type", "beneficiary_member_id",
                                    "level_no", "gross_amount", "rate_bps", "amount", "rule_version", "status",
                                    "entry_type", "balance_bucket", "currency", "gross_amount_units", "amount_units",
                                    "amount_scale", "reversal_of_id", "reconciliation_revision_id",
                                    "policy_snapshot_id", "revision_no", "legacy_unverified", "creator",
                                    "create_time", "deleted")),
                    new Task2TriggerSpec("skit_ad_callback_key", "trg_skit_callback_key_immutable",
                            credentialUpdateAction("id", "tenant_id", "ad_account_id", "key_version",
                                    "callback_key_hash", "creator", "create_time", "deleted")),
                    new Task2TriggerSpec("skit_ad_callback_key", "trg_skit_callback_key_no_delete", "DELETE",
                            rejectDeleteAction("credential version rows cannot be deleted")),
                    new Task2TriggerSpec("skit_ad_reward_secret_version", "trg_skit_reward_secret_immutable",
                            credentialUpdateAction("id", "tenant_id", "ad_account_id", "secret_version",
                                    "ciphertext", "nonce", "encryption_key_id", "envelope_version", "creator",
                                    "create_time", "deleted")),
                    new Task2TriggerSpec("skit_ad_reward_secret_version", "trg_skit_reward_secret_no_delete", "DELETE",
                            rejectDeleteAction("credential version rows cannot be deleted")),
                    new Task2TriggerSpec("skit_invite_code_registry", "trg_skit_invite_registry_immutable",
                            inviteRegistryUpdateAction("id", "tenant_id", "code", "owner_type", "agent_id",
                                    "member_id", "creator", "create_time", "deleted")),
                    new Task2TriggerSpec("skit_invite_code_registry", "trg_skit_invite_registry_no_delete", "DELETE",
                            rejectDeleteAction("invite code registry rows cannot be deleted"))));
    private static final int POLICY_SNAPSHOT_IMMUTABILITY_MIGRATION_VERSION = 2026071402;
    private static final String POLICY_SNAPSHOT_IMMUTABLE_ACTION =
            "BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='policy snapshot rows are immutable'; END";
    private static final List<Task2TriggerSpec> POLICY_SNAPSHOT_IMMUTABILITY_TRIGGER_SPECS =
            Collections.unmodifiableList(Arrays.asList(
                    new Task2TriggerSpec("skit_ad_policy_snapshot", "trg_skit_policy_snapshot_immutable",
                            POLICY_SNAPSHOT_IMMUTABLE_ACTION),
                    new Task2TriggerSpec("skit_ad_policy_snapshot", "trg_skit_policy_snapshot_no_delete", "DELETE",
                            POLICY_SNAPSHOT_IMMUTABLE_ACTION)));
    private static final int TASK_5_SCHEMA_HARDENING_MIGRATION_VERSION = 2026071403;
    private static final List<Task2ColumnSpec> TASK_5_SCHEMA_COLUMN_SPECS = Collections.unmodifiableList(
            Arrays.asList(
                    new Task2ColumnSpec("skit_ad_session", "session_token_key_version",
                            "int NOT NULL DEFAULT 1"),
                    new Task2ColumnSpec("skit_ad_session", "access_mode",
                            "varchar(32) NOT NULL DEFAULT 'MEMBER_OAUTH'"),
                    new Task2ColumnSpec("skit_ad_session", "native_player_grant_id", "bigint DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_session", "active_scope_released_at", "datetime DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_session", "active_scope_release_reason",
                            "varchar(32) DEFAULT NULL"),
                    new Task2ColumnSpec("skit_native_player_grant", "version", "int NOT NULL DEFAULT 0"),
                    new Task2ColumnSpec("skit_entitlement_grant", "entitlement_id", "bigint NOT NULL",
                            "bigint DEFAULT NULL")));
    private static final List<Task2ForeignKeySpec> TASK_5_SCHEMA_FOREIGN_KEY_SPECS =
            Collections.unmodifiableList(Arrays.asList(
                    new Task2ForeignKeySpec("skit_ad_session", "fk_skit_ad_session_player_grant",
                            "tenant_id,native_player_grant_id,member_id,drama_id", "skit_native_player_grant",
                            "tenant_id,id,member_id,drama_id"),
                    new Task2ForeignKeySpec("skit_entitlement_grant", "fk_skit_grant_session_binding",
                            "tenant_id,ad_session_id,member_id,drama_id,episode_no,provider_transaction_id",
                            "skit_ad_session",
                            "tenant_id,id,member_id,drama_id,episode_from,provider_transaction_id"),
                    new Task2ForeignKeySpec("skit_entitlement_grant", "fk_skit_grant_entitlement_binding",
                            "tenant_id,entitlement_id,member_id,drama_id,episode_no", "skit_content_entitlement",
                            "tenant_id,id,member_id,drama_id,episode_no")));
    private static final List<Task2CheckSpec> TASK_5_SCHEMA_CHECK_SPECS = Collections.unmodifiableList(
            Arrays.asList(
                    new Task2CheckSpec("skit_ad_session", "ck_skit_ad_session_token_key_version",
                            "`session_token_key_version` > 0"),
                    new Task2CheckSpec("skit_ad_session", "ck_skit_ad_session_access_mode",
                            "(`access_mode` = 'MEMBER_OAUTH' AND `native_player_grant_id` IS NULL) OR "
                                    + "(`access_mode` = 'NATIVE_PLAYER_GRANT' "
                                    + "AND `native_player_grant_id` IS NOT NULL)"),
                    new Task2CheckSpec("skit_ad_session", "ck_skit_ad_session_scope_release_pair",
                            "(`active_scope_hash` IS NOT NULL AND `active_scope_released_at` IS NULL "
                                    + "AND `active_scope_release_reason` IS NULL) OR (`active_scope_hash` IS NULL "
                                    + "AND `active_scope_released_at` IS NOT NULL "
                                    + "AND `active_scope_release_reason` IS NOT NULL)"),
                    new Task2CheckSpec("skit_ad_session", "ck_skit_ad_session_scope_release_lifecycle",
                            "(`active_scope_release_reason` IS NULL "
                                    + "AND `active_scope_hash` IS NOT NULL "
                                    + "AND `active_scope_released_at` IS NULL "
                                    + "AND `reward_verification_status` = 'PENDING' "
                                    + "AND `entitlement_status` = 'NONE' AND `entitled_at` IS NULL) OR "
                                    + "(`active_scope_release_reason` = 'VERIFY_TIMEOUT' "
                                    + "AND `active_scope_hash` IS NULL "
                                    + "AND `active_scope_released_at` IS NOT NULL "
                                    + "AND `reward_verification_status` = 'VERIFY_TIMEOUT' "
                                    + "AND `entitlement_status` = 'NONE' AND `entitled_at` IS NULL) OR "
                                    + "(`active_scope_release_reason` = 'REWARD_REJECTED' "
                                    + "AND `active_scope_hash` IS NULL "
                                    + "AND `active_scope_released_at` IS NOT NULL "
                                    + "AND `reward_verification_status` = 'REJECTED' "
                                    + "AND `entitlement_status` = 'NONE' AND `entitled_at` IS NULL) OR "
                                    + "(`active_scope_release_reason` = 'ENTITLEMENT_GRANTED' "
                                    + "AND `active_scope_hash` IS NULL "
                                    + "AND `active_scope_released_at` IS NOT NULL "
                                    + "AND `reward_verification_status` = 'SIGNED_VERIFIED' "
                                    + "AND `entitlement_status` IN ('GRANTED','SECURITY_REVOKED') "
                                    + "AND `entitled_at` IS NOT NULL)"),
                    new Task2CheckSpec("skit_native_player_grant", "ck_skit_player_grant_version",
                            "`version` >= 0"),
                    new Task2CheckSpec("skit_native_player_grant", "ck_skit_player_grant_lifecycle",
                            "(`status` IN ('ACTIVE','EXPIRED') AND `revoked_at` IS NULL) OR "
                                    + "(`status` = 'REVOKED' AND `revoked_at` IS NOT NULL)")));
    private static final int TASK_7_SCHEMA_HARDENING_MIGRATION_VERSION = 2026071404;
    private static final List<Task2ColumnSpec> TASK_7_SCHEMA_COLUMN_SPECS = Collections.unmodifiableList(
            Arrays.asList(
                    new Task2ColumnSpec("skit_ad_session", "reward_callback_inbox_id",
                            "bigint DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_session", "reward_callback_received_at",
                            "datetime DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_callback_inbox", "ingress_response_code",
                            "smallint DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_callback_inbox", "ad_session_ref_id",
                            "bigint GENERATED ALWAYS AS (IFNULL(`ad_session_id`,0)) STORED"),
                    new Task2ColumnSpec("skit_ad_callback_attempt", "ad_session_ref_id",
                            "bigint GENERATED ALWAYS AS (IFNULL(`ad_session_id`,0)) STORED"),
                    new Task2ColumnSpec("skit_ad_revenue_event", "ad_session_ref_id",
                            "bigint GENERATED ALWAYS AS (IFNULL(`ad_session_id`,0)) STORED")));
    private static final List<Task2ForeignKeySpec> TASK_7_SCHEMA_FOREIGN_KEY_SPECS =
            Collections.unmodifiableList(Arrays.asList(
                    new Task2ForeignKeySpec("skit_ad_callback_inbox",
                            "fk_skit_callback_inbox_session_account",
                            "tenant_id,ad_session_id,ad_account_id", "skit_ad_session",
                            "tenant_id,id,ad_account_id"),
                    new Task2ForeignKeySpec("skit_ad_callback_attempt",
                            "fk_skit_callback_attempt_inbox_binding",
                            "tenant_id,callback_inbox_id,ad_account_id,ad_session_ref_id",
                            "skit_ad_callback_inbox", "tenant_id,id,ad_account_id,ad_session_ref_id"),
                    new Task2ForeignKeySpec("skit_ad_session",
                            "fk_skit_ad_session_reward_callback_receipt",
                            "tenant_id,reward_callback_inbox_id,ad_account_id,id",
                            "skit_ad_callback_inbox", "tenant_id,id,ad_account_id,ad_session_ref_id"),
                    new Task2ForeignKeySpec("skit_ad_revenue_event", "fk_skit_revenue_session_binding",
                            "tenant_id,ad_session_id,ad_account_id,source_member_id,policy_snapshot_id",
                            "skit_ad_session", "tenant_id,id,ad_account_id,member_id,policy_snapshot_id"),
                    new Task2ForeignKeySpec("skit_ad_revenue_event", "fk_skit_revenue_inbox_binding",
                            "tenant_id,callback_inbox_id,ad_account_id,ad_session_ref_id",
                            "skit_ad_callback_inbox", "tenant_id,id,ad_account_id,ad_session_ref_id"),
                    new Task2ForeignKeySpec("skit_commission_ledger", "fk_skit_ledger_event_snapshot",
                            "tenant_id,event_id,policy_snapshot_id", "skit_ad_revenue_event",
                            "tenant_id,id,policy_snapshot_id")));
    private static final Task2ForeignKeySpec TASK_7_LEGACY_GRANT_SESSION_BINDING =
            new Task2ForeignKeySpec("skit_entitlement_grant", "fk_skit_grant_session_binding",
                    "tenant_id,ad_session_id,member_id,drama_id,episode_no,provider_transaction_id",
                    "skit_ad_session", "tenant_id,id,member_id,drama_id,episode_from,provider_transaction_id");
    private static final Task2ForeignKeySpec TASK_7_GRANT_SESSION_BINDING =
            new Task2ForeignKeySpec("skit_entitlement_grant", "fk_skit_grant_session_binding",
                    "tenant_id,ad_session_id,member_id,drama_id,provider_transaction_id",
                    "skit_ad_session", "tenant_id,id,member_id,drama_id,provider_transaction_id");
    private static final List<Task2CheckSpec> TASK_7_SCHEMA_CHECK_SPECS = Collections.unmodifiableList(
            Arrays.asList(
                    new Task2CheckSpec("skit_ad_session", "ck_skit_ad_session_reward_callback_receipt",
                            "(`reward_callback_inbox_id` IS NULL AND `reward_callback_received_at` IS NULL) OR "
                                    + "(`reward_callback_inbox_id` IS NOT NULL "
                                    + "AND `reward_callback_received_at` IS NOT NULL "
                                    + "AND `reward_callback_received_at` <= `reward_accept_until`)"),
                    new Task2CheckSpec("skit_ad_callback_inbox", "ck_skit_callback_inbox_response_code",
                            "`ingress_response_code` IS NULL OR `ingress_response_code` IN (200,601,602)"),
                    new Task2CheckSpec("skit_ad_revenue_event", "ck_skit_revenue_verified_binding",
                            "`legacy_unverified`=b'1' OR (`ad_session_id` IS NOT NULL "
                                    + "AND `callback_inbox_id` IS NOT NULL "
                                    + "AND `policy_snapshot_id` IS NOT NULL)"),
                    new Task2CheckSpec("skit_commission_ledger", "ck_skit_ledger_verified_snapshot",
                            "`legacy_unverified`=b'1' OR `policy_snapshot_id` IS NOT NULL"),
                    new Task2CheckSpec("skit_ad_network_capability",
                            "ck_skit_network_cap_reward_authority",
                            "`reward_authority` IN ('SIGNED_REWARD','UNSIGNED_PROVIDER_OBSERVATION',"
                                    + "'CLIENT_ONLY','NONE')"),
                    new Task2CheckSpec("skit_ad_network_capability",
                            "ck_skit_network_cap_signed_readiness",
                            "(`reward_authority`<>'SIGNED_REWARD') OR ((`supports_user_id`=b'1') "
                                    + "AND (`supports_custom_data`=b'1') "
                                    + "AND (`supports_stable_transaction`=b'1') "
                                    + "AND `verified_at` IS NOT NULL)"),
                    new Task2CheckSpec("skit_ad_callback_inbox",
                            "ck_skit_callback_inbox_processing_error",
                            "(`processing_status` IN ('PENDING','PROCESSING','SUCCEEDED') "
                                    + "AND (`error_code` IS NULL)) OR (`processing_status` IN "
                                    + "('RETRY_WAIT','REJECTED','DEAD_LETTER') AND (`error_code` IS NOT NULL))"),
                    new Task2CheckSpec("skit_ad_callback_inbox",
                            "ck_skit_callback_inbox_dead_letter_alert",
                            "`dead_letter_alerted_at` IS NULL OR (`processing_status`='DEAD_LETTER' "
                                    + "AND `processed_at` IS NOT NULL "
                                    + "AND `dead_letter_alerted_at`>=`processed_at`)")));
    private static final List<Task2TriggerSpec> TASK_7_SCHEMA_TRIGGER_SPECS = Collections.unmodifiableList(
            Arrays.asList(
                    new Task2TriggerSpec("skit_commission_ledger", "trg_skit_ledger_immutable",
                            rejectDeleteAction("commission ledger rows are immutable")),
                    new Task2TriggerSpec("skit_commission_ledger", "trg_skit_ledger_no_delete", "DELETE",
                            rejectDeleteAction("commission ledger rows are immutable")),
                    new Task2TriggerSpec("skit_entitlement_grant", "trg_skit_entitlement_grant_immutable",
                            rejectDeleteAction("entitlement grant rows are immutable")),
                    new Task2TriggerSpec("skit_entitlement_grant", "trg_skit_entitlement_grant_no_delete",
                            "DELETE", rejectDeleteAction("entitlement grant rows are immutable")),
                    new Task2TriggerSpec("skit_ad_callback_inbox", "trg_skit_callback_inbox_monotonic",
                            callbackInboxMonotonicAction()),
                    new Task2TriggerSpec("skit_ad_callback_inbox", "trg_skit_callback_inbox_no_delete", "DELETE",
                            rejectDeleteAction("callback inbox rows cannot be deleted")),
                    new Task2TriggerSpec("skit_ad_callback_attempt", "trg_skit_callback_attempt_immutable",
                            rejectDeleteAction("callback attempt rows are immutable")),
                    new Task2TriggerSpec("skit_ad_callback_edge_attempt",
                            "trg_skit_callback_edge_attempt_immutable",
                            rejectDeleteAction("callback edge attempt rows are immutable")),
                    new Task2TriggerSpec("skit_entitlement_grant",
                            "trg_skit_entitlement_grant_session_range", "INSERT",
                            entitlementGrantSessionRangeAction())));
    private static final int TASK_10_RECONCILIATION_MIGRATION_VERSION = 2026071405;
    private static final List<Task2ColumnSpec> TASK_10_RECONCILIATION_COLUMN_SPECS =
            Collections.unmodifiableList(Arrays.asList(
                    new Task2ColumnSpec("skit_ad_account", "report_timezone",
                            "varchar(64) NOT NULL DEFAULT 'UTC+8'"),
                    new Task2ColumnSpec("skit_ad_account", "report_currency",
                            "char(3) NOT NULL DEFAULT 'USD'"),
                    new Task2ColumnSpec("skit_ad_account", "report_amount_scale",
                            "tinyint NOT NULL DEFAULT 8"),
                    new Task2ColumnSpec("skit_ad_account", "report_pull_lease_owner",
                            "varchar(64) DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_account", "report_pull_lease_until",
                            "datetime DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_account", "report_next_allowed_at",
                            "datetime DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_account", "report_last_success_at",
                            "datetime DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_account", "report_failure_count",
                            "int NOT NULL DEFAULT 0"),
                    new Task2ColumnSpec("skit_ad_report_pull", "report_date", "date NOT NULL",
                            "date DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_report_pull", "report_timezone",
                            "varchar(64) NOT NULL DEFAULT 'UTC+8'"),
                    new Task2ColumnSpec("skit_ad_report_pull", "currency",
                            "char(3) NOT NULL DEFAULT 'USD'"),
                    new Task2ColumnSpec("skit_ad_report_pull", "amount_scale",
                            "tinyint NOT NULL DEFAULT 8"),
                    new Task2ColumnSpec("skit_ad_report_pull", "request_hash", "binary(32) NOT NULL",
                            "binary(32) DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_report_pull", "credential_version", "int DEFAULT NULL"),
                    new Task2ColumnSpec("skit_ad_report_pull", "final_window",
                            "bit(1) NOT NULL DEFAULT b'0'"),
                    new Task2ColumnSpec("skit_ad_reconciliation_bucket", "app_id",
                            "varchar(128) NOT NULL DEFAULT ''"),
                    new Task2ColumnSpec("skit_ad_reconciliation_bucket", "ad_format",
                            "varchar(32) NOT NULL DEFAULT ''"),
                    new Task2ColumnSpec("skit_ad_reconciliation_bucket", "network_account_id",
                            "varchar(128) NOT NULL DEFAULT ''"),
                    new Task2ColumnSpec("skit_ad_reconciliation_bucket", "attributable_actual_units",
                            "bigint NOT NULL DEFAULT 0"),
                    new Task2ColumnSpec("skit_ad_reconciliation_bucket", "suspense_units",
                            "bigint NOT NULL DEFAULT 0"),
                    new Task2ColumnSpec("skit_ad_reconciliation_bucket", "report_impressions_available",
                            "bit(1) NOT NULL DEFAULT b'1'"),
                    new Task2ColumnSpec("skit_ad_reconciliation_revision", "source_report_impressions",
                            "bigint NOT NULL DEFAULT 0"),
                    new Task2ColumnSpec("skit_ad_reconciliation_revision",
                            "source_report_impressions_available",
                            "bit(1) NOT NULL DEFAULT b'1'"),
                    new Task2ColumnSpec("skit_ad_reconciliation_revision", "matched_event_count",
                            "bigint NOT NULL DEFAULT 0"),
                    new Task2ColumnSpec("skit_ad_reconciliation_revision", "status",
                            "varchar(32) NOT NULL DEFAULT 'APPLIED'")));
    private static final List<Task2CheckSpec> TASK_10_RECONCILIATION_CHECK_SPECS =
            Collections.unmodifiableList(Arrays.asList(
                    new Task2CheckSpec("skit_ad_account", "ck_skit_ad_account_report_currency",
                            "REGEXP_LIKE(`report_currency`,'^[A-Z]{3}$')"),
                    new Task2CheckSpec("skit_ad_account", "ck_skit_ad_account_report_timezone",
                            "`report_timezone` IN ('UTC-8','UTC+8','UTC+0')"),
                    new Task2CheckSpec("skit_ad_account", "ck_skit_ad_account_report_scale",
                            "`report_amount_scale` BETWEEN 0 AND 18"),
                    new Task2CheckSpec("skit_ad_account", "ck_skit_ad_account_report_lease",
                            "(`report_pull_lease_owner` IS NULL AND `report_pull_lease_until` IS NULL) OR "
                                    + "(`report_pull_lease_owner` IS NOT NULL "
                                    + "AND `report_pull_lease_until` IS NOT NULL)"),
                    new Task2CheckSpec("skit_ad_account", "ck_skit_ad_account_report_failures",
                            "`report_failure_count` BETWEEN 0 AND 5"),
                    new Task2CheckSpec("skit_ad_report_pull", "ck_skit_report_pull_money",
                            "REGEXP_LIKE(`currency`,'^[A-Z]{3}$') AND `amount_scale` BETWEEN 0 AND 18"),
                    new Task2CheckSpec("skit_ad_report_pull", "ck_skit_report_pull_timezone",
                            "`report_timezone` IN ('UTC-8','UTC+8','UTC+0')"),
                    new Task2CheckSpec("skit_ad_report_pull", "ck_skit_report_pull_credential_version",
                            "`credential_version` IS NULL OR `credential_version` > 0"),
                    new Task2CheckSpec("skit_ad_report_pull", "ck_skit_report_pull_status",
                            "(`status`='SUCCEEDED' AND `error_code` IS NULL) OR "
                                    + "(`status`='FAILED' AND `error_code` IS NOT NULL)"),
                    new Task2CheckSpec("skit_ad_reconciliation_bucket", "ck_skit_recon_bucket_task10_amounts",
                            "`attributable_actual_units` >= 0 AND `suspense_units` >= 0 "
                                    + "AND `attributable_actual_units` + `suspense_units` "
                                    + "= `report_actual_units`"),
                    new Task2CheckSpec("skit_ad_reconciliation_bucket",
                            "ck_skit_recon_bucket_task10_impressions",
                            "`report_impressions_available`=b'1' OR `report_impressions`=0"),
                    new Task2CheckSpec("skit_ad_reconciliation_revision", "ck_skit_recon_revision_task10_counts",
                            "`source_report_impressions` >= 0 AND `matched_event_count` >= 0"),
                    new Task2CheckSpec("skit_ad_reconciliation_revision",
                            "ck_skit_recon_revision_task10_impressions",
                            "`source_report_impressions_available`=b'1' "
                                    + "OR `source_report_impressions`=0"),
                    new Task2CheckSpec("skit_ad_reconciliation_revision", "ck_skit_recon_revision_task10_status",
                            "`status` IN ('APPLIED','PARTIAL','SUSPENSE','FAILED')"),
                    new Task2CheckSpec("skit_ad_reconciliation_event_link",
                            "ck_skit_recon_event_link_revision", "`revision_no` > 0"),
                    new Task2CheckSpec("skit_ad_reconciliation_event_link",
                            "ck_skit_recon_event_link_status",
                            "`association_status` IN ('ATTRIBUTED','SUSPENSE')"),
                    new Task2CheckSpec("skit_ad_reconciliation_event_link",
                            "ck_skit_recon_event_link_amount",
                            "`actual_units` >= 0 AND (`association_status`<>'SUSPENSE' "
                                    + "OR `actual_units`=0)")));
    private static final List<Task2TriggerSpec> TASK_10_RECONCILIATION_TRIGGER_SPECS =
            Collections.unmodifiableList(Arrays.asList(
                    new Task2TriggerSpec("skit_ad_reporting_credential_version",
                            "trg_skit_reporting_credential_monotonic", reportingCredentialUpdateAction()),
                    new Task2TriggerSpec("skit_ad_reporting_credential_version",
                            "trg_skit_reporting_credential_no_delete", "DELETE",
                            rejectDeleteAction("reporting credential versions cannot be deleted")),
                    new Task2TriggerSpec("skit_ad_report_pull", "trg_skit_report_pull_immutable",
                            rejectDeleteAction("report pull rows are immutable")),
                    new Task2TriggerSpec("skit_ad_report_pull", "trg_skit_report_pull_no_delete", "DELETE",
                            rejectDeleteAction("report pull rows are immutable")),
                    new Task2TriggerSpec("skit_ad_reconciliation_revision",
                            "trg_skit_recon_revision_immutable",
                            rejectDeleteAction("reconciliation revision rows are immutable")),
                    new Task2TriggerSpec("skit_ad_reconciliation_revision",
                            "trg_skit_recon_revision_no_delete", "DELETE",
                            rejectDeleteAction("reconciliation revision rows are immutable")),
                    new Task2TriggerSpec("skit_ad_reconciliation_allocation",
                            "trg_skit_recon_allocation_immutable",
                            rejectDeleteAction("reconciliation allocation rows are immutable")),
                    new Task2TriggerSpec("skit_ad_reconciliation_allocation",
                            "trg_skit_recon_allocation_no_delete", "DELETE",
                            rejectDeleteAction("reconciliation allocation rows are immutable")),
                    new Task2TriggerSpec("skit_ad_reconciliation_event_link",
                            "trg_skit_recon_event_link_immutable",
                            rejectDeleteAction("reconciliation event links are immutable")),
                    new Task2TriggerSpec("skit_ad_reconciliation_event_link",
                            "trg_skit_recon_event_link_no_delete", "DELETE",
                            rejectDeleteAction("reconciliation event links are immutable"))));
    private static final String CREATE_TASK_10_REPORTING_CREDENTIAL_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS `skit_ad_reporting_credential_version` ("
                    + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,"
                    + "`ad_account_id` bigint NOT NULL,`credential_version` int NOT NULL,"
                    + "`ciphertext` varbinary(4096) NOT NULL,`nonce` binary(12) NOT NULL,"
                    + "`encryption_key_id` varchar(64) NOT NULL,`envelope_version` smallint NOT NULL,"
                    + "`active` bit(1) NOT NULL DEFAULT b'1',`permission_verified_at` datetime DEFAULT NULL,"
                    + "`revoked_at` datetime DEFAULT NULL,`active_account_id` bigint GENERATED ALWAYS AS "
                    + "(CASE WHEN `active`=b'1' THEN `ad_account_id` ELSE NULL END) STORED,"
                    + "`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP "
                    + "ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',"
                    + "PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_reporting_credential_tenant_id` (`tenant_id`,`id`),"
                    + "UNIQUE KEY `uk_skit_reporting_credential_version` "
                    + "(`tenant_id`,`ad_account_id`,`credential_version`),"
                    + "UNIQUE KEY `uk_skit_reporting_credential_active` (`tenant_id`,`active_account_id`),"
                    + "CONSTRAINT `fk_skit_reporting_credential_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) "
                    + "REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                    + "CONSTRAINT `ck_skit_reporting_credential_version` CHECK (`credential_version` > 0),"
                    + "CONSTRAINT `ck_skit_reporting_credential_envelope` CHECK (`envelope_version` > 0),"
                    + "CONSTRAINT `ck_skit_reporting_credential_lifecycle` CHECK "
                    + "((`active`=b'1' AND `revoked_at` IS NULL) OR "
                    + "(`active`=b'0' AND `revoked_at` IS NOT NULL)))"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    private static final String CREATE_TASK_10_RECONCILIATION_ALLOCATION_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS `skit_ad_reconciliation_allocation` ("
                    + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,"
                    + "`reconciliation_bucket_id` bigint NOT NULL,`reconciliation_revision_id` bigint NOT NULL,"
                    + "`revision_no` int NOT NULL,`event_id` bigint NOT NULL,`beneficiary_type` tinyint NOT NULL,"
                    + "`beneficiary_member_id` bigint NOT NULL DEFAULT 0,`level_no` int NOT NULL,"
                    + "`policy_snapshot_id` bigint NOT NULL,`currency` char(3) NOT NULL,"
                    + "`amount_scale` tinyint NOT NULL,`cumulative_target_units` bigint NOT NULL,"
                    + "`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP "
                    + "ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',"
                    + "PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_recon_allocation_tenant_id` (`tenant_id`,`id`),"
                    + "UNIQUE KEY `uk_skit_recon_allocation_canonical` "
                    + "(`tenant_id`,`event_id`,`reconciliation_revision_id`,`beneficiary_type`,"
                    + "`beneficiary_member_id`,`level_no`,`policy_snapshot_id`),"
                    + "KEY `idx_skit_recon_allocation_prior` "
                    + "(`tenant_id`,`event_id`,`beneficiary_type`,`beneficiary_member_id`,`level_no`,`revision_no`),"
                    + "CONSTRAINT `fk_skit_recon_allocation_bucket` FOREIGN KEY "
                    + "(`tenant_id`,`reconciliation_bucket_id`) REFERENCES `skit_ad_reconciliation_bucket` "
                    + "(`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                    + "CONSTRAINT `fk_skit_recon_allocation_revision` FOREIGN KEY "
                    + "(`tenant_id`,`reconciliation_revision_id`) REFERENCES `skit_ad_reconciliation_revision` "
                    + "(`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                    + "CONSTRAINT `fk_skit_recon_allocation_event_snapshot` FOREIGN KEY "
                    + "(`tenant_id`,`event_id`,`policy_snapshot_id`) REFERENCES `skit_ad_revenue_event` "
                    + "(`tenant_id`,`id`,`policy_snapshot_id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                    + "CONSTRAINT `ck_skit_recon_allocation_revision` CHECK (`revision_no` > 0),"
                    + "CONSTRAINT `ck_skit_recon_allocation_beneficiary` CHECK "
                    + "((`beneficiary_type`=1 AND `beneficiary_member_id`>0) OR "
                    + "(`beneficiary_type`=2 AND `beneficiary_member_id`=0)),"
                    + "CONSTRAINT `ck_skit_recon_allocation_money` CHECK "
                    + "(REGEXP_LIKE(`currency`,'^[A-Z]{3}$') AND `amount_scale` BETWEEN 0 AND 18 "
                    + "AND `cumulative_target_units` >= 0))"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    private static final String CREATE_TASK_10_RECONCILIATION_EVENT_LINK_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS `skit_ad_reconciliation_event_link` ("
                    + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,"
                    + "`reconciliation_bucket_id` bigint NOT NULL,"
                    + "`reconciliation_revision_id` bigint NOT NULL,`revision_no` int NOT NULL,"
                    + "`event_id` bigint NOT NULL,`policy_snapshot_id` bigint NOT NULL,"
                    + "`association_status` varchar(16) NOT NULL,`actual_units` bigint NOT NULL,"
                    + "`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP "
                    + "ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',"
                    + "PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_recon_event_link_tenant_id` (`tenant_id`,`id`),"
                    + "UNIQUE KEY `uk_skit_recon_event_link_canonical` "
                    + "(`tenant_id`,`reconciliation_revision_id`,`event_id`),"
                    + "KEY `idx_skit_recon_event_link_history` "
                    + "(`tenant_id`,`event_id`,`revision_no`,`id`),"
                    + "CONSTRAINT `fk_skit_recon_event_link_bucket` FOREIGN KEY "
                    + "(`tenant_id`,`reconciliation_bucket_id`) REFERENCES `skit_ad_reconciliation_bucket` "
                    + "(`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                    + "CONSTRAINT `fk_skit_recon_event_link_revision` FOREIGN KEY "
                    + "(`tenant_id`,`reconciliation_revision_id`) REFERENCES `skit_ad_reconciliation_revision` "
                    + "(`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                    + "CONSTRAINT `fk_skit_recon_event_link_event_snapshot` FOREIGN KEY "
                    + "(`tenant_id`,`event_id`,`policy_snapshot_id`) REFERENCES `skit_ad_revenue_event` "
                    + "(`tenant_id`,`id`,`policy_snapshot_id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                    + "CONSTRAINT `ck_skit_recon_event_link_revision` CHECK (`revision_no` > 0),"
                    + "CONSTRAINT `ck_skit_recon_event_link_status` CHECK "
                    + "(`association_status` IN ('ATTRIBUTED','SUSPENSE')),"
                    + "CONSTRAINT `ck_skit_recon_event_link_amount` CHECK "
                    + "(`actual_units` >= 0 AND (`association_status`<>'SUSPENSE' OR `actual_units`=0))"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    private static final int TASK_12_READINESS_MIGRATION_VERSION = 2026071406;
    private static final List<Task2ColumnSpec> TASK_12_READINESS_COLUMN_SPECS =
            Collections.unmodifiableList(Arrays.asList(
                    new Task2ColumnSpec("skit_tenant_ad_capability", "dedicated_unlock_placement_id",
                            "varchar(128) NOT NULL DEFAULT ''"),
                    new Task2ColumnSpec("skit_tenant_ad_capability", "dedicated_placement_verified_at",
                            "datetime DEFAULT NULL"),
                    new Task2ColumnSpec("skit_tenant_ad_capability", "reward_callback_template_verified_at",
                            "datetime DEFAULT NULL"),
                    new Task2ColumnSpec("skit_tenant_ad_capability", "impression_callback_template_verified_at",
                            "datetime DEFAULT NULL"),
                    new Task2ColumnSpec("skit_tenant_ad_capability", "unlock_network_firm_ids_json",
                            "varchar(512) NOT NULL DEFAULT '[]'"),
                    new Task2ColumnSpec("skit_tenant_ad_capability", "shadow_test_member_ids_json",
                            "varchar(4096) NOT NULL DEFAULT '[]'"),
                    new Task2ColumnSpec("skit_tenant_ad_capability", "min_protocol_version",
                            "int NOT NULL DEFAULT 1"),
                    new Task2ColumnSpec("skit_app_release_profile", "native_protocol_version",
                            "int NOT NULL DEFAULT 0")));
    private static final List<Task2CheckSpec> TASK_12_READINESS_CHECK_SPECS =
            Collections.unmodifiableList(Arrays.asList(
                    new Task2CheckSpec("skit_tenant_ad_capability",
                            "ck_skit_tenant_capability_network_json",
                            "JSON_VALID(`unlock_network_firm_ids_json`) AND "
                                    + "JSON_TYPE(JSON_EXTRACT(`unlock_network_firm_ids_json`,'$'))='ARRAY'"),
                    new Task2CheckSpec("skit_tenant_ad_capability",
                            "ck_skit_tenant_capability_shadow_json",
                            "JSON_VALID(`shadow_test_member_ids_json`) AND "
                                    + "JSON_TYPE(JSON_EXTRACT(`shadow_test_member_ids_json`,'$'))='ARRAY'"),
                    new Task2CheckSpec("skit_tenant_ad_capability",
                            "ck_skit_tenant_capability_protocol", "`min_protocol_version` > 0"),
                    new Task2CheckSpec("skit_app_release_profile",
                            "ck_skit_app_release_native_protocol", "`native_protocol_version` >= 0")));

    private static final int TASK_11_MANAGEMENT_MIGRATION_VERSION = 2026071407;
    private static final String CREATE_TASK_11_MANAGEMENT_AUDIT_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS `skit_management_command_audit` ("
                    + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,"
                    + "`command_id` varchar(64) NOT NULL,`operator_user_id` bigint NOT NULL,"
                    + "`original_tenant_id` bigint NOT NULL,`target_tenant_id` bigint NOT NULL,"
                    + "`command_type` varchar(64) NOT NULL,`resource_type` varchar(64) NOT NULL,"
                    + "`resource_id` varchar(128) NOT NULL,`reason` varchar(500) NOT NULL,"
                    + "`before_state_hash` binary(32) NOT NULL,`after_state_hash` binary(32) NOT NULL,"
                    + "`request_fingerprint` binary(32) NOT NULL,`trace_id` varchar(128) NOT NULL DEFAULT '',"
                    + "`result_status` varchar(16) NOT NULL,`created_at` datetime NOT NULL,"
                    + "PRIMARY KEY (`id`),"
                    + "UNIQUE KEY `uk_skit_management_audit_tenant_id` (`tenant_id`,`id`),"
                    + "UNIQUE KEY `uk_skit_management_audit_command` (`command_id`),"
                    + "KEY `idx_skit_management_audit_time` (`tenant_id`,`created_at`,`id`),"
                    + "KEY `idx_skit_management_audit_type_time` "
                    + "(`tenant_id`,`command_type`,`created_at`,`id`),"
                    + "KEY `idx_skit_management_audit_target_time` "
                    + "(`target_tenant_id`,`created_at`,`id`),"
                    + "CONSTRAINT `ck_skit_management_audit_target` CHECK (`tenant_id`=`target_tenant_id`),"
                    + "CONSTRAINT `ck_skit_management_audit_result` CHECK (`result_status`='SUCCESS'),"
                    + "CONSTRAINT `ck_skit_management_audit_reason` CHECK "
                    + "(CHAR_LENGTH(TRIM(`reason`)) BETWEEN 10 AND 500))"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    private static final String CREATE_TASK_11_CALLBACK_REPLAY_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS `skit_ad_callback_replay_command` ("
                    + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,"
                    + "`command_id` varchar(64) NOT NULL,`callback_inbox_id` bigint NOT NULL,"
                    + "`operator_user_id` bigint NOT NULL,`original_tenant_id` bigint NOT NULL,"
                    + "`source_status` varchar(32) NOT NULL,`reason` varchar(500) NOT NULL,"
                    + "`request_fingerprint` binary(32) NOT NULL,`requested_at` datetime NOT NULL,"
                    + "PRIMARY KEY (`id`),"
                    + "UNIQUE KEY `uk_skit_callback_replay_tenant_id` (`tenant_id`,`id`),"
                    + "UNIQUE KEY `uk_skit_callback_replay_command` (`command_id`),"
                    + "UNIQUE KEY `uk_skit_callback_replay_idempotency` "
                    + "(`tenant_id`,`callback_inbox_id`,`request_fingerprint`),"
                    + "KEY `idx_skit_callback_replay_time` (`tenant_id`,`requested_at`,`id`),"
                    + "CONSTRAINT `fk_skit_callback_replay_inbox` FOREIGN KEY "
                    + "(`tenant_id`,`callback_inbox_id`) REFERENCES `skit_ad_callback_inbox` "
                    + "(`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                    + "CONSTRAINT `ck_skit_callback_replay_source` CHECK "
                    + "(`source_status` IN ('DEAD_LETTER','REJECTED')),"
                    + "CONSTRAINT `ck_skit_callback_replay_reason` CHECK "
                    + "(CHAR_LENGTH(TRIM(`reason`)) BETWEEN 10 AND 500))"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    private static final String CREATE_TASK_11_SECURITY_REVOCATION_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS `skit_entitlement_security_revocation` ("
                    + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,"
                    + "`command_id` varchar(64) NOT NULL,`entitlement_id` bigint NOT NULL,"
                    + "`ad_session_id` bigint NOT NULL,`operator_user_id` bigint NOT NULL,"
                    + "`original_tenant_id` bigint NOT NULL,`reason` varchar(500) NOT NULL,"
                    + "`evidence_hash` binary(32) NOT NULL,`revoked_at` datetime NOT NULL,"
                    + "PRIMARY KEY (`id`),"
                    + "UNIQUE KEY `uk_skit_security_revocation_tenant_id` (`tenant_id`,`id`),"
                    + "UNIQUE KEY `uk_skit_security_revocation_command` (`command_id`),"
                    + "UNIQUE KEY `uk_skit_security_revocation_entitlement` (`tenant_id`,`entitlement_id`),"
                    + "KEY `idx_skit_security_revocation_time` (`tenant_id`,`revoked_at`,`id`),"
                    + "CONSTRAINT `fk_skit_security_revocation_entitlement` FOREIGN KEY "
                    + "(`tenant_id`,`entitlement_id`) REFERENCES `skit_content_entitlement` "
                    + "(`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                    + "CONSTRAINT `fk_skit_security_revocation_session` FOREIGN KEY "
                    + "(`tenant_id`,`ad_session_id`) REFERENCES `skit_ad_session` "
                    + "(`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,"
                    + "CONSTRAINT `ck_skit_security_revocation_reason` CHECK "
                    + "(CHAR_LENGTH(TRIM(`reason`)) BETWEEN 10 AND 500))"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    private static final String CREATE_TASK_11_EXPORT_TASK_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS `skit_management_export_task` ("
                    + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,"
                    + "`operator_user_id` bigint NOT NULL,`original_tenant_id` bigint NOT NULL,"
                    + "`target_tenant_id` bigint NOT NULL,`export_type` varchar(64) NOT NULL,"
                    + "`filter_json` longtext NOT NULL,`filter_hash` binary(32) NOT NULL,"
                    + "`field_mask_profile` varchar(32) NOT NULL,`as_of` datetime NOT NULL,"
                    + "`status` varchar(16) NOT NULL DEFAULT 'PENDING',"
                    + "`processed_rows` bigint NOT NULL DEFAULT 0,`total_rows` bigint DEFAULT NULL,"
                    + "`file_object_key` varchar(500) NOT NULL DEFAULT '',"
                    + "`expires_at` datetime NOT NULL,`error_code` varchar(64) NOT NULL DEFAULT '',"
                    + "`lease_owner` varchar(64) DEFAULT NULL,`lease_until` datetime DEFAULT NULL,"
                    + "`version` int NOT NULL DEFAULT 0,`created_at` datetime NOT NULL,"
                    + "`updated_at` datetime NOT NULL,PRIMARY KEY (`id`),"
                    + "UNIQUE KEY `uk_skit_management_export_tenant_id` (`tenant_id`,`id`),"
                    + "KEY `idx_skit_management_export_operator` "
                    + "(`tenant_id`,`operator_user_id`,`created_at`,`id`),"
                    + "KEY `idx_skit_management_export_claim` "
                    + "(`status`,`lease_until`,`created_at`,`id`),"
                    + "KEY `idx_skit_management_export_expiry` (`expires_at`,`id`),"
                    + "CONSTRAINT `ck_skit_management_export_target` CHECK (`tenant_id`=`target_tenant_id`),"
                    + "CONSTRAINT `ck_skit_management_export_filter` CHECK (JSON_VALID(`filter_json`)),"
                    + "CONSTRAINT `ck_skit_management_export_mask` CHECK "
                    + "(`field_mask_profile` IN ('TENANT_MASKED','PLATFORM_AUDIT_MASKED')),"
                    + "CONSTRAINT `ck_skit_management_export_status` CHECK "
                    + "(`status` IN ('PENDING','RUNNING','SUCCEEDED','FAILED','EXPIRED')),"
                    + "CONSTRAINT `ck_skit_management_export_progress` CHECK "
                    + "(`processed_rows`>=0 AND (`total_rows` IS NULL OR `total_rows`>=`processed_rows`)),"
                    + "CONSTRAINT `ck_skit_management_export_lease` CHECK "
                    + "((`lease_owner` IS NULL AND `lease_until` IS NULL) OR "
                    + "(`lease_owner` IS NOT NULL AND `lease_until` IS NOT NULL)),"
                    + "CONSTRAINT `ck_skit_management_export_version` CHECK (`version`>=0),"
                    + "CONSTRAINT `ck_skit_management_export_expiry_window` CHECK (`expires_at`>`as_of`))"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    private static final List<Task2TriggerSpec> TASK_11_MANAGEMENT_TRIGGER_SPECS =
            Collections.unmodifiableList(Arrays.asList(
                    new Task2TriggerSpec("skit_management_command_audit",
                            "trg_skit_management_audit_immutable",
                            rejectDeleteAction("management command audit rows are immutable")),
                    new Task2TriggerSpec("skit_management_command_audit",
                            "trg_skit_management_audit_no_delete", "DELETE",
                            rejectDeleteAction("management command audit rows are immutable")),
                    new Task2TriggerSpec("skit_ad_callback_replay_command",
                            "trg_skit_callback_replay_immutable",
                            rejectDeleteAction("callback replay command rows are immutable")),
                    new Task2TriggerSpec("skit_ad_callback_replay_command",
                            "trg_skit_callback_replay_no_delete", "DELETE",
                            rejectDeleteAction("callback replay command rows are immutable")),
                    new Task2TriggerSpec("skit_entitlement_security_revocation",
                            "trg_skit_security_revocation_immutable",
                            rejectDeleteAction("security revocation rows are immutable")),
                    new Task2TriggerSpec("skit_entitlement_security_revocation",
                            "trg_skit_security_revocation_no_delete", "DELETE",
                            rejectDeleteAction("security revocation rows are immutable"))));

    private static final int TASK_17_RUNTIME_UPDATE_MIGRATION_VERSION = 2026071408;
    private static final List<Task2ColumnSpec> TASK_17_RUNTIME_UPDATE_COLUMN_SPECS =
            Collections.unmodifiableList(Arrays.asList(
                    new Task2ColumnSpec("skit_app_release_profile", "hot_release_no",
                            "bigint NOT NULL DEFAULT 0"),
                    new Task2ColumnSpec("skit_app_release_profile", "hot_manifest_signature",
                            "varchar(1024) NOT NULL DEFAULT ''")));
    private static final List<Task2CheckSpec> TASK_17_RUNTIME_UPDATE_CHECK_SPECS =
            Collections.unmodifiableList(Arrays.asList(
                    new Task2CheckSpec("skit_app_release_profile",
                            "ck_skit_app_release_hot_release", "`hot_release_no` >= 0"),
                    new Task2CheckSpec("skit_app_release_profile",
                            "ck_skit_app_release_hot_signature",
                            "(`hot_release_no`=0 AND `hot_manifest_signature`='') OR "
                                    + "(`hot_release_no`>0 AND `hot_manifest_signature`<>'')")));
    private static final int TENANT_RUNTIME_UPDATE_TRUST_ROOT_MIGRATION_VERSION = 2026071501;
    private static final List<Task2ColumnSpec> TENANT_RUNTIME_UPDATE_TRUST_ROOT_COLUMN_SPECS =
            Collections.unmodifiableList(Arrays.asList(
                    new Task2ColumnSpec("skit_app_release_profile", "runtime_update_public_key",
                            "varchar(4096) NOT NULL DEFAULT ''"),
                    new Task2ColumnSpec("skit_app_release_profile", "runtime_update_key_fingerprint",
                            "char(64) NOT NULL DEFAULT ''"),
                    new Task2ColumnSpec("skit_app_release_profile",
                            "active_runtime_update_key_fingerprint",
                            "char(64) GENERATED ALWAYS AS (CASE WHEN `deleted`=b'0' "
                                    + "AND `runtime_update_key_fingerprint`<>'' THEN "
                                    + "`runtime_update_key_fingerprint` ELSE NULL END) STORED")));
    private static final Task2CheckSpec TENANT_RUNTIME_UPDATE_TRUST_ROOT_CHECK_SPEC =
            new Task2CheckSpec("skit_app_release_profile", "ck_skit_app_release_runtime_key",
                    "(`runtime_update_public_key`='' AND `runtime_update_key_fingerprint`='') OR "
                            + "(`runtime_update_public_key`<>'' AND "
                            + "REGEXP_LIKE(`runtime_update_key_fingerprint`,'^[0-9a-f]{64}$'))");
    private static final Pattern DEFAULT_DEFINITION_PATTERN = Pattern.compile(
            "(?i)\\bDEFAULT\\s+(b'[^']*'|'[^']*'|NULL|-?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern GENERATED_DEFINITION_PATTERN = Pattern.compile(
            "(?is)\\bGENERATED\\s+ALWAYS\\s+AS\\s*\\((.*)\\)\\s+STORED");
    private static final String BOOLEAN_AND_MARKER = "{#and#}";
    private static final String BOOLEAN_OR_MARKER = "{#or#}";
    private static final String DUPLICATE_ACTIVE_USERNAMES_QUERY =
            "SELECT `username` AS `duplicate_value`, GROUP_CONCAT(`id` ORDER BY `id` SEPARATOR ',') "
                    + "AS `duplicate_ids` FROM `system_users` WHERE `deleted` = b'0' GROUP BY `username` "
                    + "HAVING COUNT(*) > 1 ORDER BY `username`";
    private static final String DUPLICATE_ACTIVE_MOBILES_QUERY =
            "SELECT `mobile` AS `duplicate_value`, GROUP_CONCAT(`id` ORDER BY `id` SEPARATOR ',') "
                    + "AS `duplicate_ids` FROM `system_users` WHERE `deleted` = b'0' AND `mobile` IS NOT NULL "
                    + "AND TRIM(`mobile`) <> '' GROUP BY `mobile` HAVING COUNT(*) > 1 ORDER BY `mobile`";
    private static final int LEGACY_IDENTITY_MIGRATION_VERSION = 2026071250;
    private static final String LEGACY_IDENTITY_ALGORITHM = "repair-legacy-active-user-identities-v1";
    private static final String CREATE_IDENTITY_MIGRATION_AUDIT_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS `skit_identity_migration_audit` ("
                    + "`id` bigint NOT NULL AUTO_INCREMENT,`migration_version` int NOT NULL,"
                    + "`identity_type` varchar(16) NOT NULL,`changed_user_id` bigint NOT NULL,"
                    + "`changed_tenant_id` bigint NOT NULL,`retained_user_id` bigint NOT NULL,"
                    + "`retained_reason` varchar(64) NOT NULL,`old_value` varchar(64) DEFAULT NULL,"
                    + "`new_value` varchar(64) DEFAULT NULL,`repaired_on` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_identity_migration_target` "
                    + "(`migration_version`,`identity_type`,`changed_user_id`),"
                    + "KEY `idx_skit_identity_migration_value` (`migration_version`,`identity_type`,`old_value`))"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    private static final String DUPLICATE_ACTIVE_USERNAME_MEMBERS_QUERY =
            "SELECT `d`.`group_id`,`u`.`id`,`u`.`tenant_id`,`u`.`username` AS `identity_value` "
                    + "FROM `system_users` `u` JOIN (SELECT MIN(`id`) AS `group_id`,`username` "
                    + "FROM `system_users` WHERE `deleted` = b'0' GROUP BY `username` HAVING COUNT(*) > 1) `d` "
                    + "ON `d`.`username` = `u`.`username` WHERE `u`.`deleted` = b'0' "
                    + "ORDER BY `d`.`group_id`,`u`.`id`";
    private static final String DUPLICATE_ACTIVE_MOBILE_MEMBERS_QUERY =
            "SELECT `d`.`group_id`,`u`.`id`,`u`.`tenant_id`,`u`.`mobile` AS `identity_value` "
                    + "FROM `system_users` `u` JOIN (SELECT MIN(`id`) AS `group_id`,`mobile` "
                    + "FROM `system_users` WHERE `deleted` = b'0' AND `mobile` IS NOT NULL "
                    + "AND TRIM(`mobile`) <> '' GROUP BY `mobile` HAVING COUNT(*) > 1) `d` "
                    + "ON `d`.`mobile` = `u`.`mobile` WHERE `u`.`deleted` = b'0' "
                    + "ORDER BY `d`.`group_id`,`u`.`id`";
    private static final String INVALID_AGENT_ADMIN_BINDINGS_QUERY =
            "SELECT `a`.`id` AS `agent_id`,`a`.`tenant_id`,`t`.`contact_user_id`,`u`.`id` AS `user_id`,"
                    + "`u`.`tenant_id` AS `user_tenant_id` FROM `skit_agent` `a` "
                    + "LEFT JOIN `system_tenant` `t` ON `t`.`id` = `a`.`tenant_id` "
                    + "LEFT JOIN `system_users` `u` ON `u`.`id` = `t`.`contact_user_id` "
                    + "WHERE `a`.`deleted` = b'0' AND (`t`.`id` IS NULL OR `t`.`deleted` <> b'0' "
                    + "OR `t`.`contact_user_id` IS NULL OR `u`.`id` IS NULL OR `u`.`deleted` <> b'0' "
                    + "OR `u`.`tenant_id` <> `a`.`tenant_id`) ORDER BY `a`.`id`";
    private static final String PROTECTED_PLATFORM_ADMIN_IDS_QUERY =
            "SELECT DISTINCT `u`.`id` FROM `system_users` `u` JOIN `system_tenant` `pt` "
                    + "ON `pt`.`id` = `u`.`tenant_id` AND `pt`.`package_id` = 0 AND `pt`.`deleted` = b'0' "
                    + "JOIN `system_user_role` `ur` "
                    + "ON `ur`.`user_id` = `u`.`id` AND `ur`.`tenant_id` = `u`.`tenant_id` "
                    + "AND `ur`.`deleted` = b'0' JOIN `system_role` `r` ON `r`.`id` = `ur`.`role_id` "
                    + "AND `r`.`tenant_id` = `u`.`tenant_id` AND `r`.`deleted` = b'0' "
                    + "AND `r`.`code` = 'super_admin' WHERE `u`.`deleted` = b'0' ORDER BY `u`.`id`";
    private static final String AGENT_ADMIN_BINDINGS_QUERY =
            "SELECT `a`.`tenant_id`,`t`.`contact_user_id` AS `user_id`,"
                    + "`t`.`contact_mobile` AS `contact_mobile`,`u`.`username`,`u`.`mobile` "
                    + "FROM `skit_agent` `a` JOIN `system_tenant` `t` ON `t`.`id` = `a`.`tenant_id` "
                    + "AND `t`.`deleted` = b'0' JOIN `system_users` `u` ON `u`.`id` = `t`.`contact_user_id` "
                    + "AND `u`.`tenant_id` = `t`.`id` AND `u`.`deleted` = b'0' "
                    + "WHERE `a`.`deleted` = b'0' ORDER BY `a`.`tenant_id`";
    private static final String ACTIVE_USERNAME_HOLDERS_QUERY =
            "SELECT `id`,`tenant_id`,`username` AS `identity_value` FROM `system_users` "
                    + "WHERE `deleted` = b'0' AND `username` = ? ORDER BY `id`";
    private static final String ACTIVE_MOBILE_HOLDERS_QUERY =
            "SELECT `id`,`tenant_id`,`mobile` AS `identity_value` FROM `system_users` "
                    + "WHERE `deleted` = b'0' AND `mobile` = ? ORDER BY `id`";
    private static final String ACTIVE_USERNAME_EXISTS_QUERY =
            "SELECT COUNT(*) FROM `system_users` WHERE `deleted` = b'0' AND `username` = ?";
    private static final String INSERT_IDENTITY_MIGRATION_AUDIT_SQL =
            "INSERT INTO `skit_identity_migration_audit` (`migration_version`,`identity_type`,"
                    + "`changed_user_id`,`changed_tenant_id`,`retained_user_id`,`retained_reason`,"
                    + "`old_value`,`new_value`,`repaired_on`) VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)";
    private static final String UPDATE_LEGACY_USERNAME_SQL =
            "UPDATE `system_users` SET `username` = ?,`updater` = 'skit-migration-2026071250',"
                    + "`update_time` = CURRENT_TIMESTAMP WHERE `id` = ? AND `deleted` = b'0' AND `username` = ?";
    private static final String CLEAR_LEGACY_MOBILE_SQL =
            "UPDATE `system_users` SET `mobile` = NULL,`updater` = 'skit-migration-2026071250',"
                    + "`update_time` = CURRENT_TIMESTAMP WHERE `id` = ? AND `deleted` = b'0' AND `mobile` = ?";
    private static final String UPDATE_AGENT_MOBILE_SQL =
            "UPDATE `system_users` SET `mobile` = ?,`updater` = 'skit-migration-2026071250',"
                    + "`update_time` = CURRENT_TIMESTAMP WHERE `id` = ? AND `deleted` = b'0' AND `mobile` <=> ?";
    private static final String UPDATE_TENANT_CONTACT_MOBILE_SQL =
            "UPDATE `system_tenant` SET `contact_mobile` = ?,`updater` = 'skit-migration-2026071250',"
                    + "`update_time` = CURRENT_TIMESTAMP WHERE `id` = ? AND `deleted` = b'0' "
                    + "AND `contact_user_id` = ? AND `contact_mobile` <=> ?";
    private static final String DUPLICATE_ACTIVE_APP_RELEASE_PROFILES_QUERY =
            "SELECT `tenant_id` AS `duplicate_value`, GROUP_CONCAT(`id` ORDER BY `id` SEPARATOR ',') "
                    + "AS `duplicate_ids` FROM `skit_app_release_profile` WHERE `deleted` = b'0' "
                    + "GROUP BY `tenant_id` HAVING COUNT(*) > 1 ORDER BY `tenant_id`";
    private static final String DUPLICATE_ACTIVE_COMMISSION_PLANS_QUERY =
            "SELECT `tenant_id` AS `duplicate_value`, GROUP_CONCAT(`id` ORDER BY `id` SEPARATOR ',') "
                    + "AS `duplicate_ids` FROM `skit_commission_plan` WHERE `deleted` = b'0' AND `status` = 0 "
                    + "GROUP BY `tenant_id` HAVING COUNT(*) > 1 ORDER BY `tenant_id`";
    private static final String SEED_STANDARD_AGENT_PACKAGE_SQL =
            "INSERT INTO `system_tenant_package` (`code`, `name`, `status`, `menu_ids`) "
                    + "VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), "
                    + "`status` = VALUES(`status`), `menu_ids` = VALUES(`menu_ids`), `deleted` = b'0'";
    private static final String STANDARD_AGENT_PACKAGE_CODE = "SKIT_AGENT_STANDARD";
    private static final String STANDARD_AGENT_PACKAGE_NAME = "代理商标准套餐";
    private static final int STANDARD_AGENT_PACKAGE_STATUS = 0;
    private static final String STANDARD_AGENT_PACKAGE_MENU_IDS = "[]";

    private final JdbcTemplate jdbcTemplate;
    private final List<Migration> migrations;

    @Autowired
    public SkitSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.migrations = buildMigrations();
    }

    SkitSchemaInitializer(JdbcTemplate jdbcTemplate, List<Migration> migrations) {
        this.jdbcTemplate = jdbcTemplate;
        this.migrations = sortedMigrations(migrations);
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            if (!acquireMigrationLock(connection)) {
                throw new IllegalStateException("Could not acquire MySQL advisory lock " + MIGRATION_LOCK_NAME
                        + " within " + MIGRATION_LOCK_TIMEOUT_SECONDS + " seconds; schema migrations were not run.");
            }
            Throwable migrationFailure = null;
            try {
                applyMigrations();
                return null;
            } catch (RuntimeException | Error exception) {
                migrationFailure = exception;
                throw exception;
            } finally {
                try {
                    releaseMigrationLock(connection);
                } catch (SQLException | RuntimeException releaseFailure) {
                    if (migrationFailure != null) {
                        migrationFailure.addSuppressed(releaseFailure);
                    } else {
                        throw releaseFailure;
                    }
                }
            }
        });
    }

    private void applyMigrations() {
        jdbcTemplate.execute(CREATE_MIGRATION_TABLE_SQL);
        Map<Integer, String> appliedMigrations = loadAppliedMigrations();
        validateInstalledMigrations(appliedMigrations);
        validateKnownForwardSchemaState(appliedMigrations);
        if (hasPendingTask2Migration(appliedMigrations)) {
            validateTask2Preflight();
        }
        for (Migration migration : migrations) {
            if (appliedMigrations.containsKey(migration.getVersion())) {
                continue;
            }
            validateMigrationPreflight(migration.getVersion());
            migration.execute();
            jdbcTemplate.update(INSERT_APPLIED_MIGRATION_SQL, migration.getVersion(), migration.getDescription(),
                    migration.getChecksum());
            appliedMigrations.put(migration.getVersion(), migration.getChecksum());
            log.info("[run][applied skit schema migration {} ({})]", migration.getVersion(),
                    migration.getDescription());
        }
        log.info("[run][skit SaaS schema ready]");
    }

    /**
     * Runs each hardening preflight only after its prerequisite migrations have completed, but
     * before the hardening migration itself executes any DDL. Running every preflight up front
     * makes a legacy schema query columns which an earlier pending migration has not added yet.
     */
    private void validateMigrationPreflight(int migrationVersion) {
        if (migrationVersion == TASK_5_SCHEMA_HARDENING_MIGRATION_VERSION) {
            validateTask5SchemaHardeningPreflight();
        } else if (migrationVersion == TASK_7_SCHEMA_HARDENING_MIGRATION_VERSION) {
            validateTask7SchemaHardeningPreflight();
        } else if (migrationVersion == TASK_10_RECONCILIATION_MIGRATION_VERSION) {
            validateTask10MigrationPrefix();
        } else if (migrationVersion == TASK_12_READINESS_MIGRATION_VERSION) {
            validateTask12MigrationPrefix();
        }
    }

    private void validateKnownForwardSchemaState(Map<Integer, String> appliedMigrations) {
        boolean task10Installed = appliedMigrations.containsKey(TASK_10_RECONCILIATION_MIGRATION_VERSION);
        boolean task12Installed = appliedMigrations.containsKey(TASK_12_READINESS_MIGRATION_VERSION);
        if (task10Installed) {
            validateTask10ReconciliationSchema(true);
        } else {
            validateTask10MigrationPrefix();
        }
        if (!task10Installed && task12PhysicalStateStarted()) {
            // A canonical bootstrap installs the complete physical schema before the immutable
            // migration ledger is backfilled. Accept that state only when Task 10 is already
            // complete and exact; a partial or drifted Task 10 still fails this full validator.
            validateTask10ReconciliationSchema(true);
        }
        if (task12Installed) {
            validateTask12ReadinessSchema(true);
        } else {
            validateTask12MigrationPrefix();
        }
    }

    /**
     * Accepts only a strict physical prefix of Task 10. MySQL DDL auto-commits, so a process may
     * stop between any two schema steps; accepting an arbitrary subset would let a later
     * same-named object hide drift and make the retry mutate an unverified schema.
     */
    private void validateTask10MigrationPrefix() {
        try {
            boolean gap = false;
            for (String table : Arrays.asList("skit_ad_reporting_credential_version",
                    "skit_ad_reconciliation_allocation", "skit_ad_reconciliation_event_link")) {
                boolean present = tableExists(table);
                gap = advancePhysicalPrefix("Task 10 table " + table, present, gap);
                if (present) {
                    validateTask10NewTablePrefixShape(table);
                }
            }

            gap = advancePhysicalPrefix("Task 10 transitional column skit_ad_report_pull.report_date",
                    validateTask10TransitionalColumn("report_date", "date NOT NULL", "date DEFAULT NULL"), gap);
            gap = advancePhysicalPrefix("Task 10 transitional column skit_ad_report_pull.request_hash",
                    validateTask10TransitionalColumn("request_hash", "binary(32) NOT NULL",
                            "binary(32) DEFAULT NULL"), gap);

            for (Task2ColumnSpec spec : TASK_10_RECONCILIATION_COLUMN_SPECS) {
                boolean applied = validatePrefixColumn("Task 10", spec);
                gap = advancePhysicalPrefix("Task 10 column " + spec.table + "." + spec.column,
                        applied, gap);
            }

            gap = validatePrefixIndex("Task 10", "skit_ad_account", "idx_skit_ad_account_report_due",
                    "provider,status,report_next_allowed_at,report_pull_lease_until,id", false, gap);
            gap = validatePrefixIndex("Task 10", "skit_ad_report_pull", "idx_skit_report_pull_request",
                    "tenant_id,ad_account_id,report_date,request_hash", false, gap);
            gap = validatePrefixIndex("Task 10", "skit_ad_report_pull", "idx_skit_report_pull_credential",
                    "tenant_id,ad_account_id,credential_version", false, gap);
            gap = validatePrefixIndex("Task 10", "skit_ad_report_pull", "idx_skit_report_pull_final_window",
                    "tenant_id,ad_account_id,report_date,final_window,status", false, gap);
            gap = validatePrefixIndex("Task 10", "skit_ad_revenue_event", "idx_skit_revenue_report_pending",
                    "tenant_id,ad_account_id,reconciliation_revision_id,occurred_time,id", false, gap);
            gap = validatePrefixReplacementIndex("Task 10", "skit_ad_report_pull",
                    "uk_skit_report_pull_response",
                    "tenant_id,ad_account_id,range_start,range_end,response_hash",
                    "tenant_id,ad_account_id,range_start,range_end,request_hash,response_hash,"
                            + "credential_version,final_window", true, gap);
            gap = validatePrefixReplacementIndex("Task 10", "skit_ad_reconciliation_bucket",
                    "uk_skit_recon_bucket_identity",
                    "tenant_id,ad_account_id,bucket_key,report_date,report_timezone,placement_id,"
                            + "network_firm_id,adsource_id,currency",
                    "tenant_id,ad_account_id,bucket_key,report_date,report_timezone,app_id,placement_id,"
                            + "ad_format,network_account_id,network_firm_id,adsource_id,currency",
                    true, gap);
            gap = validatePrefixForeignKey("Task 10", new Task2ForeignKeySpec("skit_ad_report_pull",
                    "fk_skit_report_pull_credential", "tenant_id,ad_account_id,credential_version",
                    "skit_ad_reporting_credential_version", "tenant_id,ad_account_id,credential_version"), gap);
            for (Task2CheckSpec spec : TASK_10_RECONCILIATION_CHECK_SPECS) {
                if (!"skit_ad_reconciliation_event_link".equals(spec.table)) {
                    gap = validatePrefixCheck("Task 10", spec, gap);
                }
            }
            for (Task2TriggerSpec spec : TASK_10_RECONCILIATION_TRIGGER_SPECS) {
                gap = validatePrefixTrigger("Task 10", spec, gap);
            }
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("Task 10 migration prefix")) {
                throw exception;
            }
            throw new IllegalStateException("Task 10 migration prefix rejected: " + exception.getMessage(),
                    exception);
        }
    }

    private void validateTask12MigrationPrefix() {
        try {
            boolean gap = false;
            for (Task2ColumnSpec spec : TASK_12_READINESS_COLUMN_SPECS) {
                gap = advancePhysicalPrefix("Task 12 column " + spec.table + "." + spec.column,
                        validatePrefixColumn("Task 12", spec), gap);
            }
            for (Task2CheckSpec spec : TASK_12_READINESS_CHECK_SPECS) {
                gap = validatePrefixCheck("Task 12", spec, gap);
            }
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("Task 12 migration prefix")) {
                throw exception;
            }
            throw new IllegalStateException("Task 12 migration prefix rejected: " + exception.getMessage(),
                    exception);
        }
    }

    private boolean task12PhysicalStateStarted() {
        for (Task2ColumnSpec spec : TASK_12_READINESS_COLUMN_SPECS) {
            if (columnExists(spec.table, spec.column)) {
                return true;
            }
        }
        for (Task2CheckSpec spec : TASK_12_READINESS_CHECK_SPECS) {
            if (!jdbcTemplate.queryForList(CHECK_DEFINITION_QUERY,
                    String.class, spec.table, spec.constraint).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void validateTask10NewTablePrefixShape(String table) {
        String expected = SkitAdSchemaSignature.expectedTask10FinalFingerprints().get(table);
        if (expected == null) {
            throw new IllegalStateException("no canonical fingerprint exists for new table " + table);
        }
        String actual = SkitAdSchemaSignature.rawFingerprint(jdbcTemplate, table);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("incompatible same-named Task 10 table " + table
                    + ": expected fingerprint=" + expected + ", actual fingerprint=" + actual);
        }
    }

    private boolean validateTask10TransitionalColumn(String column, String finalDefinition,
                                                       String transitionalDefinition) {
        if (!columnExists("skit_ad_report_pull", column)) {
            return false;
        }
        if (!columnDefinitionMatches("skit_ad_report_pull", column, finalDefinition)
                && !columnDefinitionMatches("skit_ad_report_pull", column, transitionalDefinition)) {
            throw new IllegalStateException("incompatible transitional column skit_ad_report_pull." + column);
        }
        return true;
    }

    private boolean validatePrefixColumn(String label, Task2ColumnSpec spec) {
        if (!columnExists(spec.table, spec.column)) {
            return false;
        }
        if (columnDefinitionMatches(spec.table, spec.column, spec.definition)) {
            return true;
        }
        if (spec.compatibleLegacyDefinition != null
                && columnDefinitionMatches(spec.table, spec.column, spec.compatibleLegacyDefinition)) {
            return false;
        }
        throw new IllegalStateException(label + " has incompatible column " + spec.table + "." + spec.column);
    }

    private boolean validatePrefixIndex(String label, String table, String index, String columns,
                                        boolean unique, boolean gap) {
        String actual = jdbcTemplate.queryForObject(INDEX_DEFINITION_QUERY, String.class, table, index);
        if (actual == null) {
            return advancePhysicalPrefix(label + " index " + table + "." + index, false, gap);
        }
        String expected = indexDefinition(columns, unique);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(label + " has incompatible index " + table + "." + index
                    + ": expected=" + expected + ", actual=" + actual);
        }
        return advancePhysicalPrefix(label + " index " + table + "." + index, true, gap);
    }

    private boolean validatePrefixReplacementIndex(String label, String table, String index,
            String legacyColumns, String replacementColumns, boolean unique, boolean gap) {
        String actual = jdbcTemplate.queryForObject(INDEX_DEFINITION_QUERY, String.class, table, index);
        String legacy = indexDefinition(legacyColumns, unique);
        String replacement = indexDefinition(replacementColumns, unique);
        if (actual == null) {
            return advancePhysicalPrefix(label + " replacement index " + table + "." + index,
                    false, gap);
        }
        if (legacy.equals(actual)) {
            return advancePhysicalPrefix(label + " replacement index " + table + "." + index, false, gap);
        }
        if (!replacement.equals(actual)) {
            throw new IllegalStateException(label + " has incompatible replacement index " + table + "." + index
                    + ": expected legacy=" + legacy + " or replacement=" + replacement + ", actual=" + actual);
        }
        return advancePhysicalPrefix(label + " replacement index " + table + "." + index, true, gap);
    }

    private boolean validatePrefixForeignKey(String label, Task2ForeignKeySpec spec, boolean gap) {
        String actual = jdbcTemplate.queryForObject(FOREIGN_KEY_DEFINITION_QUERY,
                String.class, spec.table, spec.constraint);
        if (actual == null) {
            return advancePhysicalPrefix(label + " foreign key " + spec.table + "." + spec.constraint,
                    false, gap);
        }
        String expected = foreignKeyDefinition(spec);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(label + " has incompatible foreign key " + spec.table + "."
                    + spec.constraint + ": expected=" + expected + ", actual=" + actual);
        }
        return advancePhysicalPrefix(label + " foreign key " + spec.table + "." + spec.constraint,
                true, gap);
    }

    private boolean validatePrefixCheck(String label, Task2CheckSpec spec, boolean gap) {
        List<String> existing = jdbcTemplate.queryForList(CHECK_DEFINITION_QUERY,
                String.class, spec.table, spec.constraint);
        if (existing.isEmpty()) {
            return advancePhysicalPrefix(label + " check " + spec.table + "." + spec.constraint, false, gap);
        }
        String expected = normalizeCheckExpression(spec.expression);
        String actual = existing.size() == 1 ? normalizeCheckExpression(existing.get(0)) : null;
        if (!expected.equals(actual)) {
            throw new IllegalStateException(label + " has incompatible check " + spec.table + "."
                    + spec.constraint + ": expected=" + expected + ", actual=" + actual);
        }
        return advancePhysicalPrefix(label + " check " + spec.table + "." + spec.constraint, true, gap);
    }

    private boolean validatePrefixTrigger(String label, Task2TriggerSpec spec, boolean gap) {
        List<String> existing = jdbcTemplate.queryForList(TRIGGER_DEFINITION_QUERY, String.class, spec.trigger);
        if (existing.isEmpty()) {
            return advancePhysicalPrefix(label + " trigger " + spec.trigger, false, gap);
        }
        validateTask2TriggerDefinition(spec, existing);
        return advancePhysicalPrefix(label + " trigger " + spec.trigger, true, gap);
    }

    private static boolean advancePhysicalPrefix(String artifact, boolean present, boolean gap) {
        if (present && gap) {
            String task = artifact.startsWith("Task 12") ? "Task 12" : "Task 10";
            throw new IllegalStateException(task + " migration prefix is out of order at " + artifact);
        }
        return gap || !present;
    }

    private void validateInstalledMigrations(Map<Integer, String> appliedMigrations) {
        Map<Integer, Migration> knownMigrations = new HashMap<>();
        for (Migration migration : migrations) {
            knownMigrations.put(migration.getVersion(), migration);
        }
        for (Map.Entry<Integer, String> installed : appliedMigrations.entrySet()) {
            Migration known = knownMigrations.get(installed.getKey());
            if (known == null) {
                throw new IllegalStateException("Unknown installed schema migration version "
                        + installed.getKey() + ". Refusing to run with a newer or unrecognized schema history.");
            }
            if (!known.getChecksum().equals(installed.getValue())) {
                throw new IllegalStateException("Schema migration checksum mismatch for version "
                        + known.getVersion() + ": stored=" + installed.getValue() + ", expected="
                        + known.getChecksum() + ". Restore the original migration or repair the schema history.");
            }
        }
        boolean encounteredPending = false;
        for (Migration migration : migrations) {
            if (!appliedMigrations.containsKey(migration.getVersion())) {
                encounteredPending = true;
            } else if (encounteredPending) {
                throw new IllegalStateException("Schema migration history is not a continuous prefix: version "
                        + migration.getVersion() + " is installed after an earlier known migration is missing.");
            }
        }
    }

    private boolean hasPendingTask2Migration(Map<Integer, String> appliedMigrations) {
        for (Migration migration : migrations) {
            if (migration.getVersion() >= SkitAdSchemaDdl.VERSION
                    && migration.getVersion() <= TASK_10_RECONCILIATION_MIGRATION_VERSION
                    && !appliedMigrations.containsKey(migration.getVersion())) {
                return true;
            }
        }
        return false;
    }

    private static boolean acquireMigrationLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ACQUIRE_MIGRATION_LOCK_SQL)) {
            statement.setString(1, MIGRATION_LOCK_NAME);
            statement.setInt(2, MIGRATION_LOCK_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1 && !resultSet.wasNull();
            }
        }
    }

    private static void releaseMigrationLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RELEASE_MIGRATION_LOCK_SQL)) {
            statement.setString(1, MIGRATION_LOCK_NAME);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1 || resultSet.wasNull()) {
                    throw new IllegalStateException("Could not release MySQL advisory lock " + MIGRATION_LOCK_NAME);
                }
            }
        }
    }

    private List<Migration> buildMigrations() {
        List<Migration> result = new ArrayList<>();
        List<SchemaStep> baselineSteps = new ArrayList<>();
        baselineSteps.addAll(createLegacyTableSteps());
        baselineSteps.addAll(legacyTenantColumnSteps());
        baselineSteps.addAll(createDomainTableSteps());
        baselineSteps.addAll(domainColumnSteps());
        baselineSteps.addAll(domainIndexSteps());
        result.add(migrationFromSteps(2026071201, "baseline short-drama SaaS schema", baselineSteps));
        result.add(migrationFromSteps(LEGACY_IDENTITY_MIGRATION_VERSION,
                "repair legacy duplicate active system user identities", legacyIdentityRepairSteps()));
        result.add(migrationFromSteps(2026071301, "add package code and agent archive fields",
                lifecycleColumnSteps()));
        result.add(migrationFromSteps(2026071302, "enforce global active system user identities",
                activeUserIdentitySteps()));
        result.add(migrationFromSteps(2026071303, "seed standard agent package",
                Collections.singletonList(seedStandardAgentPackageStep())));
        result.add(migrationFromSteps(2026071304, "enforce domain singleton integrity and query indexes",
                domainIntegritySteps()));
        result.add(migrationFromSteps(SkitAdSchemaDdl.VERSION,
                "add tenant-safe verified advertising and finance schema", task2SchemaSteps()));
        result.add(migrationFromSteps(POLICY_SNAPSHOT_IMMUTABILITY_MIGRATION_VERSION,
                "enforce ad policy snapshot immutability", policySnapshotImmutabilitySteps()));
        result.add(migrationFromSteps(TASK_5_SCHEMA_HARDENING_MIGRATION_VERSION,
                "harden Task 5 ad session and entitlement bindings", task5SchemaHardeningSteps()));
        result.add(migrationFromSteps(TASK_7_SCHEMA_HARDENING_MIGRATION_VERSION,
                "bind callback receipts and verified advertising facts", task7SchemaHardeningSteps()));
        result.add(migrationFromSteps(TASK_10_RECONCILIATION_MIGRATION_VERSION,
                "add tenant-safe Taku reporting and reconciliation pipeline",
                task10ReconciliationSteps()));
        result.add(migrationFromSteps(TASK_12_READINESS_MIGRATION_VERSION,
                "add tenant ad readiness rollout gates", task12ReadinessSteps()));
        result.add(migrationFromSteps(TASK_11_MANAGEMENT_MIGRATION_VERSION,
                "add tenant-safe management audit and query schema", task11ManagementSteps()));
        result.add(migrationFromSteps(TASK_17_RUNTIME_UPDATE_MIGRATION_VERSION,
                "add signed runtime update manifest state", task17RuntimeUpdateSteps()));
        result.add(migrationFromSteps(TENANT_RUNTIME_UPDATE_TRUST_ROOT_MIGRATION_VERSION,
                "add tenant runtime update trust roots", tenantRuntimeUpdateTrustRootSteps()));
        return sortedMigrations(result);
    }

    private Migration migrationFromSteps(int version, String description, List<SchemaStep> suppliedSteps) {
        List<SchemaStep> steps = Collections.unmodifiableList(new ArrayList<>(suppliedSteps));
        List<String> manifest = new ArrayList<>(steps.size());
        for (SchemaStep step : steps) {
            manifest.add(step.getManifestEntry());
        }
        return new Migration(version, description, manifest, () -> executeSteps(steps));
    }

    private Map<Integer, String> loadAppliedMigrations() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_APPLIED_MIGRATIONS_SQL);
        Map<Integer, String> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Number version = (Number) row.get("version");
            Object checksum = row.get("checksum");
            result.put(version.intValue(), checksum == null ? null : checksum.toString());
        }
        return result;
    }

    private static List<Migration> sortedMigrations(List<Migration> migrations) {
        List<Migration> result = new ArrayList<>(migrations);
        Collections.sort(result, Comparator.comparingInt(Migration::getVersion));
        Set<Integer> versions = new HashSet<>();
        for (Migration migration : result) {
            if (!versions.add(migration.getVersion())) {
                throw new IllegalArgumentException("Duplicate skit schema migration version: " + migration.getVersion());
            }
        }
        return Collections.unmodifiableList(result);
    }

    static final class Migration {

        private final int version;
        private final String description;
        private final List<String> manifest;
        private final String checksum;
        private final Runnable action;

        Migration(int version, String description, List<String> manifest, Runnable action) {
            this.version = version;
            this.description = description;
            this.manifest = Collections.unmodifiableList(new ArrayList<>(manifest));
            this.checksum = sha256(canonicalManifest(version, description, this.manifest));
            this.action = action;
        }

        int getVersion() {
            return version;
        }

        String getDescription() {
            return description;
        }

        String getChecksum() {
            return checksum;
        }

        List<String> getManifest() {
            return manifest;
        }

        void execute() {
            action.run();
        }

    }

    private static final class SchemaStep {

        private final String manifestEntry;
        private final Runnable action;

        private SchemaStep(String manifestEntry, Runnable action) {
            this.manifestEntry = manifestEntry;
            this.action = action;
        }

        String getManifestEntry() {
            return manifestEntry;
        }

        void execute() {
            action.run();
        }

    }

    private static final class Task2ColumnSpec {

        private final String table;
        private final String column;
        private final String definition;
        private final String compatibleLegacyDefinition;

        private Task2ColumnSpec(String table, String column, String definition) {
            this(table, column, definition, null);
        }

        private Task2ColumnSpec(String table, String column, String definition,
                                String compatibleLegacyDefinition) {
            this.table = table;
            this.column = column;
            this.definition = definition;
            this.compatibleLegacyDefinition = compatibleLegacyDefinition;
        }
    }

    private static final class Task2ForeignKeySpec {

        private final String table;
        private final String constraint;
        private final String columns;
        private final String referencedTable;
        private final String referencedColumns;

        private Task2ForeignKeySpec(String table, String constraint, String columns,
                                    String referencedTable, String referencedColumns) {
            this.table = table;
            this.constraint = constraint;
            this.columns = columns;
            this.referencedTable = referencedTable;
            this.referencedColumns = referencedColumns;
        }
    }

    private static final class Task2CheckSpec {

        private final String table;
        private final String constraint;
        private final String expression;

        private Task2CheckSpec(String table, String constraint, String expression) {
            this.table = table;
            this.constraint = constraint;
            this.expression = expression;
        }
    }

    private static final class Task2TriggerSpec {

        private final String table;
        private final String trigger;
        private final String event;
        private final String action;

        private Task2TriggerSpec(String table, String trigger, String action) {
            this(table, trigger, "UPDATE", action);
        }

        private Task2TriggerSpec(String table, String trigger, String event, String action) {
            this.table = table;
            this.trigger = trigger;
            this.event = event;
            this.action = action;
        }
    }

    private static String legacyImmutableAction(String message, String... columns) {
        return "BEGIN IF OLD.`legacy_unverified`=b'1' AND NOT (" + unchangedColumns(columns)
                + ") THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='" + message + "'; END IF; END";
    }

    private static String credentialUpdateAction(String... immutableColumns) {
        return "BEGIN IF NOT (" + unchangedColumns(immutableColumns)
                + ") THEN SIGNAL SQLSTATE '45000' "
                + "SET MESSAGE_TEXT='credential identity and material are immutable'; "
                + "ELSEIF NOT ((NEW.`active` <=> OLD.`active`) OR "
                + "(OLD.`active`=b'1' AND NEW.`active`=b'0')) "
                + "OR NOT ((NEW.`accept_until` <=> OLD.`accept_until`) OR "
                + "(OLD.`accept_until` IS NULL AND NEW.`accept_until` IS NOT NULL AND NEW.`active`=b'0')) "
                + "OR NOT ((NEW.`revoked_at` <=> OLD.`revoked_at`) OR "
                + "(OLD.`revoked_at` IS NULL AND NEW.`revoked_at` IS NOT NULL)) "
                + "OR (NEW.`revoked_at` IS NOT NULL AND NEW.`active`=b'1') "
                + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='credential lifecycle is monotonic'; "
                + "END IF; END";
    }

    private static String reportingCredentialUpdateAction() {
        return "BEGIN IF NOT (" + unchangedColumns("id", "tenant_id", "ad_account_id",
                "credential_version", "ciphertext", "nonce", "encryption_key_id",
                "envelope_version", "creator", "create_time", "deleted")
                + ") THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT="
                + "'reporting credential identity and material are immutable'; "
                + "ELSEIF NOT ((NEW.`permission_verified_at` <=> OLD.`permission_verified_at`) OR "
                + "(OLD.`permission_verified_at` IS NULL AND NEW.`permission_verified_at` IS NOT NULL)) "
                + "OR NOT (((NEW.`active` <=> OLD.`active`) AND "
                + "(NEW.`revoked_at` <=> OLD.`revoked_at`)) OR "
                + "(OLD.`active`=b'1' AND NEW.`active`=b'0' AND OLD.`revoked_at` IS NULL "
                + "AND NEW.`revoked_at` IS NOT NULL)) "
                + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT="
                + "'reporting credential lifecycle is monotonic'; END IF; END";
    }

    private static String inviteRegistryUpdateAction(String... immutableColumns) {
        return "BEGIN IF NOT (" + unchangedColumns(immutableColumns)
                + ") THEN SIGNAL SQLSTATE '45000' "
                + "SET MESSAGE_TEXT='invite code ownership is immutable'; "
                + "ELSEIF NOT (((NEW.`status` <=> OLD.`status`) AND "
                + "(NEW.`rotated_at` <=> OLD.`rotated_at`)) OR "
                + "(OLD.`status`='ACTIVE' AND NEW.`status` IN ('ROTATED','DISABLED') "
                + "AND OLD.`rotated_at` IS NULL AND NEW.`rotated_at` IS NOT NULL)) "
                + "OR (NEW.`status`='ACTIVE' AND NEW.`rotated_at` IS NOT NULL) "
                + "OR (NEW.`status` IN ('ROTATED','DISABLED') AND NEW.`rotated_at` IS NULL) "
                + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='invite code lifecycle is monotonic'; "
                + "END IF; END";
    }

    private static String rejectDeleteAction(String message) {
        return "BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='" + message + "'; END";
    }

    private static String callbackInboxMonotonicAction() {
        String immutableProvenance = unchangedColumns("id", "tenant_id", "ad_account_id", "ad_session_id",
                "callback_key_version", "reward_secret_version", "provider", "callback_type",
                "idempotency_key", "provider_user_id", "extra_data_hash", "provider_transaction_id",
                "provider_show_id", "provider_request_id", "placement_id", "adsource_id", "network_firm_id",
                "source_currency", "source_amount_units", "amount_scale", "signed_field_mask",
                "evidence_provenance", "canonical_payload_hash", "authentication_level", "signature_status",
                "ingress_response_code", "received_at", "creator", "create_time", "deleted");
        String unchangedPayload = unchangedColumns("payload_ciphertext", "payload_nonce", "payload_key_id",
                "payload_envelope_version", "payload_expires_at");
        String unchangedProcessing = unchangedColumns("processing_status", "error_code", "lease_owner",
                "lease_until", "processing_attempt_count", "next_attempt_at", "processed_at");
        String validProcessingTransition = "(" + unchangedProcessing + ") OR ("
                + "(OLD.`processing_status`='PENDING' AND NEW.`processing_status`='PROCESSING' "
                + "AND NEW.`processing_attempt_count`=OLD.`processing_attempt_count`+1 "
                + "AND NEW.`error_code` IS NULL) OR "
                + "(OLD.`processing_status`='RETRY_WAIT' AND NEW.`processing_status`='PROCESSING' "
                + "AND NEW.`processing_attempt_count`=OLD.`processing_attempt_count`+1 "
                + "AND NEW.`error_code` IS NULL) OR "
                + "(OLD.`processing_status`='PROCESSING' AND NEW.`processing_status`='PROCESSING' "
                + "AND OLD.`lease_until`<=CURRENT_TIMESTAMP "
                + "AND (NOT (NEW.`lease_owner` <=> OLD.`lease_owner`) "
                + "OR NOT (NEW.`lease_until` <=> OLD.`lease_until`)) "
                + "AND NEW.`processing_attempt_count`=OLD.`processing_attempt_count`+1 "
                + "AND NEW.`error_code` IS NULL) OR "
                + "(OLD.`processing_status`='PROCESSING' AND NEW.`processing_status`='RETRY_WAIT' "
                + "AND NEW.`processing_attempt_count`=OLD.`processing_attempt_count` "
                + "AND NEW.`error_code` IS NOT NULL) OR "
                + "(OLD.`processing_status`='PROCESSING' "
                + "AND NEW.`processing_status` IN ('SUCCEEDED','REJECTED','DEAD_LETTER') "
                + "AND NEW.`processing_attempt_count`=OLD.`processing_attempt_count`))";
        String monotonicDeadLetterAlert = "(NEW.`dead_letter_alerted_at` "
                + "<=> OLD.`dead_letter_alerted_at`) OR (OLD.`dead_letter_alerted_at` IS NULL "
                + "AND NEW.`dead_letter_alerted_at` IS NOT NULL "
                + "AND NEW.`processing_status`='DEAD_LETTER')";
        return "BEGIN IF NOT (" + immutableProvenance + ") THEN SIGNAL SQLSTATE '45000' "
                + "SET MESSAGE_TEXT='callback inbox provenance is immutable'; "
                + "ELSEIF NOT (((NEW.`delivery_integrity_status` <=> OLD.`delivery_integrity_status`) "
                + "AND (NEW.`integrity_conflict_at` <=> OLD.`integrity_conflict_at`)) OR "
                + "(OLD.`delivery_integrity_status`='CANONICAL' "
                + "AND NEW.`delivery_integrity_status`='PAYLOAD_CONFLICT' "
                + "AND OLD.`integrity_conflict_at` IS NULL "
                + "AND NEW.`integrity_conflict_at` IS NOT NULL)) THEN SIGNAL SQLSTATE '45000' "
                + "SET MESSAGE_TEXT='callback inbox integrity is monotonic'; "
                + "ELSEIF NOT (" + validProcessingTransition + ") THEN SIGNAL SQLSTATE '45000' "
                + "SET MESSAGE_TEXT='callback processing transition is not allowed'; "
                + "ELSEIF NOT (" + monotonicDeadLetterAlert + ") THEN SIGNAL SQLSTATE '45000' "
                + "SET MESSAGE_TEXT='callback dead-letter alert is monotonic'; "
                + "ELSEIF NOT ((" + unchangedPayload + ") OR "
                + "(OLD.`processing_status` IN ('SUCCEEDED','REJECTED','DEAD_LETTER') "
                + "AND NEW.`processing_status`=OLD.`processing_status` "
                + "AND OLD.`payload_ciphertext` IS NOT NULL AND OLD.`payload_nonce` IS NOT NULL "
                + "AND OLD.`payload_key_id` IS NOT NULL AND OLD.`payload_envelope_version` IS NOT NULL "
                + "AND OLD.`payload_expires_at` IS NOT NULL AND OLD.`payload_expires_at` <= CURRENT_TIMESTAMP "
                + "AND NEW.`payload_ciphertext` IS NULL AND NEW.`payload_nonce` IS NULL "
                + "AND NEW.`payload_key_id` IS NULL AND NEW.`payload_envelope_version` IS NULL "
                + "AND NEW.`payload_expires_at` IS NULL)) THEN SIGNAL SQLSTATE '45000' "
                + "SET MESSAGE_TEXT='callback payload can only be erased after expiry'; END IF; END";
    }

    private static String entitlementGrantSessionRangeAction() {
        return "BEGIN DECLARE session_episode_from INT DEFAULT NULL; "
                + "DECLARE session_episode_to INT DEFAULT NULL; "
                + "SELECT `episode_from`,`episode_to` INTO session_episode_from,session_episode_to "
                + "FROM `skit_ad_session` WHERE `tenant_id`=NEW.`tenant_id` "
                + "AND `id`=NEW.`ad_session_id` AND `member_id`=NEW.`member_id` "
                + "AND `drama_id`=NEW.`drama_id` "
                + "AND `provider_transaction_id`=NEW.`provider_transaction_id` FOR SHARE; "
                + "IF session_episode_from IS NULL OR NEW.`episode_no`<session_episode_from "
                + "OR NEW.`episode_no`>session_episode_to THEN SIGNAL SQLSTATE '45000' "
                + "SET MESSAGE_TEXT='entitlement grant episode is outside the session scope'; END IF; END";
    }

    private static String unchangedColumns(String... columns) {
        StringBuilder result = new StringBuilder();
        for (String column : columns) {
            if (result.length() > 0) {
                result.append(" AND ");
            }
            result.append("NEW.`").append(column).append("` <=> OLD.`").append(column).append('`');
        }
        return result.toString();
    }

    private static String canonicalManifest(int version, String description, List<String> manifest) {
        StringBuilder result = new StringBuilder();
        appendManifestPart(result, Integer.toString(version));
        appendManifestPart(result, description);
        for (String entry : manifest) {
            appendManifestPart(result, entry);
        }
        return result.toString();
    }

    private static String manifestEntry(String operation, Object... content) {
        StringBuilder result = new StringBuilder();
        appendManifestPart(result, operation);
        for (Object item : content) {
            String value = item == null ? "<null>" : item.getClass().getName() + ":" + item;
            appendManifestPart(result, value);
        }
        return result.toString();
    }

    private static void appendManifestPart(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void executeSteps(List<SchemaStep> steps) {
        for (SchemaStep step : steps) {
            step.execute();
        }
    }

    private SchemaStep executeSqlStep(String sql) {
        return schemaStep("execute-sql", () -> jdbcTemplate.execute(sql), sql);
    }

    private SchemaStep addColumnStep(String table, String column, String definition) {
        return schemaStep("add-column-if-missing", () -> addColumnIfMissing(table, column, definition),
                COLUMN_EXISTS_QUERY, table, column, addColumnSql(table, column, definition));
    }

    private SchemaStep task2ColumnStep(Task2ColumnSpec spec) {
        return schemaStep("ensure-task2-column", () -> ensureTask2Column(spec),
                COLUMN_DEFINITION_QUERY, spec.table, spec.column,
                addColumnSql(spec.table, spec.column, spec.definition),
                modifyColumnSql(spec.table, spec.column, spec.definition),
                spec.compatibleLegacyDefinition);
    }

    private SchemaStep addIndexStep(String table, String index, String columns, boolean unique) {
        return schemaStep("add-index-if-missing", () -> addIndexIfMissing(table, index, columns, unique),
                INDEX_EXISTS_QUERY, table, index, addIndexSql(table, index, columns, unique));
    }

    private SchemaStep dropIndexStep(String table, String index) {
        return schemaStep("drop-index-if-present", () -> dropIndexIfExists(table, index),
                INDEX_EXISTS_QUERY, table, index, dropIndexSql(table, index));
    }

    private SchemaStep replaceIndexStep(String table, String index, String legacyColumns,
                                        String replacementColumns, boolean unique) {
        return schemaStep("replace-index-if-legacy",
                () -> replaceIndexIfLegacy(table, index, legacyColumns, replacementColumns, unique),
                INDEX_DEFINITION_QUERY, table, index,
                indexDefinition(legacyColumns, unique), indexDefinition(replacementColumns, unique),
                replaceIndexSql(table, index, replacementColumns, unique));
    }

    private SchemaStep addForeignKeyStep(String table, String constraint, String columns,
                                         String referencedTable, String referencedColumns) {
        String sql = addForeignKeySql(table, constraint, columns, referencedTable, referencedColumns);
        return schemaStep("add-foreign-key-if-missing",
                () -> addForeignKeyIfMissing(table, constraint, columns, referencedTable, referencedColumns),
                FOREIGN_KEY_DEFINITION_QUERY, table, constraint, sql);
    }

    private SchemaStep replaceForeignKeyStep(Task2ForeignKeySpec legacy, Task2ForeignKeySpec replacement) {
        return schemaStep("replace-foreign-key-if-legacy",
                () -> replaceForeignKeyIfLegacy(legacy, replacement),
                FOREIGN_KEY_DEFINITION_QUERY, legacy.table, legacy.constraint,
                foreignKeyDefinition(legacy), foreignKeyDefinition(replacement),
                dropForeignKeySql(legacy.table, legacy.constraint),
                INDEX_DEFINITION_QUERY, "1:" + legacy.columns, "1:" + replacement.columns,
                dropIndexSql(legacy.table, legacy.constraint),
                addForeignKeySql(replacement.table, replacement.constraint,
                        quoteColumns(replacement.columns), replacement.referencedTable,
                        quoteColumns(replacement.referencedColumns)));
    }

    private SchemaStep addCheckStep(String table, String constraint, String expression) {
        String sql = addCheckSql(table, constraint, expression);
        return schemaStep("add-check-if-missing", () -> addCheckIfMissing(table, constraint, expression),
                CHECK_DEFINITION_QUERY, table, constraint, sql);
    }

    private SchemaStep task2TriggerStep(Task2TriggerSpec spec) {
        String sql = addTriggerSql(spec.table, spec.trigger, spec.event, spec.action);
        return schemaStep("ensure-task2-trigger", () -> ensureTask2Trigger(spec),
                TRIGGER_DEFINITION_QUERY, spec.table, spec.trigger, spec.event, spec.action, sql);
    }

    private SchemaStep schemaStep(String operation, Runnable action, Object... content) {
        return new SchemaStep(manifestEntry(operation, content), action);
    }

    private SchemaStep updateSqlStep(String sql, Object... suppliedParameters) {
        Object[] parameters = Arrays.copyOf(suppliedParameters, suppliedParameters.length);
        Object[] manifestContent = new Object[parameters.length + 1];
        manifestContent[0] = sql;
        System.arraycopy(parameters, 0, manifestContent, 1, parameters.length);
        return schemaStep("update-sql", () -> jdbcTemplate.update(sql, parameters), manifestContent);
    }

    private static String addColumnSql(String table, String column, String definition) {
        return "ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition;
    }

    private static String modifyColumnSql(String table, String column, String definition) {
        return "ALTER TABLE `" + table + "` MODIFY COLUMN `" + column + "` " + definition;
    }

    private static String quoteColumns(String columns) {
        return "`" + columns.replace(",", "`,`") + "`";
    }

    private static String addIndexSql(String table, String index, String columns, boolean unique) {
        return "ALTER TABLE `" + table + "` ADD " + (unique ? "UNIQUE " : "")
                + "INDEX `" + index + "` (" + columns + ")";
    }

    private static String dropIndexSql(String table, String index) {
        return "ALTER TABLE `" + table + "` DROP INDEX `" + index + "`";
    }

    private static String replaceIndexSql(String table, String index, String columns, boolean unique) {
        return "ALTER TABLE `" + table + "` DROP INDEX `" + index + "`, ADD "
                + (unique ? "UNIQUE " : "") + "INDEX `" + index + "` (" + columns + ")";
    }

    private static String indexDefinition(String columns, boolean unique) {
        return (unique ? "0:" : "1:") + normalizeColumnList(columns);
    }

    private static String dropForeignKeySql(String table, String constraint) {
        return "ALTER TABLE `" + table + "` DROP FOREIGN KEY `" + constraint + "`";
    }

    private static String addForeignKeySql(String table, String constraint, String columns,
                                           String referencedTable, String referencedColumns) {
        return "ALTER TABLE `" + table + "` ADD CONSTRAINT `" + constraint + "` FOREIGN KEY ("
                + columns + ") REFERENCES `" + referencedTable + "` (" + referencedColumns
                + ") ON UPDATE RESTRICT ON DELETE RESTRICT";
    }

    private static String foreignKeyDefinition(Task2ForeignKeySpec spec) {
        return spec.columns + "->" + spec.referencedTable + "(" + spec.referencedColumns
                + "):RESTRICT:RESTRICT";
    }

    private static String addCheckSql(String table, String constraint, String expression) {
        return "ALTER TABLE `" + table + "` ADD CONSTRAINT `" + constraint + "` CHECK (" + expression + ")";
    }

    private static String addTriggerSql(String table, String trigger, String event, String action) {
        return "CREATE TRIGGER IF NOT EXISTS `" + trigger + "` BEFORE " + event + " ON `" + table
                + "` FOR EACH ROW " + action;
    }

    private void createLegacyTables() {
        executeSteps(createLegacyTableSteps());
    }

    private List<SchemaStep> createLegacyTableSteps() {
        return Arrays.asList(executeSqlStep("CREATE TABLE IF NOT EXISTS `skit_admin_record` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL DEFAULT 1,"
                + "`page_key` varchar(64) NOT NULL,`row_key` varchar(128) NOT NULL,`record_data` longtext NOT NULL,"
                + "`status` tinyint NOT NULL DEFAULT 0,`sort` int NOT NULL DEFAULT 0,"
                + auditColumns() + ",PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"),
                executeSqlStep("CREATE TABLE IF NOT EXISTS `skit_system_config` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL DEFAULT 1,`config_data` longtext NOT NULL,"
                + auditColumns() + ",PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"));
    }

    private void migrateLegacyTenantColumns() {
        executeSteps(legacyTenantColumnSteps());
    }

    private List<SchemaStep> legacyTenantColumnSteps() {
        List<SchemaStep> steps = new ArrayList<>();
        steps.add(addColumnStep("skit_admin_record", "tenant_id", "bigint NOT NULL DEFAULT 1 COMMENT '租户编号'"));
        steps.add(addColumnStep("skit_system_config", "tenant_id", "bigint NOT NULL DEFAULT 1 COMMENT '租户编号'"));
        // Keep this released baseline manifest immutable. Task 2 diagnoses duplicates before replacing
        // these compatibility indexes with the same unique singleton keys used by both SQL bootstraps.
        steps.add(addIndexStep("skit_admin_record", "idx_skit_admin_record_tenant_page_row",
                "`tenant_id`,`page_key`,`row_key`", false));
        steps.add(addIndexStep("skit_admin_record", "idx_skit_admin_record_tenant_page",
                "`tenant_id`,`page_key`", false));
        steps.add(addIndexStep("skit_admin_record", "idx_skit_admin_record_tenant_status",
                "`tenant_id`,`status`", false));
        steps.add(addIndexStep("skit_system_config", "idx_skit_system_config_tenant", "`tenant_id`", false));
        return steps;
    }

    private void createDomainTables() {
        executeSteps(createDomainTableSteps());
    }

    private List<SchemaStep> createDomainTableSteps() {
        List<SchemaStep> steps = new ArrayList<>();
        steps.add(executeSqlStep("CREATE TABLE IF NOT EXISTS `skit_agent` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`tenant_code` varchar(32) NOT NULL,"
                + "`root_invite_code` varchar(32) NOT NULL,`status` tinyint NOT NULL DEFAULT 0,`remark` varchar(500) DEFAULT '',"
                + auditColumns() + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_agent_tenant` (`tenant_id`),"
                + "UNIQUE KEY `uk_skit_agent_code` (`tenant_code`),UNIQUE KEY `uk_skit_agent_invite` (`root_invite_code`))"
                + tableOptions()));
        steps.add(executeSqlStep("CREATE TABLE IF NOT EXISTS `skit_app_release_profile` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`profile_code` varchar(32) NOT NULL,"
                + "`channel` varchar(16) NOT NULL DEFAULT 'production',`min_native_version` varchar(32) DEFAULT '',"
                + "`hot_version` varchar(32) DEFAULT '',`hot_bundle_url` varchar(500) DEFAULT '',"
                + "`hot_bundle_sha256` char(64) DEFAULT '',`native_version` varchar(32) DEFAULT '',"
                + "`native_package` varchar(255) DEFAULT '',`status` tinyint NOT NULL DEFAULT 0," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_app_release_profile_code` (`profile_code`),"
                + "UNIQUE KEY `uk_skit_app_release_profile_tenant_channel` (`tenant_id`,`channel`))" + tableOptions()));
        steps.add(executeSqlStep("CREATE TABLE IF NOT EXISTS `skit_ad_account` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`provider` varchar(16) NOT NULL,"
                + "`account_name` varchar(128) DEFAULT '',`account_id` varchar(128) DEFAULT '',`app_id` varchar(128) DEFAULT '',"
                + "`app_key` varchar(255) DEFAULT '',`secret` text,`config_data` longtext,`status` tinyint NOT NULL DEFAULT 1,"
                + auditColumns() + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_ad_account_tenant_provider` (`tenant_id`,`provider`))"
                + tableOptions()));
        steps.add(executeSqlStep("CREATE TABLE IF NOT EXISTS `skit_member` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`mobile` varchar(32) NOT NULL,"
                + "`password` varchar(100) NOT NULL,`nickname` varchar(64) NOT NULL,`inviter_id` bigint DEFAULT NULL,"
                + "`invite_code` varchar(32) NOT NULL,`depth` int NOT NULL DEFAULT 1,`status` tinyint NOT NULL DEFAULT 0,"
                + "`register_ip` varchar(50) DEFAULT '',`login_ip` varchar(50) DEFAULT '',`login_time` datetime DEFAULT NULL,"
                + auditColumns() + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_member_tenant_mobile` (`tenant_id`,`mobile`),"
                + "UNIQUE KEY `uk_skit_member_invite_code` (`invite_code`),KEY `idx_skit_member_tenant_inviter` (`tenant_id`,`inviter_id`))"
                + tableOptions()));
        steps.add(executeSqlStep("CREATE TABLE IF NOT EXISTS `skit_member_closure` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ancestor_id` bigint NOT NULL,"
                + "`descendant_id` bigint NOT NULL,`distance` int NOT NULL," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_member_closure_path` (`tenant_id`,`ancestor_id`,`descendant_id`),"
                + "KEY `idx_skit_member_closure_desc_distance` (`tenant_id`,`descendant_id`,`distance`))" + tableOptions()));
        steps.add(executeSqlStep("CREATE TABLE IF NOT EXISTS `skit_commission_plan` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`version` int NOT NULL,"
                + "`status` tinyint NOT NULL,`published_time` datetime NOT NULL," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_commission_plan_version` (`tenant_id`,`version`),"
                + "KEY `idx_skit_commission_plan_status` (`tenant_id`,`status`))" + tableOptions()));
        steps.add(executeSqlStep("CREATE TABLE IF NOT EXISTS `skit_commission_rule` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`plan_id` bigint NOT NULL,"
                + "`level_no` int NOT NULL,`rate_bps` int NOT NULL," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_commission_rule_level` (`tenant_id`,`plan_id`,`level_no`))"
                + tableOptions()));
        steps.add(executeSqlStep("CREATE TABLE IF NOT EXISTS `skit_ad_revenue_event` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_account_id` bigint NOT NULL,"
                + "`provider` varchar(16) NOT NULL,`placement_id` varchar(128) NOT NULL,"
                + "`external_event_id` varchar(128) NOT NULL,`source_member_id` bigint NOT NULL,"
                + "`gross_amount` decimal(20,8) NOT NULL,`occurred_time` datetime NOT NULL,`completed` bit(1) NOT NULL,"
                + "`mock` bit(1) NOT NULL,`status` tinyint NOT NULL,`rule_version` int DEFAULT NULL,`raw_data` longtext,"
                + auditColumns() + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_revenue_event_external`"
                + " (`tenant_id`,`provider`,`external_event_id`),KEY `idx_skit_revenue_event_member`"
                + " (`tenant_id`,`source_member_id`,`create_time`))" + tableOptions()));
        steps.add(executeSqlStep("CREATE TABLE IF NOT EXISTS `skit_commission_ledger` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`event_id` bigint NOT NULL,"
                + "`beneficiary_type` tinyint NOT NULL,`beneficiary_member_id` bigint NOT NULL DEFAULT 0,`level_no` int NOT NULL,"
                + "`gross_amount` decimal(20,8) NOT NULL,`rate_bps` int NOT NULL,`amount` decimal(20,8) NOT NULL,"
                + "`rule_version` int NOT NULL,`status` tinyint NOT NULL," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_ledger_beneficiary`"
                + " (`tenant_id`,`event_id`,`beneficiary_type`,`beneficiary_member_id`,`level_no`),"
                + "KEY `idx_skit_ledger_member_time` (`tenant_id`,`beneficiary_member_id`,`create_time`))" + tableOptions()));
        return steps;
    }

    private void migrateDomainColumns() {
        executeSteps(domainColumnSteps());
    }

    private List<SchemaStep> domainColumnSteps() {
        return Collections.singletonList(addColumnStep("skit_ad_revenue_event", "placement_id",
                "varchar(128) NOT NULL DEFAULT '' COMMENT '广告位编号' AFTER `provider`"));
    }

    private void migrateDomainIndexes() {
        executeSteps(domainIndexSteps());
    }

    private List<SchemaStep> domainIndexSteps() {
        // 手机号是租户内会员身份；邀请码和 App 上下文决定所属代理商租户。
        return Arrays.asList(dropIndexStep("skit_member", "uk_skit_member_mobile"),
                addIndexStep("skit_member", "uk_skit_member_tenant_mobile", "`tenant_id`,`mobile`", true));
    }

    void migrateLifecycleColumns() {
        executeSteps(lifecycleColumnSteps());
    }

    private List<SchemaStep> lifecycleColumnSteps() {
        return Arrays.asList(addColumnStep("system_tenant_package", "code",
                        "varchar(64) DEFAULT NULL COMMENT '稳定套餐编码' AFTER `id`"),
                addColumnStep("system_tenant_package", "active_code",
                "varchar(64) GENERATED ALWAYS AS (CASE WHEN `deleted` = b'0' AND `code` IS NOT NULL "
                        + "AND TRIM(`code`) <> '' THEN `code` ELSE NULL END) STORED COMMENT '有效套餐编码' "),
                addIndexStep("system_tenant_package", "uk_system_tenant_package_active_code", "`active_code`", true),
                addColumnStep("skit_agent", "archived_time",
                        "datetime DEFAULT NULL COMMENT '归档时间' AFTER `status`"),
                addColumnStep("skit_agent", "archived_by",
                        "bigint DEFAULT NULL COMMENT '归档操作人' AFTER `archived_time`"));
    }

    void migrateActiveUserIdentityConstraints() {
        executeSteps(activeUserIdentitySteps());
    }

    private List<SchemaStep> activeUserIdentitySteps() {
        return Arrays.asList(schemaStep("validate-active-user-identities", this::validateNoActiveUserIdentityDuplicates,
                        DUPLICATE_ACTIVE_USERNAMES_QUERY, DUPLICATE_ACTIVE_MOBILES_QUERY),
                addColumnStep("system_users", "active_username",
                "varchar(30) GENERATED ALWAYS AS (CASE WHEN `deleted` = b'0' THEN `username` ELSE NULL END) "
                        + "STORED COMMENT '有效用户名唯一键'"),
                addColumnStep("system_users", "active_mobile",
                "varchar(11) GENERATED ALWAYS AS (CASE WHEN `deleted` = b'0' AND `mobile` IS NOT NULL "
                        + "AND TRIM(`mobile`) <> '' THEN `mobile` ELSE NULL END) STORED COMMENT '有效手机号唯一键'"),
                addIndexStep("system_users", "uk_system_users_active_username", "`active_username`", true),
                addIndexStep("system_users", "uk_system_users_active_mobile", "`active_mobile`", true));
    }

    private List<SchemaStep> legacyIdentityRepairSteps() {
        return Arrays.asList(executeSqlStep(CREATE_IDENTITY_MIGRATION_AUDIT_TABLE_SQL),
                schemaStep(LEGACY_IDENTITY_ALGORITHM, this::normalizeLegacyActiveUserIdentities,
                        LEGACY_IDENTITY_ALGORITHM, DUPLICATE_ACTIVE_USERNAME_MEMBERS_QUERY,
                        DUPLICATE_ACTIVE_MOBILE_MEMBERS_QUERY, INVALID_AGENT_ADMIN_BINDINGS_QUERY,
                        PROTECTED_PLATFORM_ADMIN_IDS_QUERY, AGENT_ADMIN_BINDINGS_QUERY,
                        ACTIVE_USERNAME_HOLDERS_QUERY, ACTIVE_MOBILE_HOLDERS_QUERY,
                        ACTIVE_USERNAME_EXISTS_QUERY, INSERT_IDENTITY_MIGRATION_AUDIT_SQL,
                        UPDATE_LEGACY_USERNAME_SQL, CLEAR_LEGACY_MOBILE_SQL, UPDATE_AGENT_MOBILE_SQL,
                        UPDATE_TENANT_CONTACT_MOBILE_SQL, "legacy<userId>x<base36Counter>"));
    }

    void normalizeLegacyActiveUserIdentities() {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) { // Isolated unit tests use a mock JdbcTemplate without a DataSource.
            normalizeLegacyActiveUserIdentitiesInCurrentTransaction();
            return;
        }
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactionTemplate.execute(status -> {
            normalizeLegacyActiveUserIdentitiesInCurrentTransaction();
            return null;
        });
    }

    private void normalizeLegacyActiveUserIdentitiesInCurrentTransaction() {
        List<Map<String, Object>> invalidBindings = jdbcTemplate.queryForList(INVALID_AGENT_ADMIN_BINDINGS_QUERY);
        if (!invalidBindings.isEmpty()) {
            throw new IllegalStateException("Cannot repair legacy identities because agent administrator bindings "
                    + "are incomplete or cross-tenant: " + invalidBindings);
        }
        Map<Long, AgentIdentityBinding> agentBindings = loadAgentIdentityBindings(
                jdbcTemplate.queryForList(AGENT_ADMIN_BINDINGS_QUERY));
        Map<Long, List<IdentityMember>> usernameGroups = loadIdentityGroups(
                jdbcTemplate.queryForList(DUPLICATE_ACTIVE_USERNAME_MEMBERS_QUERY));
        Map<Long, List<IdentityMember>> mobileGroups = loadIdentityGroups(
                jdbcTemplate.queryForList(DUPLICATE_ACTIVE_MOBILE_MEMBERS_QUERY));
        if (usernameGroups.isEmpty() && mobileGroups.isEmpty() && agentBindings.isEmpty()) {
            return;
        }

        Set<Long> platformAdmins = loadUserIds(jdbcTemplate.queryForList(PROTECTED_PLATFORM_ADMIN_IDS_QUERY));
        Map<String, IdentityRepair> legacyRepairMap = new LinkedHashMap<>();
        List<String> protectedConflicts = new ArrayList<>();
        planAgentTargetRepairs(agentBindings, platformAdmins, legacyRepairMap, protectedConflicts);
        planIdentityRepairs("USERNAME", usernameGroups, platformAdmins, agentBindings,
                legacyRepairMap, protectedConflicts);
        planIdentityRepairs("MOBILE", mobileGroups, platformAdmins, agentBindings,
                legacyRepairMap, protectedConflicts);
        if (!protectedConflicts.isEmpty()) {
            throw new IllegalStateException("Protected duplicate identities require manual resolution before "
                    + "migration: " + protectedConflicts);
        }

        Set<String> plannedUsernames = new HashSet<>();
        for (IdentityRepair repair : legacyRepairMap.values()) {
            if ("USERNAME".equals(repair.identityType)) {
                repair.newValue = generateLegacyUsername(repair.changedUserId, plannedUsernames);
            }
        }
        for (IdentityRepair repair : legacyRepairMap.values()) {
            applyIdentityRepair(repair);
        }
        for (AgentIdentityBinding binding : agentBindings.values()) {
            synchronizeAgentIdentity(binding);
        }
        validateNoActiveUserIdentityDuplicates();
    }

    private Map<Long, AgentIdentityBinding> loadAgentIdentityBindings(List<Map<String, Object>> rows) {
        Map<Long, AgentIdentityBinding> result = new LinkedHashMap<>();
        Map<String, Long> targetOwners = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long tenantId = requiredLong(row, "tenant_id");
            Long userId = requiredLong(row, "user_id");
            String rawContactMobile = row.get("contact_mobile") == null ? null
                    : row.get("contact_mobile").toString();
            String targetMobile = SkitAgentCreateReqVO.normalizeMobile(rawContactMobile);
            if (!ValidationUtils.isMobile(targetMobile)) {
                throw new IllegalStateException("Agent tenant " + tenantId
                        + " has an invalid contact mobile and cannot migrate its login identity: "
                        + rawContactMobile);
            }
            AgentIdentityBinding binding = new AgentIdentityBinding(tenantId, userId, rawContactMobile,
                    targetMobile, String.valueOf(row.get("username")),
                    row.get("mobile") == null ? null : row.get("mobile").toString());
            AgentIdentityBinding previousBinding = result.put(userId, binding);
            if (previousBinding != null) {
                throw new IllegalStateException("Agent administrator user " + userId
                        + " is bound to multiple tenants: " + previousBinding.tenantId + "," + tenantId);
            }
            Long previousOwner = targetOwners.put(targetMobile, userId);
            if (previousOwner != null && !previousOwner.equals(userId)) {
                throw new IllegalStateException("Multiple agent administrators require the same login mobile "
                        + targetMobile + ": users=" + previousOwner + "," + userId);
            }
        }
        return result;
    }

    private Map<Long, List<IdentityMember>> loadIdentityGroups(List<Map<String, Object>> rows) {
        Map<Long, List<IdentityMember>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long groupId = requiredLong(row, "group_id");
            IdentityMember member = new IdentityMember(requiredLong(row, "id"),
                    requiredLong(row, "tenant_id"), String.valueOf(row.get("identity_value")));
            groups.computeIfAbsent(groupId, key -> new ArrayList<>()).add(member);
        }
        return groups;
    }

    private static Set<Long> loadUserIds(List<Map<String, Object>> rows) {
        Set<Long> result = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            result.add(requiredLong(row, "id"));
        }
        return result;
    }

    private static Long requiredLong(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (!(value instanceof Number)) {
            throw new IllegalStateException("Identity migration query returned invalid " + key + ": " + row);
        }
        return ((Number) value).longValue();
    }

    private void planAgentTargetRepairs(Map<Long, AgentIdentityBinding> agentBindings,
                                        Set<Long> platformAdmins,
                                        Map<String, IdentityRepair> repairs,
                                        List<String> protectedConflicts) {
        for (AgentIdentityBinding binding : agentBindings.values()) {
            planAgentTargetHolders("USERNAME", binding,
                    jdbcTemplate.queryForList(ACTIVE_USERNAME_HOLDERS_QUERY, binding.targetMobile),
                    platformAdmins, agentBindings, repairs, protectedConflicts);
            planAgentTargetHolders("MOBILE", binding,
                    jdbcTemplate.queryForList(ACTIVE_MOBILE_HOLDERS_QUERY, binding.targetMobile),
                    platformAdmins, agentBindings, repairs, protectedConflicts);
        }
    }

    private static void planAgentTargetHolders(String identityType,
                                               AgentIdentityBinding binding,
                                               List<Map<String, Object>> holderRows,
                                               Set<Long> platformAdmins,
                                               Map<Long, AgentIdentityBinding> agentBindings,
                                               Map<String, IdentityRepair> repairs,
                                               List<String> protectedConflicts) {
        for (Map<String, Object> row : holderRows) {
            IdentityMember holder = new IdentityMember(requiredLong(row, "id"),
                    requiredLong(row, "tenant_id"), row.get("identity_value") == null
                    ? null : row.get("identity_value").toString());
            if (holder.userId.equals(binding.userId)) {
                continue;
            }
            if (platformAdmins.contains(holder.userId) || agentBindings.containsKey(holder.userId)) {
                protectedConflicts.add(identityType + "=" + binding.targetMobile + " (ids="
                        + binding.userId + "," + holder.userId + ")");
                continue;
            }
            addRepair(repairs, new IdentityRepair(identityType, holder, binding.userId,
                    "AGENT_CONTACT_PHONE_TARGET"));
        }
    }

    private static void planIdentityRepairs(String identityType,
                                            Map<Long, List<IdentityMember>> groups,
                                            Set<Long> platformAdmins,
                                            Map<Long, AgentIdentityBinding> agentBindings,
                                            Map<String, IdentityRepair> repairs,
                                            List<String> protectedConflicts) {
        for (List<IdentityMember> members : groups.values()) {
            List<IdentityMember> protectedMembers = new ArrayList<>();
            for (IdentityMember member : members) {
                AgentIdentityBinding agentBinding = agentBindings.get(member.userId);
                if (platformAdmins.contains(member.userId)
                        || agentRetainsIdentity(agentBinding, member.identityValue)) {
                    protectedMembers.add(member);
                }
            }
            if (protectedMembers.size() > 1) {
                protectedConflicts.add(identityType + "=" + members.get(0).identityValue + " (ids="
                        + memberIds(protectedMembers) + ")");
                continue;
            }
            IdentityMember retained = protectedMembers.isEmpty()
                    ? firstLegacyMember(members, agentBindings) : protectedMembers.get(0);
            if (retained == null) {
                continue; // Every current holder is an agent administrator moving to its contact mobile.
            }
            String retainedReason = platformAdmins.contains(retained.userId) ? "PLATFORM_SUPER_ADMIN"
                    : agentRetainsIdentity(agentBindings.get(retained.userId), retained.identityValue)
                    ? "AGENT_CONTACT" : "LOWEST_ID";
            for (IdentityMember member : members) {
                if (!member.userId.equals(retained.userId) && !agentBindings.containsKey(member.userId)) {
                    addRepair(repairs, new IdentityRepair(identityType, member,
                            retained.userId, retainedReason));
                }
            }
        }
    }

    private static boolean agentRetainsIdentity(AgentIdentityBinding binding, String identityValue) {
        return binding != null && Objects.equals(binding.targetMobile, identityValue);
    }

    private static IdentityMember firstLegacyMember(List<IdentityMember> members,
                                                     Map<Long, AgentIdentityBinding> agentBindings) {
        for (IdentityMember member : members) {
            if (!agentBindings.containsKey(member.userId)) {
                return member;
            }
        }
        return null;
    }

    private static void addRepair(Map<String, IdentityRepair> repairs, IdentityRepair repair) {
        String key = repair.identityType + ":" + repair.changedUserId;
        IdentityRepair previous = repairs.get(key);
        if (previous == null) {
            repairs.put(key, repair);
            return;
        }
        if (!Objects.equals(previous.oldValue, repair.oldValue)) {
            throw new IllegalStateException("Conflicting identity repair plans for user "
                    + repair.changedUserId + " (" + repair.identityType + ")");
        }
    }

    private static String memberIds(List<IdentityMember> members) {
        List<Long> ids = new ArrayList<>();
        for (IdentityMember member : members) {
            ids.add(member.userId);
        }
        return ids.toString().replace(" ", "");
    }

    private String generateLegacyUsername(Long userId, Set<String> plannedUsernames) {
        String base = "legacy" + userId;
        for (int attempt = 0; attempt < 100000; attempt++) {
            String suffix = attempt == 0 ? "" : "x" + Integer.toString(attempt, 36);
            int maxBaseLength = 30 - suffix.length();
            String candidate = (base.length() <= maxBaseLength ? base : base.substring(0, maxBaseLength)) + suffix;
            Integer existing = jdbcTemplate.queryForObject(ACTIVE_USERNAME_EXISTS_QUERY, Integer.class, candidate);
            if ((existing == null || existing == 0) && plannedUsernames.add(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a unique legacy username for user " + userId);
    }

    private void synchronizeAgentIdentity(AgentIdentityBinding binding) {
        if (!Objects.equals(binding.username, binding.targetMobile)) {
            IdentityRepair usernameRepair = new IdentityRepair("USERNAME",
                    new IdentityMember(binding.userId, binding.tenantId, binding.username),
                    binding.userId, "AGENT_CONTACT_PHONE_SYNC");
            usernameRepair.newValue = binding.targetMobile;
            applyIdentityRepair(usernameRepair);
        }
        if (!Objects.equals(binding.mobile, binding.targetMobile)) {
            IdentityRepair mobileRepair = new IdentityRepair("MOBILE",
                    new IdentityMember(binding.userId, binding.tenantId, binding.mobile),
                    binding.userId, "AGENT_CONTACT_PHONE_SYNC");
            mobileRepair.newValue = binding.targetMobile;
            applyIdentityRepair(mobileRepair);
        }
        if (!Objects.equals(binding.rawContactMobile, binding.targetMobile)) {
            IdentityRepair tenantMobileRepair = new IdentityRepair("TENANT_MOBILE",
                    new IdentityMember(binding.userId, binding.tenantId, binding.rawContactMobile),
                    binding.userId, "AGENT_CONTACT_PHONE_NORMALIZED");
            tenantMobileRepair.newValue = binding.targetMobile;
            applyIdentityRepair(tenantMobileRepair);
        }
    }

    private void applyIdentityRepair(IdentityRepair repair) {
        int auditRows = jdbcTemplate.update(INSERT_IDENTITY_MIGRATION_AUDIT_SQL,
                LEGACY_IDENTITY_MIGRATION_VERSION, repair.identityType, repair.changedUserId,
                repair.changedTenantId, repair.retainedUserId, repair.retainedReason,
                repair.oldValue, repair.newValue);
        if (auditRows != 1) {
            throw new IllegalStateException("Could not audit identity repair for user " + repair.changedUserId);
        }
        int changedRows;
        if ("USERNAME".equals(repair.identityType)) {
            changedRows = jdbcTemplate.update(UPDATE_LEGACY_USERNAME_SQL,
                    repair.newValue, repair.changedUserId, repair.oldValue);
        } else if ("TENANT_MOBILE".equals(repair.identityType)) {
            changedRows = jdbcTemplate.update(UPDATE_TENANT_CONTACT_MOBILE_SQL,
                    repair.newValue, repair.changedTenantId, repair.changedUserId, repair.oldValue);
        } else if (repair.newValue != null) {
            changedRows = jdbcTemplate.update(UPDATE_AGENT_MOBILE_SQL,
                    repair.newValue, repair.changedUserId, repair.oldValue);
        } else {
            changedRows = jdbcTemplate.update(CLEAR_LEGACY_MOBILE_SQL,
                    repair.changedUserId, repair.oldValue);
        }
        if (changedRows != 1) {
            throw new IllegalStateException("Identity changed while migration was planning repair for user "
                    + repair.changedUserId + " (" + repair.identityType + ")");
        }
    }

    private static final class IdentityMember {

        private final Long userId;
        private final Long tenantId;
        private final String identityValue;

        private IdentityMember(Long userId, Long tenantId, String identityValue) {
            this.userId = userId;
            this.tenantId = tenantId;
            this.identityValue = identityValue;
        }

    }

    private static final class AgentIdentityBinding {

        private final Long tenantId;
        private final Long userId;
        private final String rawContactMobile;
        private final String targetMobile;
        private final String username;
        private final String mobile;

        private AgentIdentityBinding(Long tenantId, Long userId, String rawContactMobile,
                                     String targetMobile, String username, String mobile) {
            this.tenantId = tenantId;
            this.userId = userId;
            this.rawContactMobile = rawContactMobile;
            this.targetMobile = targetMobile;
            this.username = username;
            this.mobile = mobile;
        }

    }

    private static final class IdentityRepair {

        private final String identityType;
        private final Long changedUserId;
        private final Long changedTenantId;
        private final Long retainedUserId;
        private final String retainedReason;
        private final String oldValue;
        private String newValue;

        private IdentityRepair(String identityType, IdentityMember changed, Long retainedUserId,
                               String retainedReason) {
            this.identityType = identityType;
            this.changedUserId = changed.userId;
            this.changedTenantId = changed.tenantId;
            this.retainedUserId = retainedUserId;
            this.retainedReason = retainedReason;
            this.oldValue = changed.identityValue;
        }

    }

    void migrateDomainIntegrityConstraints() {
        executeSteps(domainIntegritySteps());
    }

    private List<SchemaStep> domainIntegritySteps() {
        return Arrays.asList(schemaStep("validate-domain-singletons",
                        this::validateNoActiveDomainSingletonDuplicates,
                        DUPLICATE_ACTIVE_APP_RELEASE_PROFILES_QUERY, DUPLICATE_ACTIVE_COMMISSION_PLANS_QUERY),
                addColumnStep("skit_app_release_profile", "active_tenant_id",
                        "bigint GENERATED ALWAYS AS (CASE WHEN `deleted` = b'0' THEN `tenant_id` ELSE NULL END) "
                                + "STORED COMMENT '有效租户发布档案唯一键'"),
                addIndexStep("skit_app_release_profile", "uk_skit_app_release_profile_active_tenant",
                        "`active_tenant_id`", true),
                addColumnStep("skit_commission_plan", "active_tenant_id",
                        "bigint GENERATED ALWAYS AS (CASE WHEN `deleted` = b'0' AND `status` = 0 "
                                + "THEN `tenant_id` ELSE NULL END) STORED COMMENT '生效分成方案租户唯一键'"),
                addIndexStep("skit_commission_plan", "uk_skit_commission_plan_active_tenant",
                        "`active_tenant_id`", true),
                addIndexStep("skit_member", "idx_skit_member_tenant_status_id",
                        "`tenant_id`,`status`,`id`", false),
                addIndexStep("skit_commission_plan", "idx_skit_commission_plan_status_version",
                        "`tenant_id`,`status`,`version`", false),
                addIndexStep("skit_commission_ledger", "idx_skit_ledger_member_type_time_id",
                        "`tenant_id`,`beneficiary_member_id`,`beneficiary_type`,`create_time`,`id`", false),
                addIndexStep("skit_commission_ledger", "idx_skit_ledger_beneficiary_time_id",
                        "`tenant_id`,`beneficiary_type`,`create_time`,`id`", false),
                addIndexStep("skit_ad_revenue_event", "idx_skit_revenue_provider_time_id",
                        "`tenant_id`,`provider`,`occurred_time`,`id`", false));
    }

    private List<SchemaStep> task2SchemaSteps() {
        List<SchemaStep> steps = new ArrayList<>();
        steps.add(dropIndexStep("skit_admin_record", "idx_skit_admin_record_tenant_page_row"));
        steps.add(dropIndexStep("skit_system_config", "idx_skit_system_config_tenant"));
        steps.add(addIndexStep("skit_admin_record", "uk_skit_admin_record_tenant_page_row",
                "`tenant_id`,`page_key`,`row_key`", true));
        steps.add(addIndexStep("skit_system_config", "uk_skit_system_config_tenant", "`tenant_id`", true));
        steps.add(addIndexStep("skit_agent", "uk_skit_agent_tenant_id", "`tenant_id`,`id`", true));
        steps.add(addIndexStep("skit_ad_account", "uk_skit_ad_account_tenant_id", "`tenant_id`,`id`", true));
        steps.add(addIndexStep("skit_member", "uk_skit_member_tenant_id", "`tenant_id`,`id`", true));
        steps.add(addIndexStep("skit_commission_plan", "uk_skit_commission_plan_tenant_id",
                "`tenant_id`,`id`", true));
        steps.add(addIndexStep("skit_ad_revenue_event", "uk_skit_revenue_event_tenant_id",
                "`tenant_id`,`id`", true));
        steps.add(addIndexStep("skit_commission_ledger", "uk_skit_ledger_tenant_id",
                "`tenant_id`,`id`", true));

        steps.addAll(task2ParentColumnSteps());
        for (String sql : SkitAdSchemaDdl.createTableStatements()) {
            steps.add(executeSqlStep(sql));
        }

        steps.add(updateSqlStep("UPDATE `skit_ad_revenue_event` SET "
                + "`source_type`='LEGACY_CLIENT',`source_amount_units`=CAST(`gross_amount` * ? AS SIGNED),"
                + "`estimated_amount_units`=CAST(`gross_amount` * ? AS SIGNED),`reconciled_amount_units`=0,"
                + "`amount_scale`=8,`source_currency`='CNY',`match_status`='LEGACY_UNMATCHED',"
                + "`source_verification_status`='LEGACY_UNVERIFIED',"
                + "`reward_qualification_status`='NOT_APPLICABLE',"
                + "`reconciliation_status`='NON_SETTLEABLE',`legacy_unverified`=b'1' "
                + "WHERE `legacy_unverified`=b'1'", TASK_2_MONEY_SCALE_FACTOR, TASK_2_MONEY_SCALE_FACTOR));
        steps.add(updateSqlStep("UPDATE `skit_commission_ledger` SET "
                + "`entry_type`='LEGACY_ESTIMATE',`balance_bucket`='NON_SETTLEABLE',`currency`='CNY',"
                + "`gross_amount_units`=CAST(`gross_amount` * ? AS SIGNED),"
                + "`amount_units`=CAST(`amount` * ? AS SIGNED),`amount_scale`=8,`revision_no`=0,"
                + "`legacy_unverified`=b'1' WHERE `legacy_unverified`=b'1'",
                TASK_2_MONEY_SCALE_FACTOR, TASK_2_MONEY_SCALE_FACTOR));
        steps.addAll(task2ParentTriggerSteps());
        steps.add(updateSqlStep("INSERT INTO `skit_invite_code_registry` "
                + "(`tenant_id`,`code`,`owner_type`,`agent_id`,`member_id`,`status`,`creator`,`updater`) "
                + "SELECT `a`.`tenant_id`,TRIM(`a`.`root_invite_code`),'AGENT',`a`.`id`,NULL,'ACTIVE',"
                + "'skit-migration-2026071401','skit-migration-2026071401' FROM `skit_agent` `a` "
                + "WHERE `a`.`deleted`=b'0' AND NOT EXISTS (SELECT 1 FROM `skit_invite_code_registry` `r` "
                + "WHERE `r`.`normalized_code`=UPPER(TRIM(`a`.`root_invite_code`)) "
                + "AND `r`.`tenant_id`=`a`.`tenant_id` AND `r`.`owner_type`='AGENT' "
                + "AND `r`.`agent_id`=`a`.`id` AND `r`.`member_id` IS NULL AND `r`.`status`='ACTIVE')"));
        steps.add(updateSqlStep("INSERT INTO `skit_invite_code_registry` "
                + "(`tenant_id`,`code`,`owner_type`,`agent_id`,`member_id`,`status`,`creator`,`updater`) "
                + "SELECT `m`.`tenant_id`,TRIM(`m`.`invite_code`),'MEMBER',NULL,`m`.`id`,'ACTIVE',"
                + "'skit-migration-2026071401','skit-migration-2026071401' FROM `skit_member` `m` "
                + "WHERE `m`.`deleted`=b'0' AND NOT EXISTS (SELECT 1 FROM `skit_invite_code_registry` `r` "
                + "WHERE `r`.`normalized_code`=UPPER(TRIM(`m`.`invite_code`)) "
                + "AND `r`.`tenant_id`=`m`.`tenant_id` AND `r`.`owner_type`='MEMBER' "
                + "AND `r`.`member_id`=`m`.`id` AND `r`.`agent_id` IS NULL AND `r`.`status`='ACTIVE')"));
        steps.add(schemaStep("validate-task2-invite-registry-coverage",
                this::validateTask2InviteRegistryCoverage, "active-source-owner-to-registry-v1"));

        steps.add(dropIndexStep("skit_commission_ledger", "uk_skit_ledger_beneficiary"));
        steps.add(dropIndexStep("skit_ad_revenue_event", "uk_skit_revenue_event_external"));
        steps.add(addIndexStep("skit_ad_revenue_event", "idx_skit_revenue_event_external",
                "`tenant_id`,`provider`,`external_event_id`", false));
        steps.add(addIndexStep("skit_commission_ledger", "uk_skit_ledger_entry_revision",
                "`tenant_id`,`event_id`,`beneficiary_type`,`beneficiary_member_id`,`level_no`,"
                        + "`entry_type`,`revision_no`", true));
        steps.add(addIndexStep("skit_ad_revenue_event", "uk_skit_revenue_source_idem",
                "`tenant_id`,`ad_account_id`,`source_type`,`external_event_id`", true));
        steps.add(addIndexStep("skit_ad_revenue_event", "uk_skit_revenue_inbox_source",
                "`tenant_id`,`callback_inbox_id`,`source_type`", true));
        steps.add(addIndexStep("skit_ad_revenue_event", "uk_skit_revenue_session_source",
                "`tenant_id`,`ad_session_id`,`source_type`", true));

        steps.addAll(task2ParentForeignKeySteps());
        steps.addAll(task2ParentCheckSteps());
        steps.add(schemaStep("validate-task2-final-schema", () -> validateTask2TableSignatures(true),
                "task2-table-signatures-v2", SkitAdSchemaSignature.expectedFingerprints()));
        return steps;
    }

    private List<SchemaStep> task2ParentColumnSteps() {
        List<SchemaStep> steps = new ArrayList<>();
        for (Task2ColumnSpec spec : TASK_2_PARENT_COLUMN_SPECS) {
            steps.add(task2ColumnStep(spec));
        }
        return steps;
    }

    private List<SchemaStep> task2ParentForeignKeySteps() {
        List<SchemaStep> steps = new ArrayList<>();
        for (Task2ForeignKeySpec spec : TASK_2_PARENT_FOREIGN_KEY_SPECS) {
            steps.add(addForeignKeyStep(spec.table, spec.constraint, quoteColumns(spec.columns),
                    spec.referencedTable, quoteColumns(spec.referencedColumns)));
        }
        return steps;
    }

    private List<SchemaStep> task2ParentCheckSteps() {
        List<SchemaStep> steps = new ArrayList<>();
        for (Task2CheckSpec spec : TASK_2_PARENT_CHECK_SPECS) {
            steps.add(addCheckStep(spec.table, spec.constraint, spec.expression));
        }
        return steps;
    }

    private List<SchemaStep> task2ParentTriggerSteps() {
        List<SchemaStep> steps = new ArrayList<>();
        for (Task2TriggerSpec spec : TASK_2_PARENT_TRIGGER_SPECS) {
            steps.add(task2TriggerStep(spec));
        }
        return steps;
    }

    private List<SchemaStep> policySnapshotImmutabilitySteps() {
        List<SchemaStep> steps = new ArrayList<>();
        for (Task2TriggerSpec spec : POLICY_SNAPSHOT_IMMUTABILITY_TRIGGER_SPECS) {
            steps.add(task2TriggerStep(spec));
        }
        return steps;
    }

    private List<SchemaStep> task5SchemaHardeningSteps() {
        List<SchemaStep> steps = new ArrayList<>();
        for (Task2ColumnSpec spec : TASK_5_SCHEMA_COLUMN_SPECS) {
            steps.add(task2ColumnStep(spec));
        }
        steps.add(addIndexStep("skit_ad_session", "uk_skit_ad_session_grant_scope",
                "`tenant_id`,`id`,`member_id`,`drama_id`,`episode_from`,`provider_transaction_id`", true));
        steps.add(addIndexStep("skit_native_player_grant", "uk_skit_player_grant_scope_id",
                "`tenant_id`,`id`,`member_id`,`drama_id`", true));
        steps.add(addIndexStep("skit_native_player_grant", "idx_skit_player_grant_lookup",
                "`tenant_id`,`member_id`,`drama_id`,`status`,`expires_at`,`id`", false));
        steps.add(addIndexStep("skit_content_entitlement", "uk_skit_entitlement_grant_binding",
                "`tenant_id`,`id`,`member_id`,`drama_id`,`episode_no`", true));
        for (Task2ForeignKeySpec spec : TASK_5_SCHEMA_FOREIGN_KEY_SPECS) {
            steps.add(addForeignKeyStep(spec.table, spec.constraint, quoteColumns(spec.columns),
                    spec.referencedTable, quoteColumns(spec.referencedColumns)));
        }
        for (Task2CheckSpec spec : TASK_5_SCHEMA_CHECK_SPECS) {
            steps.add(addCheckStep(spec.table, spec.constraint, spec.expression));
        }
        steps.add(schemaStep("validate-task5-schema-hardening", () -> validateTask5SchemaHardening(true),
                "task5-ad-session-entitlement-signatures-v2",
                SkitAdSchemaSignature.expectedTask5HardenedFingerprints()));
        return steps;
    }

    private List<SchemaStep> task7SchemaHardeningSteps() {
        List<SchemaStep> steps = new ArrayList<>();
        for (Task2ColumnSpec spec : TASK_7_SCHEMA_COLUMN_SPECS) {
            steps.add(task2ColumnStep(spec));
        }
        steps.add(addIndexStep("skit_ad_session", "uk_skit_ad_session_account_binding",
                "`tenant_id`,`id`,`ad_account_id`", true));
        steps.add(addIndexStep("skit_ad_session", "uk_skit_ad_session_revenue_binding",
                "`tenant_id`,`id`,`ad_account_id`,`member_id`,`policy_snapshot_id`", true));
        steps.add(addIndexStep("skit_ad_session", "uk_skit_ad_session_grant_envelope",
                "`tenant_id`,`id`,`member_id`,`drama_id`,`provider_transaction_id`", true));
        steps.add(addIndexStep("skit_ad_callback_inbox", "uk_skit_callback_inbox_attempt_binding",
                "`tenant_id`,`id`,`ad_account_id`,`ad_session_ref_id`", true));
        steps.add(addIndexStep("skit_ad_revenue_event", "uk_skit_revenue_event_snapshot_binding",
                "`tenant_id`,`id`,`policy_snapshot_id`", true));
        steps.add(addIndexStep("skit_ad_callback_inbox", "idx_skit_callback_inbox_payload_expiry",
                "`payload_expires_at`,`id`", false));
        steps.add(addIndexStep("skit_ad_callback_attempt", "idx_skit_callback_attempt_retention",
                "`received_at`,`id`", false));
        steps.add(addIndexStep("skit_ad_callback_edge_attempt", "idx_skit_callback_edge_retention",
                "`received_at`,`id`", false));
        steps.add(addIndexStep("skit_ad_network_capability", "idx_skit_network_cap_readiness",
                "`tenant_id`,`ad_account_id`,`network_firm_id`,`enabled`,`reward_authority`,`verified_at`",
                false));
        steps.add(replaceForeignKeyStep(TASK_7_LEGACY_GRANT_SESSION_BINDING,
                TASK_7_GRANT_SESSION_BINDING));
        for (Task2ForeignKeySpec spec : TASK_7_SCHEMA_FOREIGN_KEY_SPECS) {
            steps.add(addForeignKeyStep(spec.table, spec.constraint, quoteColumns(spec.columns),
                    spec.referencedTable, quoteColumns(spec.referencedColumns)));
        }
        for (Task2CheckSpec spec : TASK_7_SCHEMA_CHECK_SPECS) {
            steps.add(addCheckStep(spec.table, spec.constraint, spec.expression));
        }
        for (Task2TriggerSpec spec : TASK_7_SCHEMA_TRIGGER_SPECS) {
            steps.add(task2TriggerStep(spec));
        }
        steps.add(schemaStep("validate-task7-schema-hardening", () -> validateTask7SchemaHardening(true),
                "task7-callback-finance-signatures-v1",
                SkitAdSchemaSignature.expectedTask7HardenedFingerprints()));
        return steps;
    }

    private List<SchemaStep> task10ReconciliationSteps() {
        List<SchemaStep> steps = new ArrayList<>();
        steps.add(executeSqlStep(CREATE_TASK_10_REPORTING_CREDENTIAL_TABLE_SQL));
        steps.add(executeSqlStep(CREATE_TASK_10_RECONCILIATION_ALLOCATION_TABLE_SQL));
        steps.add(executeSqlStep(CREATE_TASK_10_RECONCILIATION_EVENT_LINK_TABLE_SQL));

        // Existing pull history predates the provider-day/request envelope. Backfill it before
        // making those two facts mandatory; the response hash is the only immutable legacy digest.
        steps.add(addColumnStep("skit_ad_report_pull", "report_date", "date DEFAULT NULL"));
        steps.add(addColumnStep("skit_ad_report_pull", "request_hash", "binary(32) DEFAULT NULL"));
        steps.add(updateSqlStep("UPDATE `skit_ad_report_pull` SET `report_date`=DATE(`range_start`) "
                + "WHERE `report_date` IS NULL"));
        steps.add(updateSqlStep("UPDATE `skit_ad_report_pull` SET `request_hash`=`response_hash` "
                + "WHERE `request_hash` IS NULL"));
        for (Task2ColumnSpec spec : TASK_10_RECONCILIATION_COLUMN_SPECS) {
            steps.add(task2ColumnStep(spec));
        }
        steps.add(updateSqlStep("UPDATE `skit_ad_account` SET `config_data`="
                + "JSON_SET(`config_data`,'$.adFormat','rewarded_video') "
                + "WHERE `provider`='TAKU' AND JSON_VALID(`config_data`) "
                + "AND JSON_UNQUOTE(JSON_EXTRACT(`config_data`,'$.adFormat')) IS NULL"));
        // Old buckets had no attributable/suspense split; preserving their full actual amount as
        // suspense is the only fail-closed migration that cannot accidentally pay a user.
        steps.add(updateSqlStep("UPDATE `skit_ad_reconciliation_bucket` SET "
                + "`attributable_actual_units`=0,`suspense_units`=`report_actual_units` "
                + "WHERE `attributable_actual_units` + `suspense_units` <> `report_actual_units`"));

        steps.add(addIndexStep("skit_ad_account", "idx_skit_ad_account_report_due",
                "`provider`,`status`,`report_next_allowed_at`,`report_pull_lease_until`,`id`", false));
        steps.add(addIndexStep("skit_ad_report_pull", "idx_skit_report_pull_request",
                "`tenant_id`,`ad_account_id`,`report_date`,`request_hash`", false));
        steps.add(addIndexStep("skit_ad_report_pull", "idx_skit_report_pull_credential",
                "`tenant_id`,`ad_account_id`,`credential_version`", false));
        steps.add(addIndexStep("skit_ad_report_pull", "idx_skit_report_pull_final_window",
                "`tenant_id`,`ad_account_id`,`report_date`,`final_window`,`status`", false));
        steps.add(addIndexStep("skit_ad_revenue_event", "idx_skit_revenue_report_pending",
                "`tenant_id`,`ad_account_id`,`reconciliation_revision_id`,`occurred_time`,`id`", false));
        steps.add(replaceIndexStep("skit_ad_report_pull", "uk_skit_report_pull_response",
                "`tenant_id`,`ad_account_id`,`range_start`,`range_end`,`response_hash`",
                "`tenant_id`,`ad_account_id`,`range_start`,`range_end`,`request_hash`,`response_hash`,"
                        + "`credential_version`,`final_window`", true));
        steps.add(replaceIndexStep("skit_ad_reconciliation_bucket", "uk_skit_recon_bucket_identity",
                "`tenant_id`,`ad_account_id`,`bucket_key`,`report_date`,`report_timezone`,`placement_id`,"
                        + "`network_firm_id`,`adsource_id`,`currency`",
                "`tenant_id`,`ad_account_id`,`bucket_key`,`report_date`,`report_timezone`,`app_id`,"
                        + "`placement_id`,`ad_format`,`network_account_id`,`network_firm_id`,"
                        + "`adsource_id`,`currency`", true));
        steps.add(addForeignKeyStep("skit_ad_report_pull", "fk_skit_report_pull_credential",
                "`tenant_id`,`ad_account_id`,`credential_version`",
                "skit_ad_reporting_credential_version",
                "`tenant_id`,`ad_account_id`,`credential_version`"));
        for (Task2CheckSpec spec : TASK_10_RECONCILIATION_CHECK_SPECS) {
            steps.add(addCheckStep(spec.table, spec.constraint, spec.expression));
        }
        for (Task2TriggerSpec spec : TASK_10_RECONCILIATION_TRIGGER_SPECS) {
            steps.add(task2TriggerStep(spec));
        }
        steps.add(schemaStep("validate-task10-reconciliation-schema",
                () -> validateTask10ReconciliationSchema(true),
                "task10-taku-reporting-reconciliation-v1"));
        return steps;
    }

    private List<SchemaStep> task12ReadinessSteps() {
        List<SchemaStep> steps = new ArrayList<>();
        for (Task2ColumnSpec spec : TASK_12_READINESS_COLUMN_SPECS) {
            steps.add(task2ColumnStep(spec));
        }
        for (Task2CheckSpec spec : TASK_12_READINESS_CHECK_SPECS) {
            steps.add(addCheckStep(spec.table, spec.constraint, spec.expression));
        }
        steps.add(schemaStep("validate-task12-ad-readiness-schema",
                () -> validateTask12ReadinessSchema(true),
                "task12-ad-readiness-rollout-v1"));
        return steps;
    }

    private List<SchemaStep> task11ManagementSteps() {
        List<SchemaStep> steps = new ArrayList<>();
        steps.add(executeSqlStep(CREATE_TASK_11_MANAGEMENT_AUDIT_TABLE_SQL));
        steps.add(executeSqlStep(CREATE_TASK_11_CALLBACK_REPLAY_TABLE_SQL));
        steps.add(executeSqlStep(CREATE_TASK_11_SECURITY_REVOCATION_TABLE_SQL));
        steps.add(executeSqlStep(CREATE_TASK_11_EXPORT_TASK_TABLE_SQL));

        steps.add(addIndexStep("skit_ad_session", "idx_skit_ad_session_management_account",
                "`tenant_id`,`ad_account_id`,`create_time`,`id`", false));
        steps.add(addIndexStep("skit_ad_session", "idx_skit_ad_session_management_reward",
                "`tenant_id`,`reward_verification_status`,`create_time`,`id`", false));
        steps.add(addIndexStep("skit_ad_callback_inbox",
                "idx_skit_callback_inbox_management_account",
                "`tenant_id`,`ad_account_id`,`received_at`,`id`", false));
        steps.add(addIndexStep("skit_ad_callback_inbox",
                "idx_skit_callback_inbox_management_status",
                "`tenant_id`,`processing_status`,`received_at`,`id`", false));
        steps.add(addIndexStep("skit_ad_revenue_event", "idx_skit_revenue_management_time",
                "`tenant_id`,`occurred_time`,`id`", false));
        steps.add(addIndexStep("skit_ad_revenue_event", "idx_skit_revenue_management_member",
                "`tenant_id`,`source_member_id`,`occurred_time`,`id`", false));
        steps.add(addIndexStep("skit_ad_revenue_event",
                "idx_skit_revenue_management_reconciliation",
                "`tenant_id`,`reconciliation_status`,`source_currency`,`occurred_time`,`id`", false));
        steps.add(addIndexStep("skit_commission_ledger", "idx_skit_ledger_management_balance",
                "`tenant_id`,`currency`,`balance_bucket`,`create_time`,`id`", false));
        steps.add(addIndexStep("skit_commission_ledger", "idx_skit_ledger_management_event",
                "`tenant_id`,`event_id`,`id`", false));
        steps.add(addIndexStep("skit_ad_report_pull", "idx_skit_report_pull_management_account",
                "`tenant_id`,`ad_account_id`,`pulled_at`,`id`", false));
        steps.add(addIndexStep("skit_ad_report_pull", "idx_skit_report_pull_management_status",
                "`tenant_id`,`status`,`pulled_at`,`id`", false));
        steps.add(addIndexStep("skit_ad_reconciliation_bucket",
                "idx_skit_recon_bucket_management_account",
                "`tenant_id`,`ad_account_id`,`report_date`,`id`", false));
        steps.add(addIndexStep("skit_ad_reconciliation_revision",
                "idx_skit_recon_revision_management_bucket",
                "`tenant_id`,`reconciliation_bucket_id`,`revision_no`,`id`", false));
        steps.add(addIndexStep("skit_member_closure",
                "idx_skit_member_closure_ancestor_distance",
                "`tenant_id`,`ancestor_id`,`distance`,`descendant_id`", false));
        // Platform-wide audit scans are only available to super_admin and intentionally omit
        // tenant_id as the leading column. Keep the names/shapes explicit in the schema
        // signature allow-list so a tenant query can never accidentally depend on them.
        steps.add(addIndexStep("skit_ad_session", "idx_skit_ad_session_global_created",
                "`create_time`,`id`", false));
        steps.add(addIndexStep("skit_ad_revenue_event", "idx_skit_ad_revenue_global_occurred",
                "`occurred_time`,`id`", false));
        steps.add(addIndexStep("skit_ad_callback_inbox", "idx_skit_ad_callback_global_received",
                "`received_at`,`id`", false));
        steps.add(addIndexStep("skit_ad_reconciliation_bucket", "idx_skit_ad_recon_bucket_global_date",
                "`report_date`,`id`", false));
        for (Task2TriggerSpec spec : TASK_11_MANAGEMENT_TRIGGER_SPECS) {
            steps.add(task2TriggerStep(spec));
        }
        steps.add(schemaStep("validate-task11-management-schema",
                () -> validateTask11ManagementSchema(true),
                "task11-management-audit-query-v1"));
        return steps;
    }

    private List<SchemaStep> task17RuntimeUpdateSteps() {
        List<SchemaStep> steps = new ArrayList<>();
        for (Task2ColumnSpec spec : TASK_17_RUNTIME_UPDATE_COLUMN_SPECS) {
            steps.add(task2ColumnStep(spec));
        }
        for (Task2CheckSpec spec : TASK_17_RUNTIME_UPDATE_CHECK_SPECS) {
            steps.add(addCheckStep(spec.table, spec.constraint, spec.expression));
        }
        steps.add(schemaStep("validate-task17-runtime-update-schema",
                () -> validateTask17RuntimeUpdateSchema(true),
                "task17-signed-runtime-update-v1"));
        return steps;
    }

    private List<SchemaStep> tenantRuntimeUpdateTrustRootSteps() {
        List<SchemaStep> steps = new ArrayList<>();
        for (Task2ColumnSpec spec : TENANT_RUNTIME_UPDATE_TRUST_ROOT_COLUMN_SPECS) {
            steps.add(task2ColumnStep(spec));
        }
        steps.add(addCheckStep(TENANT_RUNTIME_UPDATE_TRUST_ROOT_CHECK_SPEC.table,
                TENANT_RUNTIME_UPDATE_TRUST_ROOT_CHECK_SPEC.constraint,
                TENANT_RUNTIME_UPDATE_TRUST_ROOT_CHECK_SPEC.expression));
        steps.add(addIndexStep("skit_app_release_profile", "uk_skit_app_release_runtime_key",
                "`active_runtime_update_key_fingerprint`", true));
        steps.add(schemaStep("validate-tenant-runtime-update-trust-root-schema",
                () -> validateTenantRuntimeUpdateTrustRootSchema(true),
                "tenant-runtime-update-trust-root-v1"));
        return steps;
    }

    void seedStandardAgentPackage() {
        seedStandardAgentPackageStep().execute();
    }

    private void validateTask2Preflight() {
        validateTask2TableSignatures(false);
        validateTask2LegacySingletons();
        if (tableExists("skit_agent") && tableExists("skit_member")) {
            assertNoTask2PreflightRows("normalized global invite-code collision",
                    TASK_2_INVITE_COLLISIONS_QUERY);
            assertNoTask2PreflightRows("blank agent invite-code owner",
                    "SELECT 'skit_agent' AS `owner_table`,`tenant_id`,`id` AS `owner_id` FROM `skit_agent` "
                            + "WHERE `deleted`=b'0' AND TRIM(`root_invite_code`)='' ORDER BY `tenant_id`,`id`");
            assertNoTask2PreflightRows("blank member invite-code owner",
                    "SELECT 'skit_member' AS `owner_table`,`tenant_id`,`id` AS `owner_id` FROM `skit_member` "
                            + "WHERE `deleted`=b'0' AND TRIM(`invite_code`)='' ORDER BY `tenant_id`,`id`");
        }
        validateExistingInviteRegistryOwners();
        validateTask2Reference("skit_commission_rule", "plan_id", "skit_commission_plan");
        validateTask2Reference("skit_member", "inviter_id", "skit_member");
        validateTask2Reference("skit_member_closure", "ancestor_id", "skit_member");
        validateTask2Reference("skit_member_closure", "descendant_id", "skit_member");
        validateTask2Reference("skit_ad_revenue_event", "ad_account_id", "skit_ad_account");
        validateTask2Reference("skit_ad_revenue_event", "source_member_id", "skit_member");
        validateTask2Reference("skit_commission_ledger", "event_id", "skit_ad_revenue_event");
        validateTask2RevenueIdempotency();
        validateTask2LedgerBeneficiaries();
        validateTask2TenantOwners();
        validateTask2LegacyMoney();
    }

    private void validateTask5SchemaHardeningPreflight() {
        validateTask5SchemaHardening(false);
        if (tableExists("skit_ad_session")) {
            boolean hasScopeReleasedAt = columnExists("skit_ad_session", "active_scope_released_at");
            boolean hasScopeReleaseReason = columnExists("skit_ad_session", "active_scope_release_reason");
            if (hasScopeReleasedAt && hasScopeReleaseReason) {
                assertNoTask5PreflightRows("active scope release evidence",
                        "SELECT `tenant_id`,`id`,`reward_verification_status`,`entitlement_status` "
                                + "FROM `skit_ad_session` WHERE NOT (((`active_scope_hash` IS NOT NULL "
                                + "AND `active_scope_released_at` IS NULL "
                                + "AND `active_scope_release_reason` IS NULL) OR (`active_scope_hash` IS NULL "
                                + "AND `active_scope_released_at` IS NOT NULL "
                                + "AND `active_scope_release_reason` IS NOT NULL)) AND "
                                + "((`active_scope_release_reason` IS NULL "
                                + "AND `active_scope_hash` IS NOT NULL "
                                + "AND `active_scope_released_at` IS NULL "
                                + "AND `reward_verification_status`='PENDING' "
                                + "AND `entitlement_status`='NONE' AND `entitled_at` IS NULL) OR "
                                + "(`active_scope_release_reason`='VERIFY_TIMEOUT' "
                                + "AND `active_scope_hash` IS NULL "
                                + "AND `active_scope_released_at` IS NOT NULL "
                                + "AND `reward_verification_status`='VERIFY_TIMEOUT' "
                                + "AND `entitlement_status`='NONE' AND `entitled_at` IS NULL) OR "
                                + "(`active_scope_release_reason`='REWARD_REJECTED' "
                                + "AND `active_scope_hash` IS NULL "
                                + "AND `active_scope_released_at` IS NOT NULL "
                                + "AND `reward_verification_status`='REJECTED' "
                                + "AND `entitlement_status`='NONE' AND `entitled_at` IS NULL) OR "
                                + "(`active_scope_release_reason`='ENTITLEMENT_GRANTED' "
                                + "AND `active_scope_hash` IS NULL "
                                + "AND `active_scope_released_at` IS NOT NULL "
                                + "AND `reward_verification_status`='SIGNED_VERIFIED' "
                                + "AND `entitlement_status` IN ('GRANTED','SECURITY_REVOKED') "
                                + "AND `entitled_at` IS NOT NULL))) ORDER BY `tenant_id`,`id` LIMIT 100");
            } else {
                String partialReleaseEvidence = hasScopeReleasedAt
                        ? " OR `active_scope_released_at` IS NOT NULL" : "";
                partialReleaseEvidence += hasScopeReleaseReason
                        ? " OR `active_scope_release_reason` IS NOT NULL" : "";
                assertNoTask5PreflightRows("active scope release evidence",
                        "SELECT `tenant_id`,`id`,`reward_verification_status`,`entitlement_status` "
                                + "FROM `skit_ad_session` WHERE `active_scope_hash` IS NULL "
                                + "OR `reward_verification_status`<>'PENDING' "
                                + "OR `entitlement_status`<>'NONE' OR `entitled_at` IS NOT NULL"
                                + partialReleaseEvidence + " "
                                + "ORDER BY `tenant_id`,`id` LIMIT 100");
            }
            boolean hasAccessMode = columnExists("skit_ad_session", "access_mode");
            boolean hasNativePlayerGrantId = columnExists("skit_ad_session", "native_player_grant_id");
            if (hasAccessMode && hasNativePlayerGrantId) {
                assertNoTask5PreflightRows("session access proof pairing",
                        "SELECT `tenant_id`,`id`,`access_mode`,`native_player_grant_id` FROM `skit_ad_session` "
                                + "WHERE NOT ((`access_mode`='MEMBER_OAUTH' AND `native_player_grant_id` IS NULL) "
                                + "OR (`access_mode`='NATIVE_PLAYER_GRANT' "
                                + "AND `native_player_grant_id` IS NOT NULL)) "
                                + "ORDER BY `tenant_id`,`id` LIMIT 100");
                if (tableExists("skit_native_player_grant")) {
                    assertNoTask5PreflightRows("same-tenant native player grant binding",
                            "SELECT `s`.`tenant_id`,`s`.`id`,`s`.`native_player_grant_id` "
                                    + "FROM `skit_ad_session` `s` LEFT JOIN `skit_native_player_grant` `g` "
                                    + "ON `g`.`tenant_id`=`s`.`tenant_id` "
                                    + "AND `g`.`id`=`s`.`native_player_grant_id` "
                                    + "AND `g`.`member_id`=`s`.`member_id` AND `g`.`drama_id`=`s`.`drama_id` "
                                    + "WHERE `s`.`access_mode`='NATIVE_PLAYER_GRANT' AND `g`.`id` IS NULL "
                                    + "ORDER BY `s`.`tenant_id`,`s`.`id` LIMIT 100");
                }
            } else if (hasAccessMode) {
                assertNoTask5PreflightRows("session access proof pairing",
                        "SELECT `tenant_id`,`id`,`access_mode` FROM `skit_ad_session` "
                                + "WHERE `access_mode`<>'MEMBER_OAUTH' "
                                + "ORDER BY `tenant_id`,`id` LIMIT 100");
            } else if (hasNativePlayerGrantId) {
                assertNoTask5PreflightRows("session access proof pairing",
                        "SELECT `tenant_id`,`id`,`native_player_grant_id` FROM `skit_ad_session` "
                                + "WHERE `native_player_grant_id` IS NOT NULL "
                                + "ORDER BY `tenant_id`,`id` LIMIT 100");
            }
            if (columnExists("skit_ad_session", "session_token_key_version")) {
                assertNoTask5PreflightRows("session token key version",
                        "SELECT `tenant_id`,`id`,`session_token_key_version` FROM `skit_ad_session` "
                                + "WHERE `session_token_key_version` <= 0 ORDER BY `tenant_id`,`id` LIMIT 100");
            }
        }
        if (tableExists("skit_native_player_grant")) {
            assertNoTask5PreflightRows("native player grant lifecycle",
                    "SELECT `tenant_id`,`id`,`status`,`revoked_at` FROM `skit_native_player_grant` WHERE NOT "
                            + "((`status` IN ('ACTIVE','EXPIRED') AND `revoked_at` IS NULL) OR "
                            + "(`status`='REVOKED' AND `revoked_at` IS NOT NULL)) "
                            + "ORDER BY `tenant_id`,`id` LIMIT 100");
            if (columnExists("skit_native_player_grant", "version")) {
                assertNoTask5PreflightRows("native player grant CAS version",
                        "SELECT `tenant_id`,`id`,`version` FROM `skit_native_player_grant` "
                                + "WHERE `version` < 0 ORDER BY `tenant_id`,`id` LIMIT 100");
            }
        }
        if (tableExists("skit_entitlement_grant")) {
            assertNoTask5PreflightRows("non-null entitlement grant binding",
                    "SELECT `tenant_id`,`id`,`ad_session_id`,`entitlement_id` FROM `skit_entitlement_grant` "
                            + "WHERE `entitlement_id` IS NULL ORDER BY `tenant_id`,`id` LIMIT 100");
            if (tableExists("skit_ad_session")) {
                assertNoTask5PreflightRows("same-tenant session grant binding",
                        "SELECT `g`.`tenant_id`,`g`.`id`,`g`.`ad_session_id` FROM `skit_entitlement_grant` `g` "
                                + "LEFT JOIN `skit_ad_session` `s` ON `s`.`tenant_id`=`g`.`tenant_id` "
                                + "AND `s`.`id`=`g`.`ad_session_id` AND `s`.`member_id`=`g`.`member_id` "
                                + "AND `s`.`drama_id`=`g`.`drama_id` "
                                + "AND `s`.`provider_transaction_id`=`g`.`provider_transaction_id` "
                                + "WHERE `s`.`id` IS NULL OR `g`.`episode_no`<`s`.`episode_from` "
                                + "OR `g`.`episode_no`>`s`.`episode_to` "
                                + "ORDER BY `g`.`tenant_id`,`g`.`id` LIMIT 100");
            }
            if (tableExists("skit_content_entitlement")) {
                assertNoTask5PreflightRows("same-tenant entitlement grant binding",
                        "SELECT `g`.`tenant_id`,`g`.`id`,`g`.`entitlement_id` FROM `skit_entitlement_grant` `g` "
                                + "LEFT JOIN `skit_content_entitlement` `e` ON `e`.`tenant_id`=`g`.`tenant_id` "
                                + "AND `e`.`id`=`g`.`entitlement_id` AND `e`.`member_id`=`g`.`member_id` "
                                + "AND `e`.`drama_id`=`g`.`drama_id` AND `e`.`episode_no`=`g`.`episode_no` "
                                + "WHERE `e`.`id` IS NULL ORDER BY `g`.`tenant_id`,`g`.`id` LIMIT 100");
            }
        }
    }

    private void assertNoTask5PreflightRows(String diagnostic, String sql) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        if (!rows.isEmpty()) {
            throw new IllegalStateException("Task 5 schema preflight rejected " + diagnostic
                    + " before any Task 5 DDL. Evidence=" + rows);
        }
    }

    private void validateTask7SchemaHardeningPreflight() {
        validateTask7SchemaHardening(false);
        if (tableExists("skit_ad_callback_inbox") && tableExists("skit_ad_session")) {
            assertNoTask7PreflightRows("callback inbox session/account binding",
                    "SELECT `i`.`tenant_id`,`i`.`id` AS `inbox_id`,`i`.`ad_account_id` AS `inbox_account_id`,"
                            + "`i`.`ad_session_id`,`s`.`ad_account_id` AS `session_account_id` "
                            + "FROM `skit_ad_callback_inbox` `i` JOIN `skit_ad_session` `s` "
                            + "ON `s`.`tenant_id`=`i`.`tenant_id` AND `s`.`id`=`i`.`ad_session_id` "
                            + "WHERE `i`.`ad_account_id`<>`s`.`ad_account_id` "
                            + "ORDER BY `i`.`tenant_id`,`i`.`id` LIMIT 100");
        }
        if (tableExists("skit_ad_callback_attempt") && tableExists("skit_ad_callback_inbox")) {
            assertNoTask7PreflightRows("callback attempt inbox/account/session binding",
                    "SELECT `a`.`tenant_id`,`a`.`id` AS `attempt_id`,`a`.`callback_inbox_id`,"
                            + "`a`.`ad_account_id`,`a`.`ad_session_id` "
                            + "FROM `skit_ad_callback_attempt` `a` JOIN `skit_ad_callback_inbox` `i` "
                            + "ON `i`.`tenant_id`=`a`.`tenant_id` AND `i`.`id`=`a`.`callback_inbox_id` "
                            + "WHERE `a`.`ad_account_id`<>`i`.`ad_account_id` "
                            + "OR NOT (`a`.`ad_session_id` <=> `i`.`ad_session_id`) "
                            + "ORDER BY `a`.`tenant_id`,`a`.`id` LIMIT 100");
        }
        if (tableExists("skit_ad_revenue_event") && tableExists("skit_ad_session")
                && tableExists("skit_ad_callback_inbox") && tableExists("skit_ad_policy_snapshot")) {
            assertNoTask7PreflightRows("verified revenue session/inbox/snapshot binding",
                    "SELECT `r`.`tenant_id`,`r`.`id` AS `event_id`,`r`.`ad_account_id`,"
                            + "`r`.`ad_session_id`,`r`.`callback_inbox_id`,`r`.`policy_snapshot_id` "
                            + "FROM `skit_ad_revenue_event` `r` "
                            + "LEFT JOIN `skit_ad_session` `s` ON `s`.`tenant_id`=`r`.`tenant_id` "
                            + "AND `s`.`id`=`r`.`ad_session_id` "
                            + "LEFT JOIN `skit_ad_callback_inbox` `i` ON `i`.`tenant_id`=`r`.`tenant_id` "
                            + "AND `i`.`id`=`r`.`callback_inbox_id` "
                            + "LEFT JOIN `skit_ad_policy_snapshot` `p` ON `p`.`tenant_id`=`r`.`tenant_id` "
                            + "AND `p`.`id`=`r`.`policy_snapshot_id` WHERE `r`.`legacy_unverified`=b'0' AND ("
                            + "`r`.`ad_session_id` IS NULL OR `r`.`callback_inbox_id` IS NULL "
                            + "OR `r`.`policy_snapshot_id` IS NULL OR `s`.`id` IS NULL OR `i`.`id` IS NULL "
                            + "OR `p`.`id` IS NULL OR `s`.`ad_account_id`<>`r`.`ad_account_id` "
                            + "OR `s`.`member_id`<>`r`.`source_member_id` "
                            + "OR `s`.`policy_snapshot_id`<>`r`.`policy_snapshot_id` "
                            + "OR `i`.`ad_account_id`<>`r`.`ad_account_id` "
                            + "OR NOT (`i`.`ad_session_id` <=> `r`.`ad_session_id`)) "
                            + "ORDER BY `r`.`tenant_id`,`r`.`id` LIMIT 100");
        }
        if (tableExists("skit_commission_ledger") && tableExists("skit_ad_revenue_event")) {
            assertNoTask7PreflightRows("verified ledger event/snapshot binding",
                    "SELECT `l`.`tenant_id`,`l`.`id` AS `ledger_id`,`l`.`event_id`,`l`.`policy_snapshot_id` "
                            + "FROM `skit_commission_ledger` `l` LEFT JOIN `skit_ad_revenue_event` `r` "
                            + "ON `r`.`tenant_id`=`l`.`tenant_id` AND `r`.`id`=`l`.`event_id` "
                            + "WHERE `l`.`legacy_unverified`=b'0' AND (`l`.`policy_snapshot_id` IS NULL "
                            + "OR `r`.`id` IS NULL OR NOT (`l`.`policy_snapshot_id` <=> `r`.`policy_snapshot_id`)) "
                            + "ORDER BY `l`.`tenant_id`,`l`.`id` LIMIT 100");
        }
        if (tableExists("skit_entitlement_grant") && tableExists("skit_ad_session")) {
            assertNoTask7PreflightRows("entitlement grant session range binding",
                    "SELECT `g`.`tenant_id`,`g`.`id` AS `grant_id`,`g`.`ad_session_id`,"
                            + "`g`.`member_id`,`g`.`drama_id`,`g`.`episode_no` "
                            + "FROM `skit_entitlement_grant` `g` LEFT JOIN `skit_ad_session` `s` "
                            + "ON `s`.`tenant_id`=`g`.`tenant_id` AND `s`.`id`=`g`.`ad_session_id` "
                            + "AND `s`.`member_id`=`g`.`member_id` AND `s`.`drama_id`=`g`.`drama_id` "
                            + "AND `s`.`provider_transaction_id`=`g`.`provider_transaction_id` "
                            + "WHERE `s`.`id` IS NULL OR `g`.`episode_no`<`s`.`episode_from` "
                            + "OR `g`.`episode_no`>`s`.`episode_to` "
                            + "ORDER BY `g`.`tenant_id`,`g`.`id` LIMIT 100");
        }
        if (tableExists("skit_ad_callback_inbox")) {
            assertNoTask7PreflightRows("callback processing error state",
                    "SELECT `tenant_id`,`id`,`processing_status`,`processing_attempt_count` "
                            + "FROM `skit_ad_callback_inbox` WHERE NOT ((`processing_status` IN "
                            + "('PENDING','PROCESSING','SUCCEEDED') AND `error_code` IS NULL) OR "
                            + "(`processing_status` IN ('RETRY_WAIT','REJECTED','DEAD_LETTER') "
                            + "AND `error_code` IS NOT NULL)) ORDER BY `tenant_id`,`id` LIMIT 100");
            assertNoTask7PreflightRows("callback dead-letter alert state",
                    "SELECT `tenant_id`,`id`,`processing_status`,`processing_attempt_count` "
                            + "FROM `skit_ad_callback_inbox` WHERE `dead_letter_alerted_at` IS NOT NULL "
                            + "AND (`processing_status`<>'DEAD_LETTER' OR `processed_at` IS NULL "
                            + "OR `dead_letter_alerted_at`<`processed_at`) "
                            + "ORDER BY `tenant_id`,`id` LIMIT 100");
        }
        if (tableExists("skit_ad_network_capability")) {
            assertNoTask7PreflightRows("network reward authority",
                    "SELECT `tenant_id`,`id`,`ad_account_id`,`network_firm_id`,`reward_authority` "
                            + "FROM `skit_ad_network_capability` WHERE `reward_authority` NOT IN "
                            + "('SIGNED_REWARD','UNSIGNED_PROVIDER_OBSERVATION','CLIENT_ONLY','NONE') "
                            + "ORDER BY `tenant_id`,`id` LIMIT 100");
            assertNoTask7PreflightRows("signed reward network readiness",
                    "SELECT `tenant_id`,`id`,`ad_account_id`,`network_firm_id`,`reward_authority`,"
                            + "`supports_user_id`,`supports_custom_data`,`supports_stable_transaction`,"
                            + "`verified_at` FROM `skit_ad_network_capability` "
                            + "WHERE `reward_authority`='SIGNED_REWARD' AND ("
                            + "`supports_user_id`<>b'1' OR `supports_custom_data`<>b'1' "
                            + "OR `supports_stable_transaction`<>b'1' OR `verified_at` IS NULL) "
                            + "ORDER BY `tenant_id`,`id` LIMIT 100");
        }
        if (tableExists("skit_ad_session")) {
            boolean hasInboxId = columnExists("skit_ad_session", "reward_callback_inbox_id");
            boolean hasReceivedAt = columnExists("skit_ad_session", "reward_callback_received_at");
            if (hasInboxId && hasReceivedAt && tableExists("skit_ad_callback_inbox")) {
                assertNoTask7PreflightRows("reward callback receipt marker",
                        "SELECT `s`.`tenant_id`,`s`.`id` AS `session_id`,`s`.`reward_callback_inbox_id`,"
                                + "`s`.`reward_callback_received_at` FROM `skit_ad_session` `s` "
                                + "LEFT JOIN `skit_ad_callback_inbox` `i` ON `i`.`tenant_id`=`s`.`tenant_id` "
                                + "AND `i`.`id`=`s`.`reward_callback_inbox_id` "
                                + "AND `i`.`ad_account_id`=`s`.`ad_account_id` "
                                + "AND `i`.`ad_session_id`=`s`.`id` WHERE NOT (("
                                + "`s`.`reward_callback_inbox_id` IS NULL "
                                + "AND `s`.`reward_callback_received_at` IS NULL) OR ("
                                + "`s`.`reward_callback_inbox_id` IS NOT NULL "
                                + "AND `s`.`reward_callback_received_at` IS NOT NULL "
                                + "AND `s`.`reward_callback_received_at`<=`s`.`reward_accept_until` "
                                + "AND `i`.`id` IS NOT NULL)) ORDER BY `s`.`tenant_id`,`s`.`id` LIMIT 100");
            } else if (hasInboxId) {
                assertNoTask7PreflightRows("partial reward callback receipt marker",
                        "SELECT `tenant_id`,`id` AS `session_id`,`reward_callback_inbox_id` "
                                + "FROM `skit_ad_session` WHERE `reward_callback_inbox_id` IS NOT NULL "
                                + "ORDER BY `tenant_id`,`id` LIMIT 100");
            } else if (hasReceivedAt) {
                assertNoTask7PreflightRows("partial reward callback receipt marker",
                        "SELECT `tenant_id`,`id` AS `session_id`,`reward_callback_received_at` "
                                + "FROM `skit_ad_session` WHERE `reward_callback_received_at` IS NOT NULL "
                                + "ORDER BY `tenant_id`,`id` LIMIT 100");
            }
        }
        if (tableExists("skit_ad_callback_inbox")
                && columnExists("skit_ad_callback_inbox", "ingress_response_code")) {
            assertNoTask7PreflightRows("callback ingress response code",
                    "SELECT `tenant_id`,`id` AS `inbox_id`,`ingress_response_code` "
                            + "FROM `skit_ad_callback_inbox` WHERE `ingress_response_code` IS NOT NULL "
                            + "AND `ingress_response_code` NOT IN (200,601,602) "
                            + "ORDER BY `tenant_id`,`id` LIMIT 100");
        }
    }

    private void assertNoTask7PreflightRows(String diagnostic, String sql) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        if (!rows.isEmpty()) {
            throw new IllegalStateException("Task 7 schema preflight rejected " + diagnostic
                    + " before any Task 7 DDL. Evidence=" + rows);
        }
    }

    private void validateTask2LegacySingletons() {
        if (tableExists("skit_admin_record")) {
            assertNoTask2PreflightRows("legacy admin record singleton duplicate",
                    "SELECT `tenant_id`,`page_key`,`row_key`,GROUP_CONCAT(`id` ORDER BY `id`) AS `owner_ids` "
                            + "FROM `skit_admin_record` GROUP BY `tenant_id`,`page_key`,`row_key` "
                            + "HAVING COUNT(*) > 1 ORDER BY `tenant_id`,`page_key`,`row_key`");
        }
        if (tableExists("skit_system_config")) {
            assertNoTask2PreflightRows("legacy system config singleton duplicate",
                    "SELECT `tenant_id`,GROUP_CONCAT(`id` ORDER BY `id`) AS `owner_ids` "
                            + "FROM `skit_system_config` GROUP BY `tenant_id` HAVING COUNT(*) > 1 "
                            + "ORDER BY `tenant_id`");
        }
    }

    private void validateTask2LedgerBeneficiaries() {
        if (!tableExists("skit_commission_ledger") || !tableExists("skit_member")) {
            return;
        }
        assertNoTask2PreflightRows("ledger member beneficiary mismatch",
                "SELECT 'skit_commission_ledger' AS `owner_table`,`l`.`tenant_id`,`l`.`id` AS `owner_id`,"
                        + "`l`.`beneficiary_type`,`l`.`beneficiary_member_id`,`m`.`tenant_id` AS `member_tenant_id` "
                        + "FROM `skit_commission_ledger` `l` LEFT JOIN `skit_member` `m` "
                        + "ON `m`.`id`=`l`.`beneficiary_member_id` WHERE "
                        + "(`l`.`beneficiary_type`=1 AND (`l`.`beneficiary_member_id`<=0 OR `m`.`id` IS NULL "
                        + "OR `m`.`tenant_id`<>`l`.`tenant_id`)) OR "
                        + "(`l`.`beneficiary_type`=2 AND `l`.`beneficiary_member_id`<>0) OR "
                        + "`l`.`beneficiary_type` NOT IN (1,2) ORDER BY `l`.`tenant_id`,`l`.`id`");
    }

    private void validateTask2RevenueIdempotency() {
        if (!tableExists("skit_ad_revenue_event")) {
            return;
        }
        String sourceExpression = columnExists("skit_ad_revenue_event", "source_type")
                ? "`source_type`" : "'LEGACY_CLIENT'";
        assertNoTask2PreflightRows("revenue source idempotency collision",
                "SELECT `tenant_id`,`ad_account_id`," + sourceExpression + " AS `source_type`,"
                        + "`external_event_id`,GROUP_CONCAT(`id` ORDER BY `id`) AS `owner_ids` "
                        + "FROM `skit_ad_revenue_event` GROUP BY `tenant_id`,`ad_account_id`,"
                        + sourceExpression + ",`external_event_id` HAVING COUNT(*) > 1 "
                        + "ORDER BY `tenant_id`,`ad_account_id`,`source_type`,`external_event_id`");
        if (indexExists("skit_ad_revenue_event", "uk_skit_revenue_event_external")) {
            String actual = jdbcTemplate.queryForObject(INDEX_DEFINITION_QUERY, String.class,
                    "skit_ad_revenue_event", "uk_skit_revenue_event_external");
            if (!"0:tenant_id,provider,external_event_id".equals(actual)) {
                throw new IllegalStateException("Incompatible legacy revenue idempotency index "
                        + "skit_ad_revenue_event.uk_skit_revenue_event_external: expected="
                        + "0:tenant_id,provider,external_event_id, actual=" + actual);
            }
        }
    }

    private void validateExistingInviteRegistryOwners() {
        if (!tableExists("skit_invite_code_registry")) {
            return;
        }
        if (tableExists("skit_agent")) {
            assertNoTask2PreflightRows("invite registry agent owner mismatch",
                    "SELECT 'skit_agent' AS `owner_table`,`a`.`tenant_id`,`a`.`id` AS `owner_id`,"
                            + "UPPER(TRIM(`a`.`root_invite_code`)) AS `expected_code`,`r`.`id` AS `registry_id`,"
                            + "`r`.`owner_type`,`r`.`tenant_id` AS `registry_tenant_id`,`r`.`agent_id`,`r`.`member_id` "
                            + "FROM `skit_agent` `a` JOIN `skit_invite_code_registry` `r` "
                            + "ON `r`.`normalized_code`=UPPER(TRIM(`a`.`root_invite_code`)) "
                            + "WHERE `a`.`deleted`=b'0' AND (`r`.`owner_type`<>'AGENT' OR `r`.`tenant_id`<>`a`.`tenant_id` "
                            + "OR `r`.`agent_id`<>`a`.`id` OR `r`.`member_id` IS NOT NULL "
                            + "OR `r`.`status`<>'ACTIVE') ORDER BY `a`.`tenant_id`,`a`.`id`");
            assertNoTask2PreflightRows("invite registry active agent code mismatch",
                    "SELECT 'skit_agent' AS `owner_table`,`a`.`tenant_id`,`a`.`id` AS `owner_id`,"
                            + "UPPER(TRIM(`a`.`root_invite_code`)) AS `expected_code`,`r`.`normalized_code` AS `actual_code`,"
                            + "`r`.`id` AS `registry_id` FROM `skit_agent` `a` JOIN `skit_invite_code_registry` `r` "
                            + "ON `r`.`tenant_id`=`a`.`tenant_id` AND `r`.`agent_id`=`a`.`id` AND `r`.`status`='ACTIVE' "
                            + "WHERE `a`.`deleted`=b'0' AND `r`.`normalized_code`<>UPPER(TRIM(`a`.`root_invite_code`)) "
                            + "ORDER BY `a`.`tenant_id`,`a`.`id`");
        }
        if (tableExists("skit_member")) {
            assertNoTask2PreflightRows("invite registry member owner mismatch",
                    "SELECT 'skit_member' AS `owner_table`,`m`.`tenant_id`,`m`.`id` AS `owner_id`,"
                            + "UPPER(TRIM(`m`.`invite_code`)) AS `expected_code`,`r`.`id` AS `registry_id`,"
                            + "`r`.`owner_type`,`r`.`tenant_id` AS `registry_tenant_id`,`r`.`agent_id`,`r`.`member_id` "
                            + "FROM `skit_member` `m` JOIN `skit_invite_code_registry` `r` "
                            + "ON `r`.`normalized_code`=UPPER(TRIM(`m`.`invite_code`)) "
                            + "WHERE `m`.`deleted`=b'0' AND (`r`.`owner_type`<>'MEMBER' OR `r`.`tenant_id`<>`m`.`tenant_id` "
                            + "OR `r`.`member_id`<>`m`.`id` OR `r`.`agent_id` IS NOT NULL "
                            + "OR `r`.`status`<>'ACTIVE') ORDER BY `m`.`tenant_id`,`m`.`id`");
            assertNoTask2PreflightRows("invite registry active member code mismatch",
                    "SELECT 'skit_member' AS `owner_table`,`m`.`tenant_id`,`m`.`id` AS `owner_id`,"
                            + "UPPER(TRIM(`m`.`invite_code`)) AS `expected_code`,`r`.`normalized_code` AS `actual_code`,"
                            + "`r`.`id` AS `registry_id` FROM `skit_member` `m` JOIN `skit_invite_code_registry` `r` "
                            + "ON `r`.`tenant_id`=`m`.`tenant_id` AND `r`.`member_id`=`m`.`id` AND `r`.`status`='ACTIVE' "
                            + "WHERE `m`.`deleted`=b'0' AND `r`.`normalized_code`<>UPPER(TRIM(`m`.`invite_code`)) "
                            + "ORDER BY `m`.`tenant_id`,`m`.`id`");
        }
    }

    private void validateTask2InviteRegistryCoverage() {
        assertNoTask2PreflightRows("agent without its exact active invite registry owner",
                "SELECT 'AGENT' AS `owner_type`,`a`.`tenant_id`,`a`.`id` AS `owner_id`,"
                        + "UPPER(TRIM(`a`.`root_invite_code`)) AS `normalized_code` FROM `skit_agent` `a` "
                        + "LEFT JOIN `skit_invite_code_registry` `r` ON `r`.`tenant_id`=`a`.`tenant_id` "
                        + "AND `r`.`owner_type`='AGENT' AND `r`.`agent_id`=`a`.`id` AND `r`.`member_id` IS NULL "
                        + "AND `r`.`status`='ACTIVE' AND `r`.`normalized_code`=UPPER(TRIM(`a`.`root_invite_code`)) "
                        + "WHERE `a`.`deleted`=b'0' AND `r`.`id` IS NULL ORDER BY `a`.`tenant_id`,`a`.`id`");
        assertNoTask2PreflightRows("member without its exact active invite registry owner",
                "SELECT 'MEMBER' AS `owner_type`,`m`.`tenant_id`,`m`.`id` AS `owner_id`,"
                        + "UPPER(TRIM(`m`.`invite_code`)) AS `normalized_code` FROM `skit_member` `m` "
                        + "LEFT JOIN `skit_invite_code_registry` `r` ON `r`.`tenant_id`=`m`.`tenant_id` "
                        + "AND `r`.`owner_type`='MEMBER' AND `r`.`member_id`=`m`.`id` AND `r`.`agent_id` IS NULL "
                        + "AND `r`.`status`='ACTIVE' AND `r`.`normalized_code`=UPPER(TRIM(`m`.`invite_code`)) "
                        + "WHERE `m`.`deleted`=b'0' AND `r`.`id` IS NULL ORDER BY `m`.`tenant_id`,`m`.`id`");
    }

    private void validateTask2Reference(String childTable, String childColumn, String parentTable) {
        if (!tableExists(childTable) || !tableExists(parentTable)) {
            return;
        }
        String sql = "SELECT '" + childTable + "' AS `owner_table`,`c`.`tenant_id`,`c`.`id` AS `owner_id`,"
                + "`c`.`" + childColumn + "` AS `referenced_id`,`p`.`tenant_id` AS `referenced_tenant_id` "
                + "FROM `" + childTable + "` `c` LEFT JOIN `" + parentTable + "` `p` "
                + "ON `p`.`id`=`c`.`" + childColumn + "` WHERE `c`.`" + childColumn + "` IS NOT NULL "
                + "AND (`p`.`id` IS NULL OR `p`.`tenant_id`<>`c`.`tenant_id`) "
                + "ORDER BY `c`.`tenant_id`,`c`.`id`";
        assertNoTask2PreflightRows("orphan or cross-tenant reference " + childTable + "." + childColumn, sql);
    }

    private void validateTask2TenantOwners() {
        if (!tableExists("system_tenant")) {
            return;
        }
        List<String> tenantTables = Arrays.asList("skit_agent", "skit_app_release_profile", "skit_ad_account",
                "skit_member", "skit_member_closure", "skit_commission_plan", "skit_commission_rule",
                "skit_ad_revenue_event", "skit_commission_ledger");
        for (String table : tenantTables) {
            if (!tableExists(table)) {
                continue;
            }
            assertNoTask2PreflightRows("row with missing system tenant in " + table,
                    "SELECT '" + table + "' AS `owner_table`,`c`.`tenant_id`,`c`.`id` AS `owner_id` "
                            + "FROM `" + table + "` `c` LEFT JOIN `system_tenant` `t` ON `t`.`id`=`c`.`tenant_id` "
                            + "WHERE `t`.`id` IS NULL ORDER BY `c`.`tenant_id`,`c`.`id`");
        }
    }

    private void validateTask2LegacyMoney() {
        if (tableExists("skit_ad_revenue_event")) {
            assertNoTask2PreflightRows("legacy revenue amount outside canonical integer range",
                    "SELECT 'skit_ad_revenue_event' AS `owner_table`,`tenant_id`,`id` AS `owner_id`,"
                            + "`gross_amount` FROM `skit_ad_revenue_event` WHERE `gross_amount` < 0 "
                            + "OR ABS(`gross_amount`) > 92233720368.54775807 ORDER BY `tenant_id`,`id`");
        }
        if (tableExists("skit_commission_ledger")) {
            assertNoTask2PreflightRows("legacy ledger amount outside canonical integer range",
                    "SELECT 'skit_commission_ledger' AS `owner_table`,`tenant_id`,`id` AS `owner_id`,"
                            + "`gross_amount`,`amount` FROM `skit_commission_ledger` "
                            + "WHERE ABS(`gross_amount`) > 92233720368.54775807 "
                            + "OR ABS(`amount`) > 92233720368.54775807 ORDER BY `tenant_id`,`id`");
        }
    }

    private void assertNoTask2PreflightRows(String diagnostic, String sql) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        if (!rows.isEmpty()) {
            throw new IllegalStateException("Task 2 schema preflight rejected " + diagnostic
                    + " before any Task 2 DDL. Evidence=" + rows);
        }
    }

    void validateTask2TableSignatures(boolean requireAll) {
        validateTask2OwnedTableFingerprints(requireAll);
        validateTask2ParentExtensionColumns(requireAll);
        String[][] candidateKeys = {
                {"skit_agent", "uk_skit_agent_tenant_id", "tenant_id,id"},
                {"skit_ad_account", "uk_skit_ad_account_tenant_id", "tenant_id,id"},
                {"skit_member", "uk_skit_member_tenant_id", "tenant_id,id"},
                {"skit_commission_plan", "uk_skit_commission_plan_tenant_id", "tenant_id,id"},
                {"skit_ad_revenue_event", "uk_skit_revenue_event_tenant_id", "tenant_id,id"},
                {"skit_commission_ledger", "uk_skit_ledger_tenant_id", "tenant_id,id"},
                {"skit_ad_callback_key", "uk_skit_callback_key_tenant_id", "tenant_id,id"},
                {"skit_ad_reward_secret_version", "uk_skit_reward_secret_tenant_id", "tenant_id,id"},
                {"skit_ad_policy_snapshot", "uk_skit_policy_snapshot_tenant_id", "tenant_id,id"},
                {"skit_ad_session", "uk_skit_ad_session_tenant_id", "tenant_id,id"},
                {"skit_ad_client_event", "uk_skit_client_event_tenant_id", "tenant_id,id"},
                {"skit_ad_callback_inbox", "uk_skit_callback_inbox_tenant_id", "tenant_id,id"},
                {"skit_ad_callback_attempt", "uk_skit_callback_attempt_tenant_id", "tenant_id,id"},
                {"skit_ad_network_capability", "uk_skit_network_cap_tenant_id", "tenant_id,id"},
                {"skit_content_entitlement", "uk_skit_entitlement_tenant_id", "tenant_id,id"},
                {"skit_entitlement_grant", "uk_skit_grant_tenant_id", "tenant_id,id"},
                {"skit_native_player_grant", "uk_skit_player_grant_tenant_id", "tenant_id,id"},
                {"skit_ad_report_pull", "uk_skit_report_pull_tenant_id", "tenant_id,id"},
                {"skit_ad_reconciliation_bucket", "uk_skit_recon_bucket_tenant_id", "tenant_id,id"},
                {"skit_ad_reconciliation_revision", "uk_skit_recon_revision_tenant_id", "tenant_id,id"},
                {"skit_tenant_ad_capability", "uk_skit_tenant_capability_tenant_id", "tenant_id,id"},
                {"skit_invite_code_registry", "uk_skit_invite_registry_tenant_id", "tenant_id,id"}
        };
        for (String[] key : candidateKeys) {
            validateRequiredIndex(key[0], key[1], key[2], true, requireAll);
        }
        validateRequiredIndex("skit_ad_callback_key", "uk_skit_callback_key_hash",
                "callback_key_hash", true, requireAll);
        validateRequiredIndex("skit_ad_callback_key", "uk_skit_callback_key_version",
                "tenant_id,ad_account_id,key_version", true, requireAll);
        validateRequiredIndex("skit_ad_reward_secret_version", "uk_skit_reward_secret_version",
                "tenant_id,ad_account_id,secret_version", true, requireAll);
        validateRequiredIndex("skit_ad_session", "uk_skit_ad_session_public", "session_id", true, requireAll);
        validateRequiredIndex("skit_ad_session", "uk_skit_ad_session_token", "session_token_hash", true, requireAll);
        validateRequiredIndex("skit_invite_code_registry", "uk_skit_invite_registry_code",
                "normalized_code", true, requireAll);
        validateRequiredIndex("skit_admin_record", "uk_skit_admin_record_tenant_page_row",
                "tenant_id,page_key,row_key", true, requireAll);
        validateRequiredIndex("skit_system_config", "uk_skit_system_config_tenant",
                "tenant_id", true, requireAll);
        validateRequiredIndex("skit_commission_ledger", "uk_skit_ledger_entry_revision",
                "tenant_id,event_id,beneficiary_type,beneficiary_member_id,level_no,entry_type,revision_no",
                true, requireAll);
        validateRequiredIndex("skit_ad_revenue_event", "uk_skit_revenue_source_idem",
                "tenant_id,ad_account_id,source_type,external_event_id", true, requireAll);
        validateRequiredIndex("skit_ad_revenue_event", "idx_skit_revenue_event_external",
                "tenant_id,provider,external_event_id", false, requireAll);
        validateRequiredIndex("skit_ad_revenue_event", "uk_skit_revenue_inbox_source",
                "tenant_id,callback_inbox_id,source_type", true, requireAll);
        validateRequiredIndex("skit_ad_revenue_event", "uk_skit_revenue_session_source",
                "tenant_id,ad_session_id,source_type", true, requireAll);

        validateTask2ParentForeignKeys(requireAll);
        validateTask2ParentChecks(requireAll);
        validateTask2ParentTriggers(requireAll);

        validateCriticalTask2Column("skit_ad_callback_key", "callback_key_hash", "binary(32) NOT NULL", requireAll);
        validateCriticalTask2Column("skit_ad_reward_secret_version", "ciphertext",
                "varbinary(4096) NOT NULL", requireAll);
        validateCriticalTask2Column("skit_ad_reward_secret_version", "nonce", "binary(12) NOT NULL", requireAll);
        validateCriticalTask2Column("skit_ad_callback_inbox", "payload_ciphertext",
                "mediumblob DEFAULT NULL", requireAll);
        validateCriticalTask2Column("skit_ad_report_pull", "response_ciphertext",
                "mediumblob DEFAULT NULL", requireAll);
        validateCriticalTask2Column("skit_tenant_ad_capability", "rollout_state",
                "varchar(32) NOT NULL DEFAULT 'OFF'", requireAll);
        validateCriticalTask2Column("skit_commission_ledger", "revision_no",
                "int NOT NULL DEFAULT 0", requireAll);
    }

    void validateTask5SchemaHardening(boolean requireAll) {
        for (Task2ColumnSpec spec : TASK_5_SCHEMA_COLUMN_SPECS) {
            if (!tableExists(spec.table)) {
                if (requireAll) {
                    throw new IllegalStateException("Task 5 schema is missing required table " + spec.table);
                }
                continue;
            }
            if (!columnExists(spec.table, spec.column)) {
                if (requireAll) {
                    throw new IllegalStateException("Task 5 schema is missing required column "
                            + spec.table + "." + spec.column);
                }
                continue;
            }
            if (!requireAll && spec.compatibleLegacyDefinition != null
                    && columnDefinitionMatches(spec.table, spec.column, spec.compatibleLegacyDefinition)) {
                continue;
            }
            validateColumnDefinition(spec.table, spec.column, spec.definition);
        }
        validateTask5Index("skit_ad_session", "uk_skit_ad_session_grant_scope",
                "tenant_id,id,member_id,drama_id,episode_from,provider_transaction_id", true, requireAll);
        validateTask5Index("skit_native_player_grant", "uk_skit_player_grant_scope_id",
                "tenant_id,id,member_id,drama_id", true, requireAll);
        validateTask5Index("skit_native_player_grant", "idx_skit_player_grant_lookup",
                "tenant_id,member_id,drama_id,status,expires_at,id", false, requireAll);
        validateTask5Index("skit_content_entitlement", "uk_skit_entitlement_grant_binding",
                "tenant_id,id,member_id,drama_id,episode_no", true, requireAll);
        for (Task2ForeignKeySpec spec : TASK_5_SCHEMA_FOREIGN_KEY_SPECS) {
            validateTask5ForeignKey(spec, requireAll);
        }
        for (Task2CheckSpec spec : TASK_5_SCHEMA_CHECK_SPECS) {
            validateTask5Check(spec, requireAll);
        }
        if (requireAll) {
            validateTask5HardenedTableFingerprints();
        }
    }

    void validateTask7SchemaHardening(boolean requireAll) {
        for (Task2ColumnSpec spec : TASK_7_SCHEMA_COLUMN_SPECS) {
            if (!tableExists(spec.table)) {
                if (requireAll) {
                    throw new IllegalStateException("Task 7 schema is missing required table " + spec.table);
                }
                continue;
            }
            if (!columnExists(spec.table, spec.column)) {
                if (requireAll) {
                    throw new IllegalStateException("Task 7 schema is missing required column "
                            + spec.table + "." + spec.column);
                }
                continue;
            }
            validateColumnDefinition(spec.table, spec.column, spec.definition);
        }
        validateTask5Index("skit_ad_session", "uk_skit_ad_session_account_binding",
                "tenant_id,id,ad_account_id", true, requireAll);
        validateTask5Index("skit_ad_session", "uk_skit_ad_session_revenue_binding",
                "tenant_id,id,ad_account_id,member_id,policy_snapshot_id", true, requireAll);
        validateTask5Index("skit_ad_session", "uk_skit_ad_session_grant_envelope",
                "tenant_id,id,member_id,drama_id,provider_transaction_id", true, requireAll);
        validateTask5Index("skit_ad_callback_inbox", "uk_skit_callback_inbox_attempt_binding",
                "tenant_id,id,ad_account_id,ad_session_ref_id", true, requireAll);
        validateTask5Index("skit_ad_revenue_event", "uk_skit_revenue_event_snapshot_binding",
                "tenant_id,id,policy_snapshot_id", true, requireAll);
        validateTask5Index("skit_ad_callback_inbox", "idx_skit_callback_inbox_payload_expiry",
                "payload_expires_at,id", false, requireAll);
        validateTask5Index("skit_ad_callback_attempt", "idx_skit_callback_attempt_retention",
                "received_at,id", false, requireAll);
        validateTask5Index("skit_ad_callback_edge_attempt", "idx_skit_callback_edge_retention",
                "received_at,id", false, requireAll);
        validateTask5Index("skit_ad_network_capability", "idx_skit_network_cap_readiness",
                "tenant_id,ad_account_id,network_firm_id,enabled,reward_authority,verified_at",
                false, requireAll);
        validateTask7GrantSessionBinding(requireAll);
        for (Task2ForeignKeySpec spec : TASK_7_SCHEMA_FOREIGN_KEY_SPECS) {
            validateTask5ForeignKey(spec, requireAll);
        }
        for (Task2CheckSpec spec : TASK_7_SCHEMA_CHECK_SPECS) {
            validateTask5Check(spec, requireAll);
        }
        for (Task2TriggerSpec spec : TASK_7_SCHEMA_TRIGGER_SPECS) {
            if (!tableExists(spec.table)) {
                if (requireAll) {
                    throw new IllegalStateException("Task 7 schema is missing required table " + spec.table);
                }
                continue;
            }
            List<String> existing = jdbcTemplate.queryForList(TRIGGER_DEFINITION_QUERY,
                    String.class, spec.trigger);
            if (existing.isEmpty() && !requireAll) {
                continue;
            }
            validateTask2TriggerDefinition(spec, existing);
        }
        if (requireAll) {
            for (Map.Entry<String, String> expected
                    : SkitAdSchemaSignature.expectedTask7HardenedFingerprints().entrySet()) {
                validateExactTableFingerprint("Task 7 hardened", expected.getKey(), expected.getValue(), true);
            }
        }
    }

    void validateTask10ReconciliationSchema(boolean requireAll) {
        for (String table : Arrays.asList("skit_ad_reporting_credential_version",
                "skit_ad_reconciliation_allocation", "skit_ad_reconciliation_event_link")) {
            if (!tableExists(table) && requireAll) {
                throw new IllegalStateException("Task 10 schema is missing required table " + table);
            }
        }
        for (Task2ColumnSpec spec : TASK_10_RECONCILIATION_COLUMN_SPECS) {
            if (!tableExists(spec.table)) {
                if (requireAll) {
                    throw new IllegalStateException("Task 10 schema is missing required table " + spec.table);
                }
                continue;
            }
            if (!columnExists(spec.table, spec.column)) {
                if (requireAll) {
                    throw new IllegalStateException("Task 10 schema is missing required column "
                            + spec.table + "." + spec.column);
                }
                continue;
            }
            validateColumnDefinition(spec.table, spec.column, spec.definition);
        }
        validateCriticalTask2Column("skit_ad_reporting_credential_version", "ciphertext",
                "varbinary(4096) NOT NULL", requireAll);
        validateCriticalTask2Column("skit_ad_reporting_credential_version", "nonce",
                "binary(12) NOT NULL", requireAll);
        validateCriticalTask2Column("skit_ad_reconciliation_allocation", "cumulative_target_units",
                "bigint NOT NULL", requireAll);
        validateCriticalTask2Column("skit_ad_reconciliation_event_link", "actual_units",
                "bigint NOT NULL", requireAll);
        validateTask5Index("skit_ad_account", "idx_skit_ad_account_report_due",
                "provider,status,report_next_allowed_at,report_pull_lease_until,id", false, requireAll);
        validateTask5Index("skit_ad_report_pull", "idx_skit_report_pull_request",
                "tenant_id,ad_account_id,report_date,request_hash", false, requireAll);
        validateTask5Index("skit_ad_report_pull", "idx_skit_report_pull_credential",
                "tenant_id,ad_account_id,credential_version", false, requireAll);
        validateTask5Index("skit_ad_report_pull", "idx_skit_report_pull_final_window",
                "tenant_id,ad_account_id,report_date,final_window,status", false, requireAll);
        validateTask5Index("skit_ad_revenue_event", "idx_skit_revenue_report_pending",
                "tenant_id,ad_account_id,reconciliation_revision_id,occurred_time,id",
                false, requireAll);
        validateTask5Index("skit_ad_report_pull", "uk_skit_report_pull_response",
                "tenant_id,ad_account_id,range_start,range_end,request_hash,response_hash,credential_version,"
                        + "final_window",
                true, requireAll);
        validateTask5Index("skit_ad_reconciliation_bucket", "uk_skit_recon_bucket_identity",
                "tenant_id,ad_account_id,bucket_key,report_date,report_timezone,app_id,placement_id,"
                        + "ad_format,network_account_id,network_firm_id,adsource_id,currency",
                true, requireAll);
        validateTask5Index("skit_ad_reporting_credential_version",
                "uk_skit_reporting_credential_version",
                "tenant_id,ad_account_id,credential_version", true, requireAll);
        validateTask5Index("skit_ad_reporting_credential_version",
                "uk_skit_reporting_credential_active", "tenant_id,active_account_id", true, requireAll);
        validateTask5Index("skit_ad_reconciliation_allocation",
                "uk_skit_recon_allocation_canonical",
                "tenant_id,event_id,reconciliation_revision_id,beneficiary_type,"
                        + "beneficiary_member_id,level_no,policy_snapshot_id", true, requireAll);
        validateTask5Index("skit_ad_reconciliation_event_link",
                "uk_skit_recon_event_link_tenant_id", "tenant_id,id", true, requireAll);
        validateTask5Index("skit_ad_reconciliation_event_link",
                "uk_skit_recon_event_link_canonical",
                "tenant_id,reconciliation_revision_id,event_id", true, requireAll);
        validateTask5Index("skit_ad_reconciliation_event_link",
                "idx_skit_recon_event_link_history",
                "tenant_id,event_id,revision_no,id", false, requireAll);
        validateTask5ForeignKey(new Task2ForeignKeySpec("skit_ad_report_pull",
                "fk_skit_report_pull_credential", "tenant_id,ad_account_id,credential_version",
                "skit_ad_reporting_credential_version", "tenant_id,ad_account_id,credential_version"),
                requireAll);
        validateTask5ForeignKey(new Task2ForeignKeySpec("skit_ad_reconciliation_event_link",
                "fk_skit_recon_event_link_bucket", "tenant_id,reconciliation_bucket_id",
                "skit_ad_reconciliation_bucket", "tenant_id,id"), requireAll);
        validateTask5ForeignKey(new Task2ForeignKeySpec("skit_ad_reconciliation_event_link",
                "fk_skit_recon_event_link_revision", "tenant_id,reconciliation_revision_id",
                "skit_ad_reconciliation_revision", "tenant_id,id"), requireAll);
        validateTask5ForeignKey(new Task2ForeignKeySpec("skit_ad_reconciliation_event_link",
                "fk_skit_recon_event_link_event_snapshot", "tenant_id,event_id,policy_snapshot_id",
                "skit_ad_revenue_event", "tenant_id,id,policy_snapshot_id"), requireAll);
        for (Task2CheckSpec spec : TASK_10_RECONCILIATION_CHECK_SPECS) {
            validateTask5Check(spec, requireAll);
        }
        for (Task2TriggerSpec spec : TASK_10_RECONCILIATION_TRIGGER_SPECS) {
            if (!tableExists(spec.table)) {
                if (requireAll) {
                    throw new IllegalStateException("Task 10 schema is missing trigger table " + spec.table);
                }
                continue;
            }
            List<String> existing = jdbcTemplate.queryForList(TRIGGER_DEFINITION_QUERY,
                    String.class, spec.trigger);
            if (existing.isEmpty() && !requireAll) {
                continue;
            }
            validateTask2TriggerDefinition(spec, existing);
        }
        if (requireAll) {
            for (Map.Entry<String, String> expected
                    : SkitAdSchemaSignature.expectedTask10FinalFingerprints().entrySet()) {
                validateFinalTableFingerprint("Task 10 final", expected.getKey(), expected.getValue(),
                        SkitAdSchemaSignature.task10FinalFingerprint(jdbcTemplate, expected.getKey()));
            }
        }
    }

    void validateTask12ReadinessSchema(boolean requireAll) {
        validateAdditiveColumnSpecs("Task 12", TASK_12_READINESS_COLUMN_SPECS, requireAll);
        for (Task2CheckSpec spec : TASK_12_READINESS_CHECK_SPECS) {
            validateTask5Check(spec, requireAll);
        }
        if (requireAll) {
            for (Map.Entry<String, String> expected
                    : SkitAdSchemaSignature.expectedTask12FinalFingerprints().entrySet()) {
                validateFinalTableFingerprint("Task 12 final", expected.getKey(), expected.getValue(),
                        SkitAdSchemaSignature.rawFingerprint(jdbcTemplate, expected.getKey()));
            }
        }
    }

    void validateTask11ManagementSchema(boolean requireAll) {
        for (String table : Arrays.asList("skit_management_command_audit",
                "skit_ad_callback_replay_command", "skit_entitlement_security_revocation",
                "skit_management_export_task")) {
            if (!tableExists(table) && requireAll) {
                throw new IllegalStateException("Task 11 schema is missing required table " + table);
            }
        }
        validateCriticalTask2Column("skit_management_command_audit", "before_state_hash",
                "binary(32) NOT NULL", requireAll);
        validateCriticalTask2Column("skit_management_command_audit", "after_state_hash",
                "binary(32) NOT NULL", requireAll);
        validateCriticalTask2Column("skit_management_command_audit", "request_fingerprint",
                "binary(32) NOT NULL", requireAll);
        validateCriticalTask2Column("skit_ad_callback_replay_command", "request_fingerprint",
                "binary(32) NOT NULL", requireAll);
        validateCriticalTask2Column("skit_entitlement_security_revocation", "evidence_hash",
                "binary(32) NOT NULL", requireAll);
        validateCriticalTask2Column("skit_management_export_task", "filter_json",
                "longtext NOT NULL", requireAll);
        validateCriticalTask2Column("skit_management_export_task", "filter_hash",
                "binary(32) NOT NULL", requireAll);

        validateTask5Index("skit_management_command_audit",
                "uk_skit_management_audit_tenant_id", "tenant_id,id", true, requireAll);
        validateTask5Index("skit_management_command_audit",
                "uk_skit_management_audit_command", "command_id", true, requireAll);
        validateTask5Index("skit_ad_callback_replay_command",
                "uk_skit_callback_replay_tenant_id", "tenant_id,id", true, requireAll);
        validateTask5Index("skit_ad_callback_replay_command",
                "uk_skit_callback_replay_command", "command_id", true, requireAll);
        validateTask5Index("skit_ad_callback_replay_command",
                "uk_skit_callback_replay_idempotency",
                "tenant_id,callback_inbox_id,request_fingerprint", true, requireAll);
        validateTask5Index("skit_entitlement_security_revocation",
                "uk_skit_security_revocation_tenant_id", "tenant_id,id", true, requireAll);
        validateTask5Index("skit_entitlement_security_revocation",
                "uk_skit_security_revocation_command", "command_id", true, requireAll);
        validateTask5Index("skit_entitlement_security_revocation",
                "uk_skit_security_revocation_entitlement", "tenant_id,entitlement_id",
                true, requireAll);
        validateTask5Index("skit_management_export_task",
                "uk_skit_management_export_tenant_id", "tenant_id,id", true, requireAll);

        validateTask5Index("skit_ad_session", "idx_skit_ad_session_management_account",
                "tenant_id,ad_account_id,create_time,id", false, requireAll);
        validateTask5Index("skit_ad_session", "idx_skit_ad_session_management_reward",
                "tenant_id,reward_verification_status,create_time,id", false, requireAll);
        validateTask5Index("skit_ad_callback_inbox",
                "idx_skit_callback_inbox_management_account",
                "tenant_id,ad_account_id,received_at,id", false, requireAll);
        validateTask5Index("skit_ad_callback_inbox",
                "idx_skit_callback_inbox_management_status",
                "tenant_id,processing_status,received_at,id", false, requireAll);
        validateTask5Index("skit_ad_revenue_event", "idx_skit_revenue_management_time",
                "tenant_id,occurred_time,id", false, requireAll);
        validateTask5Index("skit_ad_revenue_event", "idx_skit_revenue_management_member",
                "tenant_id,source_member_id,occurred_time,id", false, requireAll);
        validateTask5Index("skit_ad_revenue_event",
                "idx_skit_revenue_management_reconciliation",
                "tenant_id,reconciliation_status,source_currency,occurred_time,id",
                false, requireAll);
        validateTask5Index("skit_commission_ledger", "idx_skit_ledger_management_balance",
                "tenant_id,currency,balance_bucket,create_time,id", false, requireAll);
        validateTask5Index("skit_commission_ledger", "idx_skit_ledger_management_event",
                "tenant_id,event_id,id", false, requireAll);
        validateTask5Index("skit_ad_report_pull", "idx_skit_report_pull_management_account",
                "tenant_id,ad_account_id,pulled_at,id", false, requireAll);
        validateTask5Index("skit_ad_report_pull", "idx_skit_report_pull_management_status",
                "tenant_id,status,pulled_at,id", false, requireAll);
        validateTask5Index("skit_ad_reconciliation_bucket",
                "idx_skit_recon_bucket_management_account",
                "tenant_id,ad_account_id,report_date,id", false, requireAll);
        validateTask5Index("skit_ad_reconciliation_revision",
                "idx_skit_recon_revision_management_bucket",
                "tenant_id,reconciliation_bucket_id,revision_no,id", false, requireAll);
        validateTask5Index("skit_member_closure",
                "idx_skit_member_closure_ancestor_distance",
                "tenant_id,ancestor_id,distance,descendant_id", false, requireAll);
        validateTask5Index("skit_ad_session", "idx_skit_ad_session_global_created",
                "create_time,id", false, requireAll);
        validateTask5Index("skit_ad_revenue_event", "idx_skit_ad_revenue_global_occurred",
                "occurred_time,id", false, requireAll);
        validateTask5Index("skit_ad_callback_inbox", "idx_skit_ad_callback_global_received",
                "received_at,id", false, requireAll);
        validateTask5Index("skit_ad_reconciliation_bucket", "idx_skit_ad_recon_bucket_global_date",
                "report_date,id", false, requireAll);

        validateTask5ForeignKey(new Task2ForeignKeySpec("skit_ad_callback_replay_command",
                "fk_skit_callback_replay_inbox", "tenant_id,callback_inbox_id",
                "skit_ad_callback_inbox", "tenant_id,id"), requireAll);
        validateTask5ForeignKey(new Task2ForeignKeySpec("skit_entitlement_security_revocation",
                "fk_skit_security_revocation_entitlement", "tenant_id,entitlement_id",
                "skit_content_entitlement", "tenant_id,id"), requireAll);
        validateTask5ForeignKey(new Task2ForeignKeySpec("skit_entitlement_security_revocation",
                "fk_skit_security_revocation_session", "tenant_id,ad_session_id",
                "skit_ad_session", "tenant_id,id"), requireAll);

        validateTask5Check(new Task2CheckSpec("skit_management_command_audit",
                "ck_skit_management_audit_target", "`tenant_id`=`target_tenant_id`"), requireAll);
        validateTask5Check(new Task2CheckSpec("skit_management_command_audit",
                "ck_skit_management_audit_result", "`result_status`='SUCCESS'"), requireAll);
        validateTask5Check(new Task2CheckSpec("skit_ad_callback_replay_command",
                "ck_skit_callback_replay_source",
                "`source_status` IN ('DEAD_LETTER','REJECTED')"), requireAll);
        validateTask5Check(new Task2CheckSpec("skit_management_export_task",
                "ck_skit_management_export_target", "`tenant_id`=`target_tenant_id`"), requireAll);
        validateTask5Check(new Task2CheckSpec("skit_management_export_task",
                "ck_skit_management_export_filter", "JSON_VALID(`filter_json`)"), requireAll);
        validateTask5Check(new Task2CheckSpec("skit_management_export_task",
                "ck_skit_management_export_status",
                "`status` IN ('PENDING','RUNNING','SUCCEEDED','FAILED','EXPIRED')"), requireAll);
        for (Task2TriggerSpec spec : TASK_11_MANAGEMENT_TRIGGER_SPECS) {
            if (!tableExists(spec.table)) {
                if (requireAll) {
                    throw new IllegalStateException(
                            "Task 11 schema is missing trigger table " + spec.table);
                }
                continue;
            }
            List<String> existing = jdbcTemplate.queryForList(TRIGGER_DEFINITION_QUERY,
                    String.class, spec.trigger);
            if (existing.isEmpty() && !requireAll) {
                continue;
            }
            validateTask2TriggerDefinition(spec, existing);
        }
    }

    void validateTask17RuntimeUpdateSchema(boolean requireAll) {
        validateAdditiveColumnSpecs("Task 17", TASK_17_RUNTIME_UPDATE_COLUMN_SPECS, requireAll);
        for (Task2CheckSpec spec : TASK_17_RUNTIME_UPDATE_CHECK_SPECS) {
            validateTask5Check(spec, requireAll);
        }
    }

    void validateTenantRuntimeUpdateTrustRootSchema(boolean requireAll) {
        validateAdditiveColumnSpecs("Tenant runtime update trust root",
                TENANT_RUNTIME_UPDATE_TRUST_ROOT_COLUMN_SPECS, requireAll);
        validateTask5Check(TENANT_RUNTIME_UPDATE_TRUST_ROOT_CHECK_SPEC, requireAll);
        validateTask5Index("skit_app_release_profile", "uk_skit_app_release_runtime_key",
                "active_runtime_update_key_fingerprint", true, requireAll);
    }

    private void validateAdditiveColumnSpecs(String label, List<Task2ColumnSpec> specs,
                                             boolean requireAll) {
        for (Task2ColumnSpec spec : specs) {
            if (!tableExists(spec.table)) {
                if (requireAll) {
                    throw new IllegalStateException(label + " schema is missing required table " + spec.table);
                }
                continue;
            }
            if (!columnExists(spec.table, spec.column)) {
                if (requireAll) {
                    throw new IllegalStateException(label + " schema is missing required column "
                            + spec.table + "." + spec.column);
                }
                continue;
            }
            validateColumnDefinition(spec.table, spec.column, spec.definition);
        }
    }

    private void validateTask5HardenedTableFingerprints() {
        boolean task7Hardened = columnExists("skit_ad_session", "reward_callback_inbox_id");
        for (Map.Entry<String, String> expected
                : SkitAdSchemaSignature.expectedTask5HardenedFingerprints().entrySet()) {
            String expectedFingerprint = expected.getValue();
            if (task7Hardened) {
                expectedFingerprint = SkitAdSchemaSignature.expectedTask7HardenedFingerprints()
                        .getOrDefault(expected.getKey(), expectedFingerprint);
            }
            validateExactTableFingerprint("Task 5 hardened", expected.getKey(), expectedFingerprint, true);
        }
    }

    private void validateTask5Index(String table, String index, String columns,
                                    boolean unique, boolean requireAll) {
        if (!tableExists(table)) {
            if (requireAll) {
                throw new IllegalStateException("Task 5 schema is missing required table " + table);
            }
            return;
        }
        String actual = jdbcTemplate.queryForObject(INDEX_DEFINITION_QUERY, String.class, table, index);
        if (actual == null && !requireAll) {
            return;
        }
        String expected = (unique ? "0:" : "1:") + columns;
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Incompatible Task 5 index " + table + "." + index
                    + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private void validateTask5ForeignKey(Task2ForeignKeySpec spec, boolean requireAll) {
        if (!tableExists(spec.table)) {
            if (requireAll) {
                throw new IllegalStateException("Task 5 schema is missing required table " + spec.table);
            }
            return;
        }
        String actual = jdbcTemplate.queryForObject(FOREIGN_KEY_DEFINITION_QUERY,
                String.class, spec.table, spec.constraint);
        if (actual == null && !requireAll) {
            return;
        }
        String expected = spec.columns + "->" + spec.referencedTable + "(" + spec.referencedColumns
                + "):RESTRICT:RESTRICT";
        if (!expected.equals(actual)
                && !isForwardCompatibleGrantSessionBinding(spec.table, spec.constraint, actual)) {
            throw new IllegalStateException("Incompatible Task 5 foreign key " + spec.table + "."
                    + spec.constraint + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private void validateTask7GrantSessionBinding(boolean requireAll) {
        if (!tableExists(TASK_7_GRANT_SESSION_BINDING.table)) {
            if (requireAll) {
                throw new IllegalStateException("Task 7 schema is missing required table "
                        + TASK_7_GRANT_SESSION_BINDING.table);
            }
            return;
        }
        String actual = jdbcTemplate.queryForObject(FOREIGN_KEY_DEFINITION_QUERY, String.class,
                TASK_7_GRANT_SESSION_BINDING.table, TASK_7_GRANT_SESSION_BINDING.constraint);
        if (actual == null && !requireAll) {
            return;
        }
        String expected = foreignKeyDefinition(TASK_7_GRANT_SESSION_BINDING);
        if (expected.equals(actual)) {
            return;
        }
        if (!requireAll && foreignKeyDefinition(TASK_7_LEGACY_GRANT_SESSION_BINDING).equals(actual)) {
            return;
        }
        throw new IllegalStateException("Incompatible Task 7 foreign key "
                + TASK_7_GRANT_SESSION_BINDING.table + "." + TASK_7_GRANT_SESSION_BINDING.constraint
                + ": expected=" + expected + ", actual=" + actual);
    }

    private void validateTask5Check(Task2CheckSpec spec, boolean requireAll) {
        if (!tableExists(spec.table)) {
            if (requireAll) {
                throw new IllegalStateException("Task 5 schema is missing required table " + spec.table);
            }
            return;
        }
        List<String> existing = jdbcTemplate.queryForList(CHECK_DEFINITION_QUERY,
                String.class, spec.table, spec.constraint);
        if (existing.isEmpty() && !requireAll) {
            return;
        }
        String actual = existing.isEmpty() ? null : normalizeCheckExpression(existing.get(0));
        String expected = normalizeCheckExpression(spec.expression);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Incompatible Task 5 check constraint " + spec.table + "."
                    + spec.constraint + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private void validateTask2ParentExtensionColumns(boolean requireAll) {
        for (Task2ColumnSpec spec : TASK_2_PARENT_COLUMN_SPECS) {
            if (!tableExists(spec.table)) {
                if (requireAll) {
                    throw new IllegalStateException("Task 2 schema is missing required table " + spec.table);
                }
                continue;
            }
            if (!columnExists(spec.table, spec.column)) {
                if (requireAll) {
                    throw new IllegalStateException("Task 2 schema is missing required column "
                            + spec.table + "." + spec.column);
                }
                continue;
            }
            if (!requireAll && spec.compatibleLegacyDefinition != null
                    && columnDefinitionMatches(spec.table, spec.column, spec.compatibleLegacyDefinition)) {
                continue;
            }
            validateColumnDefinition(spec.table, spec.column, spec.definition);
        }
    }

    private void validateTask2ParentForeignKeys(boolean requireAll) {
        for (Task2ForeignKeySpec spec : TASK_2_PARENT_FOREIGN_KEY_SPECS) {
            validateTask2ParentForeignKey(spec, requireAll);
        }
    }

    private void validateTask2ParentForeignKey(Task2ForeignKeySpec spec, boolean requireAll) {
        if (!tableExists(spec.table)) {
            if (requireAll) {
                throw new IllegalStateException("Task 2 schema is missing required table " + spec.table);
            }
            return;
        }
        String actual = jdbcTemplate.queryForObject(FOREIGN_KEY_DEFINITION_QUERY,
                String.class, spec.table, spec.constraint);
        String expected = spec.columns + "->" + spec.referencedTable + "(" + spec.referencedColumns
                + "):RESTRICT:RESTRICT";
        if (actual == null && !requireAll) {
            return;
        }
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Incompatible existing foreign key " + spec.table + "."
                    + spec.constraint + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private void validateTask2ParentChecks(boolean requireAll) {
        for (Task2CheckSpec spec : TASK_2_PARENT_CHECK_SPECS) {
            if (!tableExists(spec.table)) {
                if (requireAll) {
                    throw new IllegalStateException("Task 2 schema is missing required table " + spec.table);
                }
                continue;
            }
            List<String> existing = jdbcTemplate.queryForList(CHECK_DEFINITION_QUERY,
                    String.class, spec.table, spec.constraint);
            if (existing.isEmpty() && !requireAll) {
                continue;
            }
            String actual = existing.isEmpty() ? null : normalizeCheckExpression(existing.get(0));
            String expected = normalizeCheckExpression(spec.expression);
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Incompatible existing check constraint " + spec.table + "."
                        + spec.constraint + ": expected=" + expected + ", actual=" + actual);
            }
        }
    }

    private void validateTask2ParentTriggers(boolean requireAll) {
        for (Task2TriggerSpec spec : TASK_2_PARENT_TRIGGER_SPECS) {
            if (!tableExists(spec.table)) {
                if (requireAll) {
                    throw new IllegalStateException("Task 2 schema is missing required table " + spec.table);
                }
                continue;
            }
            List<String> existing = jdbcTemplate.queryForList(TRIGGER_DEFINITION_QUERY,
                    String.class, spec.trigger);
            if (existing.isEmpty() && !requireAll) {
                continue;
            }
            validateTask2TriggerDefinition(spec, existing);
        }
    }

    private void validateTask2OwnedTableFingerprints(boolean requireAll) {
        boolean task5Hardened = columnExists("skit_ad_session", "session_token_key_version");
        boolean task7Hardened = columnExists("skit_ad_session", "reward_callback_inbox_id");
        boolean task10Hardened = tableExists("skit_ad_reporting_credential_version")
                && tableExists("skit_ad_reconciliation_allocation")
                && columnExists("skit_ad_report_pull", "request_hash")
                && columnExists("skit_ad_reconciliation_bucket", "app_id")
                && columnExists("skit_ad_reconciliation_revision", "source_report_impressions");
        boolean task12Hardened = columnExists("skit_tenant_ad_capability",
                "dedicated_unlock_placement_id");
        for (Map.Entry<String, String> expected : SkitAdSchemaSignature.expectedFingerprints().entrySet()) {
            if (laterValidatorOwnsReleasedFingerprint(expected.getKey(), task10Hardened,
                    task12Hardened)) {
                continue;
            }
            String expectedFingerprint = expected.getValue();
            if (task5Hardened) {
                expectedFingerprint = SkitAdSchemaSignature.expectedTask5HardenedFingerprints()
                        .getOrDefault(expected.getKey(), expectedFingerprint);
            }
            if (task7Hardened) {
                expectedFingerprint = SkitAdSchemaSignature.expectedTask7HardenedFingerprints()
                        .getOrDefault(expected.getKey(), expectedFingerprint);
            }
            validateExactTableFingerprint("Task 2", expected.getKey(), expectedFingerprint, requireAll);
        }
        if (task10Hardened) {
            validateTask10ReconciliationSchema(requireAll);
        }
        if (task12Hardened) {
            validateTask12ReadinessSchema(requireAll);
        }
    }

    static boolean laterValidatorOwnsReleasedFingerprint(String table, boolean task10Hardened,
                                                          boolean task12Hardened) {
        return false;
    }

    private void validateExactTableFingerprint(String schemaLabel, String table, String expected,
                                               boolean requireTable) {
        if (!tableExists(table)) {
            if (requireTable) {
                throw new IllegalStateException(schemaLabel + " schema is missing required table " + table);
            }
            return;
        }
        String actual = SkitAdSchemaSignature.fingerprint(jdbcTemplate, table);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Incompatible canonical " + schemaLabel + " table " + table
                    + ": expected fingerprint=" + expected + ", actual fingerprint=" + actual
                    + ". A same-named column, index, foreign key, check, generated expression, or table "
                    + "property differs from the released schema.");
        }
    }

    private void validateFinalTableFingerprint(String schemaLabel, String table, String expected,
                                               String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Incompatible canonical " + schemaLabel + " table " + table
                    + ": expected fingerprint=" + expected + ", actual fingerprint=" + actual
                    + ". A same-named column, index, foreign key, check, generated expression, or table "
                    + "property differs from the released schema.");
        }
    }

    private void validateRequiredIndex(String table, String index, String columns,
                                       boolean unique, boolean requireTable) {
        if (!tableExists(table)) {
            if (requireTable) {
                throw new IllegalStateException("Task 2 schema is missing required table " + table);
            }
            return;
        }
        String actual = jdbcTemplate.queryForObject(INDEX_DEFINITION_QUERY, String.class, table, index);
        String expected = (unique ? "0:" : "1:") + columns;
        if (actual == null && !requireTable && !isTask2OwnedTable(table)) {
            return; // Released parent legitimately lacks the new candidate key before Task 2.
        }
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Incompatible index " + table + "." + index
                    + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static boolean isTask2OwnedTable(String table) {
        return Arrays.asList("skit_ad_callback_key", "skit_ad_reward_secret_version",
                "skit_ad_policy_snapshot", "skit_ad_session", "skit_ad_client_event",
                "skit_ad_callback_edge_attempt", "skit_ad_callback_inbox", "skit_ad_callback_attempt",
                "skit_ad_network_capability", "skit_content_entitlement", "skit_entitlement_grant",
                "skit_native_player_grant", "skit_ad_report_pull", "skit_ad_reconciliation_bucket",
                "skit_ad_reconciliation_revision", "skit_tenant_ad_capability",
                "skit_invite_code_registry").contains(table);
    }

    private void validateCriticalTask2Column(String table, String column, String definition,
                                             boolean requireTable) {
        if (!tableExists(table)) {
            if (requireTable) {
                throw new IllegalStateException("Task 2 schema is missing required table " + table);
            }
            return;
        }
        if (!columnExists(table, column)) {
            if (!requireTable && !isTask2OwnedTable(table)) {
                return;
            }
            throw new IllegalStateException("Task 2 schema is missing required column " + table + "." + column);
        }
        validateColumnDefinition(table, column, definition);
    }

    private SchemaStep seedStandardAgentPackageStep() {
        return updateSqlStep(SEED_STANDARD_AGENT_PACKAGE_SQL, STANDARD_AGENT_PACKAGE_CODE,
                STANDARD_AGENT_PACKAGE_NAME, STANDARD_AGENT_PACKAGE_STATUS, STANDARD_AGENT_PACKAGE_MENU_IDS);
    }

    void validateNoActiveUserIdentityDuplicates() {
        List<Map<String, Object>> duplicateUsernames = jdbcTemplate.queryForList(DUPLICATE_ACTIVE_USERNAMES_QUERY);
        List<Map<String, Object>> duplicateMobiles = jdbcTemplate.queryForList(DUPLICATE_ACTIVE_MOBILES_QUERY);
        if (duplicateUsernames.isEmpty() && duplicateMobiles.isEmpty()) {
            return;
        }
        throw new IllegalStateException("Cannot create global active system user identity indexes. "
                + "Duplicate usernames=" + describeDuplicates(duplicateUsernames) + ", duplicate mobiles="
                + describeDuplicates(duplicateMobiles) + ". Resolve the duplicates in system_users before restarting.");
    }

    void validateNoActiveDomainSingletonDuplicates() {
        List<Map<String, Object>> duplicateProfiles =
                jdbcTemplate.queryForList(DUPLICATE_ACTIVE_APP_RELEASE_PROFILES_QUERY);
        List<Map<String, Object>> duplicateCommissionPlans =
                jdbcTemplate.queryForList(DUPLICATE_ACTIVE_COMMISSION_PLANS_QUERY);
        if (duplicateProfiles.isEmpty() && duplicateCommissionPlans.isEmpty()) {
            return;
        }
        throw new IllegalStateException("Cannot enforce short-drama domain singleton constraints. "
                + "App release profiles=" + describeDuplicates(duplicateProfiles) + ", active commission plans="
                + describeDuplicates(duplicateCommissionPlans) + ". Resolve the duplicate rows before restarting.");
    }

    private static String describeDuplicates(List<Map<String, Object>> duplicates) {
        List<String> descriptions = new ArrayList<>();
        for (Map<String, Object> duplicate : duplicates) {
            descriptions.add(String.valueOf(duplicate.get("duplicate_value")) + " (ids="
                    + String.valueOf(duplicate.get("duplicate_ids")) + ")");
        }
        return descriptions.toString();
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(COLUMN_EXISTS_QUERY,
                Integer.class, table, column);
        if (count != null && count == 0) {
            jdbcTemplate.execute(addColumnSql(table, column, definition));
        }
    }

    private void ensureTask2Column(Task2ColumnSpec spec) {
        if (!tableExists(spec.table)) {
            throw new IllegalStateException("Task 2 schema is missing required table " + spec.table);
        }
        if (!columnExists(spec.table, spec.column)) {
            jdbcTemplate.execute(addColumnSql(spec.table, spec.column, spec.definition));
            validateColumnDefinition(spec.table, spec.column, spec.definition);
            return;
        }
        if (columnDefinitionMatches(spec.table, spec.column, spec.definition)) {
            return;
        }
        if (spec.compatibleLegacyDefinition != null
                && columnDefinitionMatches(spec.table, spec.column, spec.compatibleLegacyDefinition)) {
            jdbcTemplate.execute(modifyColumnSql(spec.table, spec.column, spec.definition));
            validateColumnDefinition(spec.table, spec.column, spec.definition);
            return;
        }
        validateColumnDefinition(spec.table, spec.column, spec.definition);
    }

    private boolean columnDefinitionMatches(String table, String column, String definition) {
        try {
            validateColumnDefinition(table, column, definition);
            return true;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private void addIndexIfMissing(String table, String index, String columns, boolean unique) {
        Integer count = jdbcTemplate.queryForObject(INDEX_EXISTS_QUERY,
                Integer.class, table, index);
        if (count != null && count == 0) {
            jdbcTemplate.execute(addIndexSql(table, index, columns, unique));
            return;
        }
        if (count != null && count > 0) {
            String actual = jdbcTemplate.queryForObject(INDEX_DEFINITION_QUERY, String.class, table, index);
            String expected = (unique ? "0:" : "1:") + normalizeColumnList(columns);
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Incompatible existing index " + table + "." + index
                        + ": expected=" + expected + ", actual=" + actual);
            }
        }
    }

    private void replaceIndexIfLegacy(String table, String index, String legacyColumns,
                                      String replacementColumns, boolean unique) {
        String actual = jdbcTemplate.queryForObject(INDEX_DEFINITION_QUERY, String.class, table, index);
        String legacy = indexDefinition(legacyColumns, unique);
        String replacement = indexDefinition(replacementColumns, unique);
        if (replacement.equals(actual)) {
            return;
        }
        if (actual == null) {
            jdbcTemplate.execute(addIndexSql(table, index, replacementColumns, unique));
            return;
        }
        if (!legacy.equals(actual)) {
            throw new IllegalStateException("Incompatible existing index " + table + "." + index
                    + ": expected legacy=" + legacy + " or replacement=" + replacement
                    + ", actual=" + actual);
        }
        // MySQL can use this index as an implicit child-side foreign-key index. Replacing it in
        // one ALTER keeps an equivalent left prefix available throughout the DDL operation.
        jdbcTemplate.execute(replaceIndexSql(table, index, replacementColumns, unique));
    }

    private void addForeignKeyIfMissing(String table, String constraint, String columns,
                                        String referencedTable, String referencedColumns) {
        String actual = jdbcTemplate.queryForObject(FOREIGN_KEY_DEFINITION_QUERY,
                String.class, table, constraint);
        String expected = normalizeColumnList(columns) + "->" + referencedTable + "("
                + normalizeColumnList(referencedColumns) + "):RESTRICT:RESTRICT";
        if (actual == null) {
            jdbcTemplate.execute(addForeignKeySql(table, constraint, columns, referencedTable, referencedColumns));
        } else if (isForwardCompatibleGrantSessionBinding(table, constraint, actual)) {
            return;
        } else if (!expected.equals(actual)) {
            throw new IllegalStateException("Incompatible existing foreign key " + table + "." + constraint
                    + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private void replaceForeignKeyIfLegacy(Task2ForeignKeySpec legacy, Task2ForeignKeySpec replacement) {
        String actual = jdbcTemplate.queryForObject(FOREIGN_KEY_DEFINITION_QUERY,
                String.class, legacy.table, legacy.constraint);
        String expectedLegacy = foreignKeyDefinition(legacy);
        String expectedReplacement = foreignKeyDefinition(replacement);
        if (expectedReplacement.equals(actual)) {
            return;
        }
        if (actual != null && !expectedLegacy.equals(actual)) {
            throw new IllegalStateException("Incompatible existing foreign key " + legacy.table + "."
                    + legacy.constraint + ": expected legacy=" + expectedLegacy
                    + " or replacement=" + expectedReplacement + ", actual=" + actual);
        }
        if (actual != null) {
            jdbcTemplate.execute(dropForeignKeySql(legacy.table, legacy.constraint));
        }
        String childIndex = jdbcTemplate.queryForObject(INDEX_DEFINITION_QUERY,
                String.class, legacy.table, legacy.constraint);
        String legacyChildIndex = "1:" + legacy.columns;
        String replacementChildIndex = "1:" + replacement.columns;
        if (childIndex != null && !legacyChildIndex.equals(childIndex)
                && !replacementChildIndex.equals(childIndex)) {
            throw new IllegalStateException("Incompatible supporting index " + legacy.table + "."
                    + legacy.constraint + ": expected legacy=" + legacyChildIndex
                    + " or replacement=" + replacementChildIndex + ", actual=" + childIndex);
        }
        if (legacyChildIndex.equals(childIndex)) {
            jdbcTemplate.execute(dropIndexSql(legacy.table, legacy.constraint));
        }
        jdbcTemplate.execute(addForeignKeySql(replacement.table, replacement.constraint,
                quoteColumns(replacement.columns), replacement.referencedTable,
                quoteColumns(replacement.referencedColumns)));
    }

    private static boolean isForwardCompatibleGrantSessionBinding(String table, String constraint,
                                                                   String actual) {
        return TASK_7_LEGACY_GRANT_SESSION_BINDING.table.equals(table)
                && TASK_7_LEGACY_GRANT_SESSION_BINDING.constraint.equals(constraint)
                && foreignKeyDefinition(TASK_7_GRANT_SESSION_BINDING).equals(actual);
    }

    private void addCheckIfMissing(String table, String constraint, String expression) {
        List<String> existing = jdbcTemplate.queryForList(CHECK_DEFINITION_QUERY,
                String.class, table, constraint);
        if (existing.isEmpty()) {
            jdbcTemplate.execute(addCheckSql(table, constraint, expression));
            return;
        }
        String expected = normalizeCheckExpression(expression);
        String actual = normalizeCheckExpression(existing.get(0));
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Incompatible existing check constraint " + table + "." + constraint
                    + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private void ensureTask2Trigger(Task2TriggerSpec spec) {
        List<String> existing = jdbcTemplate.queryForList(TRIGGER_DEFINITION_QUERY, String.class, spec.trigger);
        if (existing.isEmpty()) {
            jdbcTemplate.execute(addTriggerSql(spec.table, spec.trigger, spec.event, spec.action));
            existing = jdbcTemplate.queryForList(TRIGGER_DEFINITION_QUERY, String.class, spec.trigger);
        }
        validateTask2TriggerDefinition(spec, existing);
    }

    private void validateTask2TriggerDefinition(Task2TriggerSpec spec, List<String> existing) {
        String actual = existing.size() == 1 ? normalizeTriggerDefinition(existing.get(0)) : null;
        String expected = normalizeTriggerDefinition("BEFORE:" + spec.event + ":" + spec.table + ":" + spec.action);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Incompatible existing trigger " + spec.trigger
                    + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private void validateColumnDefinition(String table, String column, String definition) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(COLUMN_DEFINITION_QUERY, table, column);
        if (rows.size() != 1) {
            throw new IllegalStateException("Missing or ambiguous column definition for " + table + "." + column);
        }
        Map<String, Object> row = rows.get(0);
        String expectedType = definition.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        String actualType = String.valueOf(row.get("COLUMN_TYPE")).toLowerCase(Locale.ROOT);
        boolean expectedNullable = !definition.toUpperCase(Locale.ROOT).contains("NOT NULL");
        boolean actualNullable = "YES".equals(row.get("IS_NULLABLE"));
        String expectedDefault = expectedDefault(definition);
        String actualDefault = normalizeDefault(row.get("COLUMN_DEFAULT"));
        boolean expectedGenerated = definition.toUpperCase(Locale.ROOT).contains("GENERATED ALWAYS");
        boolean actualGenerated = row.get("GENERATION_EXPRESSION") != null
                && !String.valueOf(row.get("GENERATION_EXPRESSION")).trim().isEmpty();
        String expectedGeneration = expectedGeneration(definition);
        String actualGeneration = actualGenerated
                ? normalizeGeneratedExpression(String.valueOf(row.get("GENERATION_EXPRESSION"))) : null;
        if (!expectedType.equals(actualType) || expectedNullable != actualNullable
                || !Objects.equals(expectedDefault, actualDefault) || expectedGenerated != actualGenerated
                || !Objects.equals(expectedGeneration, actualGeneration)) {
            throw new IllegalStateException("Incompatible existing column " + table + "." + column
                    + ": expected={type=" + expectedType + ",nullable=" + expectedNullable + ",default="
                    + expectedDefault + ",generated=" + expectedGenerated + ",generation="
                    + expectedGeneration + "}, actual={type=" + actualType
                    + ",nullable=" + actualNullable + ",default=" + actualDefault + ",generated="
                    + actualGenerated + ",generation=" + actualGeneration + "}");
        }
    }

    private static String expectedDefault(String definition) {
        Matcher matcher = DEFAULT_DEFINITION_PATTERN.matcher(definition);
        return matcher.find() ? normalizeDefault(matcher.group(1)) : null;
    }

    private static String normalizeDefault(Object value) {
        if (value == null || "NULL".equalsIgnoreCase(String.valueOf(value))) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        if ((normalized.startsWith("'") && normalized.endsWith("'"))
                || (normalized.startsWith("b'") && normalized.endsWith("'"))) {
            int start = normalized.startsWith("b'") ? 2 : 1;
            normalized = normalized.substring(start, normalized.length() - 1);
        }
        return normalized;
    }

    static String normalizeCheckExpression(String expression) {
        if (expression == null) {
            return null;
        }
        String normalized = markBooleanOperators(normalizeSqlExpressionLexemes(expression));
        normalized = removeWhitespaceOutsideSqlLiterals(normalized);
        normalized = stripWrappingParentheses(normalized);
        boolean changed;
        do {
            changed = false;
            for (int[] pair : findParenthesisPairs(normalized)) {
                String content = normalized.substring(pair[0] + 1, pair[1]);
                boolean hasAnd = containsTopLevelMarker(content, BOOLEAN_AND_MARKER);
                boolean hasOr = containsTopLevelMarker(content, BOOLEAN_OR_MARKER);
                boolean safeBooleanGrouping = isRedundantBooleanGroupingContext(
                        normalized, pair[0], pair[1] + 1);
                boolean safeComparisonOperandGrouping = !hasAnd && !hasOr
                        && isRedundantComparisonOperandGrouping(normalized, pair[0], pair[1] + 1);
                if ((safeBooleanGrouping && ((!hasAnd && !hasOr) || (hasAnd && !hasOr
                        && !isNegatedBooleanGroup(normalized, pair[0]))))
                        || safeComparisonOperandGrouping) {
                    normalized = normalized.substring(0, pair[0]) + content
                            + normalized.substring(pair[1] + 1);
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return stripWrappingParentheses(normalized)
                .replace(BOOLEAN_AND_MARKER, "and").replace(BOOLEAN_OR_MARKER, "or");
    }

    /**
     * Canonicalizes metadata spelling without changing string-literal contents. In particular,
     * whitespace and case inside a literal are schema semantics and must remain fingerprinted.
     */
    private static String normalizeSqlExpressionLexemes(String expression) {
        String source = expression.replace("\\'", "'"); // INFORMATION_SCHEMA escapes literal delimiters
        StringBuilder result = new StringBuilder(source.length());
        boolean quoted = false;
        for (int index = 0; index < source.length();) {
            char current = source.charAt(index);
            if (quoted) {
                result.append(current);
                if (current == '\'' && index + 1 < source.length()
                        && source.charAt(index + 1) == '\'') {
                    result.append(source.charAt(index + 1));
                    index += 2;
                    continue;
                }
                if (current == '\'') {
                    quoted = false;
                }
                index++;
                continue;
            }
            if (isCharsetIntroducer(source, index, "_utf8mb4")
                    || isCharsetIntroducer(source, index, "_binary")) {
                index += startsSqlFragment(source, index, "_utf8mb4") ? 8 : 7;
                continue;
            }
            if (isStandaloneHexLiteral(source, index, "0x00")
                    || startsSqlFragment(source, index, "b'0'")
                    || startsSqlFragment(source, index, "'\\0'")) {
                result.append('0');
                index += 4;
                continue;
            }
            if (isStandaloneHexLiteral(source, index, "0x01")
                    || startsSqlFragment(source, index, "b'1'")
                    || startsSqlFragment(source, index, "'\\1'")) {
                result.append('1');
                index += 4;
                continue;
            }
            if (current == '`') {
                index++;
                continue;
            }
            if (current == '\'') {
                quoted = true;
                result.append(current);
            } else {
                result.append(Character.toLowerCase(current));
            }
            index++;
        }
        return result.toString();
    }

    private static boolean startsSqlFragment(String expression, int offset, String fragment) {
        return offset + fragment.length() <= expression.length()
                && expression.regionMatches(true, offset, fragment, 0, fragment.length());
    }

    private static boolean isCharsetIntroducer(String expression, int offset, String introducer) {
        int literalOffset = offset + introducer.length();
        return startsSqlFragment(expression, offset, introducer)
                && literalOffset < expression.length() && expression.charAt(literalOffset) == '\''
                && (offset == 0 || !isSqlIdentifierCharacter(expression.charAt(offset - 1)));
    }

    private static boolean isStandaloneHexLiteral(String expression, int offset, String literal) {
        int end = offset + literal.length();
        return startsSqlFragment(expression, offset, literal)
                && (offset == 0 || !isSqlIdentifierCharacter(expression.charAt(offset - 1)))
                && (end == expression.length() || !isSqlIdentifierCharacter(expression.charAt(end)));
    }

    private static String removeWhitespaceOutsideSqlLiterals(String expression) {
        StringBuilder result = new StringBuilder(expression.length());
        boolean quoted = false;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == '\'' && quoted && index + 1 < expression.length()
                    && expression.charAt(index + 1) == '\'') {
                result.append(current).append(expression.charAt(++index));
                continue;
            }
            if (current == '\'') {
                quoted = !quoted;
                result.append(current);
            } else if (quoted || !Character.isWhitespace(current)) {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static List<int[]> findParenthesisPairs(String expression) {
        List<Integer> openings = new ArrayList<>();
        List<int[]> pairs = new ArrayList<>();
        boolean quoted = false;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == '\'' && quoted && index + 1 < expression.length()
                    && expression.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (current == '\'') {
                quoted = !quoted;
            } else if (!quoted && current == '(') {
                openings.add(index);
            } else if (!quoted && current == ')' && !openings.isEmpty()) {
                int opening = openings.remove(openings.size() - 1);
                pairs.add(new int[]{opening, index});
            }
        }
        pairs.sort(Comparator.comparingInt(pair -> pair[0]));
        return pairs;
    }

    private static boolean containsTopLevelMarker(String expression, String marker) {
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < expression.length();) {
            char current = expression.charAt(index);
            if (current == '\'' && quoted && index + 1 < expression.length()
                    && expression.charAt(index + 1) == '\'') {
                index += 2;
                continue;
            }
            if (current == '\'') {
                quoted = !quoted;
                index++;
                continue;
            }
            if (!quoted && current == '(') {
                depth++;
            } else if (!quoted && current == ')') {
                depth--;
            } else if (!quoted && depth == 0 && expression.startsWith(marker, index)) {
                return true;
            }
            index++;
        }
        return false;
    }

    private static boolean isRedundantBooleanGroupingContext(String expression, int opening, int afterClosing) {
        return isBooleanBoundaryBefore(expression, opening) && isBooleanBoundaryAfter(expression, afterClosing);
    }

    /**
     * MySQL sometimes parenthesizes a complete arithmetic operand of a comparison. Removing that
     * wrapper is safe only when the opposite side of the group is already a boolean boundary; this
     * deliberately preserves expressions such as {@code x=(a+b)*c} and {@code x*(a+b)=c}.
     */
    private static boolean isRedundantComparisonOperandGrouping(String expression, int opening,
                                                                 int afterClosing) {
        return (isBooleanBoundaryBefore(expression, opening)
                && startsWithComparisonOperator(expression, afterClosing))
                || (endsWithComparisonOperator(expression, opening)
                && isBooleanBoundaryAfter(expression, afterClosing));
    }

    private static boolean startsWithComparisonOperator(String expression, int offset) {
        return startsSqlFragment(expression, offset, "<=>")
                || startsSqlFragment(expression, offset, "<>")
                || startsSqlFragment(expression, offset, "!=")
                || startsSqlFragment(expression, offset, ">=")
                || startsSqlFragment(expression, offset, "<=")
                || startsSqlFragment(expression, offset, "=")
                || startsSqlFragment(expression, offset, ">")
                || startsSqlFragment(expression, offset, "<");
    }

    private static boolean endsWithComparisonOperator(String expression, int end) {
        return endsWithToken(expression, end, "<=>") || endsWithToken(expression, end, "<>")
                || endsWithToken(expression, end, "!=") || endsWithToken(expression, end, ">=")
                || endsWithToken(expression, end, "<=") || endsWithToken(expression, end, "=")
                || endsWithToken(expression, end, ">") || endsWithToken(expression, end, "<");
    }

    private static boolean isBooleanBoundaryBefore(String expression, int opening) {
        if (opening == 0 || expression.charAt(opening - 1) == '(') {
            return true;
        }
        return endsWithToken(expression, opening, BOOLEAN_AND_MARKER)
                || endsWithToken(expression, opening, BOOLEAN_OR_MARKER)
                || endsWithSqlKeyword(expression, opening, "not")
                || endsWithToken(expression, opening, "when");
    }

    private static boolean isBooleanBoundaryAfter(String expression, int offset) {
        if (offset == expression.length() || expression.charAt(offset) == ')') {
            return true;
        }
        return expression.startsWith(BOOLEAN_AND_MARKER, offset)
                || expression.startsWith(BOOLEAN_OR_MARKER, offset)
                || expression.startsWith("then", offset)
                || expression.startsWith("else", offset)
                || expression.startsWith("end", offset);
    }

    private static boolean endsWithToken(String expression, int end, String token) {
        int start = end - token.length();
        return start >= 0 && expression.startsWith(token, start);
    }

    private static boolean endsWithSqlKeyword(String expression, int end, String keyword) {
        int start = end - keyword.length();
        return start >= 0 && expression.regionMatches(start, keyword, 0, keyword.length())
                && (start == 0 || !isSqlIdentifierCharacter(expression.charAt(start - 1)));
    }

    private static boolean isNegatedBooleanGroup(String expression, int openingParenthesis) {
        int keywordStart = openingParenthesis - 3;
        return keywordStart >= 0 && expression.regionMatches(keywordStart, "not", 0, 3)
                && (keywordStart == 0
                || !isSqlIdentifierCharacter(expression.charAt(keywordStart - 1)));
    }

    private static String markBooleanOperators(String expression) {
        StringBuilder result = new StringBuilder(expression.length());
        boolean quoted = false;
        boolean awaitingBetweenAnd = false;
        for (int index = 0; index < expression.length();) {
            char current = expression.charAt(index);
            if (quoted) {
                result.append(current);
                if (current == '\\' && index + 1 < expression.length()) {
                    result.append(expression.charAt(index + 1));
                    index += 2;
                    continue;
                }
                if (current == '\'' && index + 1 < expression.length()
                        && expression.charAt(index + 1) == '\'') {
                    result.append(expression.charAt(index + 1));
                    index += 2;
                    continue;
                }
                if (current == '\'') {
                    quoted = false;
                }
                index++;
                continue;
            }
            if (current == '\'') {
                quoted = true;
                result.append(current);
                index++;
                continue;
            }
            if (startsSqlKeyword(expression, index, "between")) {
                result.append("between");
                awaitingBetweenAnd = true;
                index += "between".length();
                continue;
            }
            if (startsSqlKeyword(expression, index, "and")) {
                result.append(awaitingBetweenAnd ? "and" : BOOLEAN_AND_MARKER);
                awaitingBetweenAnd = false;
                index += "and".length();
                continue;
            }
            if (startsSqlKeyword(expression, index, "or")) {
                result.append(BOOLEAN_OR_MARKER);
                awaitingBetweenAnd = false;
                index += "or".length();
                continue;
            }
            result.append(current);
            index++;
        }
        return result.toString();
    }

    private static boolean startsSqlKeyword(String expression, int offset, String keyword) {
        int end = offset + keyword.length();
        if (end > expression.length() || !expression.regionMatches(offset, keyword, 0, keyword.length())) {
            return false;
        }
        return (offset == 0 || !isSqlIdentifierCharacter(expression.charAt(offset - 1)))
                && (end == expression.length() || !isSqlIdentifierCharacter(expression.charAt(end)));
    }

    private static boolean isSqlIdentifierCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private static String normalizeTriggerDefinition(String definition) {
        return definition == null ? null : definition.toLowerCase(Locale.ROOT)
                .replace("`", "").replaceAll("\\s+", "");
    }

    private static String expectedGeneration(String definition) {
        Matcher matcher = GENERATED_DEFINITION_PATTERN.matcher(definition);
        return matcher.find() ? normalizeGeneratedExpression(matcher.group(1)) : null;
    }

    private static String normalizeGeneratedExpression(String expression) {
        return normalizeCheckExpression(expression);
    }

    private static String stripWrappingParentheses(String expression) {
        String result = expression;
        while (result.startsWith("(") && result.endsWith(")") && wrapsWholeExpression(result)) {
            result = result.substring(1, result.length() - 1);
        }
        return result;
    }

    private static boolean wrapsWholeExpression(String expression) {
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < expression.length(); index++) {
            char character = expression.charAt(index);
            if (character == '\'' && quoted && index + 1 < expression.length()
                    && expression.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (character == '\'') {
                quoted = !quoted;
                continue;
            }
            if (quoted) {
                continue;
            }
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
                if (depth == 0 && index < expression.length() - 1) {
                    return false;
                }
            }
            if (depth < 0) {
                return false;
            }
        }
        return depth == 0;
    }

    private static String normalizeColumnList(String columns) {
        return columns.replace("`", "").replace(" ", "");
    }

    private boolean tableExists(String table) {
        Integer count = jdbcTemplate.queryForObject(TABLE_EXISTS_QUERY, Integer.class, table);
        return count != null && count > 0;
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(COLUMN_EXISTS_QUERY, Integer.class, table, column);
        return count != null && count > 0;
    }

    private boolean indexExists(String table, String index) {
        Integer count = jdbcTemplate.queryForObject(INDEX_EXISTS_QUERY, Integer.class, table, index);
        return count != null && count > 0;
    }

    private void dropIndexIfExists(String table, String index) {
        if (indexExists(table, index)) {
            jdbcTemplate.execute(dropIndexSql(table, index));
        }
    }

    private static String auditColumns() {
        return "`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "`deleted` bit(1) NOT NULL DEFAULT b'0'";
    }

    private static String tableOptions() {
        return " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    }

}
