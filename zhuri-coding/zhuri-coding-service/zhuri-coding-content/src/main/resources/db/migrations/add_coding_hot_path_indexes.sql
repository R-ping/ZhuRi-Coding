-- =============================================================================
-- Coding 延展（能力测评 / 模拟面试）热路径索引补齐（content 服务 / 库：leadnews_article）
--
-- 背景：ap_coding_interview / ap_coding_assessment 建表时只给了「按用户看历史」的单列索引：
--         interview   : idx_user_started(user_id, started_time) / idx_status(status)
--         assessment  : idx_user_created(user_id, created_time) / idx_status_score(status, score)
--       但真正高频的查询是「取该用户当前进行中/最近一次已提交的场次」，其形态为
--       user_id 等值 + status 等值 + 按时间/主键倒序取 1 条。单列索引都无法同时完成
--       「等值过滤 + 排序」，只能扫出该用户全部行再 filesort。
--       interview 因为还有每日场次上限（3），行数可控；assessment 没有每日上限
--       （弃卷→过期→重开，只受 90 天冷却约束，而冷却只管「已提交」），
--       所以 status=3 的废行可以无限累积。若不加索引，selectOngoing 每次都要把这批废行扫一遍。
--
-- 执行方式（本项目约定：脚本入库，需手动执行）：
--   mysql -h127.0.0.1 -uroot -p123456 --default-character-set=utf8mb4 leadnews_article < add_coding_hot_path_indexes.sql
--   ⚠️ 必须带 --default-character-set=utf8mb4，否则中文注释会双重编码成乱码。
--
-- ⚠️ MySQL 不支持 CREATE INDEX IF NOT EXISTS，本脚本不可重复执行（重复执行会报
--    "Duplicate key name"）。执行前请先用下方「执行前检查」确认索引尚不存在。
-- ⚠️ 这两张表目前数据量很小，正常执行是秒级；仍建议先在测试库 EXPLAIN 验证再上生产。
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 执行前检查：确认目标索引尚未存在（返回空即代表可以安全执行）
-- ---------------------------------------------------------------------------
-- SELECT TABLE_NAME, INDEX_NAME FROM information_schema.STATISTICS
--  WHERE TABLE_SCHEMA = 'leadnews_article'
--    AND INDEX_NAME IN ('idx_user_status_id','idx_user_status_submitted')
--  GROUP BY TABLE_NAME, INDEX_NAME;

-- ---------------------------------------------------------------------------
-- 1. ap_coding_interview：模拟面试记录
--
--    覆盖两个查询，一个是热路径、一个是列表页：
--    a) ApCodingInterviewMapper#selectOngoing
--         WHERE user_id = ? AND status = 1 ORDER BY id DESC LIMIT 1
--       —— start / current / submit 三个接口每次都要调，是最高频的一条。
--    b) CodingInterviewServiceImpl#history 的分页查询
--         WHERE user_id = ? AND status = 2 ORDER BY id DESC LIMIT ?, ?
--       两者等值前缀相同（user_id, status），只差 status 的取值（1 / 2），
--       索引末尾带 id 后，ORDER BY id DESC 可以走索引反向扫描（MySQL 支持 backward
--       index scan），既省掉 filesort，也不必把该用户的全部历史行读出来。
-- ---------------------------------------------------------------------------
ALTER TABLE `ap_coding_interview`
  ADD INDEX `idx_user_status_id` (`user_id`,`status`,`id`);

-- ---------------------------------------------------------------------------
-- 2. ap_coding_assessment：能力测评记录
--
--    覆盖三个查询：
--    a) ApCodingAssessmentMapper#selectLatestSubmitted（热路径，开卷前查冷却 / 档案 / 成绩单）
--         WHERE user_id = ? AND status = 2 ORDER BY submitted_time DESC, id DESC LIMIT 1
--       现有 idx_user_created 排的是 created_time，对 submitted_time 的排序完全用不上，
--       本索引让等值前缀（user_id, status）之后正好按 submitted_time, id 有序，反向扫描即可。
--    b) ApCodingAssessmentMapper#selectOngoing（热路径，同为 start / current / submit 必调）
--         WHERE user_id = ? AND status = 1 ORDER BY id DESC LIMIT 1
--       等值前缀到 status 为止，索引范围本身就只剩「进行中」那 0~1 行，
--       因此即便 ORDER BY id DESC 与索引内层顺序不一致、产生 filesort，代价也只是对 0~1 行排序；
--       关键收益是**不再需要扫描该用户累积的 status=3 废行**。
--    c) CodingAssessmentServiceImpl#history 的分页查询
--         WHERE user_id = ? ORDER BY id DESC LIMIT ?, ?
--       这条没有 status 过滤，只能吃到 user_id 前缀，会保留一次有界的 filesort
--       （排序集合 = 该用户的行数）。它是用户主动触发的低频接口，不值得再单建一条
--       (user_id, id) 索引去换写入放大。
--
--    说明：本表两个「取 1 条」的查询共用这一条索引即可覆盖，无需再建 (user_id, status, id)。
-- ---------------------------------------------------------------------------
ALTER TABLE `ap_coding_assessment`
  ADD INDEX `idx_user_status_submitted` (`user_id`,`status`,`submitted_time`,`id`);

-- ---------------------------------------------------------------------------
-- 刻意未建的索引（避免写入放大）
--
--   * ap_coding_interview#countToday
--       WHERE user_id = ? AND status IN (1,2) AND started_time >= ?
--     现有 idx_user_started(user_id, started_time) 已能用 user_id 等值 + started_time 范围
--     把候选集压到「今天开的几场」，再回表过滤 status 代价极小，故不再为它单建索引。
--
--   * ap_coding_interview#selectRecentPlanSnapshots
--       WHERE user_id = ? AND status = 2 ORDER BY started_time DESC LIMIT ?
--     同上，走 idx_user_started 只多一次 status 过滤；该 javadoc 已记录此取舍。
--
--   * 两侧 expireStaleOngoing（定时收尾）
--       WHERE status = 1 AND (deadline_time IS NULL OR deadline_time < ?) LIMIT ?
--     以 status 为唯一等值条件，现有 idx_status(status) 已覆盖，且 status=1 的行数
--     本就等于「当前正在进行的场次」，恒为极小值。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 执行后验证：确认 type 由 ALL/index 变为 ref，Extra 中不再出现 Using filesort
-- ---------------------------------------------------------------------------
-- EXPLAIN SELECT id, status FROM ap_coding_interview
--  WHERE user_id = 1001 AND status = 1 ORDER BY id DESC LIMIT 1;

-- EXPLAIN SELECT id, status FROM ap_coding_interview
--  WHERE user_id = 1001 AND status = 2 ORDER BY id DESC LIMIT 0, 10;

-- EXPLAIN SELECT id, score, correct_count, total_count, status, submitted_time
--   FROM ap_coding_assessment
--  WHERE user_id = 1001 AND status = 2 ORDER BY submitted_time DESC, id DESC LIMIT 1;

-- EXPLAIN SELECT id, status FROM ap_coding_assessment
--  WHERE user_id = 1001 AND status = 1 ORDER BY id DESC LIMIT 1;

-- ---------------------------------------------------------------------------
-- 回滚（仅在确认索引导致写入放大或优化器选错索引时使用）
-- ---------------------------------------------------------------------------
-- ALTER TABLE `ap_coding_interview`   DROP INDEX `idx_user_status_id`;
-- ALTER TABLE `ap_coding_assessment`  DROP INDEX `idx_user_status_submitted`;
