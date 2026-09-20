-- 站内信系统初始化SQL
-- 数据库: leadnews_notification

CREATE DATABASE IF NOT EXISTS `leadnews_notification` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `leadnews_notification`;

-- 被动通知表
CREATE TABLE IF NOT EXISTS `notifications` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '接收通知的用户ID',
  `type` tinyint(4) NOT NULL COMMENT '1-评论 2-赞/收藏 3-粉丝 4-系统',
  `source_id` varchar(64) DEFAULT NULL COMMENT '触发源ID（评论ID/文章ID/用户ID等）',
  `content` text COMMENT '通知内容摘要（JSON存储多态数据）',
  `is_read` tinyint(1) DEFAULT '0' COMMENT '0-未读 1-已读',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_read_created` (`user_id`, `is_read`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='被动通知表';

-- 系统通知表
CREATE TABLE IF NOT EXISTS `system_notifications` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `notification_id` bigint(20) NOT NULL COMMENT '关联notifications表ID',
  `content` text COMMENT '系统通知内容',
  `action_url` varchar(512) DEFAULT NULL COMMENT '跳转链接',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_notification_id` (`notification_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统通知详情表';

-- 私信会话表
CREATE TABLE IF NOT EXISTS `im_sessions` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `session_key` varchar(64) NOT NULL COMMENT '会话唯一标识，小ID_大ID排序后拼接',
  `user1_id` bigint(20) NOT NULL,
  `user2_id` bigint(20) NOT NULL,
  `last_message` varchar(500) DEFAULT NULL COMMENT '最后一条消息预览',
  `last_message_at` datetime DEFAULT NULL,
  `user1_unread_count` int(11) DEFAULT '0',
  `user2_unread_count` int(11) DEFAULT '0',
  `is_active` tinyint(1) DEFAULT '0' COMMENT 'B是否回复过A（状态机S3激活标记）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_session` (`session_key`),
  KEY `idx_user1_lastmsg` (`user1_id`, `last_message_at`),
  KEY `idx_user2_lastmsg` (`user2_id`, `last_message_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='私信会话表';

-- 私信消息表
CREATE TABLE IF NOT EXISTS `im_messages` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `session_id` bigint(20) NOT NULL,
  `sender_id` bigint(20) NOT NULL,
  `receiver_id` bigint(20) NOT NULL,
  `content` text NOT NULL,
  `msg_type` tinyint(4) DEFAULT '1' COMMENT '1-文本 2-图片',
  `status` tinyint(4) DEFAULT '0' COMMENT '0-已发送 1-已读',
  `is_deleted_for_sender` tinyint(1) DEFAULT '0',
  `is_deleted_for_receiver` tinyint(1) DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session_created` (`session_id`, `created_at`),
  KEY `idx_sender_receiver` (`sender_id`, `receiver_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='私信消息表';