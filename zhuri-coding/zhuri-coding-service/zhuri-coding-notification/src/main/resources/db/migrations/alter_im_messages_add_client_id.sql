-- =============================================================
-- 私信消息客户端去重键：断线重发 / 超时重试不再产出重复消息
-- 数据库：leadnews_notification
-- 配合 ImServiceImpl.sendMessage 的「先按 (session_id, client_id) 查重」逻辑使用
-- =============================================================

-- 1. 先确认无重复（若已有数据存在重复，需先清理再执行本脚本）
--    SELECT session_id, client_id, COUNT(*) c FROM im_messages
--    WHERE client_id IS NOT NULL GROUP BY session_id, client_id HAVING c > 1;

-- 2. 新增列。允许为空：存量消息与不传 clientId 的客户端都没有它，
--    为空即表示「这条不去重」，保持改动前向后的兼容。
ALTER TABLE `im_messages`
  ADD COLUMN `client_id` VARCHAR(64) NULL COMMENT '客户端去重ID，同一会话内唯一；为空表示不去重'
  AFTER `receiver_id`;

-- 3. 唯一索引。MySQL 的唯一索引允许存在多个 NULL，
--    所以「不传 clientId」的消息仍能共存，不会互相冲突。
ALTER TABLE `im_messages`
  ADD UNIQUE KEY `uk_session_client` (`session_id`, `client_id`);
