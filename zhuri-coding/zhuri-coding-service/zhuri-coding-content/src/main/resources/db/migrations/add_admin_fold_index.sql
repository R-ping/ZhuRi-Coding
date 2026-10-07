-- =============================================================================
-- 运营「内容折叠」列表索引（content 服务 / 库：leadnews_article）
--
-- 背景：运营后台新增折叠复核列表，查询形态固定为
--         SELECT ... FROM ap_comment WHERE is_hidden = ? ORDER BY id DESC LIMIT ?, ?
--       两张表（ap_comment / ap_pins_comment）在建表时都没有 is_hidden 索引，
--       于是「只看已折叠」这条最常用的查询要走全表扫描 + filesort。
--
-- 列序 = 等值列 → 排序列：
--   is_hidden 是等值条件，id 是排序列（同时充当分页 tie-breaker）。
--   索引 (is_hidden, id) 既能定位到 is_hidden = 1 的那一段，又天然按 id 有序，
--   排序一步省掉；写反过来 (id, is_hidden) 就只能扫全表。
--
-- ⚠️ 本脚本**不可重复执行**（MySQL 没有 CREATE INDEX IF NOT EXISTS）。
--    重复执行会报 "Duplicate key name"，属预期，忽略即可。
-- 执行: mysql -h127.0.0.1 -uroot -p123456 --default-character-set=utf8mb4 leadnews_article < add_admin_fold_index.sql
--
-- 不执行的后果：功能正常，只是折叠列表变慢（数据量小时感知不到）。
-- =============================================================================

ALTER TABLE `ap_comment` ADD INDEX `idx_hidden_id` (`is_hidden`, `id`);

ALTER TABLE `ap_pins_comment` ADD INDEX `idx_hidden_id` (`is_hidden`, `id`);

-- ---------------------------------------------------------------------------
-- 执行后验证
-- ---------------------------------------------------------------------------
-- SHOW INDEX FROM ap_comment WHERE Key_name = 'idx_hidden_id';
-- EXPLAIN SELECT id FROM ap_comment WHERE is_hidden = 1 ORDER BY id DESC LIMIT 20;
--   → key = idx_hidden_id，Extra 里不应出现 "Using filesort"

-- 回滚
-- ALTER TABLE `ap_comment` DROP INDEX `idx_hidden_id`;
-- ALTER TABLE `ap_pins_comment` DROP INDEX `idx_hidden_id`;
