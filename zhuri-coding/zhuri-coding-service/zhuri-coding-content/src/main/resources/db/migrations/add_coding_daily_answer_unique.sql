-- =============================================================================
-- ap_coding_answer_record：「当日一题」补唯一约束（Coding 延展第一层 / 库：leadnews_article）
--
-- 背景：当日一题的防重原先只靠「Redis 锁 + 事务内 SELECT」——CodingAnswerTxService#saveAnswer
--       在事务里先 findDailyRecord(用户, 今天, is_daily=1)，命中就直接抛"今日一题已作答"。
--       这道预查询在锁有效期内没问题，但锁只有 10s（长事务、GC 停顿、慢 SQL 都可能让它过期），
--       一旦两个请求同时越过预查询，就会插出两条同日重复作答：
--         * 榜单按 elapsed_seconds 取最快记录，重复行会让同一用户占两个名次；
--         * ap_coding_user_stat.total_count / ap_coding_question.answer_count 被重复 +1。
--       这里补一条唯一约束，把"并发下同一天只能有一条当日一题记录"变成数据库层的事实。
--
-- 为什么不能直接 UNIQUE(user_id, answer_date)：
--       本表同时承载「自由练习」（is_daily = 0），而自由练习允许同一用户同一天反复作答同一题，
--       直接加唯一键会把练习场景全部拒掉。所以用「生成列 + 条件唯一」的手法：
--       只在 is_daily = 1 时生成键值，自由练习生成为 NULL，而唯一索引允许多个 NULL 共存。
--
-- 执行方式（本项目约定：脚本入库，需手动执行）：
--   mysql -h127.0.0.1 -uroot -p123456 --default-character-set=utf8mb4 leadnews_article < add_coding_daily_answer_unique.sql
--   ⚠️ 必须带 --default-character-set=utf8mb4，否则中文注释会双重编码成乱码。
--
-- ⚠️ MySQL 不支持 CREATE INDEX IF NOT EXISTS / ADD COLUMN IF NOT EXISTS，
--    本脚本不可重复执行（重复执行会报 "Duplicate column name"）。
-- ⚠️ 若表中已存在同日重复的当日一题记录，加唯一键会直接失败 —— 必须先跑第 0 步检查与清理。
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 0. 执行前检查：找出已有的「同一用户 + 同一天 + 当日一题」重复记录
--    返回空集 = 可以直接往下执行；有输出 = 先按下方清理语句去重。
-- ---------------------------------------------------------------------------
-- SELECT user_id, answer_date, COUNT(*) AS dup_count, MIN(id) AS keep_id, MAX(id) AS drop_id
--   FROM ap_coding_answer_record
--  WHERE is_daily = 1
--  GROUP BY user_id, answer_date
-- HAVING dup_count > 1;

-- ---------------------------------------------------------------------------
-- 0b. 清理重复（保留最早的一条，删掉其余；执行前建议先 SELECT 同条件确认行数）
--     注意：这里只删流水行。被重复计入的 ap_coding_user_stat.total_count 与
--     ap_coding_question.answer_count 是累加值、无法精确回滚，量级极小（重复次数最多几条），
--     按"不回补"处理。
-- ---------------------------------------------------------------------------
-- DELETE r FROM ap_coding_answer_record r
--   JOIN (SELECT MIN(id) AS keep_id, user_id, answer_date
--           FROM ap_coding_answer_record
--          WHERE is_daily = 1
--          GROUP BY user_id, answer_date
--         HAVING COUNT(*) > 1) d
--     ON r.user_id = d.user_id
--    AND r.answer_date = d.answer_date
--    AND r.is_daily = 1
--    AND r.id <> d.keep_id;

-- ---------------------------------------------------------------------------
-- 1. 加生成列 + 条件唯一键
--
--    daily_key = is_daily = 1 ? "userId:answerDate" : NULL
--      * 用 STORED 而非 VIRTUAL：值随行物化，唯一索引与回查都不依赖二次求值；
--        表体量小，多占一列存储无实际代价。
--      * VARCHAR(32) 足够：INT 用户ID 最长 10 位 + 分隔符 1 位 + DATE 10 位 = 21 位。
--      * 自由练习（is_daily = 0）生成 NULL，唯一索引对多个 NULL 不判重，练习不受影响。
-- ---------------------------------------------------------------------------
ALTER TABLE `ap_coding_answer_record`
  ADD COLUMN `daily_key` VARCHAR(32)
      GENERATED ALWAYS AS (IF(`is_daily` = 1, CONCAT(`user_id`, ':', `answer_date`), NULL)) STORED
      COMMENT '当日一题唯一键（is_daily=1 时为 userId:answerDate，自由练习为 NULL）',
  ADD UNIQUE KEY `uk_user_daily` (`daily_key`);

-- ---------------------------------------------------------------------------
-- 执行后验证：应返回 daily_key 落在 (user_id, answer_date) 上的唯一索引
-- ---------------------------------------------------------------------------
-- SHOW INDEX FROM ap_coding_answer_record WHERE Key_name = 'uk_user_daily';

-- 验证唯一性真的生效（第二条应报 Duplicate entry）：
-- INSERT INTO ap_coding_answer_record
--   (user_id, question_id, answer_date, user_answer, is_correct, is_daily)
--   VALUES (1001, 1, CURDATE(), '[0]', 1, 1);
-- INSERT INTO ap_coding_answer_record
--   (user_id, question_id, answer_date, user_answer, is_correct, is_daily)
--   VALUES (1001, 2, CURDATE(), '[1]', 0, 1);

-- 验证自由练习不受约束（两条都应成功，因为 daily_key 为 NULL）：
-- INSERT INTO ap_coding_answer_record
--   (user_id, question_id, answer_date, user_answer, is_correct, is_daily)
--   VALUES (1001, 1, CURDATE(), '[0]', 1, 0);
-- INSERT INTO ap_coding_answer_record
--   (user_id, question_id, answer_date, user_answer, is_correct, is_daily)
--   VALUES (1001, 1, CURDATE(), '[0]', 1, 0);

-- ---------------------------------------------------------------------------
-- 回滚
-- ---------------------------------------------------------------------------
-- ALTER TABLE `ap_coding_answer_record`
--   DROP INDEX `uk_user_daily`,
--   DROP COLUMN `daily_key`;
