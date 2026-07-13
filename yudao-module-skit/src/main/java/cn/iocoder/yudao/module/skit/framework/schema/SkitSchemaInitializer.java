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
    private static final String INDEX_EXISTS_QUERY = "SELECT COUNT(*) FROM information_schema.STATISTICS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?";
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
        for (Migration migration : migrations) {
            if (appliedMigrations.containsKey(migration.getVersion())) {
                String installedChecksum = appliedMigrations.get(migration.getVersion());
                if (!migration.getChecksum().equals(installedChecksum)) {
                    throw new IllegalStateException("Schema migration checksum mismatch for version "
                            + migration.getVersion() + ": stored=" + installedChecksum + ", expected="
                            + migration.getChecksum() + ". Restore the original migration or repair the schema history.");
                }
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

    private SchemaStep addIndexStep(String table, String index, String columns, boolean unique) {
        return schemaStep("add-index-if-missing", () -> addIndexIfMissing(table, index, columns, unique),
                INDEX_EXISTS_QUERY, table, index, addIndexSql(table, index, columns, unique));
    }

    private SchemaStep dropIndexStep(String table, String index) {
        return schemaStep("drop-index-if-present", () -> dropIndexIfExists(table, index),
                INDEX_EXISTS_QUERY, table, index, dropIndexSql(table, index));
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

    private static String addIndexSql(String table, String index, String columns, boolean unique) {
        return "ALTER TABLE `" + table + "` ADD " + (unique ? "UNIQUE " : "")
                + "INDEX `" + index + "` (" + columns + ")";
    }

    private static String dropIndexSql(String table, String index) {
        return "ALTER TABLE `" + table + "` DROP INDEX `" + index + "`";
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
        // 存量数据可能已有重复行；启动迁移只补普通索引，避免唯一键导致生产启动失败。
        // 全新部署由 sql/mysql/skit-saas.sql 保留唯一约束。
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

    void seedStandardAgentPackage() {
        seedStandardAgentPackageStep().execute();
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

    private void addIndexIfMissing(String table, String index, String columns, boolean unique) {
        Integer count = jdbcTemplate.queryForObject(INDEX_EXISTS_QUERY,
                Integer.class, table, index);
        if (count != null && count == 0) {
            jdbcTemplate.execute(addIndexSql(table, index, columns, unique));
        }
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
