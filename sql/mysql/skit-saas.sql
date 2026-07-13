-- 短剧 SaaS 业务表。除 skit_agent 外，所有业务表均以 tenant_id 隔离。

CREATE TABLE IF NOT EXISTS `skit_schema_migration` (
  `version` int NOT NULL COMMENT '迁移版本',
  `description` varchar(255) NOT NULL COMMENT '迁移说明',
  `checksum` char(64) NOT NULL COMMENT '迁移校验和',
  `installed_on` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '安装时间',
  PRIMARY KEY (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='短剧 SaaS 数据库迁移记录';

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
  PRIMARY KEY (`id`), UNIQUE KEY `uk_skit_agent_tenant` (`tenant_id`),
  UNIQUE KEY `uk_skit_agent_code` (`tenant_code`), UNIQUE KEY `uk_skit_agent_invite` (`root_invite_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='代理商全局注册表';

INSERT INTO `system_tenant_package` (`code`, `name`, `status`, `menu_ids`)
VALUES ('SKIT_AGENT_STANDARD', '代理商标准套餐', 0, '[]')
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `status` = VALUES(`status`),
  `menu_ids` = VALUES(`menu_ids`), `deleted` = b'0';

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
  `status` tinyint NOT NULL DEFAULT 0,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_app_release_profile_code` (`profile_code`),
  UNIQUE KEY `uk_skit_app_release_profile_tenant_channel` (`tenant_id`,`channel`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='代理商白标 App 发布档案';

CREATE TABLE IF NOT EXISTS `skit_ad_account` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL,
  `provider` varchar(16) NOT NULL, `account_name` varchar(128) DEFAULT '', `account_id` varchar(128) DEFAULT '',
  `app_id` varchar(128) DEFAULT '', `app_key` varchar(255) DEFAULT '', `secret` text,
  `config_data` longtext, `status` tinyint NOT NULL DEFAULT 1,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_ad_account_tenant_provider` (`tenant_id`,`provider`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户广告平台账号';

CREATE TABLE IF NOT EXISTS `skit_member` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL,
  `mobile` varchar(32) NOT NULL, `password` varchar(100) NOT NULL, `nickname` varchar(64) NOT NULL,
  `inviter_id` bigint DEFAULT NULL, `invite_code` varchar(32) NOT NULL, `depth` int NOT NULL DEFAULT 1,
  `status` tinyint NOT NULL DEFAULT 0, `register_ip` varchar(50) DEFAULT '', `login_ip` varchar(50) DEFAULT '',
  `login_time` datetime DEFAULT NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_member_tenant_mobile` (`tenant_id`,`mobile`),
  UNIQUE KEY `uk_skit_member_invite_code` (`invite_code`),
  KEY `idx_skit_member_tenant_inviter` (`tenant_id`,`inviter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='短剧独立会员';

CREATE TABLE IF NOT EXISTS `skit_member_closure` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL,
  `ancestor_id` bigint NOT NULL, `descendant_id` bigint NOT NULL, `distance` int NOT NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_member_closure_path` (`tenant_id`,`ancestor_id`,`descendant_id`),
  KEY `idx_skit_member_closure_desc_distance` (`tenant_id`,`descendant_id`,`distance`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会员邀请闭包树';

CREATE TABLE IF NOT EXISTS `skit_commission_plan` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL, `version` int NOT NULL,
  `status` tinyint NOT NULL, `published_time` datetime NOT NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_commission_plan_version` (`tenant_id`,`version`),
  KEY `idx_skit_commission_plan_status` (`tenant_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='版本化分成方案';

CREATE TABLE IF NOT EXISTS `skit_commission_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL, `plan_id` bigint NOT NULL,
  `level_no` int NOT NULL COMMENT '0 本人，1..N 祖先', `rate_bps` int NOT NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_commission_rule_level` (`tenant_id`,`plan_id`,`level_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分成方案层级规则';

CREATE TABLE IF NOT EXISTS `skit_ad_revenue_event` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL, `ad_account_id` bigint NOT NULL,
  `provider` varchar(16) NOT NULL, `placement_id` varchar(128) NOT NULL,
  `external_event_id` varchar(128) NOT NULL, `source_member_id` bigint NOT NULL,
  `gross_amount` decimal(20,8) NOT NULL, `occurred_time` datetime NOT NULL,
  `completed` bit(1) NOT NULL, `mock` bit(1) NOT NULL, `status` tinyint NOT NULL,
  `rule_version` int DEFAULT NULL, `raw_data` longtext,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_revenue_event_external` (`tenant_id`,`provider`,`external_event_id`),
  KEY `idx_skit_revenue_event_member` (`tenant_id`,`source_member_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='广告收益幂等事件';

CREATE TABLE IF NOT EXISTS `skit_commission_ledger` (
  `id` bigint NOT NULL AUTO_INCREMENT, `tenant_id` bigint NOT NULL, `event_id` bigint NOT NULL,
  `beneficiary_type` tinyint NOT NULL, `beneficiary_member_id` bigint NOT NULL DEFAULT 0,
  `level_no` int NOT NULL, `gross_amount` decimal(20,8) NOT NULL, `rate_bps` int NOT NULL,
  `amount` decimal(20,8) NOT NULL, `rule_version` int NOT NULL, `status` tinyint NOT NULL,
  `creator` varchar(64) DEFAULT '', `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updater` varchar(64) DEFAULT '', `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` bit(1) NOT NULL DEFAULT b'0', PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skit_ledger_beneficiary` (`tenant_id`,`event_id`,`beneficiary_type`,`beneficiary_member_id`,`level_no`),
  KEY `idx_skit_ledger_member_time` (`tenant_id`,`beneficiary_member_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='广告分成不可变账本';
