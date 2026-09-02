-- =============================================================
-- 清理孤儿表：leadnews_notification 通知库
-- 说明：system_notifications 表在正式代码中零引用（无实体/Mapper），
--       属历史遗留，故 DROP。对应 schema.sql DDL 已同步移除。
-- =============================================================
DROP TABLE IF EXISTS system_notifications;