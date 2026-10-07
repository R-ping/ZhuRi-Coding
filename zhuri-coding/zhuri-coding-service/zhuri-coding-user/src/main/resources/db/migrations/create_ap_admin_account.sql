-- =============================================================================
-- 运营账号表（库：leadnews_user）
--
-- 背景：运营身份原先复用 C 端账号 —— 登录走 C 端链路，ap_admin_user_role 只回答
--       "这个账号是不是运营"。这带来两个问题：
--         1) 运营后台必须实现 C 端那套「accToken + refToken + 444 刷新」逻辑，
--            而它是个内部系统，用服务端会话（Spring Session）更合适；
--         2) 运营账号与 C 端账号共用一个 ID 空间，"某 C 端用户的 id 恰好等于
--            某个有角色的运营账号 id"就会凭空获得运营权限。
--       所以运营账号独立成表，与 C 端账号**完全隔离**，登录也不再经过 C 端链路。
--
-- 执行方式（本项目约定：脚本入库，需在部署时按顺序执行）：
--   mysql -h127.0.0.1 -uroot -p123456 --default-character-set=utf8mb4 leadnews_user < create_ap_admin_account.sql
--   ⚠️ 必须带 --default-character-set=utf8mb4，否则中文注释会双重编码成乱码。
--
-- 本脚本可重复执行（CREATE TABLE IF NOT EXISTS + INSERT ... WHERE NOT EXISTS）。
--
-- 不执行会怎样：登录接口直接 500（表不存在），运营后台整体不可用。
--
-- ⚠️ 初始账号：admin / ZhuriAdmin@2026（口令为 BCrypt 哈希，见下方 INSERT）。
--    这是**演示用的公开口令**，仅用于让内部系统开箱可用。真实部署必须立刻用
--    POST /user/api/v1/admin/me/password 改掉（服务启动时会就此持续告警）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ap_admin_account` (
  `id` INT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '运营账号ID',
  `username` VARCHAR(64) NOT NULL COMMENT '登录名',
  `password` VARCHAR(100) NOT NULL COMMENT '口令（BCrypt，$2a$10$ 开头共 60 字符）',
  `nick_name` VARCHAR(64) DEFAULT NULL COMMENT '展示名（会话里下发给前端的昵称）',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用；停用后登录被拒且已签发会话立即失效',
  `must_change_password` TINYINT NOT NULL DEFAULT 0 COMMENT '1 表示仍在使用初始口令，前端据此强提示改密',
  `created_time` DATETIME NOT NULL COMMENT '创建时间',
  `updated_time` DATETIME NOT NULL COMMENT '更新时间',
  `last_login_time` DATETIME DEFAULT NULL COMMENT '最近一次登录成功时间',
  `remark` VARCHAR(200) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '运营账号（与 C 端账号完全隔离）';

-- ---------------------------------------------------------------------------
-- 初始账号
--
--   口令明文：ZhuriAdmin@2026
--   哈希由 spring-security-crypto 的 BCryptPasswordEncoder（strength=10）生成，
--   与 ApUserServiceImpl 校验 C 端口令用的是同一个实现，可直接被 passwordEncoder.matches 通过。
--
--   must_change_password = 1：明确标记"这是初始口令"，前端据此强提示改密。
-- ---------------------------------------------------------------------------
INSERT INTO `ap_admin_account`
  (`username`, `password`, `nick_name`, `status`, `must_change_password`,
   `created_time`, `updated_time`, `remark`)
SELECT 'admin',
       '$2a$10$qya1CqltaUudvCckOYUhPuUQKZQQme0wLG9gysYATN2vNaZ0Q8I/K',
       '超级管理员',
       1,
       1,
       NOW(),
       NOW(),
       '初始账号：口令为公开的演示口令，请用「修改口令」接口立刻更换'
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `ap_admin_account` WHERE `username` = 'admin');

-- ---------------------------------------------------------------------------
-- 执行后验证：应返回初始账号一行，且 must_change_password = 1
-- ---------------------------------------------------------------------------
-- SELECT id, username, nick_name, status, must_change_password FROM ap_admin_account;

-- ---------------------------------------------------------------------------
-- 回滚
-- ---------------------------------------------------------------------------
-- DROP TABLE `ap_admin_account`;
