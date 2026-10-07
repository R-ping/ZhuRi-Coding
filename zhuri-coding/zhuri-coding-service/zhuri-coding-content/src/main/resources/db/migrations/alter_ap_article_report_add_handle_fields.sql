-- =============================================================================
-- ap_article_report 补运营处置字段（content 服务 / 库：leadnews_article）
--
-- 背景：举报功能原先是「只有入口、没有出口」——全仓库只有一处 insert（status=0），
--       没有任何代码把 status 改成 1，也没有任何运营界面。运营后台落地后需要把处置结论
--       落库，并向举报人回执，因此补以下字段。
--
-- 为什么 status 与 handle_result 要分开：
--   status 只有 0待处理/1已处理 两态，表达"办没办"；handle_result 表达"怎么办的"
--   （驳回/警告/下架/折叠）。合成一个字段会导致"已处理但不知道处理成什么"，
--   而处置结论恰恰是回执给举报人的内容。
--
-- notify_status 单列的理由：「处置成功」与「回执送达」是两件事。通知服务不可用时
--   处置不能回滚（内容该下架还是得下架），但回执必须能补发，所以需要独立状态位支持重试。
--
-- 执行方式（本项目约定：脚本入库，需手动执行）：
--   mysql -h127.0.0.1 -uroot -p123456 --default-character-set=utf8mb4 leadnews_article < alter_ap_article_report_add_handle_fields.sql
--   ⚠️ 必须带 --default-character-set=utf8mb4，否则中文注释会双重编码成乱码。
--
-- ⚠️ MySQL 不支持 ADD COLUMN IF NOT EXISTS，本脚本不可重复执行。
--    执行前请先用下方「执行前检查」确认字段尚不存在。
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 执行前检查（返回空 = 可以执行）
-- ---------------------------------------------------------------------------
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = 'leadnews_article' AND TABLE_NAME = 'ap_article_report'
--    AND COLUMN_NAME IN ('handle_result','handle_reason','handler_id','handle_time','notify_status');

ALTER TABLE `ap_article_report`
  ADD COLUMN `handle_result` tinyint DEFAULT NULL
      COMMENT '处置结论：1驳回举报 2警告作者 3下架内容 4折叠评论',
  ADD COLUMN `handle_reason` varchar(500) DEFAULT NULL
      COMMENT '处置说明（运营填写，会回执给举报人）',
  ADD COLUMN `handler_id` int unsigned DEFAULT NULL
      COMMENT '处置人账号ID',
  ADD COLUMN `handle_time` datetime DEFAULT NULL
      COMMENT '处置时间',
  ADD COLUMN `notify_status` tinyint NOT NULL DEFAULT '0'
      COMMENT '回执通知状态：0未发 1已发 2发送失败（可重试）',
  -- 运营队列的主查询形态：按状态筛选 + 按时间倒序。等值列在前、排序列在后。
  ADD KEY `idx_status_created` (`status`, `created_time`);

-- ---------------------------------------------------------------------------
-- 执行后验证
-- ---------------------------------------------------------------------------
-- SHOW INDEX FROM ap_article_report WHERE Key_name = 'idx_status_created';
-- SELECT id, status, handle_result, handle_reason, handler_id, handle_time, notify_status
--   FROM ap_article_report ORDER BY id DESC LIMIT 10;

-- ---------------------------------------------------------------------------
-- 回滚
-- ---------------------------------------------------------------------------
-- ALTER TABLE `ap_article_report`
--   DROP INDEX `idx_status_created`,
--   DROP COLUMN `notify_status`,
--   DROP COLUMN `handle_time`,
--   DROP COLUMN `handler_id`,
--   DROP COLUMN `handle_reason`,
--   DROP COLUMN `handle_result`;
