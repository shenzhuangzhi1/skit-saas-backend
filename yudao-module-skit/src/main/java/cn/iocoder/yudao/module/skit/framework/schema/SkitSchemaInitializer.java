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
    private static final String TASK_2_INVITE_COLLISIONS_QUERY = "SELECT UPPER(TRIM(`a`.`root_invite_code`)) "
            + "AS `normalized_code`,`a`.`tenant_id` AS `agent_tenant_id`,`a`.`id` AS `agent_id`,"
            + "`m`.`tenant_id` AS `member_tenant_id`,`m`.`id` AS `member_id` FROM `skit_agent` `a` "
            + "JOIN `skit_member` `m` ON UPPER(TRIM(`m`.`invite_code`))=UPPER(TRIM(`a`.`root_invite_code`)) "
            + "WHERE `a`.`deleted`=b'0' AND `m`.`deleted`=b'0' ORDER BY `normalized_code`,`a`.`id`,`m`.`id`";
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
                            "BEGIN IF OLD.`legacy_unverified`=b'1' AND NOT ("
                                    + "NEW.`legacy_unverified` <=> OLD.`legacy_unverified` AND "
                                    + "NEW.`source_type` <=> OLD.`source_type` AND "
                                    + "NEW.`match_status` <=> OLD.`match_status` AND "
                                    + "NEW.`source_verification_status` <=> OLD.`source_verification_status` AND "
                                    + "NEW.`reward_qualification_status` <=> OLD.`reward_qualification_status` AND "
                                    + "NEW.`reconciliation_status` <=> OLD.`reconciliation_status` AND "
                                    + "NEW.`status` <=> OLD.`status`) THEN SIGNAL SQLSTATE '45000' "
                                    + "SET MESSAGE_TEXT='legacy revenue facts are immutable'; END IF; END"),
                    new Task2TriggerSpec("skit_commission_ledger", "trg_skit_ledger_legacy_immutable",
                            "BEGIN IF OLD.`legacy_unverified`=b'1' AND NOT ("
                                    + "NEW.`legacy_unverified` <=> OLD.`legacy_unverified` AND "
                                    + "NEW.`entry_type` <=> OLD.`entry_type` AND "
                                    + "NEW.`balance_bucket` <=> OLD.`balance_bucket` AND "
                                    + "NEW.`revision_no` <=> OLD.`revision_no` AND "
                                    + "NEW.`status` <=> OLD.`status`) THEN SIGNAL SQLSTATE '45000' "
                                    + "SET MESSAGE_TEXT='legacy ledger facts are immutable'; END IF; END")));
    private static final Pattern DEFAULT_DEFINITION_PATTERN = Pattern.compile(
            "(?i)\\bDEFAULT\\s+(b'[^']*'|'[^']*'|NULL|-?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern GENERATED_DEFINITION_PATTERN = Pattern.compile(
            "(?is)\\bGENERATED\\s+ALWAYS\\s+AS\\s*\\((.*)\\)\\s+STORED");
    private static final Pattern INNER_PARENTHESES_PATTERN = Pattern.compile("\\(([^()]*)\\)");
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
        if (hasPendingTask2Migration(appliedMigrations)) {
            validateTask2Preflight();
        }
        for (Migration migration : migrations) {
            if (appliedMigrations.containsKey(migration.getVersion())) {
                continue;
            }
            migration.execute();
            jdbcTemplate.update(INSERT_APPLIED_MIGRATION_SQL, migration.getVersion(), migration.getDescription(),
                    migration.getChecksum());
            log.info("[run][applied skit schema migration {} ({})]", migration.getVersion(),
                    migration.getDescription());
        }
        log.info("[run][skit SaaS schema ready]");
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
    }

    private boolean hasPendingTask2Migration(Map<Integer, String> appliedMigrations) {
        for (Migration migration : migrations) {
            if (migration.getVersion() >= SkitAdSchemaDdl.VERSION
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
        private final String action;

        private Task2TriggerSpec(String table, String trigger, String action) {
            this.table = table;
            this.trigger = trigger;
            this.action = action;
        }
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

    private SchemaStep addForeignKeyStep(String table, String constraint, String columns,
                                         String referencedTable, String referencedColumns) {
        String sql = addForeignKeySql(table, constraint, columns, referencedTable, referencedColumns);
        return schemaStep("add-foreign-key-if-missing",
                () -> addForeignKeyIfMissing(table, constraint, columns, referencedTable, referencedColumns),
                FOREIGN_KEY_DEFINITION_QUERY, table, constraint, sql);
    }

    private SchemaStep addCheckStep(String table, String constraint, String expression) {
        String sql = addCheckSql(table, constraint, expression);
        return schemaStep("add-check-if-missing", () -> addCheckIfMissing(table, constraint, expression),
                CHECK_DEFINITION_QUERY, table, constraint, sql);
    }

    private SchemaStep task2TriggerStep(Task2TriggerSpec spec) {
        String sql = addTriggerSql(spec.table, spec.trigger, spec.action);
        return schemaStep("ensure-task2-trigger", () -> ensureTask2Trigger(spec),
                TRIGGER_DEFINITION_QUERY, spec.table, spec.trigger, spec.action, sql);
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

    private static String addForeignKeySql(String table, String constraint, String columns,
                                           String referencedTable, String referencedColumns) {
        return "ALTER TABLE `" + table + "` ADD CONSTRAINT `" + constraint + "` FOREIGN KEY ("
                + columns + ") REFERENCES `" + referencedTable + "` (" + referencedColumns
                + ") ON UPDATE RESTRICT ON DELETE RESTRICT";
    }

    private static String addCheckSql(String table, String constraint, String expression) {
        return "ALTER TABLE `" + table + "` ADD CONSTRAINT `" + constraint + "` CHECK (" + expression + ")";
    }

    private static String addTriggerSql(String table, String trigger, String action) {
        return "CREATE TRIGGER IF NOT EXISTS `" + trigger + "` BEFORE UPDATE ON `" + table
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
                + "SELECT `tenant_id`,TRIM(`root_invite_code`),'AGENT',`id`,NULL,'ACTIVE',"
                + "'skit-migration-2026071401','skit-migration-2026071401' FROM `skit_agent` "
                + "WHERE `deleted`=b'0' ON DUPLICATE KEY UPDATE `code`=VALUES(`code`)"));
        steps.add(updateSqlStep("INSERT INTO `skit_invite_code_registry` "
                + "(`tenant_id`,`code`,`owner_type`,`agent_id`,`member_id`,`status`,`creator`,`updater`) "
                + "SELECT `tenant_id`,TRIM(`invite_code`),'MEMBER',NULL,`id`,'ACTIVE',"
                + "'skit-migration-2026071401','skit-migration-2026071401' FROM `skit_member` "
                + "WHERE `deleted`=b'0' ON DUPLICATE KEY UPDATE `code`=VALUES(`code`)"));

        steps.add(dropIndexStep("skit_commission_ledger", "uk_skit_ledger_beneficiary"));
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

    void seedStandardAgentPackage() {
        seedStandardAgentPackageStep().execute();
    }

    private void validateTask2Preflight() {
        validateTask2TableSignatures(false);
        validateTask2LegacySingletons();
        if (tableExists("skit_agent") && tableExists("skit_member")) {
            assertNoTask2PreflightRows("normalized cross-table invite-code collision",
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
        validateTask2LedgerBeneficiaries();
        validateTask2TenantOwners();
        validateTask2LegacyMoney();
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
                            + "OR `r`.`agent_id`<>`a`.`id` OR `r`.`member_id` IS NOT NULL) ORDER BY `a`.`tenant_id`,`a`.`id`");
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
                            + "OR `r`.`member_id`<>`m`.`id` OR `r`.`agent_id` IS NOT NULL) ORDER BY `m`.`tenant_id`,`m`.`id`");
            assertNoTask2PreflightRows("invite registry active member code mismatch",
                    "SELECT 'skit_member' AS `owner_table`,`m`.`tenant_id`,`m`.`id` AS `owner_id`,"
                            + "UPPER(TRIM(`m`.`invite_code`)) AS `expected_code`,`r`.`normalized_code` AS `actual_code`,"
                            + "`r`.`id` AS `registry_id` FROM `skit_member` `m` JOIN `skit_invite_code_registry` `r` "
                            + "ON `r`.`tenant_id`=`m`.`tenant_id` AND `r`.`member_id`=`m`.`id` AND `r`.`status`='ACTIVE' "
                            + "WHERE `m`.`deleted`=b'0' AND `r`.`normalized_code`<>UPPER(TRIM(`m`.`invite_code`)) "
                            + "ORDER BY `m`.`tenant_id`,`m`.`id`");
        }
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
        for (Map.Entry<String, String> expected : SkitAdSchemaSignature.expectedFingerprints().entrySet()) {
            if (!tableExists(expected.getKey())) {
                if (requireAll) {
                    throw new IllegalStateException("Task 2 schema is missing required table " + expected.getKey());
                }
                continue;
            }
            String actual = SkitAdSchemaSignature.fingerprint(jdbcTemplate, expected.getKey());
            if (!expected.getValue().equals(actual)) {
                throw new IllegalStateException("Incompatible canonical Task 2 table " + expected.getKey()
                        + ": expected fingerprint=" + expected.getValue() + ", actual fingerprint=" + actual
                        + ". A same-named column, index, foreign key, check, generated expression, or table "
                        + "property differs from the released schema.");
            }
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

    private void addForeignKeyIfMissing(String table, String constraint, String columns,
                                        String referencedTable, String referencedColumns) {
        String actual = jdbcTemplate.queryForObject(FOREIGN_KEY_DEFINITION_QUERY,
                String.class, table, constraint);
        String expected = normalizeColumnList(columns) + "->" + referencedTable + "("
                + normalizeColumnList(referencedColumns) + "):RESTRICT:RESTRICT";
        if (actual == null) {
            jdbcTemplate.execute(addForeignKeySql(table, constraint, columns, referencedTable, referencedColumns));
        } else if (!expected.equals(actual)) {
            throw new IllegalStateException("Incompatible existing foreign key " + table + "." + constraint
                    + ": expected=" + expected + ", actual=" + actual);
        }
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
            jdbcTemplate.execute(addTriggerSql(spec.table, spec.trigger, spec.action));
            existing = jdbcTemplate.queryForList(TRIGGER_DEFINITION_QUERY, String.class, spec.trigger);
        }
        validateTask2TriggerDefinition(spec, existing);
    }

    private void validateTask2TriggerDefinition(Task2TriggerSpec spec, List<String> existing) {
        String actual = existing.size() == 1 ? normalizeTriggerDefinition(existing.get(0)) : null;
        String expected = normalizeTriggerDefinition("BEFORE:UPDATE:" + spec.table + ":" + spec.action);
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

    private static String normalizeCheckExpression(String expression) {
        if (expression == null) {
            return null;
        }
        String normalized = expression.toLowerCase(Locale.ROOT).replace("_utf8mb4", "")
                .replace("_binary", "")
                .replace("\\'", "'")
                .replace("0x00", "0").replace("0x01", "1")
                .replace("b'0'", "0").replace("b'1'", "1")
                .replace("'\\0'", "0").replace("'\\1'", "1")
                .replace("`", "").replaceAll("\\s+", "");
        normalized = stripWrappingParentheses(normalized);
        boolean changed;
        do {
            Matcher matcher = INNER_PARENTHESES_PATTERN.matcher(normalized);
            StringBuffer result = new StringBuffer();
            changed = false;
            while (matcher.find()) {
                String content = matcher.group(1);
                if (!content.contains("and") && !content.contains("or")) {
                    matcher.appendReplacement(result, Matcher.quoteReplacement(content));
                    changed = true;
                }
            }
            matcher.appendTail(result);
            normalized = result.toString();
        } while (changed);
        return stripWrappingParentheses(normalized);
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
        return expression.toLowerCase(Locale.ROOT).replace("_utf8mb4", "")
                .replace("_binary", "").replace("b'0'", "0").replace("b'1'", "1")
                .replace("'\\0'", "0").replace("'\\1'", "1")
                .replace("`", "").replace("(", "").replace(")", "")
                .replaceAll("\\s+", "");
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
        for (int index = 0; index < expression.length(); index++) {
            char character = expression.charAt(index);
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

    private void dropIndexIfExists(String table, String index) {
        Integer count = jdbcTemplate.queryForObject(INDEX_EXISTS_QUERY,
                Integer.class, table, index);
        if (count != null && count > 0) {
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
