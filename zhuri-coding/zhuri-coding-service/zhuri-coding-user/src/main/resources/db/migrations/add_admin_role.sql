-- =============================================================================
-- ⚠️⚠️ 本脚本已被取代，不要再执行 ⚠️⚠️
--
-- 它建的是 ap_admin_user_role —— 把角色绑在 **C 端账号 id** 上。那个方案（见下方"设计取舍"第 1 条）
-- 后来被推翻了，原因正是原文自己留的那句预警：运营后台需要强隔离，且复用一个 ID 空间会带来
-- "某 C 端用户的 id 恰好等于某个有角色的运营账号 id → 凭空获得运营权限"。
--
-- 现在的落地方式：
--   1. create_ap_admin_account.sql      新建独立的运营账号表（不复用 ap_user、不共享 ID 空间）
--   2. create_ap_admin_account_role.sql 角色绑定改绑 ap_admin_account.id
--   3. drop_ap_admin_user_role.sql      删掉本脚本建的那张表
--
-- 下面这段历史内容**刻意保留不改**：迁移脚本是唯一演进路径，回改会让"谁在哪个版本执行的什么"
-- 变得无从考证。这里只做标记，读的人从这段注释就知道该往哪走。
-- =============================================================================
--
-- =============================================================================
-- 运营角色绑定表（user 服务 / 库：leadnews_user）
--
-- 背景：运营后台需要「授权」能力。在建这张表之前，全项目的"运营身份"靠两处配置白名单：
--         EditorConfig.EDITOR_USER_IDS = {4}        （硬编码在代码里）
--         audit.reviewer-user-ids                   （配置数组）
--       两者都只能靠改代码/改配置来调整，且无法表达"不同运营做不同的事"。
--
-- 设计取舍：
--   1) **复用 C 端账号，不另建账号体系**。本表只做「账号 → 角色」的绑定，
--      登录仍走 C 端既有链路。理由：真正的风险是"普通用户能调运营接口"（越权），
--      角色校验即可消除；独立的账号表/Token 链在有多个运营人员之前不改变威胁模型，
--      成本却很高（新登录页、新 Token 链、网关双链路）。
--      ⚠️ 若后续运营人数增长、需要强隔离（如外聘审核员），再补独立账号体系。
--   2) **只存角色编码，不存权限点**。角色 → 权限点的映射写在代码里（AdminRole 枚举），
--      不建权限表。原因：权限点与接口一一对应，属于代码契约；入库后反而出现
--      "代码加了接口、库里没加权限点"的漂移。
--   3) **未知角色编码一律视为无权限（fail-closed）**。代码里认不出的编码不报错、不授权。
--   4) 一人可多角色（uk 建在 user_id + role_code 上），权限取并集。
--
-- 执行方式（本项目约定：脚本入库，需手动执行）：
--   mysql -h127.0.0.1 -uroot -p123456 --default-character-set=utf8mb4 leadnews_user < add_admin_role.sql
--   ⚠️ 必须带 --default-character-set=utf8mb4，否则中文注释会双重编码成乱码。
--
-- ⚠️ 本脚本可重复执行（CREATE TABLE IF NOT EXISTS）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ap_admin_user_role` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` int unsigned NOT NULL COMMENT '账号ID（复用 C 端账号）',
    `role_code` varchar(32) NOT NULL COMMENT '角色编码：AUDITOR审核员 / OPERATOR运营 / SUPER_ADMIN超级管理员',
    `granted_by` int unsigned DEFAULT NULL COMMENT '授权人账号ID',
    `granted_time` datetime NOT NULL COMMENT '授权时间',
    `remark` varchar(200) DEFAULT NULL COMMENT '授权备注（为什么给他这个角色）',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_user_role` (`user_id`, `role_code`),
    KEY `idx_role` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运营角色绑定（一人可多角色，权限取并集）';

-- ---------------------------------------------------------------------------
-- 初始化：把既有的两个白名单账号迁进本表，避免上线后运营突然"失去权限"
--
--   原 EditorConfig.EDITOR_USER_IDS = {4}  →  编辑账号（小册/沸点管理）授 OPERATOR
--   原 audit.reviewer-user-ids             →  申诉终审审核员授 AUDITOR
--
-- ⚠️ 执行前请先确认：audit.reviewer-user-ids 当前配置了哪些 id（默认可能为空）。
--    下面只迁 user_id = 4；若配置里还有别的 id，请按同格式补插。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `ap_admin_user_role` (`user_id`, `role_code`, `granted_by`, `granted_time`, `remark`)
VALUES (4, 'OPERATOR', NULL, NOW(), '初始迁移：原 EditorConfig.EDITOR_USER_IDS 白名单');

-- ---------------------------------------------------------------------------
-- 执行后验证
-- ---------------------------------------------------------------------------
-- SELECT user_id, role_code, granted_time, remark FROM ap_admin_user_role ORDER BY user_id;

-- 回滚
-- DROP TABLE IF EXISTS `ap_admin_user_role`;
