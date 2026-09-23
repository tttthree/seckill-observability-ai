-- hm-dianping 秒杀主线最小数据库结构
-- 用户数据与秒杀活动通过接口创建，不附带真实或课程样例数据。

CREATE DATABASE IF NOT EXISTS `hmdp`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `hmdp`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `tb_incident`;
DROP TABLE IF EXISTS `tb_voucher_order`;
DROP TABLE IF EXISTS `tb_seckill_voucher`;
DROP TABLE IF EXISTS `tb_voucher`;
DROP TABLE IF EXISTS `tb_user`;

CREATE TABLE `tb_user` (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `phone` varchar(11) NOT NULL COMMENT '登录手机号',
  `nick_name` varchar(32) NOT NULL COMMENT '随机昵称',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀用户';

CREATE TABLE `tb_voucher` (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `title` varchar(255) NOT NULL COMMENT '秒杀券标题',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀券基础信息';

CREATE TABLE `tb_seckill_voucher` (
  `voucher_id` bigint UNSIGNED NOT NULL COMMENT '关联秒杀券 id',
  `stock` int UNSIGNED NOT NULL COMMENT '数据库库存',
  `begin_time` timestamp NOT NULL COMMENT '开始时间',
  `end_time` timestamp NOT NULL COMMENT '结束时间',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`voucher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀券库存与有效期';

CREATE TABLE `tb_voucher_order` (
  `id` bigint NOT NULL COMMENT 'RedisIdWorker 生成的订单 id',
  `user_id` bigint UNSIGNED NOT NULL COMMENT '下单用户 id',
  `voucher_id` bigint UNSIGNED NOT NULL COMMENT '秒杀券 id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_voucher` (`user_id`, `voucher_id`),
  KEY `idx_voucher_id` (`voucher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单';

-- 统一故障事件：对账偏差、死信、消费者不健康
-- 去重依赖 open_key 唯一索引：OPEN 时 open_key = incident_type:business_key，RESOLVED 时置 NULL，
-- 因此同一故障同时只有一条 OPEN 记录，历史记录全部保留。
CREATE TABLE `tb_incident` (
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

SET FOREIGN_KEY_CHECKS = 1;
