-- =============================================================================
-- 运营账号角色绑定表（库：leadnews_user）
--
-- 取代 ap_admin_user_role（该表把角色绑在 C 端账号 id 上，见 create_ap_admin_account.sql
-- 头部说明的问题）。新表绑定的是 ap_admin_account.id —— 独立的运营账号 ID 空间，
-- 与 C 端账号再无交集，因此不存在"id 撞上就拿到权限"的可能。
--
-- 执行方式：
--   mysql -h127.0.0.1 -uroot -p123456 --default-character-set=utf8mb4 leadnews_user < create_ap_admin_account_role.sql
--   ⚠️ 必须带 --default-character-set=utf8mb4，否则中文注释会双重编码成乱码。
--
--   ⚠️ 必须先执行 create_ap_admin_account.sql（本脚本的种子行要按 username 找账号）。
--
-- 本脚本可重复执行（CREATE TABLE IF NOT EXISTS + INSERT ... WHERE NOT EXISTS）。
--
-- 不执行会怎样：所有运营接口一律 403（角色查不出来 = 没有权限）。
-- 这是刻意的 fail-closed —— 表现为"运营后台打不开"，而不是"运营后台谁都能进"。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ap_admin_account_role` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `account_id` INT UNSIGNED NOT NULL COMMENT '运营账号ID（ap_admin_account.id）',
  `role_code` VARCHAR(32) NOT NULL COMMENT '角色编码：AUDITOR / OPERATOR / SUPER_ADMIN',
  `granted_by` INT UNSIGNED DEFAULT NULL COMMENT '授权人运营账号ID；初始迁移为 NULL',
  `granted_time` DATETIME NOT NULL COMMENT '授权时间',
  `remark` VARCHAR(200) DEFAULT NULL COMMENT '授权备注（为什么给他这个角色）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_role` (`account_id`, `role_code`),
  KEY `idx_role_code` (`role_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '运营账号角色绑定（一人多角色取权限并集）';

-- ---------------------------------------------------------------------------
-- 初始账号的角色：SUPER_ADMIN
--
--   只给初始账号 SUPER_ADMIN —— 否则新系统里没有任何账号能授予角色，
--   运营后台的角色管理页面对所有人都是 403，等于死锁。
--   后续新建的运营账号由它按最小权限原则逐个授予。
-- ---------------------------------------------------------------------------
INSERT INTO `ap_admin_account_role`
  (`account_id`, `role_code`, `granted_by`, `granted_time`, `remark`)
SELECT a.`id`, 'SUPER_ADMIN', NULL, NOW(), '初始迁移：内置超管账号'
  FROM `ap_admin_account` a
 WHERE a.`username` = 'admin'
   AND NOT EXISTS (SELECT 1
                     FROM `ap_admin_account_role` r
                    WHERE r.`account_id` = a.`id`
                      AND r.`role_code` = 'SUPER_ADMIN');

-- ---------------------------------------------------------------------------
-- 执行后验证：应返回 (admin, SUPER_ADMIN) 一行
-- ---------------------------------------------------------------------------
-- SELECT a.username, r.role_code, r.granted_time
--   FROM ap_admin_account_role r JOIN ap_admin_account a ON a.id = r.account_id;

-- ---------------------------------------------------------------------------
-- 回滚
-- ---------------------------------------------------------------------------
-- DROP TABLE `ap_admin_account_role`;
