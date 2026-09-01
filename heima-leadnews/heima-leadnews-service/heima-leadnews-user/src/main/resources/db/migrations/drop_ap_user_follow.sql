-- =============================================================
-- 清理"同一概念两份存储"冗余副本：leadnews_user.ap_user_follow
-- 说明：关注关系的唯一持有方在 leadnews_article（content 服务经 ApFollow/ApFollowMapper
--       读写 ap_user_follow），user 库这份副本全工程零引用，故 DROP。
--       已同步移除 user/schema.sql 对应 DDL。
-- =============================================================
DROP TABLE IF EXISTS ap_user_follow;