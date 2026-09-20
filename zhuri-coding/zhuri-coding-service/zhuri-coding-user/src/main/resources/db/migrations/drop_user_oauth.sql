-- =============================================================
-- 废弃遗留表 user_oauth：统一改用 ap_user_social_binding
-- 数据库：leadnews_user
--
-- 背景：user_oauth 是早期遗留的"用户-三方绑定"表，生产代码已无任何写入点，
--       实际绑定数据由社交登录写入 ap_user_social_binding。account 设置页的
--       绑定查询已改为读取 ap_user_social_binding，故删除本表。
-- 前置校验：确认无仍依赖 user_oauth 的代码（已移除 UserOauth / UserOauthMapper）。
-- =============================================================

DROP TABLE IF EXISTS `user_oauth`;