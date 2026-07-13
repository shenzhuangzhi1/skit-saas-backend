package cn.iocoder.yudao.module.skit.integration;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Minimal system schema required before {@code SkitSchemaInitializer} runs.
 */
final class SkitMySqlPrerequisiteFixture {

    private SkitMySqlPrerequisiteFixture() {
    }

    static void install(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE `system_tenant_package` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,"
                + "`name` varchar(30) NOT NULL,"
                + "`status` tinyint NOT NULL DEFAULT 0,"
                + "`menu_ids` text NOT NULL,"
                + "`deleted` bit(1) NOT NULL DEFAULT b'0',"
                + "PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        jdbc.execute("CREATE TABLE `system_tenant` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,"
                + "`package_id` bigint NOT NULL,"
                + "`contact_user_id` bigint DEFAULT NULL,"
                + "`contact_mobile` varchar(11) DEFAULT NULL,"
                + "`updater` varchar(64) DEFAULT '',"
                + "`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "`deleted` bit(1) NOT NULL DEFAULT b'0',"
                + "PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        jdbc.execute("CREATE TABLE `system_users` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,"
                + "`tenant_id` bigint NOT NULL,"
                + "`username` varchar(30) NOT NULL,"
                + "`mobile` varchar(11) DEFAULT NULL,"
                + "`updater` varchar(64) DEFAULT '',"
                + "`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "`deleted` bit(1) NOT NULL DEFAULT b'0',"
                + "PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        jdbc.execute("CREATE TABLE `system_role` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,"
                + "`tenant_id` bigint NOT NULL,"
                + "`code` varchar(64) NOT NULL,"
                + "`deleted` bit(1) NOT NULL DEFAULT b'0',"
                + "PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        jdbc.execute("CREATE TABLE `system_user_role` ("
                + "`id` bigint NOT NULL AUTO_INCREMENT,"
                + "`tenant_id` bigint NOT NULL,"
                + "`user_id` bigint NOT NULL,"
                + "`role_id` bigint NOT NULL,"
                + "`deleted` bit(1) NOT NULL DEFAULT b'0',"
                + "PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    }

}
