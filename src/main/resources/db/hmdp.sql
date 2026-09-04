-- hm-dianping 秒杀主线最小数据库结构
-- 用户数据与秒杀活动通过接口创建，不附带真实或课程样例数据。

CREATE DATABASE IF NOT EXISTS `hmdp`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `hmdp`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

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

SET FOREIGN_KEY_CHECKS = 1;
