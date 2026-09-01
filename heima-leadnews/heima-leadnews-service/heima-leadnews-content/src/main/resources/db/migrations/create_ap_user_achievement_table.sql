-- =====================================================
-- 用户成就解锁记录表迁移脚本
-- ap_user_achievement：事件驱动写入（行为发生时由 AchievementProcessor 检查并落库），
-- 成就页查询只读本表，不再全量实时统计
-- =====================================================

CREATE TABLE `ap_user_achievement` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `achievement_code` varchar(64) NOT NULL COMMENT '勋章编码（关联 ap_achievement.code）',
  `progress` bigint NOT NULL DEFAULT '0' COMMENT '当前进度快照（只增不减）',
  `threshold` int NOT NULL DEFAULT '0' COMMENT '解锁阈值快照',
  `unlocked` tinyint NOT NULL DEFAULT '0' COMMENT '是否解锁：1=已解锁',
  `unlocked_at` datetime DEFAULT NULL COMMENT '解锁时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_code` (`user_id`,`achievement_code`),
  KEY `idx_user_unlocked` (`user_id`,`unlocked`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户成就解锁记录表（事件驱动）';
