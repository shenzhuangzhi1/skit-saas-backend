package cn.iocoder.yudao.module.skit.integration;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Released pre-Task-2 Skit domain schema used to exercise a real legacy upgrade.
 */
final class SkitLegacyAdSchemaFixture {

    private static final long TENANT_ID = 101L;

    private SkitLegacyAdSchemaFixture() {
    }

    static void installValidLegacyRows(JdbcTemplate jdbc) {
        installReleasedTables(jdbc);
        seedTenant(jdbc, TENANT_ID, 1001L, "13800000101");
        jdbc.update("INSERT INTO skit_agent "
                        + "(id,tenant_id,tenant_code,root_invite_code,status) VALUES (?,?,?,?,?)",
                101L, TENANT_ID, "legacy-101", "AGENT101", 0);
        jdbc.update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,app_key,status) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                201L, TENANT_ID, "TAKU", "legacy account", "legacy-account", "legacy-app", "", 1);
        jdbc.update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,inviter_id,invite_code,depth,status) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                301L, TENANT_ID, "13900000301", "legacy-password-hash", "legacy member", null,
                "MEMBER301", 1, 0);
        jdbc.update("INSERT INTO skit_member_closure "
                        + "(id,tenant_id,ancestor_id,descendant_id,distance) VALUES (?,?,?,?,?)",
                351L, TENANT_ID, 301L, 301L, 0);
        jdbc.update("INSERT INTO skit_commission_plan "
                        + "(id,tenant_id,version,status,published_time) VALUES (?,?,?,?,CURRENT_TIMESTAMP)",
                401L, TENANT_ID, 1, 0);
        jdbc.update("INSERT INTO skit_commission_rule "
                        + "(id,tenant_id,plan_id,level_no,rate_bps) VALUES (?,?,?,?,?)",
                501L, TENANT_ID, 401L, 1, 2500);
        jdbc.update("INSERT INTO skit_ad_revenue_event "
                        + "(id,tenant_id,ad_account_id,provider,placement_id,external_event_id,source_member_id,"
                        + "gross_amount,occurred_time,completed,mock,status,rule_version) "
                        + "VALUES (?,?,?,?,?,?,?,12.34567890,CURRENT_TIMESTAMP,b'1',b'0',0,1)",
                601L, TENANT_ID, 201L, "TAKU", "legacy-placement", "legacy-event", 301L);
        jdbc.update("INSERT INTO skit_commission_ledger "
                        + "(id,tenant_id,event_id,beneficiary_type,beneficiary_member_id,level_no,gross_amount,"
                        + "rate_bps,amount,rule_version,status) VALUES (?,?,?,?,?,?,12.34567890,2500,3.08641973,1,0)",
                701L, TENANT_ID, 601L, 1, 301L, 1);
    }

    static void installInviteCollision(JdbcTemplate jdbc) {
        installReleasedTables(jdbc);
        seedTenant(jdbc, TENANT_ID, 1001L, "13800000101");
        jdbc.update("INSERT INTO skit_agent "
                        + "(id,tenant_id,tenant_code,root_invite_code,status) VALUES (?,?,?,?,?)",
                101L, TENANT_ID, "legacy-101", " Collision ", 0);
        jdbc.update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) VALUES (?,?,?,?,?,?,?,?)",
                301L, TENANT_ID, "13900000301", "legacy-password-hash", "legacy member",
                "collision", 1, 0);
    }

    static void installCrossTenantRevenueReference(JdbcTemplate jdbc) {
        installReleasedTables(jdbc);
        seedTenant(jdbc, TENANT_ID, 1001L, "13800000101");
        seedTenant(jdbc, 102L, 1002L, "13800000102");
        jdbc.update("INSERT INTO skit_ad_account "
                        + "(id,tenant_id,provider,account_name,account_id,app_id,app_key,status) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                202L, 102L, "TAKU", "foreign account", "foreign-account", "foreign-app", "", 1);
        jdbc.update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) VALUES (?,?,?,?,?,?,?,?)",
                301L, TENANT_ID, "13900000301", "legacy-password-hash", "legacy member",
                "MEMBER301", 1, 0);
        jdbc.update("INSERT INTO skit_ad_revenue_event "
                        + "(id,tenant_id,ad_account_id,provider,placement_id,external_event_id,source_member_id,"
                        + "gross_amount,occurred_time,completed,mock,status) "
                        + "VALUES (?,?,?,?,?,?,?,1.00000000,CURRENT_TIMESTAMP,b'1',b'0',0)",
                601L, TENANT_ID, 202L, "TAKU", "legacy-placement", "cross-tenant-event", 301L);
    }

    static void installCrossTenantLedgerBeneficiary(JdbcTemplate jdbc) {
        installValidLegacyRows(jdbc);
        seedTenant(jdbc, 102L, 1002L, "13800000102");
        jdbc.update("INSERT INTO skit_member "
                        + "(id,tenant_id,mobile,password,nickname,invite_code,depth,status) VALUES (?,?,?,?,?,?,?,?)",
                302L, 102L, "13900000302", "legacy-password-hash", "foreign member",
                "MEMBER302", 1, 0);
        jdbc.update("UPDATE skit_commission_ledger SET beneficiary_member_id=302 WHERE id=701");
    }

    static void installLegacySingletonDuplicates(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE skit_admin_record (id bigint NOT NULL AUTO_INCREMENT,tenant_id bigint NOT NULL,"
                + "page_key varchar(64) NOT NULL,row_key varchar(128) NOT NULL,record_data longtext NOT NULL,"
                + "status tinyint NOT NULL DEFAULT 0,sort int NOT NULL DEFAULT 0," + auditColumns()
                + ",PRIMARY KEY (id))" + tableOptions());
        jdbc.execute("CREATE TABLE skit_system_config (id bigint NOT NULL AUTO_INCREMENT,tenant_id bigint NOT NULL,"
                + "config_data longtext NOT NULL," + auditColumns() + ",PRIMARY KEY (id))" + tableOptions());
        jdbc.update("INSERT INTO skit_admin_record (tenant_id,page_key,row_key,record_data) "
                + "VALUES (101,'tenant','duplicate','{}'),(101,'tenant','duplicate','{}')");
        jdbc.update("INSERT INTO skit_system_config (tenant_id,config_data) VALUES (101,'{}'),(101,'{}')");
    }

    static void installDisguisedPartialTask2Table(JdbcTemplate jdbc) {
        installValidLegacyRows(jdbc);
        jdbc.execute("CREATE TABLE skit_ad_callback_key ("
                + "id bigint NOT NULL AUTO_INCREMENT,tenant_id bigint NOT NULL,ad_account_id bigint NOT NULL,"
                + "key_version int NOT NULL,callback_key_hash binary(32) NOT NULL,"
                + "PRIMARY KEY (id),UNIQUE KEY uk_skit_callback_key_tenant_id (tenant_id,id),"
                + "UNIQUE KEY uk_skit_callback_key_version (tenant_id,ad_account_id,key_version),"
                + "UNIQUE KEY uk_skit_callback_key_hash (callback_key_hash))" + tableOptions());
    }

    private static void seedTenant(JdbcTemplate jdbc, long tenantId, long userId, String mobile) {
        jdbc.update("INSERT IGNORE INTO system_tenant_package (id,name,status,menu_ids) VALUES (100,'legacy',0,'[]')");
        jdbc.update("INSERT INTO system_users (id,tenant_id,username,mobile) VALUES (?,?,?,?)",
                userId, tenantId, mobile, mobile);
        jdbc.update("INSERT INTO system_tenant (id,package_id,contact_user_id,contact_mobile) VALUES (?,?,?,?)",
                tenantId, 100L, userId, mobile);
    }

    private static void installReleasedTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE skit_agent ("
                + "id bigint NOT NULL AUTO_INCREMENT,tenant_id bigint NOT NULL,tenant_code varchar(32) NOT NULL,"
                + "root_invite_code varchar(32) NOT NULL,status tinyint NOT NULL DEFAULT 0,remark varchar(500) DEFAULT '',"
                + auditColumns() + ",PRIMARY KEY (id),UNIQUE KEY uk_skit_agent_tenant (tenant_id),"
                + "UNIQUE KEY uk_skit_agent_code (tenant_code),UNIQUE KEY uk_skit_agent_invite (root_invite_code))"
                + tableOptions());
        jdbc.execute("CREATE TABLE skit_ad_account ("
                + "id bigint NOT NULL AUTO_INCREMENT,tenant_id bigint NOT NULL,provider varchar(16) NOT NULL,"
                + "account_name varchar(128) DEFAULT '',account_id varchar(128) DEFAULT '',"
                + "app_id varchar(128) DEFAULT '',app_key varchar(255) DEFAULT '',secret text,config_data longtext,"
                + "status tinyint NOT NULL DEFAULT 1," + auditColumns() + ",PRIMARY KEY (id),"
                + "UNIQUE KEY uk_skit_ad_account_tenant_provider (tenant_id,provider))" + tableOptions());
        jdbc.execute("CREATE TABLE skit_member ("
                + "id bigint NOT NULL AUTO_INCREMENT,tenant_id bigint NOT NULL,mobile varchar(32) NOT NULL,"
                + "password varchar(100) NOT NULL,nickname varchar(64) NOT NULL,inviter_id bigint DEFAULT NULL,"
                + "invite_code varchar(32) NOT NULL,depth int NOT NULL DEFAULT 1,status tinyint NOT NULL DEFAULT 0,"
                + "register_ip varchar(50) DEFAULT '',login_ip varchar(50) DEFAULT '',login_time datetime DEFAULT NULL,"
                + auditColumns() + ",PRIMARY KEY (id),UNIQUE KEY uk_skit_member_tenant_mobile (tenant_id,mobile),"
                + "UNIQUE KEY uk_skit_member_invite_code (invite_code),"
                + "KEY idx_skit_member_tenant_inviter (tenant_id,inviter_id))" + tableOptions());
        jdbc.execute("CREATE TABLE skit_member_closure ("
                + "id bigint NOT NULL AUTO_INCREMENT,tenant_id bigint NOT NULL,ancestor_id bigint NOT NULL,"
                + "descendant_id bigint NOT NULL,distance int NOT NULL," + auditColumns() + ",PRIMARY KEY (id),"
                + "UNIQUE KEY uk_skit_member_closure_path (tenant_id,ancestor_id,descendant_id),"
                + "KEY idx_skit_member_closure_desc_distance (tenant_id,descendant_id,distance))" + tableOptions());
        jdbc.execute("CREATE TABLE skit_commission_plan ("
                + "id bigint NOT NULL AUTO_INCREMENT,tenant_id bigint NOT NULL,version int NOT NULL,status tinyint NOT NULL,"
                + "published_time datetime NOT NULL," + auditColumns() + ",PRIMARY KEY (id),"
                + "UNIQUE KEY uk_skit_commission_plan_version (tenant_id,version),"
                + "KEY idx_skit_commission_plan_status (tenant_id,status))" + tableOptions());
        jdbc.execute("CREATE TABLE skit_commission_rule ("
                + "id bigint NOT NULL AUTO_INCREMENT,tenant_id bigint NOT NULL,plan_id bigint NOT NULL,"
                + "level_no int NOT NULL,rate_bps int NOT NULL," + auditColumns() + ",PRIMARY KEY (id),"
                + "UNIQUE KEY uk_skit_commission_rule_level (tenant_id,plan_id,level_no))" + tableOptions());
        jdbc.execute("CREATE TABLE skit_ad_revenue_event ("
                + "id bigint NOT NULL AUTO_INCREMENT,tenant_id bigint NOT NULL,ad_account_id bigint NOT NULL,"
                + "provider varchar(16) NOT NULL,placement_id varchar(128) NOT NULL,"
                + "external_event_id varchar(128) NOT NULL,source_member_id bigint NOT NULL,"
                + "gross_amount decimal(20,8) NOT NULL,occurred_time datetime NOT NULL,completed bit(1) NOT NULL,"
                + "mock bit(1) NOT NULL,status tinyint NOT NULL,rule_version int DEFAULT NULL,raw_data longtext,"
                + auditColumns() + ",PRIMARY KEY (id),UNIQUE KEY uk_skit_revenue_event_external "
                + "(tenant_id,provider,external_event_id),"
                + "KEY idx_skit_revenue_event_member (tenant_id,source_member_id,create_time))" + tableOptions());
        jdbc.execute("CREATE TABLE skit_commission_ledger ("
                + "id bigint NOT NULL AUTO_INCREMENT,tenant_id bigint NOT NULL,event_id bigint NOT NULL,"
                + "beneficiary_type tinyint NOT NULL,beneficiary_member_id bigint NOT NULL DEFAULT 0,"
                + "level_no int NOT NULL,gross_amount decimal(20,8) NOT NULL,rate_bps int NOT NULL,"
                + "amount decimal(20,8) NOT NULL,rule_version int NOT NULL,status tinyint NOT NULL,"
                + auditColumns() + ",PRIMARY KEY (id),UNIQUE KEY uk_skit_ledger_beneficiary "
                + "(tenant_id,event_id,beneficiary_type,beneficiary_member_id,level_no),"
                + "KEY idx_skit_ledger_member_time (tenant_id,beneficiary_member_id,create_time))" + tableOptions());
    }

    private static String auditColumns() {
        return "creator varchar(64) DEFAULT '',create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "updater varchar(64) DEFAULT '',update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP "
                + "ON UPDATE CURRENT_TIMESTAMP,deleted bit(1) NOT NULL DEFAULT b'0'";
    }

    private static String tableOptions() {
        return " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    }

}
