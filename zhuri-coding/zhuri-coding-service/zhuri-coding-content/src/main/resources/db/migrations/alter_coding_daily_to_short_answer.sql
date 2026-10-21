-- =============================================================
-- 每日一题：选择题 → 简答题 的表改造
-- 数据库：leadnews_article
--
-- 三张表一起改，且必须原子完成：
--   ap_coding_answer_record / ap_coding_user_stat / （题目池见 create_coding_daily_pool.sql）
--
-- ⚠️ 本脚本会清空历史作答数据。原因：选择题流水（答案下标 + is_correct）在简答口径下
--    语义不成立，is_correct → level 没有可靠的映射规则。执行前已备份到
--    ap_coding_answer_record_bak_20261009 / ap_coding_user_stat_bak_20261009。
--
-- ⚠️ MySQL 不支持 ADD/DROP COLUMN IF EXISTS，本脚本不可重复执行。
-- =============================================================

-- ---------------------------------------------------------------------------
-- 0. 备份（可回滚）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ap_coding_answer_record_bak_20261009`
  AS SELECT * FROM `ap_coding_answer_record`;
CREATE TABLE IF NOT EXISTS `ap_coding_user_stat_bak_20261009`
  AS SELECT * FROM `ap_coding_user_stat`;

-- ---------------------------------------------------------------------------
-- 1. ap_coding_answer_record
--
--    「生成列 + 条件唯一键」这套手法退役：它当初存在的唯一理由是"一张表同时装
--    当日一题和自由练习"，而练习概念消失后 is_daily 恒为 1 —— 直接对
--    (user_id, answer_date) 建唯一键即可，不再需要 daily_key。
--
--    user_answer 原为 VARCHAR(50)（装 JSON 下标数组），装不下简答文本 → 改 TEXT。
--    question_id 语义从"选择题ID"变为"题目池ID"，一并改名 pool_id 避免误读。
-- ---------------------------------------------------------------------------
ALTER TABLE `ap_coding_answer_record`
  DROP INDEX `uk_user_daily`,
  DROP INDEX `idx_daily_date`,
  DROP COLUMN `daily_key`,
  DROP COLUMN `is_correct`,
  DROP COLUMN `is_daily`,
  CHANGE COLUMN `question_id` `pool_id` bigint NOT NULL COMMENT '题目池ID（ap_coding_daily_pool.id）',
  MODIFY COLUMN `user_answer` text NOT NULL COMMENT '用户作答文本',
  MODIFY COLUMN `elapsed_seconds` int DEFAULT NULL COMMENT '作答用时（秒）',
  ADD COLUMN `level` tinyint DEFAULT NULL COMMENT '综合等级 1-5（评估产出，未评估为 NULL）' AFTER `user_answer`,
  ADD COLUMN `feedback` text COMMENT '点评' AFTER `level`,
  ADD COLUMN `covered` text COMMENT '已覆盖考点（JSON 数组）' AFTER `feedback`,
  ADD COLUMN `missing` text COMMENT '未覆盖考点（JSON 数组）' AFTER `covered`,
  ADD UNIQUE KEY `uk_user_date` (`user_id`, `answer_date`);

-- 历史流水清空（reason 见文件头）。统计表随之一并重建，因此这里只动流水。
TRUNCATE TABLE `ap_coding_answer_record`;

-- ---------------------------------------------------------------------------
-- 2. ap_coding_user_stat
--
--    删除选择题口径的计数字段（答对数 / 练习数）；加 direction（用户选的练习方向，
--    每日一题按它抽题，也是面试「薄弱方向」之外的又一路径）。
--
--    tag_stats 口径同时改变：
--      旧：{"Redis":{"total":3,"correct":2}}   选择题——正确率
--      新：{"Redis":{"total":3,"levelSum":11}}  简答题——累计等级（均值由读取方算）
--    字段名与 JSON 形状保留，是为了让能力档案的领域分布块与面试的弱项输入
--    继续读同一列，只是换了口径。
-- ---------------------------------------------------------------------------
ALTER TABLE `ap_coding_user_stat`
  DROP COLUMN `correct_count`,
  DROP COLUMN `practice_count`,
  DROP COLUMN `practice_correct_count`,
  MODIFY COLUMN `tag_stats` text COMMENT '领域答题分布（JSON：{"Redis":{"total":3,"levelSum":11}}，levelSum 为累计等级）',
  ADD COLUMN `direction` varchar(64) DEFAULT NULL COMMENT '用户选择的练习方向（每日一题按此抽题）' AFTER `user_id`;

TRUNCATE TABLE `ap_coding_user_stat`;

-- ---------------------------------------------------------------------------
-- 执行后验证
-- ---------------------------------------------------------------------------
-- SHOW CREATE TABLE ap_coding_answer_record\G   -- 应有 uk_user_date，无 daily_key/is_correct/is_daily
-- SHOW CREATE TABLE ap_coding_user_stat\G      -- 应有 direction，无 correct_count/practice_*
-- SELECT direction, COUNT(*) FROM ap_coding_daily_pool GROUP BY direction;
