-- =============================================================================
-- 登录查询索引补齐（user 服务 / 库：leadnews_user）
--
-- 背景：代码评审发现 ap_user 仅有主键，而密码登录按 phone / email 等值查询
--       （ApUserServiceImpl#login：eq(ApUser::getPhone/Email, phoneOrEmail)），
--       登录属最高频路径之一，缺索引将随用户量线性劣化（全表扫描）。
--
-- 执行方式（本项目约定：脚本入库，需手动执行）：
--   mysql -h127.0.0.1 -uroot -p123456 --default-character-set=utf8mb4 leadnews_user < add_user_login_indexes.sql
--   ⚠️ 必须带 --default-character-set=utf8mb4，否则中文注释会双重编码成乱码。
--
-- ⚠️ 唯一索引要求存量数据无重复。请务必先跑下面的「执行前检查」，
--    若查出重复手机号/邮箱，先治理数据再加唯一索引，否则 ALTER 会失败。
-- ⚠️ 本脚本不可重复执行（MySQL 不支持 CREATE INDEX IF NOT EXISTS）。
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 执行前检查：存量重复数据核查（两条都应返回空结果）
-- ---------------------------------------------------------------------------
-- 重复手机号
-- SELECT phone, COUNT(*) c FROM ap_user
--  WHERE phone IS NOT NULL AND phone <> '' GROUP BY phone HAVING c > 1;
-- 重复邮箱
-- SELECT email, COUNT(*) c FROM ap_user
--  WHERE email IS NOT NULL AND email <> '' GROUP BY email HAVING c > 1;
--
-- 目标索引是否已存在（返回空即代表可以安全执行）
-- SELECT INDEX_NAME FROM information_schema.STATISTICS
--  WHERE TABLE_SCHEMA = 'leadnews_user' AND TABLE_NAME = 'ap_user'
--    AND INDEX_NAME IN ('uk_phone','uk_email') GROUP BY INDEX_NAME;

-- ---------------------------------------------------------------------------
-- 唯一索引
-- 说明：MySQL 的唯一索引允许多个 NULL，因此历史用户中 phone / email 为 NULL 的行不受影响；
--       但空字符串 '' 会被视为有效值参与去重，若存在多条 phone='' 需先统一改为 NULL。
-- ---------------------------------------------------------------------------
ALTER TABLE `ap_user`
  ADD UNIQUE KEY `uk_phone` (`phone`);

ALTER TABLE `ap_user`
  ADD UNIQUE KEY `uk_email` (`email`);

-- ---------------------------------------------------------------------------
-- 执行后验证：EXPLAIN 的 type 应为 const/ref，rows 为 1，且 key 命中 uk_phone / uk_email
-- ---------------------------------------------------------------------------
-- EXPLAIN SELECT id, nickname, password FROM ap_user WHERE phone = '13800000000';
-- EXPLAIN SELECT id, nickname, password FROM ap_user WHERE email = 'a@b.com';

-- ---------------------------------------------------------------------------
-- 回滚
-- ---------------------------------------------------------------------------
-- ALTER TABLE `ap_user` DROP INDEX `uk_phone`, DROP INDEX `uk_email`;

-- ---------------------------------------------------------------------------
-- 遗留项（本次未纳入，需产品确认后单独处理）
-- ---------------------------------------------------------------------------
-- ap_user_social_binding 的 phone / open_id / git_uid / weibo_uid 也是三方登录的回查键，
-- 建议按 (platform, open_id) 等实际查询形态补唯一索引，避免重复绑定。
-- 见评审报告 P1-12 与「进一步加深方向」。
