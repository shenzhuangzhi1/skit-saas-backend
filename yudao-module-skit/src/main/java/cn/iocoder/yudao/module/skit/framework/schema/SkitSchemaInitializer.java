package cn.iocoder.yudao.module.skit.framework.schema;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 短剧 SaaS 模块幂等建表及存量表补丁。
 */
@Component
@Slf4j
public class SkitSchemaInitializer implements ApplicationRunner {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        createLegacyTables();
        migrateLegacyTenantColumns();
        createDomainTables();
        migrateDomainColumns();
        migrateDomainIndexes();
        log.info("[run][skit SaaS schema ready]");
    }

    private void createLegacyTables() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS `skit_admin_record` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL DEFAULT 1,"
                + "`page_key` varchar(64) NOT NULL,`row_key` varchar(128) NOT NULL,`record_data` longtext NOT NULL,"
                + "`status` tinyint NOT NULL DEFAULT 0,`sort` int NOT NULL DEFAULT 0,"
                + auditColumns() + ",PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS `skit_system_config` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL DEFAULT 1,`config_data` longtext NOT NULL,"
                + auditColumns() + ",PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    }

    private void migrateLegacyTenantColumns() {
        addColumnIfMissing("skit_admin_record", "tenant_id", "bigint NOT NULL DEFAULT 1 COMMENT '租户编号'");
        addColumnIfMissing("skit_system_config", "tenant_id", "bigint NOT NULL DEFAULT 1 COMMENT '租户编号'");
        // 存量数据可能已有重复行；启动迁移只补普通索引，避免唯一键导致生产启动失败。
        // 全新部署由 sql/mysql/skit-saas.sql 保留唯一约束。
        addIndexIfMissing("skit_admin_record", "idx_skit_admin_record_tenant_page_row",
                "`tenant_id`,`page_key`,`row_key`", false);
        addIndexIfMissing("skit_admin_record", "idx_skit_admin_record_tenant_page",
                "`tenant_id`,`page_key`", false);
        addIndexIfMissing("skit_admin_record", "idx_skit_admin_record_tenant_status",
                "`tenant_id`,`status`", false);
        addIndexIfMissing("skit_system_config", "idx_skit_system_config_tenant", "`tenant_id`", false);
    }

    private void createDomainTables() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS `skit_agent` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`tenant_code` varchar(32) NOT NULL,"
                + "`root_invite_code` varchar(32) NOT NULL,`status` tinyint NOT NULL DEFAULT 0,`remark` varchar(500) DEFAULT '',"
                + auditColumns() + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_agent_tenant` (`tenant_id`),"
                + "UNIQUE KEY `uk_skit_agent_code` (`tenant_code`),UNIQUE KEY `uk_skit_agent_invite` (`root_invite_code`))"
                + tableOptions());
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS `skit_app_release_profile` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`profile_code` varchar(32) NOT NULL,"
                + "`channel` varchar(16) NOT NULL DEFAULT 'production',`min_native_version` varchar(32) DEFAULT '',"
                + "`hot_version` varchar(32) DEFAULT '',`hot_bundle_url` varchar(500) DEFAULT '',"
                + "`hot_bundle_sha256` char(64) DEFAULT '',`native_version` varchar(32) DEFAULT '',"
                + "`native_package` varchar(255) DEFAULT '',`status` tinyint NOT NULL DEFAULT 0," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_app_release_profile_code` (`profile_code`),"
                + "UNIQUE KEY `uk_skit_app_release_profile_tenant_channel` (`tenant_id`,`channel`))" + tableOptions());
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS `skit_ad_account` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`provider` varchar(16) NOT NULL,"
                + "`account_name` varchar(128) DEFAULT '',`account_id` varchar(128) DEFAULT '',`app_id` varchar(128) DEFAULT '',"
                + "`app_key` varchar(255) DEFAULT '',`secret` text,`config_data` longtext,`status` tinyint NOT NULL DEFAULT 1,"
                + auditColumns() + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_ad_account_tenant_provider` (`tenant_id`,`provider`))"
                + tableOptions());
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS `skit_member` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`mobile` varchar(32) NOT NULL,"
                + "`password` varchar(100) NOT NULL,`nickname` varchar(64) NOT NULL,`inviter_id` bigint DEFAULT NULL,"
                + "`invite_code` varchar(32) NOT NULL,`depth` int NOT NULL DEFAULT 1,`status` tinyint NOT NULL DEFAULT 0,"
                + "`register_ip` varchar(50) DEFAULT '',`login_ip` varchar(50) DEFAULT '',`login_time` datetime DEFAULT NULL,"
                + auditColumns() + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_member_tenant_mobile` (`tenant_id`,`mobile`),"
                + "UNIQUE KEY `uk_skit_member_invite_code` (`invite_code`),KEY `idx_skit_member_tenant_inviter` (`tenant_id`,`inviter_id`))"
                + tableOptions());
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS `skit_member_closure` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ancestor_id` bigint NOT NULL,"
                + "`descendant_id` bigint NOT NULL,`distance` int NOT NULL," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_member_closure_path` (`tenant_id`,`ancestor_id`,`descendant_id`),"
                + "KEY `idx_skit_member_closure_desc_distance` (`tenant_id`,`descendant_id`,`distance`))" + tableOptions());
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS `skit_commission_plan` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`version` int NOT NULL,"
                + "`status` tinyint NOT NULL,`published_time` datetime NOT NULL," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_commission_plan_version` (`tenant_id`,`version`),"
                + "KEY `idx_skit_commission_plan_status` (`tenant_id`,`status`))" + tableOptions());
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS `skit_commission_rule` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`plan_id` bigint NOT NULL,"
                + "`level_no` int NOT NULL,`rate_bps` int NOT NULL," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_commission_rule_level` (`tenant_id`,`plan_id`,`level_no`))"
                + tableOptions());
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS `skit_ad_revenue_event` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_account_id` bigint NOT NULL,"
                + "`provider` varchar(16) NOT NULL,`placement_id` varchar(128) NOT NULL,"
                + "`external_event_id` varchar(128) NOT NULL,`source_member_id` bigint NOT NULL,"
                + "`gross_amount` decimal(20,8) NOT NULL,`occurred_time` datetime NOT NULL,`completed` bit(1) NOT NULL,"
                + "`mock` bit(1) NOT NULL,`status` tinyint NOT NULL,`rule_version` int DEFAULT NULL,`raw_data` longtext,"
                + auditColumns() + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_revenue_event_external`"
                + " (`tenant_id`,`provider`,`external_event_id`),KEY `idx_skit_revenue_event_member`"
                + " (`tenant_id`,`source_member_id`,`create_time`))" + tableOptions());
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS `skit_commission_ledger` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`event_id` bigint NOT NULL,"
                + "`beneficiary_type` tinyint NOT NULL,`beneficiary_member_id` bigint NOT NULL DEFAULT 0,`level_no` int NOT NULL,"
                + "`gross_amount` decimal(20,8) NOT NULL,`rate_bps` int NOT NULL,`amount` decimal(20,8) NOT NULL,"
                + "`rule_version` int NOT NULL,`status` tinyint NOT NULL," + auditColumns()
                + ",PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_ledger_beneficiary`"
                + " (`tenant_id`,`event_id`,`beneficiary_type`,`beneficiary_member_id`,`level_no`),"
                + "KEY `idx_skit_ledger_member_time` (`tenant_id`,`beneficiary_member_id`,`create_time`))" + tableOptions());
    }

    private void migrateDomainColumns() {
        addColumnIfMissing("skit_ad_revenue_event", "placement_id",
                "varchar(128) NOT NULL DEFAULT '' COMMENT '广告位编号' AFTER `provider`");
    }

    private void migrateDomainIndexes() {
        // 手机号是租户内会员身份；邀请码和 App 上下文决定所属代理商租户。
        dropIndexIfExists("skit_member", "uk_skit_member_mobile");
        addIndexIfMissing("skit_member", "uk_skit_member_tenant_mobile", "`tenant_id`,`mobile`", true);
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
        }
    }

    private void addIndexIfMissing(String table, String index, String columns, boolean unique) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, index);
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` ADD " + (unique ? "UNIQUE " : "")
                    + "INDEX `" + index + "` (" + columns + ")");
        }
    }

    private void dropIndexIfExists(String table, String index) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, index);
        if (count != null && count > 0) {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` DROP INDEX `" + index + "`");
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
