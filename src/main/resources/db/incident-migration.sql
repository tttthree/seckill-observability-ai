-- V2-1 Incident 增量迁移：新增统一故障事件表 tb_incident
-- 适用：已存在 hmdp 库的既有环境（可重复执行）。
-- 全新环境直接执行 db/hmdp.sql 即可，其中已包含本表的 DROP/CREATE。
--
-- 执行方式：
--   mysql -u root -p < src/main/resources/db/incident-migration.sql

USE `hmdp`;

CREATE TABLE IF NOT EXISTS `tb_incident` (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键（自增：故障期间不依赖 Redis 生成 ID）',
  `incident_type` varchar(48) NOT NULL COMMENT '故障类型 INVENTORY_MISMATCH/DEAD_LETTER/CONSUMER_UNHEALTHY',
  `severity` varchar(16) NOT NULL COMMENT '故障级别 LOW/MEDIUM/HIGH/CRITICAL',
  `source` varchar(32) NOT NULL COMMENT '来源组件 RECONCILE/STREAM_CONSUMER/HEALTH_INDICATOR',
  `status` varchar(16) NOT NULL COMMENT '状态 OPEN/RESOLVED',
  `business_key` varchar(128) NOT NULL COMMENT '故障聚合键，如 voucher:123',
  `open_key` varchar(191) DEFAULT NULL COMMENT 'OPEN 时=incident_type:business_key，RESOLVED 置 NULL',
  `title` varchar(255) NOT NULL COMMENT '故障标题',
  `description` varchar(1000) DEFAULT NULL COMMENT '最近一次检测证据描述',
  `related_voucher_id` bigint UNSIGNED DEFAULT NULL COMMENT '关联秒杀券 id',
  `occurrence_count` int UNSIGNED NOT NULL DEFAULT 1 COMMENT '累计检测次数',
  `first_detected_at` datetime NOT NULL COMMENT '首次发现时间',
  `last_detected_at` datetime NOT NULL COMMENT '最近检测时间',
  `resolved_at` datetime DEFAULT NULL COMMENT '恢复时间',
  `snapshot` json DEFAULT NULL COMMENT '最近一次检测的结构化证据',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_incident_open` (`open_key`),
  KEY `idx_incident_status_last` (`status`, `last_detected_at`),
  KEY `idx_incident_type_status` (`incident_type`, `status`),
  KEY `idx_incident_voucher` (`related_voucher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一故障事件';
