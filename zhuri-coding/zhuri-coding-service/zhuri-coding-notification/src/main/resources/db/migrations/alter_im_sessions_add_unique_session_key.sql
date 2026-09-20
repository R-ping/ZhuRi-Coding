-- =============================================================
-- IM 会话唯一键：防止高并发下重复创建会话
-- 数据库：leadnews_notification
-- 配合 ImServiceImpl.getOrInsertSession 的 DuplicateKeyException 回读逻辑使用
-- =============================================================

-- 1. 先确认无重复 session_key（若有重复，需先合并清理再执行本脚本）
--    SELECT session_key, COUNT(*) c FROM im_sessions GROUP BY session_key HAVING c > 1;

-- 2. 为 session_key 添加唯一索引
ALTER TABLE `im_sessions`
  ADD UNIQUE KEY `uk_session_key` (`session_key`);