-- 短剧 SaaS 复刻模块：通用后台记录表
-- 先用 page_key + record_data(JSON 文本) 承载线上 FastAdmin 多页面 CRUD。

CREATE TABLE IF NOT EXISTS `skit_admin_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '编号',
  `page_key` varchar(64) NOT NULL COMMENT '页面键',
  `row_key` varchar(128) NOT NULL COMMENT '业务行键',
  `record_data` longtext NOT NULL COMMENT '页面字段 JSON',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态：0 正常，1 待处理，2 禁用',
  `sort` int NOT NULL DEFAULT 0 COMMENT '排序',
  `creator` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_skit_admin_record_page_key` (`page_key`),
  KEY `idx_skit_admin_record_row_key` (`row_key`),
  KEY `idx_skit_admin_record_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='短剧 SaaS 后台通用记录表';
