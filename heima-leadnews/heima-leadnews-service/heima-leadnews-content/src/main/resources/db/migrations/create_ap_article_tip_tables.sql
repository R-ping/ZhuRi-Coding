-- =====================================================
-- 文章打赏功能迁移脚本
-- 1. ap_article 增加打赏汇总字段
-- 2. ap_article_tip_order 打赏订单表
-- 3. ap_article_tip_record 打赏流水表（公开感谢名单）
-- =====================================================

-- 1. ap_article 增加打赏汇总字段
ALTER TABLE `ap_article`
  ADD COLUMN `tip_count` int unsigned NOT NULL DEFAULT 0 COMMENT '打赏人数' AFTER `comment`,
  ADD COLUMN `tip_amount` decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '打赏总金额' AFTER `tip_count`;

-- 2. 打赏订单表
CREATE TABLE `ap_article_tip_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no` varchar(32) NOT NULL COMMENT '订单号',
  `user_id` int NOT NULL COMMENT '打赏人用户ID',
  `article_id` bigint NOT NULL COMMENT '文章ID',
  `author_id` int NOT NULL COMMENT '作者用户ID',
  `amount` decimal(10,2) NOT NULL COMMENT '打赏金额',
  `message` varchar(200) DEFAULT '' COMMENT '打赏留言',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态: 0-待支付 1-已支付',
  `trade_no` varchar(64) DEFAULT '' COMMENT '支付宝交易号',
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_article_id` (`article_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_author_id` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章打赏订单表';

-- 3. 打赏流水表（公开感谢名单）
CREATE TABLE `ap_article_tip_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no` varchar(32) NOT NULL COMMENT '关联订单号',
  `user_id` int NOT NULL COMMENT '打赏人用户ID',
  `nick_name` varchar(50) DEFAULT '' COMMENT '打赏人昵称',
  `avatar` varchar(255) DEFAULT '' COMMENT '打赏人头像',
  `article_id` bigint NOT NULL COMMENT '文章ID',
  `author_id` int NOT NULL COMMENT '作者用户ID',
  `amount` decimal(10,2) NOT NULL COMMENT '打赏金额',
  `message` varchar(200) DEFAULT '' COMMENT '打赏留言',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '打赏时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_article_id` (`article_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章打赏流水表';
