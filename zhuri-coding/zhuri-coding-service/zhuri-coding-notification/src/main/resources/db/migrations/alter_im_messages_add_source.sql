-- =============================================================
-- 私信消息来源标识：自动私信与用户手打消息可区分 + 投递幂等
-- 数据库：leadnews_notification
-- 配合 AutoReplyServiceImpl.sendFollowAutoReply 使用
-- =============================================================

-- 1. 新增列。
--    sender_type：谁产生的消息。1 真实用户，2 系统以该用户身份代发。
--    source_type/source_id：来源枚举与业务主键，普通私信为空。
--    存量数据默认 sender_type=1、来源为空，无需回填。
ALTER TABLE `im_messages`
  ADD COLUMN `sender_type` TINYINT NOT NULL DEFAULT 1 COMMENT '1真实用户 2系统代发' AFTER `msg_type`,
  ADD COLUMN `source_type` VARCHAR(32) NULL COMMENT '来源枚举，当前仅 FOLLOW_AUTO_REPLY' AFTER `sender_type`,
  ADD COLUMN `source_id` BIGINT NULL COMMENT '来源业务主键（关注关系ID）' AFTER `source_type`;

-- 2. 唯一索引做幂等兜底：同一次关注（source_id）最多一条自动私信。
--    普通消息两列为 NULL，MySQL 唯一索引允许多个 NULL，互不冲突。
ALTER TABLE `im_messages`
  ADD UNIQUE KEY `uk_source` (`source_type`, `source_id`);

-- 3. 发送链路按来源回查（幂等快路径），加上来源索引比走唯一索引前缀更直观；
--    唯一索引已覆盖 (source_type, source_id) 的等值查询，这里不重复建。
