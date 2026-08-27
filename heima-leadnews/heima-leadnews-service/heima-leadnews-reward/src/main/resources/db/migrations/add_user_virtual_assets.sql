-- =============================================================
-- 虚拟道具（5折券等）：用户持有 + 抽奖入账 + 下单核销
-- 数据库：leadnews_reward
-- 1. 新增用户虚拟道具持有表
-- 2. 奖品池新增 discount_rate 字段（全课程通用折扣：payable 比例，0.5 = 5折）
-- =============================================================

CREATE TABLE IF NOT EXISTS `user_virtual_assets` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `item_code` varchar(32) NOT NULL COMMENT '虚拟道具代码（如 course50=课程5折券）',
  `item_name` varchar(64) NOT NULL COMMENT '道具名称',
  `quantity` int NOT NULL DEFAULT '0' COMMENT '持有数量',
  `source` varchar(32) NOT NULL DEFAULT 'lottery' COMMENT '来源：lottery-抽奖',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_item` (`user_id`,`item_code`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户虚拟道具持有表';

-- 奖品池新增折扣比例（全课程通用，payable 比例：0.5 = 5折，即实付50%）
ALTER TABLE `lottery_prize_pool`
  ADD COLUMN `discount_rate` decimal(5,4) NOT NULL DEFAULT '1.0000' COMMENT '全课程通用折扣比例（type=2优惠券时使用，0.5=5折）' AFTER `virtual_item_code`;

-- 为 5 折券（course50）设置折扣比例
UPDATE `lottery_prize_pool` SET `discount_rate` = 0.5000
  WHERE `type` = 2 AND `virtual_item_code` = 'course50';