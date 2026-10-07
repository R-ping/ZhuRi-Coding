-- =============================================================================
-- 运营操作审计日志（user 服务 / 库：leadnews_user）
--
-- 这是 content 库同名表的**第二份**（content 侧脚本：zhuri-coding-content/src/main/resources/
-- db/migrations/add_admin_audit_log.sql），字段完全一致、库不同。
--
-- 为什么每个服务各建一份、不做跨服务集中写入：
--   审计是"绝对不能丢"的记录。集中写会引入"审计服务不可用 → 业务照常执行但没留痕"的窗口；
--   本地表与业务变更同库，事务能覆盖。代价是查全量要跨库聚合，可以接受。
--
-- user 侧为什么需要它（不只是留痕）：
--   「警告用户」没有独立业务表 —— 警告没有持久化状态，它的产物就是"一条站内信 + 一条台账"。
--   本表就是警告的台账：警告次数（按 target 统计）与处置历史都从它读（见 UserBanService）。
--   因此 user 侧对本表是**既写又读**，与 content 侧"只写不读"不同。
--
-- 与 content 侧的差异只有一处注释说明，字段/索引逐列相同 —— 便于两边用同一套查询习惯。
--
-- 执行方式（本项目约定：脚本入库，需手动执行）：
--   mysql -h127.0.0.1 -uroot -p123456 --default-character-set=utf8mb4 leadnews_user < add_admin_audit_log.sql
--   ⚠️ 必须带 --default-character-set=utf8mb4，否则中文注释会双重编码成乱码。
--
-- ⚠️ 本脚本可重复执行（CREATE TABLE IF NOT EXISTS）。
--
-- ⚠️ 不执行本脚本的后果（fail-closed，是设计如此）：
--   封禁/解封/警告都会失败并返回错误 —— 因为「封了人但查不到是谁封的」比"这次操作失败"严重得多。
--   同时拦截器对越权尝试的留痕会静默失败（只写日志），不影响拒绝结果。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ap_admin_audit_log` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` int unsigned NOT NULL COMMENT '操作人账号ID',
    `role_codes` varchar(128) DEFAULT NULL COMMENT '操作时的角色快照（逗号分隔；角色可能事后被回收，故存快照）',
    `module` varchar(32) NOT NULL COMMENT '业务模块：REPORT举报 / CONTENT内容 / USER用户 / OPS运营位 / ACCESS访问控制（越权尝试）',
    `action` varchar(64) NOT NULL COMMENT '动作编码，如 USER_WARN / USER_BAN / USER_UNBAN',
    `target_type` varchar(32) DEFAULT NULL COMMENT '对象类型：ARTICLE / COMMENT / PINS / USER；被拒访问时为 ENDPOINT',
    `target_id` varchar(64) DEFAULT NULL COMMENT '对象ID（用字符串以兼容不同主键形态）',
    `reason` varchar(500) NOT NULL COMMENT '操作理由（必填，事后追溯的依据）',
    `detail` text COMMENT '变更摘要（处置前后摘要，不含敏感明文）',
    `result` tinyint NOT NULL DEFAULT '1' COMMENT '结果：1成功 0失败',
    `error_msg` varchar(500) DEFAULT NULL COMMENT '失败原因',
    `ip` varchar(64) DEFAULT NULL COMMENT '操作来源IP',
    `cost_ms` int DEFAULT NULL COMMENT '耗时（毫秒）',
    `created_time` datetime NOT NULL COMMENT '操作时间',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_user_time` (`user_id`, `created_time`),
    KEY `idx_module_time` (`module`, `created_time`),
    KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运营操作审计日志（谁在何时对什么做了什么、为什么）';

-- ---------------------------------------------------------------------------
-- 执行后验证
-- ---------------------------------------------------------------------------
-- SHOW INDEX FROM ap_admin_audit_log;
-- SELECT action, reason, detail, result, user_id, created_time
--   FROM ap_admin_audit_log WHERE target_type='USER' AND target_id='<某个账号ID>'
--   ORDER BY id DESC LIMIT 20;

-- 回滚
-- DROP TABLE IF EXISTS `ap_admin_audit_log`;
