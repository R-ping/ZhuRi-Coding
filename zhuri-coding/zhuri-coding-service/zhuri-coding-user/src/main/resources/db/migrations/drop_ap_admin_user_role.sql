-- =============================================================================
-- 删除已废弃的 ap_admin_user_role（库：leadnews_user）
--
-- 为什么删而不留：它的 user_id 指向 C 端账号，与新的 ap_admin_account_role.account_id
-- 是两套 ID 空间。留着它只有一个后果 —— 后来的人照着它写代码，于是运营权限又绑回
-- C 端账号上，把"ID 撞上就拿到权限"这个洞重新打开。表名相似、语义相反，比不存在更危险。
--
-- 唯一一行数据（user_id=4 / OPERATOR / "原 EditorConfig.EDITOR_USER_IDS 白名单"）
-- 无法迁移：那是 C 端账号 4，在新的独立账号体系里没有对应主体。它代表的意图
-- （原编辑账号可做小册与沸点审核）已在 create_ap_admin_account_role.sql 里由内置超管账号接管。
--
-- 执行方式：
--   mysql -h127.0.0.1 -uroot -p123456 --default-character-set=utf8mb4 leadnews_user < drop_ap_admin_user_role.sql
--
-- ⚠️ 必须在代码切换到 ap_admin_account_role **之后**执行：
--    表先没了而代码还在查它 → 所有运营接口 500。本脚本用 IF EXISTS 保证可重复执行。
-- =============================================================================

DROP TABLE IF EXISTS `ap_admin_user_role`;

-- ---------------------------------------------------------------------------
-- 执行后验证：应返回 0 行
-- ---------------------------------------------------------------------------
-- SELECT TABLE_NAME FROM information_schema.TABLES
--  WHERE TABLE_SCHEMA = 'leadnews_user' AND TABLE_NAME = 'ap_admin_user_role';
