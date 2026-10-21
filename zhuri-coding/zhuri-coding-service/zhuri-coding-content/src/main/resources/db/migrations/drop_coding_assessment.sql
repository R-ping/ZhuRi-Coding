-- =============================================================
-- 删除能力测评（选择题形态整层下线）
-- 数据库：leadnews_article
--
-- 原因：每日一题已换简答，测评是仅剩的选择题形态 —— 选择题只能判断
--       "记不记得一个事实"，测不出"能不能讲清楚"，与整个 Coding 层的目标不符。
--       「能力快照」这个职责由模拟面试报告承担（主题级三维等级 + 覆盖清单 + 综合等级）。
--
-- ⚠️ 测评数据备份到 ap_coding_assessment_bak_20261009 后原表删除。
-- =============================================================

CREATE TABLE IF NOT EXISTS `ap_coding_assessment_bak_20261009`
  AS SELECT * FROM `ap_coding_assessment`;

DROP TABLE `ap_coding_assessment`;

-- 隐私开关的「测评成绩」分项随之消失
ALTER TABLE `ap_coding_profile_setting` DROP COLUMN `public_assessment`;

-- 执行后验证
-- SHOW TABLES LIKE 'ap_coding_assessment%';            -- 应只剩 _bak_20261009
-- SHOW COLUMNS FROM ap_coding_profile_setting LIKE 'public_assessment';  -- 应为空
