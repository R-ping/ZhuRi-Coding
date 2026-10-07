-- =============================================================================
-- 账号封禁字段（user 服务 / 库：leadnews_user）
--
-- 背景：运营侧此前只能处置内容（下架文章、折叠评论），违规主体是"账号"时无处下手 ——
--       下架一篇只能治一篇，账号继续发下一篇。本脚本给出封禁所需的最小字段集。
--
-- 设计取舍：
--   1) **不复用 status**。status=0 在本系统的语义是「已注销/已锁定」：
--      UserFeignController#getValidUserIds 按它把账号从批量投递名单里剔除。
--      把封禁塞进 status，被封用户看起来就跟自愿注销一样，运营既分不清是
--      "自己注销的"还是"被平台封的"，也就无从解封。所以封禁另开一组正交字段。
--   2) **用「截止时间」而不是布尔位**。判定就是 ban_until > NOW()，到期自动失效；
--      永久封禁写入远期时间（9999-12-31 23:59:59，见 UserBanServiceImpl#PERMANENT_UNTIL），
--      于是永久与临时是同一套读写逻辑，**不需要一个定时任务去把到期账号挨个解开** ——
--      少一个会漏跑、会重复跑、还得处理"解封后又被重新封禁"竞态的组件。
--   3) **ban_reason 与 ban_operator_id 是给人看的**：当事人在登录被拒时会直接看到理由
--      （他没有别的渠道能知道为什么 —— 被封了连站内信都读不到）。
--   4) **不建外键**。与全库一致（ap_user 与 user_profile 之间也没有），
--      且运营账号本身也在这张表里，自引用外键只会带来删除顺序问题。
--   5) 解封时四个字段会被清空（不是留着当"历史"）：保住
--      「ban_until IS NULL = 没被封过」这个不变量。封禁历史完整留在 ap_admin_audit_log。
--
-- 执行方式（本项目约定：脚本入库，需手动执行）：
--   mysql -h127.0.0.1 -uroot -p123456 --default-character-set=utf8mb4 leadnews_user < add_user_ban_fields.sql
--   ⚠️ 必须带 --default-character-set=utf8mb4，否则中文注释会双重编码成乱码。
--
-- ⚠️ **本脚本不可重复执行**：MySQL 没有 ADD COLUMN IF NOT EXISTS，
--    重复执行会因 "Duplicate column name" 报错（错误无害，可忽略）。
--
-- ⚠️ 不执行本脚本的后果：封禁接口调用即报 Unknown column 'ban_until'，
--    表现为 500；登录不受影响（封禁校验查不到列时按"未封禁"fail-open 放行）。
-- =============================================================================

ALTER TABLE `ap_user`
    ADD COLUMN `ban_until` datetime DEFAULT NULL COMMENT '封禁截止时间：> NOW() 表示封禁中；NULL 表示未被封禁' AFTER `status`,
    ADD COLUMN `ban_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '封禁理由（对当事人展示）' AFTER `ban_until`,
    ADD COLUMN `ban_time` datetime DEFAULT NULL COMMENT '本次封禁的操作时间' AFTER `ban_reason`,
    ADD COLUMN `ban_operator_id` int unsigned DEFAULT NULL COMMENT '执行封禁的运营账号ID' AFTER `ban_time`;

-- ---------------------------------------------------------------------------
-- 索引说明（刻意只有一条）
--
-- idx_ban_until 支撑封禁名单的主查询「WHERE ban_until > NOW() ORDER BY ban_time DESC, id DESC」。
-- 之所以带上 id：ban_time 可能相同（批量封禁），没有 tie-breaker 时 LIMIT 分页会错行。
-- 不需要 ban_operator_id 的索引 —— 查"某人封过谁"属于审计查询，走 ap_admin_audit_log。
-- ---------------------------------------------------------------------------
ALTER TABLE `ap_user`
    ADD KEY `idx_ban_until` (`ban_until`, `ban_time`, `id`);

-- ---------------------------------------------------------------------------
-- 执行后验证
-- ---------------------------------------------------------------------------
-- SHOW COLUMNS FROM ap_user LIKE 'ban%';
-- SELECT id, nickname, ban_until, ban_reason, ban_operator_id FROM ap_user WHERE ban_until > NOW();

-- 回滚
-- ALTER TABLE `ap_user` DROP KEY `idx_ban_until`,
--     DROP COLUMN `ban_operator_id`, DROP COLUMN `ban_time`,
--     DROP COLUMN `ban_reason`, DROP COLUMN `ban_until`;
