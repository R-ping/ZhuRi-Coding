-- =============================================================================
-- 运营操作审计日志（content 服务 / 库：leadnews_article）
--
-- 背景：运营后台的每个动作都会直接改变他人内容或账号状态（下架文章、折叠评论、封禁用户）。
--       这类操作必须「可追溯」——谁、什么时候、对什么、做了什么、**为什么**。
--       合规上这是刚需（内容治理留痕），工程上它是排查争议的唯一依据。
--
-- 设计取舍：
--   1) **每个服务本地各建一份同名表，不做跨服务集中写入**。审计是"绝对不能丢"的记录，
--      走 Feign 集中写会引入"审计服务不可用 → 业务照常执行但没留痕"的窗口。
--      本表在 content 库先建；user 服务上线「用户处置」时，用同一份 DDL 在 leadnews_user 再建一份。
--   2) **reason 必填**（NOT NULL），由前端表单强制。理由事后补不回来 —— 操作时不想写，
--      事后就不会写，所以必须在操作当时就要求填。
--   3) **role_codes 存快照**：角色可以被回收，但"当时以什么身份做的"必须固化下来，
--      否则事后追溯会出现"他没有这个角色却做了这个操作"的假象。
--   4) **detail 只存变更摘要，不存敏感明文**（如用户手机号不落进来）。
--   5) **失败也要记**（result=0 + error_msg）。审计的价值一半在"谁试过但没成功"。
--
-- 执行方式（本项目约定：脚本入库，需手动执行）：
--   mysql -h127.0.0.1 -uroot -p123456 --default-character-set=utf8mb4 leadnews_article < add_admin_audit_log.sql
--   ⚠️ 必须带 --default-character-set=utf8mb4，否则中文注释会双重编码成乱码。
--
-- ⚠️ 本脚本可重复执行（CREATE TABLE IF NOT EXISTS）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ap_admin_audit_log` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` int unsigned NOT NULL COMMENT '操作人账号ID',
    `role_codes` varchar(128) DEFAULT NULL COMMENT '操作时的角色快照（逗号分隔；角色可能事后被回收，故存快照）',
    `module` varchar(32) NOT NULL COMMENT '业务模块：REPORT举报 / CONTENT内容 / USER用户 / OPS运营位 / ACCESS访问控制（越权尝试）',
    `action` varchar(64) NOT NULL COMMENT '动作编码，如 REPORT_IGNORE / REPORT_TAKE_DOWN',
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
-- SELECT COUNT(*) FROM ap_admin_audit_log;

-- 回滚
-- DROP TABLE IF EXISTS `ap_admin_audit_log`;
