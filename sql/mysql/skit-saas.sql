-- 短剧 SaaS 业务表。除 skit_agent 外，所有业务表均以 tenant_id 隔离。

CREATE TABLE IF NOT EXISTS `skit_schema_migration` (
  `version` int NOT NULL COMMENT '迁移版本',
  `description` varchar(255) NOT NULL COMMENT '迁移说明',
  `checksum` char(64) NOT NULL COMMENT '迁移校验和',
  `installed_on` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '安装时间',
  PRIMARY KEY (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='短剧 SaaS 数据库迁移记录';

CREATE TABLE IF NOT EXISTS `skit_identity_migration_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `migration_version` int NOT NULL COMMENT '迁移版本',
  `identity_type` varchar(16) NOT NULL COMMENT '身份类型',
  `changed_user_id` bigint NOT NULL COMMENT '被修复用户',
  `changed_tenant_id` bigint NOT NULL COMMENT '被修复用户原租户',
  `retained_user_id` bigint NOT NULL COMMENT '保留身份用户',
  `retained_reason` varchar(64) NOT NULL COMMENT '保留原因',
  `old_value` varchar(64) DEFAULT NULL COMMENT '原身份值',
  `new_value` varchar(64) DEFAULT NULL COMMENT '修复后身份值',
  `repaired_on` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修复时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_identity_migration_target` (`migration_version`,`identity_type`,`changed_user_id`),
  KEY `idx_skit_identity_migration_value` (`migration_version`,`identity_type`,`old_value`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='历史管理端身份修复审计';

CREATE TABLE IF NOT EXISTS `skit_admin_record_migration_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `migration_version` int NOT NULL COMMENT '迁移版本',
  `source_id` bigint NOT NULL COMMENT '被修复管理记录',
  `tenant_id` bigint NOT NULL COMMENT '所属租户',
  `page_key` varchar(64) NOT NULL COMMENT '页面键',
  `original_row_key` varchar(128) NOT NULL COMMENT '原业务行键',
  `repaired_row_key` varchar(128) NOT NULL COMMENT '修复后业务行键',
  `retained_id` bigint NOT NULL COMMENT '保留原业务行键的记录',
  `algorithm` varchar(64) NOT NULL COMMENT '修复算法',
  `repaired_on` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修复时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_admin_record_migration_source` (`migration_version`,`source_id`),
  KEY `idx_skit_admin_record_migration_scope` (`tenant_id`,`page_key`,`original_row_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='历史后台记录键修复审计';

-- SKIT_CANONICAL_SCHEMA_BEGIN

CREATE TABLE IF NOT EXISTS `skit_admin_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL DEFAULT 1 COMMENT '租户编号',
  `page_key` varchar(64) NOT NULL COMMENT '页面键',
  `row_key` varchar(128) NOT NULL COMMENT '业务行键',
  `record_data` longtext NOT NULL COMMENT '页面字段 JSON',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态',
  `sort` int NOT NULL DEFAULT 0 COMMENT '排序',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_admin_record_tenant_page_row` (`tenant_id`,`page_key`,`row_key`),
  KEY `idx_skit_admin_record_tenant_page` (`tenant_id`,`page_key`),
  KEY `idx_skit_admin_record_tenant_status` (`tenant_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='短剧 SaaS 后台通用记录表';

CREATE TABLE IF NOT EXISTS `skit_system_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `tenant_id` bigint NOT NULL DEFAULT 1 COMMENT '租户编号',
  `config_data` longtext NOT NULL COMMENT '系统配置 JSON',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_system_config_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='短剧 SaaS 租户配置表';

CREATE TABLE IF NOT EXISTS `skit_agent` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL COMMENT 'system_tenant.id',
  `tenant_code` varchar(32) NOT NULL COMMENT '代理商登录编码',
  `root_invite_code` varchar(32) NOT NULL COMMENT '代理商根邀请码',
  `status` tinyint NOT NULL DEFAULT 0,
  `archived_time` datetime DEFAULT NULL COMMENT '归档时间',
  `archived_by` bigint DEFAULT NULL COMMENT '归档操作人',
  `remark` varchar(500) DEFAULT '',
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_skit_agent_tenant_id` (`tenant_id`,`id`),
  UNIQUE KEY `uk_skit_agent_tenant` (`tenant_id`),
  UNIQUE KEY `uk_skit_agent_code` (`tenant_code`), UNIQUE KEY `uk_skit_agent_invite` (`root_invite_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='代理商全局注册表';

CREATE TABLE IF NOT EXISTS `skit_app_release_profile` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL,
  `profile_code` varchar(32) NOT NULL COMMENT '白标 App 发布档案代码，等于代理商代码',
  `channel` varchar(16) NOT NULL DEFAULT 'production' COMMENT '发布渠道',
  `min_native_version` varchar(32) DEFAULT '' COMMENT '支持热更新的最低原生版本',
  `hot_version` varchar(32) DEFAULT '' COMMENT '热更新版本',
  `hot_bundle_url` varchar(500) DEFAULT '' COMMENT '公开 HTTPS 热更新包地址',
  `hot_bundle_sha256` char(64) DEFAULT '' COMMENT '热更新包 SHA-256',
  `native_version` varchar(32) DEFAULT '' COMMENT '当前原生壳版本',
  `native_package` varchar(255) DEFAULT '' COMMENT '原生包名，不包含签名或广告凭证',
  `runtime_update_public_key` varchar(4096) NOT NULL DEFAULT '' COMMENT '租户 APK 内置 X.509 DER RSA 公钥 Base64',
  `runtime_update_key_fingerprint` char(64) NOT NULL DEFAULT '' COMMENT '租户热更新公钥 DER SHA-256 指纹',
  `status` tinyint NOT NULL DEFAULT 0,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  `active_tenant_id` bigint GENERATED ALWAYS AS (CASE WHEN `deleted` = b'0' THEN `tenant_id` ELSE NULL END) STORED,
  `active_runtime_update_key_fingerprint` char(64) GENERATED ALWAYS AS (CASE WHEN `deleted`=b'0' AND `runtime_update_key_fingerprint`<>'' THEN `runtime_update_key_fingerprint` ELSE NULL END) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_app_release_profile_code` (`profile_code`),
  UNIQUE KEY `uk_skit_app_release_profile_tenant_channel` (`tenant_id`,`channel`),
  UNIQUE KEY `uk_skit_app_release_profile_active_tenant` (`active_tenant_id`),
  UNIQUE KEY `uk_skit_app_release_runtime_key` (`active_runtime_update_key_fingerprint`),
  CONSTRAINT `ck_skit_app_release_runtime_key` CHECK ((`runtime_update_public_key`='' AND `runtime_update_key_fingerprint`='') OR (`runtime_update_public_key`<>'' AND REGEXP_LIKE(`runtime_update_key_fingerprint`,'^[0-9a-f]{64}$')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='代理商白标 App 发布档案';

CREATE TABLE IF NOT EXISTS `skit_app_build_material` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL,
  `material_version` int NOT NULL, `api_base_url` varchar(512) NOT NULL DEFAULT '',
  `app_name` varchar(128) NOT NULL DEFAULT '', `native_version_code` bigint NOT NULL DEFAULT 1,
  `native_version_name` varchar(64) NOT NULL DEFAULT '', `runtime_release_no` bigint NOT NULL DEFAULT 1,
  `secret_ciphertext` mediumblob DEFAULT NULL, `secret_nonce` binary(12) DEFAULT NULL,
  `encryption_key_id` varchar(64) DEFAULT NULL, `envelope_version` smallint DEFAULT NULL,
  `active` bit(1) NOT NULL DEFAULT b'1',
  `active_tenant_id` bigint GENERATED ALWAYS AS (CASE WHEN `active`=b'1' AND `deleted`=b'0' THEN `tenant_id` ELSE NULL END) STORED,
  `verified_at` datetime DEFAULT NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_app_build_material_tenant_id` (`tenant_id`,`id`),
  UNIQUE KEY `uk_skit_app_build_material_version` (`tenant_id`,`material_version`),
  UNIQUE KEY `uk_skit_app_build_material_active` (`active_tenant_id`),
  CONSTRAINT `fk_skit_app_build_material_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `system_tenant` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `ck_skit_app_build_material_version` CHECK (`material_version` > 0),
  CONSTRAINT `ck_skit_app_build_material_version_code` CHECK (`native_version_code` BETWEEN 1 AND 2100000000),
  CONSTRAINT `ck_skit_app_build_material_release_no` CHECK (`runtime_release_no` > 0),
  CONSTRAINT `ck_skit_app_build_material_secret` CHECK ((`secret_ciphertext` IS NULL AND `secret_nonce` IS NULL AND `encryption_key_id` IS NULL AND `envelope_version` IS NULL) OR (`secret_ciphertext` IS NOT NULL AND `secret_nonce` IS NOT NULL AND `encryption_key_id` IS NOT NULL AND `envelope_version` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户原生 APK 构建资料版本';

CREATE TABLE IF NOT EXISTS `skit_ad_account` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL,
  `provider` varchar(16) NOT NULL, `account_name` varchar(128) DEFAULT '', `account_id` varchar(128) DEFAULT '',
  `app_id` varchar(128) DEFAULT '', `app_key` varchar(255) DEFAULT '', `secret` text,
  `config_data` longtext, `status` tinyint NOT NULL DEFAULT 1,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_ad_account_tenant_id` (`tenant_id`,`id`),
  UNIQUE KEY `uk_skit_ad_account_tenant_provider` (`tenant_id`,`provider`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户广告平台账号';

CREATE TABLE IF NOT EXISTS `skit_member` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL,
  `mobile` varchar(32) NOT NULL, `password` varchar(100) NOT NULL, `nickname` varchar(64) NOT NULL,
  `inviter_id` bigint DEFAULT NULL, `invite_code` varchar(32) NOT NULL, `depth` int NOT NULL DEFAULT 1,
  `status` tinyint NOT NULL DEFAULT 0, `point_balance` int NOT NULL DEFAULT 0 COMMENT '当前积分余额',
  `register_ip` varchar(50) DEFAULT '', `login_ip` varchar(50) DEFAULT '',
  `login_time` datetime DEFAULT NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_member_tenant_id` (`tenant_id`,`id`),
  UNIQUE KEY `uk_skit_member_tenant_mobile` (`tenant_id`,`mobile`),
  UNIQUE KEY `uk_skit_member_invite_code` (`invite_code`),
  KEY `idx_skit_member_tenant_inviter` (`tenant_id`,`inviter_id`),
  KEY `idx_skit_member_tenant_status_id` (`tenant_id`,`status`,`id`),
  CONSTRAINT `fk_skit_member_inviter` FOREIGN KEY (`tenant_id`,`inviter_id`)
    REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='短剧独立会员';

CREATE TABLE IF NOT EXISTS `skit_member_point_record` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL,
  `member_id` bigint NOT NULL, `biz_type` varchar(32) NOT NULL, `biz_id` varchar(64) NOT NULL,
  `title` varchar(64) NOT NULL, `description` varchar(255) NOT NULL DEFAULT '',
  `point_delta` int NOT NULL, `balance_after` int NOT NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_member_point_tenant_id` (`tenant_id`,`id`),
  UNIQUE KEY `uk_skit_member_point_business` (`tenant_id`,`member_id`,`biz_type`,`biz_id`),
  KEY `idx_skit_member_point_member_time` (`tenant_id`,`member_id`,`create_time`,`id`),
  CONSTRAINT `fk_skit_member_point_member` FOREIGN KEY (`tenant_id`,`member_id`)
    REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `ck_skit_member_point_delta` CHECK (`point_delta` <> 0),
  CONSTRAINT `ck_skit_member_point_balance` CHECK (`balance_after` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会员积分不可变记录';

CREATE TABLE IF NOT EXISTS `skit_member_closure` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL,
  `ancestor_id` bigint NOT NULL, `descendant_id` bigint NOT NULL, `distance` int NOT NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_member_closure_path` (`tenant_id`,`ancestor_id`,`descendant_id`),
  KEY `idx_skit_member_closure_desc_distance` (`tenant_id`,`descendant_id`,`distance`),
  CONSTRAINT `fk_skit_member_closure_ancestor` FOREIGN KEY (`tenant_id`,`ancestor_id`)
    REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `fk_skit_member_closure_descendant` FOREIGN KEY (`tenant_id`,`descendant_id`)
    REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `ck_skit_member_closure_distance` CHECK (`distance` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会员邀请闭包树';

CREATE TABLE IF NOT EXISTS `skit_commission_plan` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL, `version` int NOT NULL,
  `status` tinyint NOT NULL, `published_time` datetime NOT NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  `active_tenant_id` bigint GENERATED ALWAYS AS (CASE WHEN `deleted` = b'0' AND `status` = 0 THEN `tenant_id` ELSE NULL END) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_commission_plan_tenant_id` (`tenant_id`,`id`),
  UNIQUE KEY `uk_skit_commission_plan_version` (`tenant_id`,`version`),
  UNIQUE KEY `uk_skit_commission_plan_active_tenant` (`active_tenant_id`),
  KEY `idx_skit_commission_plan_status` (`tenant_id`,`status`),
  KEY `idx_skit_commission_plan_status_version` (`tenant_id`,`status`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='版本化分成方案';

CREATE TABLE IF NOT EXISTS `skit_commission_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL, `plan_id` bigint NOT NULL,
  `level_no` int NOT NULL COMMENT '0 本人，1..N 祖先', `rate_bps` int NOT NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_commission_rule_level` (`tenant_id`,`plan_id`,`level_no`),
  CONSTRAINT `fk_skit_commission_rule_plan` FOREIGN KEY (`tenant_id`,`plan_id`)
    REFERENCES `skit_commission_plan` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `ck_skit_commission_rule_rate` CHECK (`rate_bps` BETWEEN 0 AND 10000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分成方案层级规则';

-- Task 2: tenant-safe advertising, evidence, entitlement, finance, and credential schema.
CREATE TABLE IF NOT EXISTS `skit_ad_callback_key` (`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_account_id` bigint NOT NULL,`key_version` int NOT NULL,`callback_key_hash` binary(32) NOT NULL,`active` bit(1) NOT NULL DEFAULT b'1',`accept_until` datetime DEFAULT NULL,`revoked_at` datetime DEFAULT NULL,`active_account_id` bigint GENERATED ALWAYS AS (CASE WHEN `active` = b'1' AND `revoked_at` IS NULL THEN `ad_account_id` ELSE NULL END) STORED,`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_callback_key_tenant_id` (`tenant_id`,`id`),UNIQUE KEY `uk_skit_callback_key_version` (`tenant_id`,`ad_account_id`,`key_version`),UNIQUE KEY `uk_skit_callback_key_hash` (`callback_key_hash`),UNIQUE KEY `uk_skit_callback_key_active` (`tenant_id`,`active_account_id`),CONSTRAINT `fk_skit_callback_key_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `ck_skit_callback_key_version` CHECK (`key_version` > 0)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `skit_ad_reward_secret_version` (`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_account_id` bigint NOT NULL,`secret_version` int NOT NULL,`ciphertext` varbinary(4096) NOT NULL,`nonce` binary(12) NOT NULL,`encryption_key_id` varchar(64) NOT NULL,`envelope_version` smallint NOT NULL DEFAULT 1,`active` bit(1) NOT NULL DEFAULT b'1',`accept_until` datetime DEFAULT NULL,`revoked_at` datetime DEFAULT NULL,`active_account_id` bigint GENERATED ALWAYS AS (CASE WHEN `active` = b'1' AND `revoked_at` IS NULL THEN `ad_account_id` ELSE NULL END) STORED,`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_reward_secret_tenant_id` (`tenant_id`,`id`),UNIQUE KEY `uk_skit_reward_secret_version` (`tenant_id`,`ad_account_id`,`secret_version`),UNIQUE KEY `uk_skit_reward_secret_active` (`tenant_id`,`active_account_id`),CONSTRAINT `fk_skit_reward_secret_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `ck_skit_reward_secret_version` CHECK (`secret_version` > 0),CONSTRAINT `ck_skit_reward_secret_envelope` CHECK (`envelope_version` > 0)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `skit_ad_policy_snapshot` (`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`plan_id` bigint NOT NULL,`source_member_id` bigint NOT NULL,`rule_version` int NOT NULL,`snapshot_schema_version` smallint NOT NULL DEFAULT 1,`snapshot_json` longtext NOT NULL,`snapshot_hash` binary(32) NOT NULL,`policy_snapshot_at` datetime NOT NULL,`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_policy_snapshot_tenant_id` (`tenant_id`,`id`),KEY `idx_skit_policy_snapshot_plan` (`tenant_id`,`plan_id`,`rule_version`),CONSTRAINT `fk_skit_policy_snapshot_plan` FOREIGN KEY (`tenant_id`,`plan_id`) REFERENCES `skit_commission_plan` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_policy_snapshot_member` FOREIGN KEY (`tenant_id`,`source_member_id`) REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `ck_skit_policy_snapshot_version` CHECK (`rule_version` > 0 AND `snapshot_schema_version` > 0)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `skit_native_player_grant` (`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`member_id` bigint NOT NULL,`drama_id` bigint NOT NULL,`grant_token_hash` binary(32) NOT NULL,`status` varchar(16) NOT NULL DEFAULT 'ACTIVE',`expires_at` datetime NOT NULL,`revoked_at` datetime DEFAULT NULL,`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',`version` int NOT NULL DEFAULT 0,PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_player_grant_tenant_id` (`tenant_id`,`id`),UNIQUE KEY `uk_skit_player_grant_token` (`grant_token_hash`),UNIQUE KEY `uk_skit_player_grant_scope_id` (`tenant_id`,`id`,`member_id`,`drama_id`),KEY `idx_skit_player_grant_scope` (`tenant_id`,`member_id`,`drama_id`,`expires_at`),KEY `idx_skit_player_grant_lookup` (`tenant_id`,`member_id`,`drama_id`,`status`,`expires_at`,`id`),CONSTRAINT `fk_skit_player_grant_member` FOREIGN KEY (`tenant_id`,`member_id`) REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `ck_skit_player_grant_version` CHECK (`version` >= 0),CONSTRAINT `ck_skit_player_grant_lifecycle` CHECK ((`status` IN ('ACTIVE','EXPIRED') AND `revoked_at` IS NULL) OR (`status` = 'REVOKED' AND `revoked_at` IS NOT NULL)),CONSTRAINT `ck_skit_player_grant_status` CHECK (`status` IN ('ACTIVE','EXPIRED','REVOKED'))) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `skit_ad_session` (`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`session_id` varchar(64) NOT NULL,`session_token_hash` binary(32) NOT NULL,`protocol_version` smallint NOT NULL DEFAULT 1,`member_id` bigint NOT NULL,`ad_account_id` bigint NOT NULL,`policy_snapshot_id` bigint NOT NULL,`callback_key_version` int NOT NULL,`reward_secret_version` int NOT NULL,`provider` varchar(16) NOT NULL,`placement_id` varchar(128) NOT NULL,`scenario_id` varchar(64) NOT NULL,`business_type` varchar(32) NOT NULL,`drama_id` bigint NOT NULL,`episode_from` int NOT NULL,`episode_to` int NOT NULL,`unlock_scope` varchar(512) NOT NULL,`active_scope_hash` binary(32) DEFAULT NULL,`pseudonymous_user_id` varchar(128) NOT NULL,`client_lifecycle_status` varchar(32) NOT NULL DEFAULT 'CREATED',`reward_verification_status` varchar(32) NOT NULL DEFAULT 'PENDING',`entitlement_status` varchar(32) NOT NULL DEFAULT 'NONE',`revenue_status` varchar(32) NOT NULL DEFAULT 'NONE',`load_expires_at` datetime NOT NULL,`reward_accept_until` datetime NOT NULL,`reward_verified_at` datetime DEFAULT NULL,`entitled_at` datetime DEFAULT NULL,`sdk_request_id` varchar(128) DEFAULT NULL,`provider_show_id` varchar(128) DEFAULT NULL,`provider_transaction_id` varchar(128) DEFAULT NULL,`network_firm_id` int DEFAULT NULL,`adsource_id` varchar(128) DEFAULT NULL,`last_callback_sequence` int NOT NULL DEFAULT -1,`last_client_event` varchar(32) DEFAULT NULL,`failure_reason` varchar(128) DEFAULT NULL,`version` int NOT NULL DEFAULT 0,`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',`session_token_key_version` int NOT NULL DEFAULT 1,`access_mode` varchar(32) NOT NULL DEFAULT 'MEMBER_OAUTH',`native_player_grant_id` bigint DEFAULT NULL,`active_scope_released_at` datetime DEFAULT NULL,`active_scope_release_reason` varchar(32) DEFAULT NULL,PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_ad_session_tenant_id` (`tenant_id`,`id`),UNIQUE KEY `uk_skit_ad_session_public` (`session_id`),UNIQUE KEY `uk_skit_ad_session_token` (`session_token_hash`),UNIQUE KEY `uk_skit_ad_session_active_scope` (`tenant_id`,`member_id`,`active_scope_hash`),UNIQUE KEY `uk_skit_ad_session_grant_scope` (`tenant_id`,`id`,`member_id`,`drama_id`,`episode_from`,`provider_transaction_id`),UNIQUE KEY `uk_skit_ad_session_transaction` (`tenant_id`,`ad_account_id`,`provider_transaction_id`),UNIQUE KEY `uk_skit_ad_session_show` (`tenant_id`,`ad_account_id`,`provider_show_id`),CONSTRAINT `fk_skit_ad_session_member` FOREIGN KEY (`tenant_id`,`member_id`) REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_ad_session_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_ad_session_snapshot` FOREIGN KEY (`tenant_id`,`policy_snapshot_id`) REFERENCES `skit_ad_policy_snapshot` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_ad_session_callback_key` FOREIGN KEY (`tenant_id`,`ad_account_id`,`callback_key_version`) REFERENCES `skit_ad_callback_key` (`tenant_id`,`ad_account_id`,`key_version`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_ad_session_reward_secret` FOREIGN KEY (`tenant_id`,`ad_account_id`,`reward_secret_version`) REFERENCES `skit_ad_reward_secret_version` (`tenant_id`,`ad_account_id`,`secret_version`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_ad_session_player_grant` FOREIGN KEY (`tenant_id`,`native_player_grant_id`,`member_id`,`drama_id`) REFERENCES `skit_native_player_grant` (`tenant_id`,`id`,`member_id`,`drama_id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `ck_skit_ad_session_token_key_version` CHECK (`session_token_key_version` > 0),CONSTRAINT `ck_skit_ad_session_access_mode` CHECK ((`access_mode` = 'MEMBER_OAUTH' AND `native_player_grant_id` IS NULL) OR (`access_mode` = 'NATIVE_PLAYER_GRANT' AND `native_player_grant_id` IS NOT NULL)),CONSTRAINT `ck_skit_ad_session_scope_release_pair` CHECK ((`active_scope_hash` IS NOT NULL AND `active_scope_released_at` IS NULL AND `active_scope_release_reason` IS NULL) OR (`active_scope_hash` IS NULL AND `active_scope_released_at` IS NOT NULL AND `active_scope_release_reason` IS NOT NULL)),CONSTRAINT `ck_skit_ad_session_scope_release_lifecycle` CHECK ((`active_scope_release_reason` IS NULL AND `active_scope_hash` IS NOT NULL AND `active_scope_released_at` IS NULL AND `reward_verification_status` = 'PENDING' AND `entitlement_status` = 'NONE' AND `entitled_at` IS NULL) OR (`active_scope_release_reason` = 'VERIFY_TIMEOUT' AND `active_scope_hash` IS NULL AND `active_scope_released_at` IS NOT NULL AND `reward_verification_status` = 'VERIFY_TIMEOUT' AND `entitlement_status` = 'NONE' AND `entitled_at` IS NULL) OR (`active_scope_release_reason` = 'REWARD_REJECTED' AND `active_scope_hash` IS NULL AND `active_scope_released_at` IS NOT NULL AND `reward_verification_status` = 'REJECTED' AND `entitlement_status` = 'NONE' AND `entitled_at` IS NULL) OR (`active_scope_release_reason` = 'ENTITLEMENT_GRANTED' AND `active_scope_hash` IS NULL AND `active_scope_released_at` IS NOT NULL AND `reward_verification_status` = 'SIGNED_VERIFIED' AND `entitlement_status` IN ('GRANTED','SECURITY_REVOKED') AND `entitled_at` IS NOT NULL)),CONSTRAINT `ck_skit_ad_session_episode` CHECK (`episode_from` > 0 AND `episode_to` >= `episode_from`),CONSTRAINT `ck_skit_ad_session_protocol` CHECK (`protocol_version` > 0 AND `last_callback_sequence` >= -1),CONSTRAINT `ck_skit_ad_session_window` CHECK (`reward_accept_until` >= `load_expires_at`),CONSTRAINT `ck_skit_ad_session_client_status` CHECK (`client_lifecycle_status` IN ('CREATED','LOADING','SHOWN','CLIENT_REWARDED','CLOSED','FAILED','LOAD_EXPIRED')),CONSTRAINT `ck_skit_ad_session_reward_status` CHECK (`reward_verification_status` IN ('PENDING','SIGNED_VERIFIED','REJECTED','VERIFY_TIMEOUT')),CONSTRAINT `ck_skit_ad_session_entitlement` CHECK (`entitlement_status` IN ('NONE','GRANTED','SECURITY_REVOKED')),CONSTRAINT `ck_skit_ad_session_revenue` CHECK (`revenue_status` IN ('NONE','IMPRESSION_PENDING_REWARD','FROZEN','RECONCILING','RECONCILED','SUSPENSE'))) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `skit_ad_client_event` (`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_session_id` bigint NOT NULL,`protocol_version` smallint NOT NULL,`client_event_id` varchar(128) NOT NULL,`callback_sequence` int NOT NULL,`event_type` varchar(32) NOT NULL,`native_state` varchar(32) NOT NULL,`sdk_request_id` varchar(128) DEFAULT NULL,`provider_show_id` varchar(128) DEFAULT NULL,`network_firm_id` int DEFAULT NULL,`adsource_id` varchar(128) DEFAULT NULL,`client_reward_observed` bit(1) NOT NULL DEFAULT b'0',`closed` bit(1) NOT NULL DEFAULT b'0',`payload_hash` binary(32) NOT NULL,`occurred_at` datetime NOT NULL,`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_client_event_tenant_id` (`tenant_id`,`id`),UNIQUE KEY `uk_skit_client_event_idem` (`tenant_id`,`ad_session_id`,`client_event_id`),UNIQUE KEY `uk_skit_client_event_sequence` (`tenant_id`,`ad_session_id`,`callback_sequence`),CONSTRAINT `fk_skit_client_event_session` FOREIGN KEY (`tenant_id`,`ad_session_id`) REFERENCES `skit_ad_session` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `ck_skit_client_event_sequence` CHECK (`protocol_version` > 0 AND `callback_sequence` >= 0)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `skit_ad_callback_edge_attempt` (`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint DEFAULT NULL,`ad_account_id` bigint DEFAULT NULL,`callback_key_hash` binary(32) NOT NULL,`provider` varchar(16) NOT NULL,`callback_type` varchar(32) NOT NULL,`client_ip_hash` binary(32) DEFAULT NULL,`request_method` varchar(16) NOT NULL,`result_code` varchar(32) NOT NULL,`received_at` datetime NOT NULL,`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',PRIMARY KEY (`id`),KEY `idx_skit_edge_attempt_hash_time` (`callback_key_hash`,`received_at`),KEY `idx_skit_edge_attempt_tenant_time` (`tenant_id`,`received_at`),CONSTRAINT `fk_skit_edge_attempt_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `ck_skit_edge_attempt_route_pair` CHECK ((`tenant_id` IS NULL AND `ad_account_id` IS NULL) OR (`tenant_id` IS NOT NULL AND `ad_account_id` IS NOT NULL))) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `skit_ad_callback_inbox` (`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_account_id` bigint NOT NULL,`ad_session_id` bigint DEFAULT NULL,`callback_key_version` int DEFAULT NULL,`reward_secret_version` int DEFAULT NULL,`provider` varchar(16) NOT NULL,`callback_type` varchar(32) NOT NULL,`idempotency_key` varchar(255) NOT NULL,`provider_user_id` varchar(128) DEFAULT NULL,`extra_data_hash` binary(32) DEFAULT NULL,`provider_transaction_id` varchar(128) DEFAULT NULL,`provider_show_id` varchar(128) DEFAULT NULL,`provider_request_id` varchar(128) DEFAULT NULL,`placement_id` varchar(128) DEFAULT NULL,`adsource_id` varchar(128) DEFAULT NULL,`network_firm_id` int DEFAULT NULL,`source_currency` char(3) DEFAULT NULL,`source_amount_units` bigint DEFAULT NULL,`amount_scale` tinyint DEFAULT NULL,`signed_field_mask` bigint NOT NULL DEFAULT 0,`evidence_provenance` varchar(32) NOT NULL DEFAULT 'UNCLASSIFIED',`canonical_payload_hash` binary(32) NOT NULL,`authentication_level` varchar(32) NOT NULL,`signature_status` varchar(32) NOT NULL,`delivery_integrity_status` varchar(32) NOT NULL DEFAULT 'CANONICAL',`integrity_conflict_at` datetime DEFAULT NULL,`processing_status` varchar(32) NOT NULL DEFAULT 'PENDING',`payload_ciphertext` mediumblob DEFAULT NULL,`payload_nonce` binary(12) DEFAULT NULL,`payload_key_id` varchar(64) DEFAULT NULL,`payload_envelope_version` smallint DEFAULT NULL,`payload_expires_at` datetime DEFAULT NULL,`error_code` varchar(64) DEFAULT NULL,`lease_owner` varchar(64) DEFAULT NULL,`lease_until` datetime DEFAULT NULL,`processing_attempt_count` int NOT NULL DEFAULT 0,`next_attempt_at` datetime DEFAULT NULL,`received_at` datetime NOT NULL,`processed_at` datetime DEFAULT NULL,`dead_letter_alerted_at` datetime DEFAULT NULL,`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_callback_inbox_tenant_id` (`tenant_id`,`id`),UNIQUE KEY `uk_skit_callback_inbox_idem` (`tenant_id`,`ad_account_id`,`callback_type`,`idempotency_key`),KEY `idx_skit_callback_inbox_ready` (`processing_status`,`next_attempt_at`,`id`),KEY `idx_skit_callback_inbox_recovery` (`processing_status`,`lease_until`,`id`),CONSTRAINT `fk_skit_callback_inbox_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_callback_inbox_session` FOREIGN KEY (`tenant_id`,`ad_session_id`) REFERENCES `skit_ad_session` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_callback_inbox_key` FOREIGN KEY (`tenant_id`,`ad_account_id`,`callback_key_version`) REFERENCES `skit_ad_callback_key` (`tenant_id`,`ad_account_id`,`key_version`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_callback_inbox_secret` FOREIGN KEY (`tenant_id`,`ad_account_id`,`reward_secret_version`) REFERENCES `skit_ad_reward_secret_version` (`tenant_id`,`ad_account_id`,`secret_version`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `ck_skit_callback_inbox_processing_attempts` CHECK (`processing_attempt_count` >= 0),CONSTRAINT `ck_skit_callback_inbox_delivery_integrity` CHECK ((`delivery_integrity_status` = 'CANONICAL' AND `integrity_conflict_at` IS NULL) OR (`delivery_integrity_status` = 'PAYLOAD_CONFLICT' AND `integrity_conflict_at` IS NOT NULL)),CONSTRAINT `ck_skit_callback_inbox_processing_state` CHECK ((`processing_status` = 'PENDING' AND `lease_owner` IS NULL AND `lease_until` IS NULL AND `next_attempt_at` IS NULL AND `processed_at` IS NULL) OR (`processing_status` = 'PROCESSING' AND `lease_owner` IS NOT NULL AND `lease_until` IS NOT NULL AND `next_attempt_at` IS NULL AND `processed_at` IS NULL) OR (`processing_status` = 'RETRY_WAIT' AND `lease_owner` IS NULL AND `lease_until` IS NULL AND `next_attempt_at` IS NOT NULL AND `processed_at` IS NULL) OR (`processing_status` IN ('SUCCEEDED','REJECTED','DEAD_LETTER') AND `lease_owner` IS NULL AND `lease_until` IS NULL AND `next_attempt_at` IS NULL AND `processed_at` IS NOT NULL)),CONSTRAINT `ck_skit_callback_inbox_money` CHECK ((`source_currency` IS NULL AND `source_amount_units` IS NULL AND `amount_scale` IS NULL) OR (REGEXP_LIKE(`source_currency`,'^[A-Z]{3}$') AND `source_amount_units` >= 0 AND `amount_scale` BETWEEN 0 AND 18)),CONSTRAINT `ck_skit_callback_inbox_payload` CHECK ((`payload_ciphertext` IS NULL AND `payload_nonce` IS NULL AND `payload_key_id` IS NULL AND `payload_envelope_version` IS NULL AND `payload_expires_at` IS NULL) OR (`payload_ciphertext` IS NOT NULL AND `payload_nonce` IS NOT NULL AND `payload_key_id` IS NOT NULL AND `payload_envelope_version` > 0 AND `payload_expires_at` IS NOT NULL))) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `skit_ad_callback_attempt` (`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`callback_inbox_id` bigint NOT NULL,`ad_account_id` bigint NOT NULL,`ad_session_id` bigint DEFAULT NULL,`attempt_no` int NOT NULL,`payload_hash` binary(32) NOT NULL,`result_code` varchar(32) NOT NULL,`received_at` datetime NOT NULL,`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_callback_attempt_tenant_id` (`tenant_id`,`id`),UNIQUE KEY `uk_skit_callback_attempt_no` (`tenant_id`,`callback_inbox_id`,`attempt_no`),CONSTRAINT `fk_skit_callback_attempt_inbox` FOREIGN KEY (`tenant_id`,`callback_inbox_id`) REFERENCES `skit_ad_callback_inbox` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_callback_attempt_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_callback_attempt_session` FOREIGN KEY (`tenant_id`,`ad_session_id`) REFERENCES `skit_ad_session` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `ck_skit_callback_attempt_no` CHECK (`attempt_no` > 0)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `skit_ad_network_capability` (`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_account_id` bigint NOT NULL,`network_firm_id` int NOT NULL,`reward_authority` varchar(32) NOT NULL,`supports_user_id` bit(1) NOT NULL DEFAULT b'0',`supports_custom_data` bit(1) NOT NULL DEFAULT b'0',`supports_stable_transaction` bit(1) NOT NULL DEFAULT b'0',`supports_impression_revenue` bit(1) NOT NULL DEFAULT b'0',`supports_reporting` bit(1) NOT NULL DEFAULT b'0',`enabled` bit(1) NOT NULL DEFAULT b'0',`verified_at` datetime DEFAULT NULL,`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_network_cap_tenant_id` (`tenant_id`,`id`),UNIQUE KEY `uk_skit_network_cap_firm` (`tenant_id`,`ad_account_id`,`network_firm_id`),CONSTRAINT `fk_skit_network_cap_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `ck_skit_network_cap_firm` CHECK (`network_firm_id` > 0)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `skit_content_entitlement` (`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`member_id` bigint NOT NULL,`drama_id` bigint NOT NULL,`episode_no` int NOT NULL,`status` varchar(32) NOT NULL DEFAULT 'GRANTED',`granted_at` datetime NOT NULL,`lease_activated_at` datetime NOT NULL,`version` int NOT NULL DEFAULT 0,`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_entitlement_tenant_id` (`tenant_id`,`id`),UNIQUE KEY `uk_skit_entitlement_episode` (`tenant_id`,`member_id`,`drama_id`,`episode_no`),UNIQUE KEY `uk_skit_entitlement_grant_binding` (`tenant_id`,`id`,`member_id`,`drama_id`,`episode_no`),CONSTRAINT `fk_skit_entitlement_member` FOREIGN KEY (`tenant_id`,`member_id`) REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `ck_skit_entitlement_episode` CHECK (`episode_no` > 0),CONSTRAINT `ck_skit_entitlement_status` CHECK (`status` IN ('GRANTED','SECURITY_REVOKED'))) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `skit_entitlement_grant` (`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_session_id` bigint NOT NULL,`entitlement_id` bigint NOT NULL,`member_id` bigint NOT NULL,`drama_id` bigint NOT NULL,`episode_no` int NOT NULL,`provider_transaction_id` varchar(128) NOT NULL,`grant_result` varchar(32) NOT NULL,`granted_at` datetime NOT NULL,`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_grant_tenant_id` (`tenant_id`,`id`),UNIQUE KEY `uk_skit_grant_session_episode` (`tenant_id`,`ad_session_id`,`episode_no`),CONSTRAINT `fk_skit_grant_session` FOREIGN KEY (`tenant_id`,`ad_session_id`) REFERENCES `skit_ad_session` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_grant_entitlement` FOREIGN KEY (`tenant_id`,`entitlement_id`) REFERENCES `skit_content_entitlement` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_grant_session_binding` FOREIGN KEY (`tenant_id`,`ad_session_id`,`member_id`,`drama_id`,`episode_no`,`provider_transaction_id`) REFERENCES `skit_ad_session` (`tenant_id`,`id`,`member_id`,`drama_id`,`episode_from`,`provider_transaction_id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_grant_entitlement_binding` FOREIGN KEY (`tenant_id`,`entitlement_id`,`member_id`,`drama_id`,`episode_no`) REFERENCES `skit_content_entitlement` (`tenant_id`,`id`,`member_id`,`drama_id`,`episode_no`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_grant_member` FOREIGN KEY (`tenant_id`,`member_id`) REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `ck_skit_grant_episode` CHECK (`episode_no` > 0),CONSTRAINT `ck_skit_grant_result` CHECK (`grant_result` IN ('CREATED','ALREADY_OWNED'))) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `skit_ad_report_pull` (`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_account_id` bigint NOT NULL,`provider` varchar(16) NOT NULL,`range_start` datetime NOT NULL,`range_end` datetime NOT NULL,`response_hash` binary(32) NOT NULL,`status` varchar(32) NOT NULL,`response_ciphertext` mediumblob DEFAULT NULL,`response_nonce` binary(12) DEFAULT NULL,`response_key_id` varchar(64) DEFAULT NULL,`response_envelope_version` smallint DEFAULT NULL,`response_expires_at` datetime DEFAULT NULL,`pulled_at` datetime NOT NULL,`error_code` varchar(64) DEFAULT NULL,`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_report_pull_tenant_id` (`tenant_id`,`id`),UNIQUE KEY `uk_skit_report_pull_response` (`tenant_id`,`ad_account_id`,`range_start`,`range_end`,`response_hash`),CONSTRAINT `fk_skit_report_pull_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `ck_skit_report_pull_range` CHECK (`range_end` > `range_start`),CONSTRAINT `ck_skit_report_pull_response` CHECK ((`response_ciphertext` IS NULL AND `response_nonce` IS NULL AND `response_key_id` IS NULL AND `response_envelope_version` IS NULL AND `response_expires_at` IS NULL) OR (`response_ciphertext` IS NOT NULL AND `response_nonce` IS NOT NULL AND `response_key_id` IS NOT NULL AND `response_envelope_version` > 0 AND `response_expires_at` IS NOT NULL))) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `skit_ad_reconciliation_bucket` (`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_account_id` bigint NOT NULL,`bucket_key` char(64) NOT NULL,`report_date` date NOT NULL,`report_timezone` varchar(64) NOT NULL,`placement_id` varchar(128) NOT NULL,`network_firm_id` int NOT NULL,`adsource_id` varchar(128) NOT NULL,`currency` char(3) NOT NULL,`amount_scale` tinyint NOT NULL,`estimate_units` bigint NOT NULL DEFAULT 0,`report_actual_units` bigint NOT NULL DEFAULT 0,`report_impressions` bigint NOT NULL DEFAULT 0,`matched_impressions` bigint NOT NULL DEFAULT 0,`status` varchar(32) NOT NULL DEFAULT 'OPEN',`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_recon_bucket_tenant_id` (`tenant_id`,`id`),UNIQUE KEY `uk_skit_recon_bucket_identity` (`tenant_id`,`ad_account_id`,`bucket_key`,`report_date`,`report_timezone`,`placement_id`,`network_firm_id`,`adsource_id`,`currency`),CONSTRAINT `fk_skit_recon_bucket_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `ck_skit_recon_bucket_currency` CHECK (REGEXP_LIKE(`currency`,'^[A-Z]{3}$')),CONSTRAINT `ck_skit_recon_bucket_scale` CHECK (`amount_scale` BETWEEN 0 AND 18),CONSTRAINT `ck_skit_recon_bucket_amounts` CHECK (`estimate_units` >= 0 AND `report_actual_units` >= 0 AND `report_impressions` >= 0 AND `matched_impressions` >= 0)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `skit_ad_reconciliation_revision` (`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_account_id` bigint NOT NULL,`reconciliation_bucket_id` bigint NOT NULL,`report_pull_id` bigint DEFAULT NULL,`bucket_key` char(64) NOT NULL,`report_date` date NOT NULL,`revision_hash` binary(32) NOT NULL,`revision_no` int NOT NULL,`target_actual_units` bigint NOT NULL,`unmatched_actual_units` bigint NOT NULL DEFAULT 0,`amount_scale` tinyint NOT NULL,`currency` char(3) NOT NULL,`final_revision` bit(1) NOT NULL DEFAULT b'0',`reconciled_at` datetime NOT NULL,`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_recon_revision_tenant_id` (`tenant_id`,`id`),UNIQUE KEY `uk_skit_recon_revision_hash` (`tenant_id`,`ad_account_id`,`bucket_key`,`report_date`,`revision_hash`),UNIQUE KEY `uk_skit_recon_revision_no` (`tenant_id`,`reconciliation_bucket_id`,`revision_no`),CONSTRAINT `fk_skit_recon_revision_bucket` FOREIGN KEY (`tenant_id`,`reconciliation_bucket_id`) REFERENCES `skit_ad_reconciliation_bucket` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_recon_revision_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_recon_revision_pull` FOREIGN KEY (`tenant_id`,`report_pull_id`) REFERENCES `skit_ad_report_pull` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `ck_skit_recon_revision_no` CHECK (`revision_no` >= 0),CONSTRAINT `ck_skit_recon_revision_amounts` CHECK (`target_actual_units` >= 0 AND `unmatched_actual_units` >= 0),CONSTRAINT `ck_skit_recon_revision_scale` CHECK (`amount_scale` BETWEEN 0 AND 18),CONSTRAINT `ck_skit_recon_revision_currency` CHECK (REGEXP_LIKE(`currency`,'^[A-Z]{3}$'))) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `skit_tenant_ad_capability` (`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`ad_account_id` bigint DEFAULT NULL,`rollout_state` varchar(32) NOT NULL DEFAULT 'OFF',`min_native_version` varchar(32) DEFAULT '',`readiness_version` int NOT NULL DEFAULT 0,`enforced_at` datetime DEFAULT NULL,`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_tenant_capability_tenant_id` (`tenant_id`,`id`),UNIQUE KEY `uk_skit_tenant_capability_singleton` (`tenant_id`),CONSTRAINT `fk_skit_tenant_capability_account` FOREIGN KEY (`tenant_id`,`ad_account_id`) REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `ck_skit_tenant_capability_state` CHECK (`rollout_state` IN ('OFF','SHADOW_TEST_USERS','ENFORCED'))) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `skit_invite_code_registry` (`id` bigint NOT NULL AUTO_INCREMENT,`tenant_id` bigint NOT NULL,`code` varchar(32) NOT NULL,`normalized_code` varchar(32) GENERATED ALWAYS AS (UPPER(TRIM(`code`))) STORED,`owner_type` varchar(16) NOT NULL,`agent_id` bigint DEFAULT NULL,`member_id` bigint DEFAULT NULL,`status` varchar(16) NOT NULL DEFAULT 'ACTIVE',`rotated_at` datetime DEFAULT NULL,`active_agent_id` bigint GENERATED ALWAYS AS (CASE WHEN `status` = 'ACTIVE' THEN `agent_id` ELSE NULL END) STORED,`active_member_id` bigint GENERATED ALWAYS AS (CASE WHEN `status` = 'ACTIVE' THEN `member_id` ELSE NULL END) STORED,`creator` varchar(64) DEFAULT '',`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,`updater` varchar(64) DEFAULT '',`update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,`deleted` bit(1) NOT NULL DEFAULT b'0',PRIMARY KEY (`id`),UNIQUE KEY `uk_skit_invite_registry_tenant_id` (`tenant_id`,`id`),UNIQUE KEY `uk_skit_invite_registry_code` (`normalized_code`),UNIQUE KEY `uk_skit_invite_registry_agent` (`tenant_id`,`active_agent_id`),UNIQUE KEY `uk_skit_invite_registry_member` (`tenant_id`,`active_member_id`),CONSTRAINT `fk_skit_invite_registry_agent` FOREIGN KEY (`tenant_id`,`agent_id`) REFERENCES `skit_agent` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `fk_skit_invite_registry_member` FOREIGN KEY (`tenant_id`,`member_id`) REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,CONSTRAINT `ck_skit_invite_registry_owner` CHECK ((`owner_type` = 'AGENT' AND `agent_id` IS NOT NULL AND `member_id` IS NULL) OR (`owner_type` = 'MEMBER' AND `member_id` IS NOT NULL AND `agent_id` IS NULL)),CONSTRAINT `ck_skit_invite_registry_status` CHECK (`status` IN ('ACTIVE','DISABLED','ROTATED'))) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `skit_ad_revenue_event` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL, `ad_account_id` bigint NOT NULL,
  `provider` varchar(16) NOT NULL, `placement_id` varchar(128) NOT NULL,
  `external_event_id` varchar(128) NOT NULL, `source_member_id` bigint NOT NULL,
  `gross_amount` decimal(20,8) NOT NULL, `occurred_time` datetime NOT NULL,
  `completed` bit(1) NOT NULL, `mock` bit(1) NOT NULL, `status` tinyint NOT NULL,
  `rule_version` int DEFAULT NULL, `raw_data` longtext,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  `ad_session_id` bigint DEFAULT NULL, `callback_inbox_id` bigint DEFAULT NULL,
  `policy_snapshot_id` bigint DEFAULT NULL, `reconciliation_bucket_id` bigint DEFAULT NULL,
  `reconciliation_revision_id` bigint DEFAULT NULL,
  `source_type` varchar(32) NOT NULL DEFAULT 'LEGACY_CLIENT',
  `provider_transaction_id` varchar(128) DEFAULT NULL, `provider_show_id` varchar(128) DEFAULT NULL,
  `sdk_request_id` varchar(128) DEFAULT NULL, `adsource_id` varchar(128) DEFAULT NULL,
  `source_amount_units` bigint NOT NULL DEFAULT 0, `estimated_amount_units` bigint NOT NULL DEFAULT 0,
  `reconciled_amount_units` bigint NOT NULL DEFAULT 0, `amount_scale` tinyint NOT NULL DEFAULT 8,
  `source_currency` char(3) NOT NULL DEFAULT 'CNY',
  `match_status` varchar(32) NOT NULL DEFAULT 'LEGACY_UNMATCHED',
  `source_verification_status` varchar(32) NOT NULL DEFAULT 'LEGACY_UNVERIFIED',
  `reward_qualification_status` varchar(32) NOT NULL DEFAULT 'NOT_APPLICABLE',
  `reconciliation_status` varchar(32) NOT NULL DEFAULT 'NON_SETTLEABLE',
  `reconciled_at` datetime DEFAULT NULL, `verified_at` datetime DEFAULT NULL,
  `payload_hash` binary(32) DEFAULT NULL, `version` int NOT NULL DEFAULT 0,
  `legacy_unverified` bit(1) NOT NULL DEFAULT b'1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_revenue_event_tenant_id` (`tenant_id`,`id`),
  KEY `idx_skit_revenue_event_external` (`tenant_id`,`provider`,`external_event_id`),
  UNIQUE KEY `uk_skit_revenue_source_idem` (`tenant_id`,`ad_account_id`,`source_type`,`external_event_id`),
  UNIQUE KEY `uk_skit_revenue_inbox_source` (`tenant_id`,`callback_inbox_id`,`source_type`),
  UNIQUE KEY `uk_skit_revenue_session_source` (`tenant_id`,`ad_session_id`,`source_type`),
  KEY `idx_skit_revenue_event_member` (`tenant_id`,`source_member_id`,`create_time`),
  KEY `idx_skit_revenue_provider_time_id` (`tenant_id`,`provider`,`occurred_time`,`id`),
  CONSTRAINT `fk_skit_revenue_event_account` FOREIGN KEY (`tenant_id`,`ad_account_id`)
    REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `fk_skit_revenue_event_member` FOREIGN KEY (`tenant_id`,`source_member_id`)
    REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `fk_skit_revenue_event_session` FOREIGN KEY (`tenant_id`,`ad_session_id`)
    REFERENCES `skit_ad_session` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `fk_skit_revenue_event_inbox` FOREIGN KEY (`tenant_id`,`callback_inbox_id`)
    REFERENCES `skit_ad_callback_inbox` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `fk_skit_revenue_event_snapshot` FOREIGN KEY (`tenant_id`,`policy_snapshot_id`)
    REFERENCES `skit_ad_policy_snapshot` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `fk_skit_revenue_event_bucket` FOREIGN KEY (`tenant_id`,`reconciliation_bucket_id`)
    REFERENCES `skit_ad_reconciliation_bucket` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `fk_skit_revenue_event_revision` FOREIGN KEY (`tenant_id`,`reconciliation_revision_id`)
    REFERENCES `skit_ad_reconciliation_revision` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `ck_skit_revenue_currency` CHECK (REGEXP_LIKE(`source_currency`,'^[A-Z]{3}$')),
  CONSTRAINT `ck_skit_revenue_scale` CHECK (`amount_scale` BETWEEN 0 AND 18),
  CONSTRAINT `ck_skit_revenue_amounts` CHECK (`source_amount_units` >= 0 AND `estimated_amount_units` >= 0 AND `reconciled_amount_units` >= 0),
  CONSTRAINT `ck_skit_revenue_legacy` CHECK (`legacy_unverified` = b'0' OR (`source_type` = 'LEGACY_CLIENT' AND `source_verification_status` = 'LEGACY_UNVERIFIED' AND `reward_qualification_status` = 'NOT_APPLICABLE' AND `reconciliation_status` = 'NON_SETTLEABLE')),
  CONSTRAINT `ck_skit_revenue_reward_qualification` CHECK (`reward_qualification_status` IN ('NOT_APPLICABLE','PENDING_REWARD','REWARDED','NON_REWARDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='广告收益幂等事件';

CREATE TABLE IF NOT EXISTS `skit_commission_ledger` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL, `event_id` bigint NOT NULL,
  `beneficiary_type` tinyint NOT NULL, `beneficiary_member_id` bigint NOT NULL DEFAULT 0,
  `level_no` int NOT NULL, `gross_amount` decimal(20,8) NOT NULL, `rate_bps` int NOT NULL,
  `amount` decimal(20,8) NOT NULL, `rule_version` int NOT NULL, `status` tinyint NOT NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  `entry_type` varchar(32) NOT NULL DEFAULT 'LEGACY_ESTIMATE',
  `balance_bucket` varchar(32) NOT NULL DEFAULT 'NON_SETTLEABLE',
  `currency` char(3) NOT NULL DEFAULT 'CNY', `gross_amount_units` bigint NOT NULL DEFAULT 0,
  `amount_units` bigint NOT NULL DEFAULT 0, `amount_scale` tinyint NOT NULL DEFAULT 8,
  `reversal_of_id` bigint DEFAULT NULL, `reconciliation_revision_id` bigint DEFAULT NULL,
  `policy_snapshot_id` bigint DEFAULT NULL, `revision_no` int NOT NULL DEFAULT 0,
  `legacy_unverified` bit(1) NOT NULL DEFAULT b'1',
  `beneficiary_member_ref_id` bigint GENERATED ALWAYS AS
    (CASE WHEN `beneficiary_type` = 1 THEN `beneficiary_member_id` ELSE NULL END) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_ledger_tenant_id` (`tenant_id`,`id`),
  UNIQUE KEY `uk_skit_ledger_entry_revision` (`tenant_id`,`event_id`,`beneficiary_type`,`beneficiary_member_id`,`level_no`,`entry_type`,`revision_no`),
  KEY `idx_skit_ledger_member_time` (`tenant_id`,`beneficiary_member_id`,`create_time`),
  KEY `idx_skit_ledger_member_type_time_id` (`tenant_id`,`beneficiary_member_id`,`beneficiary_type`,`create_time`,`id`),
  KEY `idx_skit_ledger_beneficiary_time_id` (`tenant_id`,`beneficiary_type`,`create_time`,`id`),
  CONSTRAINT `fk_skit_ledger_event` FOREIGN KEY (`tenant_id`,`event_id`)
    REFERENCES `skit_ad_revenue_event` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `fk_skit_ledger_beneficiary_member` FOREIGN KEY (`tenant_id`,`beneficiary_member_ref_id`)
    REFERENCES `skit_member` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `fk_skit_ledger_reversal` FOREIGN KEY (`tenant_id`,`reversal_of_id`)
    REFERENCES `skit_commission_ledger` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `fk_skit_ledger_revision` FOREIGN KEY (`tenant_id`,`reconciliation_revision_id`)
    REFERENCES `skit_ad_reconciliation_revision` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `fk_skit_ledger_snapshot` FOREIGN KEY (`tenant_id`,`policy_snapshot_id`)
    REFERENCES `skit_ad_policy_snapshot` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `ck_skit_ledger_currency` CHECK (REGEXP_LIKE(`currency`,'^[A-Z]{3}$')),
  CONSTRAINT `ck_skit_ledger_scale` CHECK (`amount_scale` BETWEEN 0 AND 18),
  CONSTRAINT `ck_skit_ledger_rate` CHECK (`rate_bps` BETWEEN 0 AND 10000),
  CONSTRAINT `ck_skit_ledger_revision_no` CHECK (`revision_no` >= 0),
  CONSTRAINT `ck_skit_ledger_beneficiary` CHECK ((`beneficiary_type` = 1 AND `beneficiary_member_id` > 0) OR (`beneficiary_type` = 2 AND `beneficiary_member_id` = 0)),
  CONSTRAINT `ck_skit_ledger_legacy` CHECK (`legacy_unverified` = b'0' OR (`entry_type` = 'LEGACY_ESTIMATE' AND `balance_bucket` = 'NON_SETTLEABLE' AND `revision_no` = 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='广告分成不可变账本';

DELIMITER $$
DROP PROCEDURE IF EXISTS `skit_apply_ddl_if_missing`$$
CREATE PROCEDURE `skit_apply_ddl_if_missing`(
  IN `object_kind` varchar(16), IN `object_table` varchar(64),
  IN `object_name` varchar(64), IN `ddl_sql` longtext)
BEGIN
  DECLARE `existing_count` int DEFAULT 0;
  IF `object_kind` = 'COLUMN' THEN
    SELECT COUNT(*) INTO `existing_count` FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = `object_table` AND COLUMN_NAME = `object_name`;
  ELSEIF `object_kind` = 'INDEX' THEN
    SELECT COUNT(*) INTO `existing_count` FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = `object_table` AND INDEX_NAME = `object_name`;
  ELSEIF `object_kind` = 'CONSTRAINT' THEN
    SELECT COUNT(*) INTO `existing_count` FROM information_schema.TABLE_CONSTRAINTS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = `object_table` AND CONSTRAINT_NAME = `object_name`;
  ELSE
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'unsupported bootstrap DDL object kind';
  END IF;
  IF `existing_count` = 0 THEN
    SET @skit_bootstrap_ddl = `ddl_sql`;
    PREPARE skit_bootstrap_statement FROM @skit_bootstrap_ddl;
    EXECUTE skit_bootstrap_statement;
    DEALLOCATE PREPARE skit_bootstrap_statement;
  END IF;
END$$
DROP PROCEDURE IF EXISTS `skit_replace_grant_session_binding`$$
CREATE PROCEDURE `skit_replace_grant_session_binding`()
BEGIN
  DECLARE `actual_definition` longtext DEFAULT NULL;
  DECLARE `supporting_index_definition` longtext DEFAULT NULL;
  SELECT CONCAT(GROUP_CONCAT(`COLUMN_NAME` ORDER BY `ORDINAL_POSITION` SEPARATOR ','),'->',
      MIN(`REFERENCED_TABLE_NAME`),'(',GROUP_CONCAT(`REFERENCED_COLUMN_NAME`
      ORDER BY `ORDINAL_POSITION` SEPARATOR ','),')') INTO `actual_definition`
    FROM information_schema.KEY_COLUMN_USAGE
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'skit_entitlement_grant'
      AND CONSTRAINT_NAME = 'fk_skit_grant_session_binding'
      AND REFERENCED_TABLE_NAME IS NOT NULL;
  IF `actual_definition` = 'tenant_id,ad_session_id,member_id,drama_id,episode_no,provider_transaction_id->skit_ad_session(tenant_id,id,member_id,drama_id,episode_from,provider_transaction_id)' THEN
    ALTER TABLE `skit_entitlement_grant` DROP FOREIGN KEY `fk_skit_grant_session_binding`;
    SET `actual_definition` = NULL;
  ELSEIF `actual_definition` IS NOT NULL AND `actual_definition` <> 'tenant_id,ad_session_id,member_id,drama_id,provider_transaction_id->skit_ad_session(tenant_id,id,member_id,drama_id,provider_transaction_id)' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'incompatible entitlement grant session binding';
  END IF;
  IF `actual_definition` IS NULL THEN
    SELECT CONCAT(MIN(`NON_UNIQUE`),':',GROUP_CONCAT(`COLUMN_NAME`
        ORDER BY `SEQ_IN_INDEX` SEPARATOR ',')) INTO `supporting_index_definition`
      FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'skit_entitlement_grant'
        AND INDEX_NAME = 'fk_skit_grant_session_binding';
    IF `supporting_index_definition` = '1:tenant_id,ad_session_id,member_id,drama_id,episode_no,provider_transaction_id' THEN
      ALTER TABLE `skit_entitlement_grant` DROP INDEX `fk_skit_grant_session_binding`;
    ELSEIF `supporting_index_definition` IS NOT NULL AND `supporting_index_definition` <> '1:tenant_id,ad_session_id,member_id,drama_id,provider_transaction_id' THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'incompatible entitlement grant supporting index';
    END IF;
    ALTER TABLE `skit_entitlement_grant`
      ADD CONSTRAINT `fk_skit_grant_session_binding`
      FOREIGN KEY (`tenant_id`,`ad_session_id`,`member_id`,`drama_id`,`provider_transaction_id`)
      REFERENCES `skit_ad_session` (`tenant_id`,`id`,`member_id`,`drama_id`,`provider_transaction_id`)
      ON UPDATE RESTRICT ON DELETE RESTRICT;
  END IF;
END$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_content_entitlement','lease_activated_at',
  'ALTER TABLE `skit_content_entitlement` ADD COLUMN `lease_activated_at` datetime DEFAULT NULL AFTER `granted_at`')$$
UPDATE `skit_content_entitlement`
  SET `lease_activated_at`=`granted_at` WHERE `lease_activated_at` IS NULL$$
ALTER TABLE `skit_content_entitlement`
  MODIFY COLUMN `lease_activated_at` datetime NOT NULL$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_session','reward_callback_inbox_id',
  'ALTER TABLE `skit_ad_session` ADD COLUMN `reward_callback_inbox_id` bigint DEFAULT NULL')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_session','reward_callback_received_at',
  'ALTER TABLE `skit_ad_session` ADD COLUMN `reward_callback_received_at` datetime DEFAULT NULL')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_callback_inbox','ingress_response_code',
  'ALTER TABLE `skit_ad_callback_inbox` ADD COLUMN `ingress_response_code` smallint DEFAULT NULL')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_callback_inbox','ad_session_ref_id',
  'ALTER TABLE `skit_ad_callback_inbox` ADD COLUMN `ad_session_ref_id` bigint GENERATED ALWAYS AS (IFNULL(`ad_session_id`,0)) STORED')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_callback_attempt','ad_session_ref_id',
  'ALTER TABLE `skit_ad_callback_attempt` ADD COLUMN `ad_session_ref_id` bigint GENERATED ALWAYS AS (IFNULL(`ad_session_id`,0)) STORED')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_revenue_event','ad_session_ref_id',
  'ALTER TABLE `skit_ad_revenue_event` ADD COLUMN `ad_session_ref_id` bigint GENERATED ALWAYS AS (IFNULL(`ad_session_id`,0)) STORED')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_session','uk_skit_ad_session_account_binding',
  'ALTER TABLE `skit_ad_session` ADD UNIQUE INDEX `uk_skit_ad_session_account_binding` (`tenant_id`,`id`,`ad_account_id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_session','uk_skit_ad_session_revenue_binding',
  'ALTER TABLE `skit_ad_session` ADD UNIQUE INDEX `uk_skit_ad_session_revenue_binding` (`tenant_id`,`id`,`ad_account_id`,`member_id`,`policy_snapshot_id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_session','uk_skit_ad_session_grant_envelope',
  'ALTER TABLE `skit_ad_session` ADD UNIQUE INDEX `uk_skit_ad_session_grant_envelope` (`tenant_id`,`id`,`member_id`,`drama_id`,`provider_transaction_id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_callback_inbox','uk_skit_callback_inbox_attempt_binding',
  'ALTER TABLE `skit_ad_callback_inbox` ADD UNIQUE INDEX `uk_skit_callback_inbox_attempt_binding` (`tenant_id`,`id`,`ad_account_id`,`ad_session_ref_id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_revenue_event','uk_skit_revenue_event_snapshot_binding',
  'ALTER TABLE `skit_ad_revenue_event` ADD UNIQUE INDEX `uk_skit_revenue_event_snapshot_binding` (`tenant_id`,`id`,`policy_snapshot_id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_callback_inbox','idx_skit_callback_inbox_payload_expiry',
  'ALTER TABLE `skit_ad_callback_inbox` ADD INDEX `idx_skit_callback_inbox_payload_expiry` (`payload_expires_at`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_callback_attempt','idx_skit_callback_attempt_retention',
  'ALTER TABLE `skit_ad_callback_attempt` ADD INDEX `idx_skit_callback_attempt_retention` (`received_at`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_callback_edge_attempt','idx_skit_callback_edge_retention',
  'ALTER TABLE `skit_ad_callback_edge_attempt` ADD INDEX `idx_skit_callback_edge_retention` (`received_at`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_network_capability','idx_skit_network_cap_readiness',
  'ALTER TABLE `skit_ad_network_capability` ADD INDEX `idx_skit_network_cap_readiness` (`tenant_id`,`ad_account_id`,`network_firm_id`,`enabled`,`reward_authority`,`verified_at`)')$$
CALL `skit_replace_grant_session_binding`()$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_callback_inbox','fk_skit_callback_inbox_session_account',
  'ALTER TABLE `skit_ad_callback_inbox` ADD CONSTRAINT `fk_skit_callback_inbox_session_account` FOREIGN KEY (`tenant_id`,`ad_session_id`,`ad_account_id`) REFERENCES `skit_ad_session` (`tenant_id`,`id`,`ad_account_id`) ON UPDATE RESTRICT ON DELETE RESTRICT')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_callback_attempt','fk_skit_callback_attempt_inbox_binding',
  'ALTER TABLE `skit_ad_callback_attempt` ADD CONSTRAINT `fk_skit_callback_attempt_inbox_binding` FOREIGN KEY (`tenant_id`,`callback_inbox_id`,`ad_account_id`,`ad_session_ref_id`) REFERENCES `skit_ad_callback_inbox` (`tenant_id`,`id`,`ad_account_id`,`ad_session_ref_id`) ON UPDATE RESTRICT ON DELETE RESTRICT')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_session','fk_skit_ad_session_reward_callback_receipt',
  'ALTER TABLE `skit_ad_session` ADD CONSTRAINT `fk_skit_ad_session_reward_callback_receipt` FOREIGN KEY (`tenant_id`,`reward_callback_inbox_id`,`ad_account_id`,`id`) REFERENCES `skit_ad_callback_inbox` (`tenant_id`,`id`,`ad_account_id`,`ad_session_ref_id`) ON UPDATE RESTRICT ON DELETE RESTRICT')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_revenue_event','fk_skit_revenue_session_binding',
  'ALTER TABLE `skit_ad_revenue_event` ADD CONSTRAINT `fk_skit_revenue_session_binding` FOREIGN KEY (`tenant_id`,`ad_session_id`,`ad_account_id`,`source_member_id`,`policy_snapshot_id`) REFERENCES `skit_ad_session` (`tenant_id`,`id`,`ad_account_id`,`member_id`,`policy_snapshot_id`) ON UPDATE RESTRICT ON DELETE RESTRICT')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_revenue_event','fk_skit_revenue_inbox_binding',
  'ALTER TABLE `skit_ad_revenue_event` ADD CONSTRAINT `fk_skit_revenue_inbox_binding` FOREIGN KEY (`tenant_id`,`callback_inbox_id`,`ad_account_id`,`ad_session_ref_id`) REFERENCES `skit_ad_callback_inbox` (`tenant_id`,`id`,`ad_account_id`,`ad_session_ref_id`) ON UPDATE RESTRICT ON DELETE RESTRICT')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_commission_ledger','fk_skit_ledger_event_snapshot',
  'ALTER TABLE `skit_commission_ledger` ADD CONSTRAINT `fk_skit_ledger_event_snapshot` FOREIGN KEY (`tenant_id`,`event_id`,`policy_snapshot_id`) REFERENCES `skit_ad_revenue_event` (`tenant_id`,`id`,`policy_snapshot_id`) ON UPDATE RESTRICT ON DELETE RESTRICT')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_session','ck_skit_ad_session_reward_callback_receipt',
  'ALTER TABLE `skit_ad_session` ADD CONSTRAINT `ck_skit_ad_session_reward_callback_receipt` CHECK ((`reward_callback_inbox_id` IS NULL AND `reward_callback_received_at` IS NULL) OR (`reward_callback_inbox_id` IS NOT NULL AND `reward_callback_received_at` IS NOT NULL AND `reward_callback_received_at` <= `reward_accept_until`))')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_callback_inbox','ck_skit_callback_inbox_response_code',
  'ALTER TABLE `skit_ad_callback_inbox` ADD CONSTRAINT `ck_skit_callback_inbox_response_code` CHECK (`ingress_response_code` IS NULL OR `ingress_response_code` IN (200,601,602))')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_revenue_event','ck_skit_revenue_verified_binding',
  'ALTER TABLE `skit_ad_revenue_event` ADD CONSTRAINT `ck_skit_revenue_verified_binding` CHECK (`legacy_unverified`=b''1'' OR (`ad_session_id` IS NOT NULL AND `callback_inbox_id` IS NOT NULL AND `policy_snapshot_id` IS NOT NULL))')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_commission_ledger','ck_skit_ledger_verified_snapshot',
  'ALTER TABLE `skit_commission_ledger` ADD CONSTRAINT `ck_skit_ledger_verified_snapshot` CHECK (`legacy_unverified`=b''1'' OR `policy_snapshot_id` IS NOT NULL)')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_network_capability','ck_skit_network_cap_reward_authority',
  'ALTER TABLE `skit_ad_network_capability` ADD CONSTRAINT `ck_skit_network_cap_reward_authority` CHECK (`reward_authority` IN (''SIGNED_REWARD'',''UNSIGNED_PROVIDER_OBSERVATION'',''CLIENT_ONLY'',''NONE''))')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_network_capability','ck_skit_network_cap_signed_readiness',
  'ALTER TABLE `skit_ad_network_capability` ADD CONSTRAINT `ck_skit_network_cap_signed_readiness` CHECK ((`reward_authority`<>''SIGNED_REWARD'') OR ((`supports_user_id`=b''1'') AND (`supports_custom_data`=b''1'') AND (`supports_stable_transaction`=b''1'') AND `verified_at` IS NOT NULL))')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_callback_inbox','ck_skit_callback_inbox_processing_error',
  'ALTER TABLE `skit_ad_callback_inbox` ADD CONSTRAINT `ck_skit_callback_inbox_processing_error` CHECK ((`processing_status` IN (''PENDING'',''PROCESSING'',''SUCCEEDED'') AND (`error_code` IS NULL)) OR (`processing_status` IN (''RETRY_WAIT'',''REJECTED'',''DEAD_LETTER'') AND (`error_code` IS NOT NULL)))')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_callback_inbox','ck_skit_callback_inbox_dead_letter_alert',
  'ALTER TABLE `skit_ad_callback_inbox` ADD CONSTRAINT `ck_skit_callback_inbox_dead_letter_alert` CHECK (`dead_letter_alerted_at` IS NULL OR (`processing_status`=''DEAD_LETTER'' AND `processed_at` IS NOT NULL AND `dead_letter_alerted_at`>=`processed_at`))')$$
CREATE TABLE IF NOT EXISTS `skit_ad_reporting_credential_version` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL,
  `ad_account_id` bigint NOT NULL, `credential_version` int NOT NULL,
  `ciphertext` varbinary(4096) NOT NULL, `nonce` binary(12) NOT NULL,
  `encryption_key_id` varchar(64) NOT NULL, `envelope_version` smallint NOT NULL,
  `active` bit(1) NOT NULL DEFAULT b'1', `permission_verified_at` datetime DEFAULT NULL,
  `revoked_at` datetime DEFAULT NULL,
  `active_account_id` bigint GENERATED ALWAYS AS
    (CASE WHEN `active`=b'1' THEN `ad_account_id` ELSE NULL END) STORED,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_reporting_credential_tenant_id` (`tenant_id`,`id`),
  UNIQUE KEY `uk_skit_reporting_credential_version` (`tenant_id`,`ad_account_id`,`credential_version`),
  UNIQUE KEY `uk_skit_reporting_credential_active` (`tenant_id`,`active_account_id`),
  CONSTRAINT `fk_skit_reporting_credential_account` FOREIGN KEY (`tenant_id`,`ad_account_id`)
    REFERENCES `skit_ad_account` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `ck_skit_reporting_credential_version` CHECK (`credential_version` > 0),
  CONSTRAINT `ck_skit_reporting_credential_envelope` CHECK (`envelope_version` > 0),
  CONSTRAINT `ck_skit_reporting_credential_lifecycle` CHECK
    ((`active`=b'1' AND `revoked_at` IS NULL) OR (`active`=b'0' AND `revoked_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_account','report_timezone',
  'ALTER TABLE `skit_ad_account` ADD COLUMN `report_timezone` varchar(64) NOT NULL DEFAULT ''UTC+8''')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_account','report_currency',
  'ALTER TABLE `skit_ad_account` ADD COLUMN `report_currency` char(3) NOT NULL DEFAULT ''USD''')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_account','report_amount_scale',
  'ALTER TABLE `skit_ad_account` ADD COLUMN `report_amount_scale` tinyint NOT NULL DEFAULT 8')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_account','report_pull_lease_owner',
  'ALTER TABLE `skit_ad_account` ADD COLUMN `report_pull_lease_owner` varchar(64) DEFAULT NULL')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_account','report_pull_lease_until',
  'ALTER TABLE `skit_ad_account` ADD COLUMN `report_pull_lease_until` datetime DEFAULT NULL')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_account','report_next_allowed_at',
  'ALTER TABLE `skit_ad_account` ADD COLUMN `report_next_allowed_at` datetime DEFAULT NULL')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_account','report_last_success_at',
  'ALTER TABLE `skit_ad_account` ADD COLUMN `report_last_success_at` datetime DEFAULT NULL')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_account','report_failure_count',
  'ALTER TABLE `skit_ad_account` ADD COLUMN `report_failure_count` int NOT NULL DEFAULT 0')$$
UPDATE `skit_ad_account` SET `config_data`=JSON_SET(`config_data`,'$.adFormat','rewarded_video')
  WHERE `provider`='TAKU' AND JSON_VALID(`config_data`)
    AND JSON_UNQUOTE(JSON_EXTRACT(`config_data`,'$.adFormat')) IS NULL$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_report_pull','report_date',
  'ALTER TABLE `skit_ad_report_pull` ADD COLUMN `report_date` date DEFAULT NULL')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_report_pull','request_hash',
  'ALTER TABLE `skit_ad_report_pull` ADD COLUMN `request_hash` binary(32) DEFAULT NULL')$$
UPDATE `skit_ad_report_pull` SET `report_date`=DATE(`range_start`) WHERE `report_date` IS NULL$$
UPDATE `skit_ad_report_pull` SET `request_hash`=`response_hash` WHERE `request_hash` IS NULL$$
ALTER TABLE `skit_ad_report_pull` MODIFY COLUMN `report_date` date NOT NULL,
  MODIFY COLUMN `request_hash` binary(32) NOT NULL$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_report_pull','report_timezone',
  'ALTER TABLE `skit_ad_report_pull` ADD COLUMN `report_timezone` varchar(64) NOT NULL DEFAULT ''UTC+8''')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_report_pull','currency',
  'ALTER TABLE `skit_ad_report_pull` ADD COLUMN `currency` char(3) NOT NULL DEFAULT ''USD''')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_report_pull','amount_scale',
  'ALTER TABLE `skit_ad_report_pull` ADD COLUMN `amount_scale` tinyint NOT NULL DEFAULT 8')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_report_pull','credential_version',
  'ALTER TABLE `skit_ad_report_pull` ADD COLUMN `credential_version` int DEFAULT NULL')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_report_pull','final_window',
  'ALTER TABLE `skit_ad_report_pull` ADD COLUMN `final_window` bit(1) NOT NULL DEFAULT b''0''')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_reconciliation_bucket','app_id',
  'ALTER TABLE `skit_ad_reconciliation_bucket` ADD COLUMN `app_id` varchar(128) NOT NULL DEFAULT ''''')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_reconciliation_bucket','ad_format',
  'ALTER TABLE `skit_ad_reconciliation_bucket` ADD COLUMN `ad_format` varchar(32) NOT NULL DEFAULT ''''')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_reconciliation_bucket','network_account_id',
  'ALTER TABLE `skit_ad_reconciliation_bucket` ADD COLUMN `network_account_id` varchar(128) NOT NULL DEFAULT ''''')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_reconciliation_bucket','attributable_actual_units',
  'ALTER TABLE `skit_ad_reconciliation_bucket` ADD COLUMN `attributable_actual_units` bigint NOT NULL DEFAULT 0')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_reconciliation_bucket','suspense_units',
  'ALTER TABLE `skit_ad_reconciliation_bucket` ADD COLUMN `suspense_units` bigint NOT NULL DEFAULT 0')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_reconciliation_bucket','report_impressions_available',
  'ALTER TABLE `skit_ad_reconciliation_bucket` ADD COLUMN `report_impressions_available` bit(1) NOT NULL DEFAULT b''1''')$$
UPDATE `skit_ad_reconciliation_bucket` SET `attributable_actual_units`=0,
  `suspense_units`=`report_actual_units`
  WHERE `attributable_actual_units` + `suspense_units` <> `report_actual_units`$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_reconciliation_revision','source_report_impressions',
  'ALTER TABLE `skit_ad_reconciliation_revision` ADD COLUMN `source_report_impressions` bigint NOT NULL DEFAULT 0')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_reconciliation_revision','source_report_impressions_available',
  'ALTER TABLE `skit_ad_reconciliation_revision` ADD COLUMN `source_report_impressions_available` bit(1) NOT NULL DEFAULT b''1''')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_reconciliation_revision','matched_event_count',
  'ALTER TABLE `skit_ad_reconciliation_revision` ADD COLUMN `matched_event_count` bigint NOT NULL DEFAULT 0')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_ad_reconciliation_revision','status',
  'ALTER TABLE `skit_ad_reconciliation_revision` ADD COLUMN `status` varchar(32) NOT NULL DEFAULT ''APPLIED''')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_account','idx_skit_ad_account_report_due',
  'ALTER TABLE `skit_ad_account` ADD INDEX `idx_skit_ad_account_report_due` (`provider`,`status`,`report_next_allowed_at`,`report_pull_lease_until`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_report_pull','idx_skit_report_pull_request',
  'ALTER TABLE `skit_ad_report_pull` ADD INDEX `idx_skit_report_pull_request` (`tenant_id`,`ad_account_id`,`report_date`,`request_hash`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_report_pull','idx_skit_report_pull_credential',
  'ALTER TABLE `skit_ad_report_pull` ADD INDEX `idx_skit_report_pull_credential` (`tenant_id`,`ad_account_id`,`credential_version`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_report_pull','idx_skit_report_pull_final_window',
  'ALTER TABLE `skit_ad_report_pull` ADD INDEX `idx_skit_report_pull_final_window` (`tenant_id`,`ad_account_id`,`report_date`,`final_window`,`status`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_revenue_event','idx_skit_revenue_report_pending',
  'ALTER TABLE `skit_ad_revenue_event` ADD INDEX `idx_skit_revenue_report_pending` (`tenant_id`,`ad_account_id`,`reconciliation_revision_id`,`occurred_time`,`id`)')$$
ALTER TABLE `skit_ad_report_pull`
  DROP INDEX `uk_skit_report_pull_response`,
  ADD UNIQUE INDEX `uk_skit_report_pull_response`
    (`tenant_id`,`ad_account_id`,`range_start`,`range_end`,`request_hash`,`response_hash`,`credential_version`,`final_window`)$$
ALTER TABLE `skit_ad_reconciliation_bucket`
  DROP INDEX `uk_skit_recon_bucket_identity`,
  ADD UNIQUE INDEX `uk_skit_recon_bucket_identity`
    (`tenant_id`,`ad_account_id`,`bucket_key`,`report_date`,`report_timezone`,`app_id`,`placement_id`,
     `ad_format`,`network_account_id`,`network_firm_id`,`adsource_id`,`currency`)$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_report_pull','fk_skit_report_pull_credential',
  'ALTER TABLE `skit_ad_report_pull` ADD CONSTRAINT `fk_skit_report_pull_credential` FOREIGN KEY (`tenant_id`,`ad_account_id`,`credential_version`) REFERENCES `skit_ad_reporting_credential_version` (`tenant_id`,`ad_account_id`,`credential_version`) ON UPDATE RESTRICT ON DELETE RESTRICT')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_account','ck_skit_ad_account_report_currency',
  'ALTER TABLE `skit_ad_account` ADD CONSTRAINT `ck_skit_ad_account_report_currency` CHECK (REGEXP_LIKE(`report_currency`,''^[A-Z]{3}$''))')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_account','ck_skit_ad_account_report_timezone',
  'ALTER TABLE `skit_ad_account` ADD CONSTRAINT `ck_skit_ad_account_report_timezone` CHECK (`report_timezone` IN (''UTC-8'',''UTC+8'',''UTC+0''))')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_account','ck_skit_ad_account_report_scale',
  'ALTER TABLE `skit_ad_account` ADD CONSTRAINT `ck_skit_ad_account_report_scale` CHECK (`report_amount_scale` BETWEEN 0 AND 18)')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_account','ck_skit_ad_account_report_lease',
  'ALTER TABLE `skit_ad_account` ADD CONSTRAINT `ck_skit_ad_account_report_lease` CHECK ((`report_pull_lease_owner` IS NULL AND `report_pull_lease_until` IS NULL) OR (`report_pull_lease_owner` IS NOT NULL AND `report_pull_lease_until` IS NOT NULL))')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_account','ck_skit_ad_account_report_failures',
  'ALTER TABLE `skit_ad_account` ADD CONSTRAINT `ck_skit_ad_account_report_failures` CHECK (`report_failure_count` BETWEEN 0 AND 5)')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_report_pull','ck_skit_report_pull_money',
  'ALTER TABLE `skit_ad_report_pull` ADD CONSTRAINT `ck_skit_report_pull_money` CHECK (REGEXP_LIKE(`currency`,''^[A-Z]{3}$'') AND `amount_scale` BETWEEN 0 AND 18)')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_report_pull','ck_skit_report_pull_timezone',
  'ALTER TABLE `skit_ad_report_pull` ADD CONSTRAINT `ck_skit_report_pull_timezone` CHECK (`report_timezone` IN (''UTC-8'',''UTC+8'',''UTC+0''))')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_report_pull','ck_skit_report_pull_credential_version',
  'ALTER TABLE `skit_ad_report_pull` ADD CONSTRAINT `ck_skit_report_pull_credential_version` CHECK (`credential_version` IS NULL OR `credential_version` > 0)')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_report_pull','ck_skit_report_pull_status',
  'ALTER TABLE `skit_ad_report_pull` ADD CONSTRAINT `ck_skit_report_pull_status` CHECK ((`status`=''SUCCEEDED'' AND `error_code` IS NULL) OR (`status`=''FAILED'' AND `error_code` IS NOT NULL))')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_reconciliation_bucket','ck_skit_recon_bucket_task10_amounts',
  'ALTER TABLE `skit_ad_reconciliation_bucket` ADD CONSTRAINT `ck_skit_recon_bucket_task10_amounts` CHECK (`attributable_actual_units` >= 0 AND `suspense_units` >= 0 AND `attributable_actual_units` + `suspense_units` = `report_actual_units`)')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_reconciliation_bucket','ck_skit_recon_bucket_task10_impressions',
  'ALTER TABLE `skit_ad_reconciliation_bucket` ADD CONSTRAINT `ck_skit_recon_bucket_task10_impressions` CHECK (`report_impressions_available`=b''1'' OR `report_impressions`=0)')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_reconciliation_revision','ck_skit_recon_revision_task10_counts',
  'ALTER TABLE `skit_ad_reconciliation_revision` ADD CONSTRAINT `ck_skit_recon_revision_task10_counts` CHECK (`source_report_impressions` >= 0 AND `matched_event_count` >= 0)')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_reconciliation_revision','ck_skit_recon_revision_task10_impressions',
  'ALTER TABLE `skit_ad_reconciliation_revision` ADD CONSTRAINT `ck_skit_recon_revision_task10_impressions` CHECK (`source_report_impressions_available`=b''1'' OR `source_report_impressions`=0)')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_ad_reconciliation_revision','ck_skit_recon_revision_task10_status',
  'ALTER TABLE `skit_ad_reconciliation_revision` ADD CONSTRAINT `ck_skit_recon_revision_task10_status` CHECK (`status` IN (''APPLIED'',''PARTIAL'',''SUSPENSE'',''FAILED''))')$$
CREATE TABLE IF NOT EXISTS `skit_ad_reconciliation_allocation` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL,
  `reconciliation_bucket_id` bigint NOT NULL, `reconciliation_revision_id` bigint NOT NULL,
  `revision_no` int NOT NULL, `event_id` bigint NOT NULL,
  `beneficiary_type` tinyint NOT NULL, `beneficiary_member_id` bigint NOT NULL DEFAULT 0,
  `level_no` int NOT NULL, `policy_snapshot_id` bigint NOT NULL,
  `currency` char(3) NOT NULL, `amount_scale` tinyint NOT NULL,
  `cumulative_target_units` bigint NOT NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_recon_allocation_tenant_id` (`tenant_id`,`id`),
  UNIQUE KEY `uk_skit_recon_allocation_canonical`
    (`tenant_id`,`event_id`,`reconciliation_revision_id`,`beneficiary_type`,`beneficiary_member_id`,`level_no`,`policy_snapshot_id`),
  KEY `idx_skit_recon_allocation_prior`
    (`tenant_id`,`event_id`,`beneficiary_type`,`beneficiary_member_id`,`level_no`,`revision_no`),
  CONSTRAINT `fk_skit_recon_allocation_bucket` FOREIGN KEY (`tenant_id`,`reconciliation_bucket_id`)
    REFERENCES `skit_ad_reconciliation_bucket` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `fk_skit_recon_allocation_revision` FOREIGN KEY (`tenant_id`,`reconciliation_revision_id`)
    REFERENCES `skit_ad_reconciliation_revision` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `fk_skit_recon_allocation_event_snapshot` FOREIGN KEY (`tenant_id`,`event_id`,`policy_snapshot_id`)
    REFERENCES `skit_ad_revenue_event` (`tenant_id`,`id`,`policy_snapshot_id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `ck_skit_recon_allocation_revision` CHECK (`revision_no` > 0),
  CONSTRAINT `ck_skit_recon_allocation_beneficiary` CHECK
    ((`beneficiary_type`=1 AND `beneficiary_member_id`>0) OR (`beneficiary_type`=2 AND `beneficiary_member_id`=0)),
  CONSTRAINT `ck_skit_recon_allocation_money` CHECK
    (REGEXP_LIKE(`currency`,'^[A-Z]{3}$') AND `amount_scale` BETWEEN 0 AND 18 AND `cumulative_target_units` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci$$
CREATE TABLE IF NOT EXISTS `skit_ad_reconciliation_event_link` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL,
  `reconciliation_bucket_id` bigint NOT NULL, `reconciliation_revision_id` bigint NOT NULL,
  `revision_no` int NOT NULL, `event_id` bigint NOT NULL, `policy_snapshot_id` bigint NOT NULL,
  `association_status` varchar(16) NOT NULL, `actual_units` bigint NOT NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_recon_event_link_tenant_id` (`tenant_id`,`id`),
  UNIQUE KEY `uk_skit_recon_event_link_canonical` (`tenant_id`,`reconciliation_revision_id`,`event_id`),
  KEY `idx_skit_recon_event_link_history` (`tenant_id`,`event_id`,`revision_no`,`id`),
  CONSTRAINT `fk_skit_recon_event_link_bucket` FOREIGN KEY (`tenant_id`,`reconciliation_bucket_id`)
    REFERENCES `skit_ad_reconciliation_bucket` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `fk_skit_recon_event_link_revision` FOREIGN KEY (`tenant_id`,`reconciliation_revision_id`)
    REFERENCES `skit_ad_reconciliation_revision` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `fk_skit_recon_event_link_event_snapshot` FOREIGN KEY (`tenant_id`,`event_id`,`policy_snapshot_id`)
    REFERENCES `skit_ad_revenue_event` (`tenant_id`,`id`,`policy_snapshot_id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `ck_skit_recon_event_link_revision` CHECK (`revision_no` > 0),
  CONSTRAINT `ck_skit_recon_event_link_status` CHECK (`association_status` IN ('ATTRIBUTED','SUSPENSE')),
  CONSTRAINT `ck_skit_recon_event_link_amount` CHECK
    (`actual_units` >= 0 AND (`association_status`<>'SUSPENSE' OR `actual_units`=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_tenant_ad_capability','dedicated_unlock_placement_id',
  'ALTER TABLE `skit_tenant_ad_capability` ADD COLUMN `dedicated_unlock_placement_id` varchar(128) NOT NULL DEFAULT ''''')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_tenant_ad_capability','dedicated_placement_verified_at',
  'ALTER TABLE `skit_tenant_ad_capability` ADD COLUMN `dedicated_placement_verified_at` datetime DEFAULT NULL')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_tenant_ad_capability','reward_callback_template_verified_at',
  'ALTER TABLE `skit_tenant_ad_capability` ADD COLUMN `reward_callback_template_verified_at` datetime DEFAULT NULL')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_tenant_ad_capability','impression_callback_template_verified_at',
  'ALTER TABLE `skit_tenant_ad_capability` ADD COLUMN `impression_callback_template_verified_at` datetime DEFAULT NULL')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_tenant_ad_capability','unlock_network_firm_ids_json',
  'ALTER TABLE `skit_tenant_ad_capability` ADD COLUMN `unlock_network_firm_ids_json` varchar(512) NOT NULL DEFAULT ''[]''')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_tenant_ad_capability','shadow_test_member_ids_json',
  'ALTER TABLE `skit_tenant_ad_capability` ADD COLUMN `shadow_test_member_ids_json` varchar(4096) NOT NULL DEFAULT ''[]''')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_tenant_ad_capability','min_protocol_version',
  'ALTER TABLE `skit_tenant_ad_capability` ADD COLUMN `min_protocol_version` int NOT NULL DEFAULT 1')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_app_release_profile','native_protocol_version',
  'ALTER TABLE `skit_app_release_profile` ADD COLUMN `native_protocol_version` int NOT NULL DEFAULT 0')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_tenant_ad_capability','ck_skit_tenant_capability_network_json',
  'ALTER TABLE `skit_tenant_ad_capability` ADD CONSTRAINT `ck_skit_tenant_capability_network_json` CHECK (JSON_VALID(`unlock_network_firm_ids_json`) AND JSON_TYPE(JSON_EXTRACT(`unlock_network_firm_ids_json`,''$''))=''ARRAY'')')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_tenant_ad_capability','ck_skit_tenant_capability_shadow_json',
  'ALTER TABLE `skit_tenant_ad_capability` ADD CONSTRAINT `ck_skit_tenant_capability_shadow_json` CHECK (JSON_VALID(`shadow_test_member_ids_json`) AND JSON_TYPE(JSON_EXTRACT(`shadow_test_member_ids_json`,''$''))=''ARRAY'')')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_tenant_ad_capability','ck_skit_tenant_capability_protocol',
  'ALTER TABLE `skit_tenant_ad_capability` ADD CONSTRAINT `ck_skit_tenant_capability_protocol` CHECK (`min_protocol_version` > 0)')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_app_release_profile','ck_skit_app_release_native_protocol',
  'ALTER TABLE `skit_app_release_profile` ADD CONSTRAINT `ck_skit_app_release_native_protocol` CHECK (`native_protocol_version` >= 0)')$$

CREATE TABLE IF NOT EXISTS `skit_management_command_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL,
  `command_id` varchar(64) NOT NULL, `operator_user_id` bigint NOT NULL,
  `original_tenant_id` bigint NOT NULL, `target_tenant_id` bigint NOT NULL,
  `command_type` varchar(64) NOT NULL, `resource_type` varchar(64) NOT NULL,
  `resource_id` varchar(128) NOT NULL, `reason` varchar(500) NOT NULL,
  `before_state_hash` binary(32) NOT NULL, `after_state_hash` binary(32) NOT NULL,
  `request_fingerprint` binary(32) NOT NULL, `trace_id` varchar(128) NOT NULL DEFAULT '',
  `result_status` varchar(16) NOT NULL, `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_management_audit_tenant_id` (`tenant_id`,`id`),
  UNIQUE KEY `uk_skit_management_audit_command` (`command_id`),
  KEY `idx_skit_management_audit_time` (`tenant_id`,`created_at`,`id`),
  KEY `idx_skit_management_audit_type_time` (`tenant_id`,`command_type`,`created_at`,`id`),
  KEY `idx_skit_management_audit_target_time` (`target_tenant_id`,`created_at`,`id`),
  CONSTRAINT `ck_skit_management_audit_target` CHECK (`tenant_id`=`target_tenant_id`),
  CONSTRAINT `ck_skit_management_audit_result` CHECK (`result_status`='SUCCESS'),
  CONSTRAINT `ck_skit_management_audit_reason` CHECK
    (CHAR_LENGTH(TRIM(`reason`)) BETWEEN 10 AND 500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci$$
CREATE TABLE IF NOT EXISTS `skit_ad_callback_replay_command` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL,
  `command_id` varchar(64) NOT NULL, `callback_inbox_id` bigint NOT NULL,
  `operator_user_id` bigint NOT NULL, `original_tenant_id` bigint NOT NULL,
  `source_status` varchar(32) NOT NULL, `reason` varchar(500) NOT NULL,
  `request_fingerprint` binary(32) NOT NULL, `requested_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_callback_replay_tenant_id` (`tenant_id`,`id`),
  UNIQUE KEY `uk_skit_callback_replay_command` (`command_id`),
  UNIQUE KEY `uk_skit_callback_replay_idempotency` (`tenant_id`,`callback_inbox_id`,`request_fingerprint`),
  KEY `idx_skit_callback_replay_time` (`tenant_id`,`requested_at`,`id`),
  CONSTRAINT `fk_skit_callback_replay_inbox` FOREIGN KEY (`tenant_id`,`callback_inbox_id`)
    REFERENCES `skit_ad_callback_inbox` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `ck_skit_callback_replay_source` CHECK (`source_status` IN ('DEAD_LETTER','REJECTED')),
  CONSTRAINT `ck_skit_callback_replay_reason` CHECK
    (CHAR_LENGTH(TRIM(`reason`)) BETWEEN 10 AND 500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci$$
CREATE TABLE IF NOT EXISTS `skit_entitlement_security_revocation` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL,
  `command_id` varchar(64) NOT NULL, `entitlement_id` bigint NOT NULL,
  `ad_session_id` bigint NOT NULL, `operator_user_id` bigint NOT NULL,
  `original_tenant_id` bigint NOT NULL, `reason` varchar(500) NOT NULL,
  `evidence_hash` binary(32) NOT NULL, `revoked_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_security_revocation_tenant_id` (`tenant_id`,`id`),
  UNIQUE KEY `uk_skit_security_revocation_command` (`command_id`),
  UNIQUE KEY `uk_skit_security_revocation_entitlement` (`tenant_id`,`entitlement_id`),
  KEY `idx_skit_security_revocation_time` (`tenant_id`,`revoked_at`,`id`),
  CONSTRAINT `fk_skit_security_revocation_entitlement` FOREIGN KEY (`tenant_id`,`entitlement_id`)
    REFERENCES `skit_content_entitlement` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `fk_skit_security_revocation_session` FOREIGN KEY (`tenant_id`,`ad_session_id`)
    REFERENCES `skit_ad_session` (`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT `ck_skit_security_revocation_reason` CHECK
    (CHAR_LENGTH(TRIM(`reason`)) BETWEEN 10 AND 500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci$$
CREATE TABLE IF NOT EXISTS `skit_management_export_task` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL,
  `operator_user_id` bigint NOT NULL, `original_tenant_id` bigint NOT NULL,
  `target_tenant_id` bigint NOT NULL, `export_type` varchar(64) NOT NULL,
  `filter_json` longtext NOT NULL, `filter_hash` binary(32) NOT NULL,
  `field_mask_profile` varchar(32) NOT NULL, `as_of` datetime NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING',
  `processed_rows` bigint NOT NULL DEFAULT 0, `total_rows` bigint DEFAULT NULL,
  `file_object_key` varchar(500) NOT NULL DEFAULT '', `expires_at` datetime NOT NULL,
  `error_code` varchar(64) NOT NULL DEFAULT '', `lease_owner` varchar(64) DEFAULT NULL,
  `lease_until` datetime DEFAULT NULL, `version` int NOT NULL DEFAULT 0,
  `created_at` datetime NOT NULL, `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_management_export_tenant_id` (`tenant_id`,`id`),
  KEY `idx_skit_management_export_operator` (`tenant_id`,`operator_user_id`,`created_at`,`id`),
  KEY `idx_skit_management_export_claim` (`status`,`lease_until`,`created_at`,`id`),
  KEY `idx_skit_management_export_expiry` (`expires_at`,`id`),
  CONSTRAINT `ck_skit_management_export_target` CHECK (`tenant_id`=`target_tenant_id`),
  CONSTRAINT `ck_skit_management_export_filter` CHECK (JSON_VALID(`filter_json`)),
  CONSTRAINT `ck_skit_management_export_mask` CHECK
    (`field_mask_profile` IN ('TENANT_MASKED','PLATFORM_AUDIT_MASKED')),
  CONSTRAINT `ck_skit_management_export_status` CHECK
    (`status` IN ('PENDING','RUNNING','SUCCEEDED','FAILED','EXPIRED')),
  CONSTRAINT `ck_skit_management_export_progress` CHECK
    (`processed_rows`>=0 AND (`total_rows` IS NULL OR `total_rows`>=`processed_rows`)),
  CONSTRAINT `ck_skit_management_export_lease` CHECK
    ((`lease_owner` IS NULL AND `lease_until` IS NULL) OR
     (`lease_owner` IS NOT NULL AND `lease_until` IS NOT NULL)),
  CONSTRAINT `ck_skit_management_export_version` CHECK (`version`>=0),
  CONSTRAINT `ck_skit_management_export_expiry_window` CHECK (`expires_at`>`as_of`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_session','idx_skit_ad_session_management_account',
  'ALTER TABLE `skit_ad_session` ADD INDEX `idx_skit_ad_session_management_account` (`tenant_id`,`ad_account_id`,`create_time`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_session','idx_skit_ad_session_management_reward',
  'ALTER TABLE `skit_ad_session` ADD INDEX `idx_skit_ad_session_management_reward` (`tenant_id`,`reward_verification_status`,`create_time`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_session','idx_skit_ad_session_consumption_time',
  'ALTER TABLE `skit_ad_session` ADD INDEX `idx_skit_ad_session_consumption_time` (`tenant_id`,`create_time`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_session','idx_skit_ad_session_global_consumption_time',
  'ALTER TABLE `skit_ad_session` ADD INDEX `idx_skit_ad_session_global_consumption_time` (`create_time`,`id`,`tenant_id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_callback_inbox','idx_skit_callback_inbox_management_account',
  'ALTER TABLE `skit_ad_callback_inbox` ADD INDEX `idx_skit_callback_inbox_management_account` (`tenant_id`,`ad_account_id`,`received_at`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_callback_inbox','idx_skit_callback_inbox_management_status',
  'ALTER TABLE `skit_ad_callback_inbox` ADD INDEX `idx_skit_callback_inbox_management_status` (`tenant_id`,`processing_status`,`received_at`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_callback_inbox','idx_skit_callback_inbox_consumption_session',
  'ALTER TABLE `skit_ad_callback_inbox` ADD INDEX `idx_skit_callback_inbox_consumption_session` (`tenant_id`,`ad_session_id`,`received_at`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_revenue_event','idx_skit_revenue_management_time',
  'ALTER TABLE `skit_ad_revenue_event` ADD INDEX `idx_skit_revenue_management_time` (`tenant_id`,`occurred_time`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_revenue_event','idx_skit_revenue_management_member',
  'ALTER TABLE `skit_ad_revenue_event` ADD INDEX `idx_skit_revenue_management_member` (`tenant_id`,`source_member_id`,`occurred_time`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_revenue_event','idx_skit_revenue_management_reconciliation',
  'ALTER TABLE `skit_ad_revenue_event` ADD INDEX `idx_skit_revenue_management_reconciliation` (`tenant_id`,`reconciliation_status`,`source_currency`,`occurred_time`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_commission_ledger','idx_skit_ledger_management_balance',
  'ALTER TABLE `skit_commission_ledger` ADD INDEX `idx_skit_ledger_management_balance` (`tenant_id`,`currency`,`balance_bucket`,`create_time`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_commission_ledger','idx_skit_ledger_management_event',
  'ALTER TABLE `skit_commission_ledger` ADD INDEX `idx_skit_ledger_management_event` (`tenant_id`,`event_id`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_report_pull','idx_skit_report_pull_management_account',
  'ALTER TABLE `skit_ad_report_pull` ADD INDEX `idx_skit_report_pull_management_account` (`tenant_id`,`ad_account_id`,`pulled_at`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_report_pull','idx_skit_report_pull_management_status',
  'ALTER TABLE `skit_ad_report_pull` ADD INDEX `idx_skit_report_pull_management_status` (`tenant_id`,`status`,`pulled_at`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_reconciliation_bucket','idx_skit_recon_bucket_management_account',
  'ALTER TABLE `skit_ad_reconciliation_bucket` ADD INDEX `idx_skit_recon_bucket_management_account` (`tenant_id`,`ad_account_id`,`report_date`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_reconciliation_revision','idx_skit_recon_revision_management_bucket',
  'ALTER TABLE `skit_ad_reconciliation_revision` ADD INDEX `idx_skit_recon_revision_management_bucket` (`tenant_id`,`reconciliation_bucket_id`,`revision_no`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_member_closure','idx_skit_member_closure_ancestor_distance',
  'ALTER TABLE `skit_member_closure` ADD INDEX `idx_skit_member_closure_ancestor_distance` (`tenant_id`,`ancestor_id`,`distance`,`descendant_id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_session','idx_skit_ad_session_global_created',
  'ALTER TABLE `skit_ad_session` ADD INDEX `idx_skit_ad_session_global_created` (`create_time`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_revenue_event','idx_skit_ad_revenue_global_occurred',
  'ALTER TABLE `skit_ad_revenue_event` ADD INDEX `idx_skit_ad_revenue_global_occurred` (`occurred_time`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_callback_inbox','idx_skit_ad_callback_global_received',
  'ALTER TABLE `skit_ad_callback_inbox` ADD INDEX `idx_skit_ad_callback_global_received` (`received_at`,`id`)')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_ad_reconciliation_bucket','idx_skit_ad_recon_bucket_global_date',
  'ALTER TABLE `skit_ad_reconciliation_bucket` ADD INDEX `idx_skit_ad_recon_bucket_global_date` (`report_date`,`id`)')$$

CALL `skit_apply_ddl_if_missing`('COLUMN','skit_member','point_balance',
  'ALTER TABLE `skit_member` ADD COLUMN `point_balance` int NOT NULL DEFAULT 0 COMMENT ''当前积分余额'' AFTER `status`')$$

CALL `skit_apply_ddl_if_missing`('COLUMN','skit_app_release_profile','hot_release_no',
  'ALTER TABLE `skit_app_release_profile` ADD COLUMN `hot_release_no` bigint NOT NULL DEFAULT 0')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_app_release_profile','hot_manifest_signature',
  'ALTER TABLE `skit_app_release_profile` ADD COLUMN `hot_manifest_signature` varchar(1024) NOT NULL DEFAULT ''''')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_app_release_profile','ck_skit_app_release_hot_release',
  'ALTER TABLE `skit_app_release_profile` ADD CONSTRAINT `ck_skit_app_release_hot_release` CHECK (`hot_release_no` >= 0)')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_app_release_profile','ck_skit_app_release_hot_signature',
  'ALTER TABLE `skit_app_release_profile` ADD CONSTRAINT `ck_skit_app_release_hot_signature` CHECK ((`hot_release_no`=0 AND `hot_manifest_signature`='''') OR (`hot_release_no`>0 AND `hot_manifest_signature`<>''''))')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_app_release_profile','runtime_update_public_key',
  'ALTER TABLE `skit_app_release_profile` ADD COLUMN `runtime_update_public_key` varchar(4096) NOT NULL DEFAULT ''''')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_app_release_profile','runtime_update_key_fingerprint',
  'ALTER TABLE `skit_app_release_profile` ADD COLUMN `runtime_update_key_fingerprint` char(64) NOT NULL DEFAULT ''''')$$
CALL `skit_apply_ddl_if_missing`('COLUMN','skit_app_release_profile','active_runtime_update_key_fingerprint',
  'ALTER TABLE `skit_app_release_profile` ADD COLUMN `active_runtime_update_key_fingerprint` char(64) GENERATED ALWAYS AS (CASE WHEN `deleted`=b''0'' AND `runtime_update_key_fingerprint`<>'''' THEN `runtime_update_key_fingerprint` ELSE NULL END) STORED')$$
CALL `skit_apply_ddl_if_missing`('CONSTRAINT','skit_app_release_profile','ck_skit_app_release_runtime_key',
  'ALTER TABLE `skit_app_release_profile` ADD CONSTRAINT `ck_skit_app_release_runtime_key` CHECK ((`runtime_update_public_key`='''' AND `runtime_update_key_fingerprint`='''') OR (`runtime_update_public_key`<>'''' AND REGEXP_LIKE(`runtime_update_key_fingerprint`,''^[0-9a-f]{64}$'')))')$$
CALL `skit_apply_ddl_if_missing`('INDEX','skit_app_release_profile','uk_skit_app_release_runtime_key',
  'ALTER TABLE `skit_app_release_profile` ADD UNIQUE INDEX `uk_skit_app_release_runtime_key` (`active_runtime_update_key_fingerprint`)')$$
DROP PROCEDURE `skit_replace_grant_session_binding`$$
DROP PROCEDURE `skit_apply_ddl_if_missing`$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_management_audit_immutable`
BEFORE UPDATE ON `skit_management_command_audit` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'management command audit rows are immutable';
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_management_audit_no_delete`
BEFORE DELETE ON `skit_management_command_audit` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'management command audit rows are immutable';
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_callback_replay_immutable`
BEFORE UPDATE ON `skit_ad_callback_replay_command` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'callback replay command rows are immutable';
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_callback_replay_no_delete`
BEFORE DELETE ON `skit_ad_callback_replay_command` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'callback replay command rows are immutable';
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_security_revocation_immutable`
BEFORE UPDATE ON `skit_entitlement_security_revocation` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'security revocation rows are immutable';
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_security_revocation_no_delete`
BEFORE DELETE ON `skit_entitlement_security_revocation` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'security revocation rows are immutable';
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_revenue_legacy_immutable`
BEFORE UPDATE ON `skit_ad_revenue_event` FOR EACH ROW
BEGIN
  IF OLD.`legacy_unverified` = b'1' AND NOT (
    NEW.`id` <=> OLD.`id`
    AND NEW.`tenant_id` <=> OLD.`tenant_id`
    AND NEW.`ad_account_id` <=> OLD.`ad_account_id`
    AND NEW.`provider` <=> OLD.`provider`
    AND NEW.`placement_id` <=> OLD.`placement_id`
    AND NEW.`external_event_id` <=> OLD.`external_event_id`
    AND NEW.`source_member_id` <=> OLD.`source_member_id`
    AND NEW.`gross_amount` <=> OLD.`gross_amount`
    AND NEW.`occurred_time` <=> OLD.`occurred_time`
    AND NEW.`completed` <=> OLD.`completed`
    AND NEW.`mock` <=> OLD.`mock`
    AND NEW.`status` <=> OLD.`status`
    AND NEW.`rule_version` <=> OLD.`rule_version`
    AND NEW.`raw_data` <=> OLD.`raw_data`
    AND NEW.`ad_session_id` <=> OLD.`ad_session_id`
    AND NEW.`callback_inbox_id` <=> OLD.`callback_inbox_id`
    AND NEW.`policy_snapshot_id` <=> OLD.`policy_snapshot_id`
    AND NEW.`reconciliation_bucket_id` <=> OLD.`reconciliation_bucket_id`
    AND NEW.`reconciliation_revision_id` <=> OLD.`reconciliation_revision_id`
    AND NEW.`source_type` <=> OLD.`source_type`
    AND NEW.`provider_transaction_id` <=> OLD.`provider_transaction_id`
    AND NEW.`provider_show_id` <=> OLD.`provider_show_id`
    AND NEW.`sdk_request_id` <=> OLD.`sdk_request_id`
    AND NEW.`adsource_id` <=> OLD.`adsource_id`
    AND NEW.`source_amount_units` <=> OLD.`source_amount_units`
    AND NEW.`estimated_amount_units` <=> OLD.`estimated_amount_units`
    AND NEW.`reconciled_amount_units` <=> OLD.`reconciled_amount_units`
    AND NEW.`amount_scale` <=> OLD.`amount_scale`
    AND NEW.`source_currency` <=> OLD.`source_currency`
    AND NEW.`match_status` <=> OLD.`match_status`
    AND NEW.`source_verification_status` <=> OLD.`source_verification_status`
    AND NEW.`reward_qualification_status` <=> OLD.`reward_qualification_status`
    AND NEW.`reconciliation_status` <=> OLD.`reconciliation_status`
    AND NEW.`reconciled_at` <=> OLD.`reconciled_at`
    AND NEW.`verified_at` <=> OLD.`verified_at`
    AND NEW.`payload_hash` <=> OLD.`payload_hash`
    AND NEW.`version` <=> OLD.`version`
    AND NEW.`legacy_unverified` <=> OLD.`legacy_unverified`
    AND NEW.`creator` <=> OLD.`creator`
    AND NEW.`create_time` <=> OLD.`create_time`
    AND NEW.`deleted` <=> OLD.`deleted`
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'legacy revenue facts are immutable';
  END IF;
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_ledger_legacy_immutable`
BEFORE UPDATE ON `skit_commission_ledger` FOR EACH ROW
BEGIN
  IF OLD.`legacy_unverified` = b'1' AND NOT (
    NEW.`id` <=> OLD.`id`
    AND NEW.`tenant_id` <=> OLD.`tenant_id`
    AND NEW.`event_id` <=> OLD.`event_id`
    AND NEW.`beneficiary_type` <=> OLD.`beneficiary_type`
    AND NEW.`beneficiary_member_id` <=> OLD.`beneficiary_member_id`
    AND NEW.`level_no` <=> OLD.`level_no`
    AND NEW.`gross_amount` <=> OLD.`gross_amount`
    AND NEW.`rate_bps` <=> OLD.`rate_bps`
    AND NEW.`amount` <=> OLD.`amount`
    AND NEW.`rule_version` <=> OLD.`rule_version`
    AND NEW.`status` <=> OLD.`status`
    AND NEW.`entry_type` <=> OLD.`entry_type`
    AND NEW.`balance_bucket` <=> OLD.`balance_bucket`
    AND NEW.`currency` <=> OLD.`currency`
    AND NEW.`gross_amount_units` <=> OLD.`gross_amount_units`
    AND NEW.`amount_units` <=> OLD.`amount_units`
    AND NEW.`amount_scale` <=> OLD.`amount_scale`
    AND NEW.`reversal_of_id` <=> OLD.`reversal_of_id`
    AND NEW.`reconciliation_revision_id` <=> OLD.`reconciliation_revision_id`
    AND NEW.`policy_snapshot_id` <=> OLD.`policy_snapshot_id`
    AND NEW.`revision_no` <=> OLD.`revision_no`
    AND NEW.`legacy_unverified` <=> OLD.`legacy_unverified`
    AND NEW.`creator` <=> OLD.`creator`
    AND NEW.`create_time` <=> OLD.`create_time`
    AND NEW.`deleted` <=> OLD.`deleted`
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'legacy ledger facts are immutable';
  END IF;
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_callback_key_immutable`
BEFORE UPDATE ON `skit_ad_callback_key` FOR EACH ROW
BEGIN
  IF NOT (
    NEW.`id` <=> OLD.`id`
    AND NEW.`tenant_id` <=> OLD.`tenant_id`
    AND NEW.`ad_account_id` <=> OLD.`ad_account_id`
    AND NEW.`key_version` <=> OLD.`key_version`
    AND NEW.`callback_key_hash` <=> OLD.`callback_key_hash`
    AND NEW.`creator` <=> OLD.`creator`
    AND NEW.`create_time` <=> OLD.`create_time`
    AND NEW.`deleted` <=> OLD.`deleted`
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'credential identity and material are immutable';
  ELSEIF NOT ((NEW.`active` <=> OLD.`active`) OR (OLD.`active` = b'1' AND NEW.`active` = b'0'))
    OR NOT ((NEW.`accept_until` <=> OLD.`accept_until`) OR
      (OLD.`accept_until` IS NULL AND NEW.`accept_until` IS NOT NULL AND NEW.`active` = b'0'))
    OR NOT ((NEW.`revoked_at` <=> OLD.`revoked_at`) OR
      (OLD.`revoked_at` IS NULL AND NEW.`revoked_at` IS NOT NULL))
    OR (NEW.`revoked_at` IS NOT NULL AND NEW.`active` = b'1') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'credential lifecycle is monotonic';
  END IF;
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_callback_key_no_delete`
BEFORE DELETE ON `skit_ad_callback_key` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'credential version rows cannot be deleted';
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_reward_secret_immutable`
BEFORE UPDATE ON `skit_ad_reward_secret_version` FOR EACH ROW
BEGIN
  IF NOT (
    NEW.`id` <=> OLD.`id`
    AND NEW.`tenant_id` <=> OLD.`tenant_id`
    AND NEW.`ad_account_id` <=> OLD.`ad_account_id`
    AND NEW.`secret_version` <=> OLD.`secret_version`
    AND NEW.`ciphertext` <=> OLD.`ciphertext`
    AND NEW.`nonce` <=> OLD.`nonce`
    AND NEW.`encryption_key_id` <=> OLD.`encryption_key_id`
    AND NEW.`envelope_version` <=> OLD.`envelope_version`
    AND NEW.`creator` <=> OLD.`creator`
    AND NEW.`create_time` <=> OLD.`create_time`
    AND NEW.`deleted` <=> OLD.`deleted`
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'credential identity and material are immutable';
  ELSEIF NOT ((NEW.`active` <=> OLD.`active`) OR (OLD.`active` = b'1' AND NEW.`active` = b'0'))
    OR NOT ((NEW.`accept_until` <=> OLD.`accept_until`) OR
      (OLD.`accept_until` IS NULL AND NEW.`accept_until` IS NOT NULL AND NEW.`active` = b'0'))
    OR NOT ((NEW.`revoked_at` <=> OLD.`revoked_at`) OR
      (OLD.`revoked_at` IS NULL AND NEW.`revoked_at` IS NOT NULL))
    OR (NEW.`revoked_at` IS NOT NULL AND NEW.`active` = b'1') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'credential lifecycle is monotonic';
  END IF;
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_reward_secret_no_delete`
BEFORE DELETE ON `skit_ad_reward_secret_version` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'credential version rows cannot be deleted';
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_invite_registry_immutable`
BEFORE UPDATE ON `skit_invite_code_registry` FOR EACH ROW
BEGIN
  IF NOT (
    NEW.`id` <=> OLD.`id`
    AND NEW.`tenant_id` <=> OLD.`tenant_id`
    AND NEW.`code` <=> OLD.`code`
    AND NEW.`owner_type` <=> OLD.`owner_type`
    AND NEW.`agent_id` <=> OLD.`agent_id`
    AND NEW.`member_id` <=> OLD.`member_id`
    AND NEW.`creator` <=> OLD.`creator`
    AND NEW.`create_time` <=> OLD.`create_time`
    AND NEW.`deleted` <=> OLD.`deleted`
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invite code ownership is immutable';
  ELSEIF NOT (((NEW.`status` <=> OLD.`status`) AND (NEW.`rotated_at` <=> OLD.`rotated_at`)) OR
      (OLD.`status` = 'ACTIVE' AND NEW.`status` IN ('ROTATED', 'DISABLED')
        AND OLD.`rotated_at` IS NULL AND NEW.`rotated_at` IS NOT NULL))
    OR (NEW.`status` = 'ACTIVE' AND NEW.`rotated_at` IS NOT NULL)
    OR (NEW.`status` IN ('ROTATED', 'DISABLED') AND NEW.`rotated_at` IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invite code lifecycle is monotonic';
  END IF;
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_invite_registry_no_delete`
BEFORE DELETE ON `skit_invite_code_registry` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invite code registry rows cannot be deleted';
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_policy_snapshot_immutable`
BEFORE UPDATE ON `skit_ad_policy_snapshot` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'policy snapshot rows are immutable';
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_policy_snapshot_no_delete`
BEFORE DELETE ON `skit_ad_policy_snapshot` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'policy snapshot rows are immutable';
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_ledger_immutable`
BEFORE UPDATE ON `skit_commission_ledger` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'commission ledger rows are immutable';
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_ledger_no_delete`
BEFORE DELETE ON `skit_commission_ledger` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'commission ledger rows are immutable';
END$$
DROP TRIGGER IF EXISTS `trg_skit_entitlement_grant_session_range`$$
CREATE TRIGGER `trg_skit_entitlement_grant_session_range`
BEFORE INSERT ON `skit_entitlement_grant` FOR EACH ROW
BEGIN
  DECLARE `session_episode_from` int DEFAULT NULL;
  DECLARE `session_episode_to` int DEFAULT NULL;
  SELECT `episode_from`,`episode_to` INTO `session_episode_from`,`session_episode_to`
    FROM `skit_ad_session`
    WHERE `tenant_id` = NEW.`tenant_id` AND `id` = NEW.`ad_session_id`
      AND `member_id` = NEW.`member_id` AND `drama_id` = NEW.`drama_id`
      AND `provider_transaction_id` = NEW.`provider_transaction_id`
    FOR SHARE;
  IF `session_episode_from` IS NULL OR NEW.`episode_no` < `session_episode_from`
      OR NEW.`episode_no` > `session_episode_to` THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'entitlement grant episode is outside the session scope';
  END IF;
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_entitlement_grant_immutable`
BEFORE UPDATE ON `skit_entitlement_grant` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'entitlement grant rows are immutable';
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_entitlement_grant_no_delete`
BEFORE DELETE ON `skit_entitlement_grant` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'entitlement grant rows are immutable';
END$$
DROP TRIGGER IF EXISTS `trg_skit_callback_inbox_monotonic`$$
CREATE TRIGGER `trg_skit_callback_inbox_monotonic`
BEFORE UPDATE ON `skit_ad_callback_inbox` FOR EACH ROW
BEGIN
  IF NOT (
    NEW.`id` <=> OLD.`id` AND NEW.`tenant_id` <=> OLD.`tenant_id`
    AND NEW.`ad_account_id` <=> OLD.`ad_account_id` AND NEW.`ad_session_id` <=> OLD.`ad_session_id`
    AND NEW.`callback_key_version` <=> OLD.`callback_key_version`
    AND NEW.`reward_secret_version` <=> OLD.`reward_secret_version`
    AND NEW.`provider` <=> OLD.`provider` AND NEW.`callback_type` <=> OLD.`callback_type`
    AND NEW.`idempotency_key` <=> OLD.`idempotency_key`
    AND NEW.`provider_user_id` <=> OLD.`provider_user_id`
    AND NEW.`extra_data_hash` <=> OLD.`extra_data_hash`
    AND NEW.`provider_transaction_id` <=> OLD.`provider_transaction_id`
    AND NEW.`provider_show_id` <=> OLD.`provider_show_id`
    AND NEW.`provider_request_id` <=> OLD.`provider_request_id`
    AND NEW.`placement_id` <=> OLD.`placement_id` AND NEW.`adsource_id` <=> OLD.`adsource_id`
    AND NEW.`network_firm_id` <=> OLD.`network_firm_id`
    AND NEW.`source_currency` <=> OLD.`source_currency`
    AND NEW.`source_amount_units` <=> OLD.`source_amount_units`
    AND NEW.`amount_scale` <=> OLD.`amount_scale`
    AND NEW.`signed_field_mask` <=> OLD.`signed_field_mask`
    AND NEW.`evidence_provenance` <=> OLD.`evidence_provenance`
    AND NEW.`canonical_payload_hash` <=> OLD.`canonical_payload_hash`
    AND NEW.`authentication_level` <=> OLD.`authentication_level`
    AND NEW.`signature_status` <=> OLD.`signature_status`
    AND NEW.`ingress_response_code` <=> OLD.`ingress_response_code`
    AND NEW.`received_at` <=> OLD.`received_at` AND NEW.`creator` <=> OLD.`creator`
    AND NEW.`create_time` <=> OLD.`create_time` AND NEW.`deleted` <=> OLD.`deleted`
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'callback inbox provenance is immutable';
  ELSEIF NOT (
    ((NEW.`delivery_integrity_status` <=> OLD.`delivery_integrity_status`)
      AND (NEW.`integrity_conflict_at` <=> OLD.`integrity_conflict_at`))
    OR (OLD.`delivery_integrity_status` = 'CANONICAL'
      AND NEW.`delivery_integrity_status` = 'PAYLOAD_CONFLICT'
      AND OLD.`integrity_conflict_at` IS NULL AND NEW.`integrity_conflict_at` IS NOT NULL)
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'callback inbox integrity is monotonic';
  ELSEIF NOT (
    (NEW.`processing_status` <=> OLD.`processing_status`
      AND NEW.`error_code` <=> OLD.`error_code`
      AND NEW.`lease_owner` <=> OLD.`lease_owner`
      AND NEW.`lease_until` <=> OLD.`lease_until`
      AND NEW.`processing_attempt_count` <=> OLD.`processing_attempt_count`
      AND NEW.`next_attempt_at` <=> OLD.`next_attempt_at`
      AND NEW.`processed_at` <=> OLD.`processed_at`)
    OR ((OLD.`processing_status` = 'PENDING' AND NEW.`processing_status` = 'PROCESSING'
      AND NEW.`processing_attempt_count` = OLD.`processing_attempt_count` + 1
      AND NEW.`error_code` IS NULL)
    OR (OLD.`processing_status` = 'RETRY_WAIT' AND NEW.`processing_status` = 'PROCESSING'
      AND NEW.`processing_attempt_count` = OLD.`processing_attempt_count` + 1
      AND NEW.`error_code` IS NULL)
    OR (OLD.`processing_status` = 'PROCESSING' AND NEW.`processing_status` = 'PROCESSING'
      AND OLD.`lease_until` <= CURRENT_TIMESTAMP
      AND (NOT (NEW.`lease_owner` <=> OLD.`lease_owner`)
        OR NOT (NEW.`lease_until` <=> OLD.`lease_until`))
      AND NEW.`processing_attempt_count` = OLD.`processing_attempt_count` + 1
      AND NEW.`error_code` IS NULL)
    OR (OLD.`processing_status` = 'PROCESSING' AND NEW.`processing_status` = 'RETRY_WAIT'
      AND NEW.`processing_attempt_count` = OLD.`processing_attempt_count`
      AND NEW.`error_code` IS NOT NULL)
    OR (OLD.`processing_status` = 'PROCESSING'
      AND NEW.`processing_status` IN ('SUCCEEDED','REJECTED','DEAD_LETTER')
      AND NEW.`processing_attempt_count` = OLD.`processing_attempt_count`))
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'callback processing transition is not allowed';
  ELSEIF NOT (
    (NEW.`dead_letter_alerted_at` <=> OLD.`dead_letter_alerted_at`)
    OR (OLD.`dead_letter_alerted_at` IS NULL AND NEW.`dead_letter_alerted_at` IS NOT NULL
      AND NEW.`processing_status` = 'DEAD_LETTER')
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'callback dead-letter alert is monotonic';
  ELSEIF NOT (
    (NEW.`payload_ciphertext` <=> OLD.`payload_ciphertext`
      AND NEW.`payload_nonce` <=> OLD.`payload_nonce`
      AND NEW.`payload_key_id` <=> OLD.`payload_key_id`
      AND NEW.`payload_envelope_version` <=> OLD.`payload_envelope_version`
      AND NEW.`payload_expires_at` <=> OLD.`payload_expires_at`)
    OR (OLD.`processing_status` IN ('SUCCEEDED','REJECTED','DEAD_LETTER')
      AND NEW.`processing_status` = OLD.`processing_status`
      AND OLD.`payload_ciphertext` IS NOT NULL AND OLD.`payload_nonce` IS NOT NULL
      AND OLD.`payload_key_id` IS NOT NULL AND OLD.`payload_envelope_version` IS NOT NULL
      AND OLD.`payload_expires_at` IS NOT NULL AND OLD.`payload_expires_at` <= CURRENT_TIMESTAMP
      AND NEW.`payload_ciphertext` IS NULL AND NEW.`payload_nonce` IS NULL
      AND NEW.`payload_key_id` IS NULL AND NEW.`payload_envelope_version` IS NULL
      AND NEW.`payload_expires_at` IS NULL)
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'callback payload can only be erased after expiry';
  END IF;
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_callback_inbox_no_delete`
BEFORE DELETE ON `skit_ad_callback_inbox` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'callback inbox rows cannot be deleted';
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_callback_attempt_immutable`
BEFORE UPDATE ON `skit_ad_callback_attempt` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'callback attempt rows are immutable';
END$$
CREATE TRIGGER IF NOT EXISTS `trg_skit_callback_edge_attempt_immutable`
BEFORE UPDATE ON `skit_ad_callback_edge_attempt` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'callback edge attempt rows are immutable';
END$$
DROP TRIGGER IF EXISTS `trg_skit_reporting_credential_monotonic`$$
CREATE TRIGGER `trg_skit_reporting_credential_monotonic`
BEFORE UPDATE ON `skit_ad_reporting_credential_version` FOR EACH ROW
BEGIN
  IF NOT (
    NEW.`id` <=> OLD.`id` AND NEW.`tenant_id` <=> OLD.`tenant_id`
    AND NEW.`ad_account_id` <=> OLD.`ad_account_id`
    AND NEW.`credential_version` <=> OLD.`credential_version`
    AND NEW.`ciphertext` <=> OLD.`ciphertext` AND NEW.`nonce` <=> OLD.`nonce`
    AND NEW.`encryption_key_id` <=> OLD.`encryption_key_id`
    AND NEW.`envelope_version` <=> OLD.`envelope_version`
    AND NEW.`creator` <=> OLD.`creator` AND NEW.`create_time` <=> OLD.`create_time`
    AND NEW.`deleted` <=> OLD.`deleted`
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'reporting credential identity and material are immutable';
  ELSEIF NOT (
      (NEW.`permission_verified_at` <=> OLD.`permission_verified_at`)
      OR (OLD.`permission_verified_at` IS NULL AND NEW.`permission_verified_at` IS NOT NULL)
    ) OR NOT (
      ((NEW.`active` <=> OLD.`active`) AND (NEW.`revoked_at` <=> OLD.`revoked_at`))
      OR (OLD.`active`=b'1' AND NEW.`active`=b'0' AND OLD.`revoked_at` IS NULL
        AND NEW.`revoked_at` IS NOT NULL)
    ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reporting credential lifecycle is monotonic';
  END IF;
END$$
DROP TRIGGER IF EXISTS `trg_skit_reporting_credential_no_delete`$$
CREATE TRIGGER `trg_skit_reporting_credential_no_delete`
BEFORE DELETE ON `skit_ad_reporting_credential_version` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reporting credential versions cannot be deleted';
END$$
DROP TRIGGER IF EXISTS `trg_skit_report_pull_immutable`$$
CREATE TRIGGER `trg_skit_report_pull_immutable`
BEFORE UPDATE ON `skit_ad_report_pull` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'report pull rows are immutable';
END$$
DROP TRIGGER IF EXISTS `trg_skit_report_pull_no_delete`$$
CREATE TRIGGER `trg_skit_report_pull_no_delete`
BEFORE DELETE ON `skit_ad_report_pull` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'report pull rows are immutable';
END$$
DROP TRIGGER IF EXISTS `trg_skit_recon_revision_immutable`$$
CREATE TRIGGER `trg_skit_recon_revision_immutable`
BEFORE UPDATE ON `skit_ad_reconciliation_revision` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reconciliation revision rows are immutable';
END$$
DROP TRIGGER IF EXISTS `trg_skit_recon_revision_no_delete`$$
CREATE TRIGGER `trg_skit_recon_revision_no_delete`
BEFORE DELETE ON `skit_ad_reconciliation_revision` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reconciliation revision rows are immutable';
END$$
DROP TRIGGER IF EXISTS `trg_skit_recon_allocation_immutable`$$
CREATE TRIGGER `trg_skit_recon_allocation_immutable`
BEFORE UPDATE ON `skit_ad_reconciliation_allocation` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reconciliation allocation rows are immutable';
END$$
DROP TRIGGER IF EXISTS `trg_skit_recon_allocation_no_delete`$$
CREATE TRIGGER `trg_skit_recon_allocation_no_delete`
BEFORE DELETE ON `skit_ad_reconciliation_allocation` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reconciliation allocation rows are immutable';
END$$
DROP TRIGGER IF EXISTS `trg_skit_recon_event_link_immutable`$$
CREATE TRIGGER `trg_skit_recon_event_link_immutable`
BEFORE UPDATE ON `skit_ad_reconciliation_event_link` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reconciliation event links are immutable';
END$$
DROP TRIGGER IF EXISTS `trg_skit_recon_event_link_no_delete`$$
CREATE TRIGGER `trg_skit_recon_event_link_no_delete`
BEFORE DELETE ON `skit_ad_reconciliation_event_link` FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reconciliation event links are immutable';
END$$
DELIMITER ;

-- SKIT_CANONICAL_SCHEMA_END

INSERT INTO `system_tenant_package` (`code`, `name`, `status`, `menu_ids`)
VALUES ('SKIT_AGENT_STANDARD', '代理商标准套餐', 0, '[]')
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `status` = VALUES(`status`),
  `menu_ids` = VALUES(`menu_ids`), `deleted` = b'0';
