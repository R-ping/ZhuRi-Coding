
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `lottery_broadcast_messages` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `user_nickname` varchar(64) DEFAULT NULL,
  `prize_name` varchar(64) NOT NULL,
  `prize_type` tinyint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_created` (`created_at` DESC)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='中奖播报消息表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `lottery_daily_state` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `stat_date` date NOT NULL,
  `draw_count` int DEFAULT '0' COMMENT '当日抽奖总次数（不含免费）',
  `free_used` tinyint(1) DEFAULT '0' COMMENT '今日免费次数是否已用',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_user_date` (`user_id`,`stat_date`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户每日抽奖状态表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `lottery_draw_records` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `draw_batch_id` varchar(64) NOT NULL COMMENT '批次ID（单次/十连共用）',
  `user_id` bigint NOT NULL,
  `prize_id` varchar(32) NOT NULL,
  `prize_name` varchar(64) NOT NULL,
  `prize_type` tinyint NOT NULL,
  `ore_amount` int DEFAULT '0',
  `virtual_item_code` varchar(32) DEFAULT NULL,
  `physical_order_id` bigint DEFAULT NULL COMMENT '关联实物订单表',
  `lucky_value_before` int NOT NULL,
  `lucky_value_after` int NOT NULL,
  `today_draw_count_at_time` int NOT NULL COMMENT '抽奖时的当日累计次数',
  `cost_ore` int DEFAULT '0',
  `is_free` tinyint(1) DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_created` (`user_id`,`created_at` DESC),
  KEY `idx_batch` (`draw_batch_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户抽奖记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `lottery_physical_orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `prize_id` varchar(32) NOT NULL,
  `prize_name` varchar(64) NOT NULL,
  `receiver_name` varchar(64) DEFAULT NULL,
  `phone` varchar(20) DEFAULT NULL,
  `address` varchar(255) DEFAULT NULL,
  `status` tinyint DEFAULT '1' COMMENT '1-待填地址 2-待发货 3-已发货 4-已签收 5-已过期',
  `express_no` varchar(64) DEFAULT NULL,
  `expire_at` datetime DEFAULT NULL COMMENT '填写地址截止时间（30天后）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_status` (`user_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='实物奖品订单表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `lottery_prize_pool` (
  `id` varchar(32) NOT NULL,
  `name` varchar(64) NOT NULL,
  `type` tinyint NOT NULL COMMENT '1-矿石 2-虚拟道具 3-实物',
  `icon_url` varchar(255) DEFAULT NULL,
  `probability` decimal(5,4) NOT NULL COMMENT '基础概率（如 0.0500 = 5%）',
  `min_ore` int DEFAULT '0' COMMENT '矿石范围最小值（type=1时使用）',
  `max_ore` int DEFAULT '0' COMMENT '矿石范围最大值',
  `virtual_item_code` varchar(32) DEFAULT NULL COMMENT '虚拟道具代码（type=2时使用）',
  `discount_rate` decimal(5,4) NOT NULL DEFAULT '1.0000' COMMENT '全课程通用折扣比例（type=2优惠券时使用，0.5=5折）',
  `total_stock` int NOT NULL DEFAULT '-1' COMMENT '实物奖品总库存(-1=不限量,0=已售罄,>0=剩余件数)',
  `unlock_required_draws` tinyint DEFAULT '0' COMMENT '需当日抽几次才解锁（0=无需解锁）',
  `is_physical` tinyint(1) DEFAULT '0',
  `sort_order` int DEFAULT '0',
  `status` tinyint DEFAULT '1' COMMENT '1-启用 0-停用',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='奖品池配置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sign_records` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `sign_date` date NOT NULL COMMENT '签到日期（yyyy-MM-dd）',
  `award_ore` int NOT NULL COMMENT '本次签到获得的矿石数（补签重算后更新）',
  `is_extra` tinyint(1) DEFAULT '0' COMMENT '是否为补签（0-正常签到 1-补签）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_user_date` (`user_id`,`sign_date`),
  KEY `idx_user_date_desc` (`user_id`,`sign_date` DESC)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='签到记录表（新）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_assets` (
  `user_id` bigint NOT NULL,
  `ore_balance` int NOT NULL DEFAULT '0' COMMENT '当前矿石余额',
  `frozen_ore` int NOT NULL DEFAULT '0' COMMENT '冻结矿石（TCC模式使用）',
  `lucky_value` int NOT NULL DEFAULT '0' COMMENT '当前幸运值',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户资产表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_checkin_state` (
  `user_id` bigint NOT NULL,
  `continuous_days` int NOT NULL DEFAULT '0' COMMENT '当前连续签到天数',
  `period_day` tinyint NOT NULL DEFAULT '0' COMMENT '当前周期内的第几天（1~30，0表示未开始）',
  `last_checkin_date` date DEFAULT NULL COMMENT '最后一次签到日期（含补签）',
  `total_checkin_days` int NOT NULL DEFAULT '0' COMMENT '历史累计签到总天数',
  `patch_card_count` int NOT NULL DEFAULT '0' COMMENT '补签卡库存',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户签到状态表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_virtual_assets` (
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
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `welfare_exchange_orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `exchange_id` varchar(64) NOT NULL COMMENT '业务订单号',
  `user_id` bigint NOT NULL,
  `goods_id` varchar(32) NOT NULL,
  `goods_name` varchar(128) NOT NULL,
  `is_virtual` tinyint(1) NOT NULL,
  `ore_cost` int NOT NULL,
  `receiver_name` varchar(64) DEFAULT NULL,
  `phone` varchar(20) DEFAULT NULL,
  `address` varchar(255) DEFAULT NULL,
  `remark` varchar(255) DEFAULT NULL,
  `virtual_code` varchar(255) DEFAULT NULL COMMENT '虚拟商品兑换码',
  `status` tinyint DEFAULT '1' COMMENT '1-待处理 2-已完成 3-已过期',
  `express_no` varchar(64) DEFAULT NULL COMMENT '物流单号（实物）',
  `address_expire_at` datetime DEFAULT NULL COMMENT '填写地址截止时间（实物，30天后）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_exchange_id` (`exchange_id`),
  KEY `idx_user_created` (`user_id`,`created_at` DESC),
  KEY `idx_status_expire` (`status`,`address_expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='兑换订单表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `welfare_goods` (
  `id` varchar(32) NOT NULL,
  `name` varchar(128) NOT NULL,
  `description` varchar(255) DEFAULT NULL,
  `image_url` varchar(255) NOT NULL,
  `type` tinyint NOT NULL COMMENT '1-实物 2-虚拟道具',
  `category` tinyint NOT NULL COMMENT '1-惊喜好物 2-社区道具',
  `ore_price` int NOT NULL COMMENT '兑换所需矿石数',
  `original_price` int DEFAULT '0' COMMENT '划线原价',
  `discount_tag` varchar(32) DEFAULT NULL COMMENT '折扣标签（如"五折"）',
  `stock` int NOT NULL DEFAULT '0' COMMENT '当前库存（-1表示无限）',
  `total_stock` int NOT NULL COMMENT '总库存（用于统计）',
  `exchanged_count` int DEFAULT '0' COMMENT '已兑换人数（冗余计数）',
  `is_virtual` tinyint(1) NOT NULL COMMENT '1-虚拟商品 0-实物',
  `time_limit_start` time DEFAULT NULL COMMENT '限时兑换开始时间',
  `time_limit_end` time DEFAULT NULL COMMENT '限时兑换结束时间',
  `time_limit_desc` varchar(64) DEFAULT NULL COMMENT '限时描述（如"周六~周日开放兑换"）',
  `virtual_code_template` varchar(255) DEFAULT NULL COMMENT '虚拟商品兑换码生成模板',
  `status` tinyint DEFAULT '1' COMMENT '1-上架 0-下架',
  `sort_order` int DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='福利商品表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `welfare_stock_logs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `goods_id` varchar(32) NOT NULL,
  `change_amount` int NOT NULL COMMENT '负数-扣减',
  `exchange_id` varchar(64) NOT NULL COMMENT '关联订单号',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_goods_created` (`goods_id`,`created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存扣减日志';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

