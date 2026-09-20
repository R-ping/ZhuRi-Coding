-- =============================================================================
-- 热点查询索引补齐（content 服务 / 库：leadnews_article）
--
-- 背景：代码评审发现 ap_article / ap_behavior_likes 等核心表仅有主键，没有二级索引，
--       首页列表、推荐候选、最新分栏、标签页、热榜、兴趣画像等查询全部走全表扫描 + filesort。
--       本脚本按「等值列 → 排序列 → 主键」的顺序建复合索引，让过滤与排序都能走同一棵 B+Tree。
--
-- 执行方式（本项目约定：脚本入库，需手动执行）：
--   mysql -h127.0.0.1 -uroot -p123456 --default-character-set=utf8mb4 leadnews_article < add_hot_path_indexes.sql
--   ⚠️ 必须带 --default-character-set=utf8mb4，否则中文注释会双重编码成乱码。
--
-- ⚠️ MySQL 不支持 CREATE INDEX IF NOT EXISTS，本脚本不可重复执行（重复执行会报
--    "Duplicate key name"）。执行前请先用下方「执行前检查」确认索引尚不存在。
-- ⚠️ 大表加索引会锁表/占用 IO，请在低峰期执行；建议先在测试库验证 EXPLAIN 再上生产。
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 执行前检查：确认目标索引尚未存在（返回空即代表可以安全执行）
-- ---------------------------------------------------------------------------
-- SELECT TABLE_NAME, INDEX_NAME FROM information_schema.STATISTICS
--  WHERE TABLE_SCHEMA = 'leadnews_article'
--    AND INDEX_NAME IN (
--      'idx_status_channel_publish','idx_status_publish','idx_status_score',
--      'idx_author_status_publish','idx_column_status_publish',
--      'idx_user_op_time','idx_entry_user',
--      'idx_user_deleted_time',
--      'idx_article_parent_created',
--      'idx_status_deleted_review')
--  GROUP BY TABLE_NAME, INDEX_NAME;

-- ---------------------------------------------------------------------------
-- 1. ap_article：文章主表（首页/推荐/最新/标签页/热榜的共同数据源）
-- ---------------------------------------------------------------------------
-- (status, channel_id, publish_time, id)：频道下按发布时间倒序 —— 首页列表、最新分栏
ALTER TABLE `ap_article`
  ADD INDEX `idx_status_channel_publish` (`status`,`channel_id`,`publish_time`,`id`);

-- (status, publish_time, id)：全站按发布时间倒序（无频道条件）—— 全站时间线
ALTER TABLE `ap_article`
  ADD INDEX `idx_status_publish` (`status`,`publish_time`,`id`);

-- (status, score, id)：按热度分倒序 —— 热榜/推荐的基础排序
-- 注意：ApArticleMapper.xml#selectRecommendCandidates 的 ORDER BY 用了 COALESCE(aa.score,0)，
--       函数包裹列会让优化器放弃该索引排序。若要让推荐也吃到索引，需先把 score 列改为
--       NOT NULL DEFAULT 0（见本目录另附说明），再去掉 SQL 里的 COALESCE。
ALTER TABLE `ap_article`
  ADD INDEX `idx_status_score` (`status`,`score`,`id`);

-- (author_id, status, publish_time, id)：作者主页文章列表
ALTER TABLE `ap_article`
  ADD INDEX `idx_author_status_publish` (`author_id`,`status`,`publish_time`,`id`);

-- (column_id, status, publish_time, id)：专栏下文章列表
ALTER TABLE `ap_article`
  ADD INDEX `idx_column_status_publish` (`column_id`,`status`,`publish_time`,`id`);

-- ---------------------------------------------------------------------------
-- 2. ap_behavior_likes：行为（点赞/收藏）表，仅主键
--    调用点：ApArticleRecommendServiceImpl（兴趣画像 200 条）、ArticleDetailServiceImpl
-- ---------------------------------------------------------------------------
-- (user_id, operation, created_time)：按用户 + 行为类型取最近 N 条
ALTER TABLE `ap_behavior_likes`
  ADD INDEX `idx_user_op_time` (`user_id`,`operation`,`created_time`);

-- (entry_id, user_id, type)：按内容反查是否被某用户操作过（点赞态判定）
ALTER TABLE `ap_behavior_likes`
  ADD INDEX `idx_entry_user` (`entry_id`,`user_id`,`type`);

-- ---------------------------------------------------------------------------
-- 3. ap_browse_history：浏览记录表（已有 idx_user_id / idx_browse_time 单列索引）
--    原查询条件为 user_id + is_deleted + browse_time > ? ORDER BY browse_time DESC，
--    两个单列索引都无法同时覆盖「等值 + 范围 + 排序」，故补复合索引。
-- ---------------------------------------------------------------------------
ALTER TABLE `ap_browse_history`
  ADD INDEX `idx_user_deleted_time` (`user_id`,`is_deleted`,`browse_time`);

-- ---------------------------------------------------------------------------
-- 4. ap_comment：评论表（已有 idx_article_id / idx_parent_id / idx_created_time 三个单列索引）
--    评论列表查询为 article_id + parent_id IS NULL + ORDER BY created_time，
--    复合索引可同时完成过滤与排序，避免 filesort。
-- ---------------------------------------------------------------------------
ALTER TABLE `ap_comment`
  ADD INDEX `idx_article_parent_created` (`article_id`,`parent_id`,`created_time`);

-- ---------------------------------------------------------------------------
-- 5. ap_pins：沸点表（已有 user_id / circle_id / topic_id / created_time 单列索引）
--    PinsQueryService#listLatest 的查询形态为 status = 9 AND is_deleted = 0
--    ORDER BY review_time DESC，需要「状态过滤 + 排序」的复合索引。
--    注意：热门榜 listHot（:110）只做 status/is_deleted 过滤后在 Java 内存中排序，
--    其过滤条件正好是本索引的前缀，无需再为点赞数单建索引。
-- ---------------------------------------------------------------------------
ALTER TABLE `ap_pins`
  ADD INDEX `idx_status_deleted_review` (`status`,`is_deleted`,`review_time`,`id`);

-- ---------------------------------------------------------------------------
-- 执行后验证：用 EXPLAIN 确认 type 由 ALL 变为 ref/range、Extra 中不再出现
--             Using filesort / Using temporary，rows 显著下降。
-- ---------------------------------------------------------------------------
-- EXPLAIN SELECT id, title, publish_time FROM ap_article
--  WHERE status = 9 AND channel_id = 1 ORDER BY publish_time DESC LIMIT 10;

-- EXPLAIN SELECT id FROM ap_behavior_likes
--  WHERE user_id = 1001 AND operation = 1 ORDER BY created_time DESC LIMIT 200;

-- EXPLAIN SELECT id FROM ap_comment
--  WHERE article_id = 123 AND parent_id IS NULL ORDER BY created_time DESC LIMIT 10;

-- ---------------------------------------------------------------------------
-- 回滚（仅在确认索引导致写入放大或优化器选错索引时使用）
-- ---------------------------------------------------------------------------
-- ALTER TABLE `ap_article`         DROP INDEX `idx_status_channel_publish`,
--                                  DROP INDEX `idx_status_publish`,
--                                  DROP INDEX `idx_status_score`,
--                                  DROP INDEX `idx_author_status_publish`,
--                                  DROP INDEX `idx_column_status_publish`;
-- ALTER TABLE `ap_behavior_likes`  DROP INDEX `idx_user_op_time`, DROP INDEX `idx_entry_user`;
-- ALTER TABLE `ap_browse_history`  DROP INDEX `idx_user_deleted_time`;
-- ALTER TABLE `ap_comment`         DROP INDEX `idx_article_parent_created`;
-- ALTER TABLE `ap_pins`            DROP INDEX `idx_status_deleted_review`;
