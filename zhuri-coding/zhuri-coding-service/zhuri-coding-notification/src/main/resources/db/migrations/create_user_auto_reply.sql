-- =============================================================
-- 关注自动私信配置表
-- 数据库：leadnews_notification
-- =============================================================

CREATE TABLE IF NOT EXISTS `ap_user_auto_reply` (
  `user_id` BIGINT NOT NULL COMMENT '配置方（被关注者）用户ID',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '开关 1开 0关',
  `content` VARCHAR(300) NOT NULL COMMENT '当前编辑中的文案（可能未过审）',
  `content_version` INT NOT NULL DEFAULT 1 COMMENT '文案版本号，每次内容变更+1',
  `audit_status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待审 1通过 2驳回',
  `audit_reason` VARCHAR(200) DEFAULT NULL COMMENT '驳回原因',
  `effective_content` VARCHAR(300) DEFAULT NULL COMMENT '当前生效文案（上一版已过审内容）',
  `last_sent_at` DATETIME DEFAULT NULL COMMENT '最近一次发送时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户关注自动私信配置';

-- 说明：
-- 1. 未建记录的用户按「默认开启 + 谢谢关注」处理，不做批量初始化 ——
--    初始化等于把「默认值」固化进数据，之后调整默认策略要改历史数据。
-- 2. effective_content 与 content 分离，是为了让「新文案审核中/被驳回」时
--    继续用上一版已过审文案，功能不停摆。
