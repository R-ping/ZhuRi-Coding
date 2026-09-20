-- =============================================================
-- 清理孤儿表：leadnews_reward 奖励库
-- 说明：以下表对应的 Mapper/实体已删除（历史签到遗留，代码零引用），
--       故 DROP。对应 schema.sql DDL 已同步移除。
-- =============================================================
DROP TABLE IF EXISTS checkin_records, checkin_reward_config, patch_card_logs;