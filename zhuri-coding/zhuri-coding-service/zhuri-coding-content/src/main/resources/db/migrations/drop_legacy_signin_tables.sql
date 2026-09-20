-- =============================================================
-- 清理签到业务在内容库的遗留表（"同一概念两份存储"冗余副本）
-- 说明：签到业务已由 leadnews_reward 承接（sign_records / user_checkin_state，
--       CheckinServiceImpl 驱动）。以下三张 content 库表仅存 POJO+空 Mapper，
--       无任何 service 调用链，属旧签到实现遗留，故连同实体/Mapper 一并清理。
--       对应内容库 schema.sql DDL 已同步移除。
-- =============================================================
DROP TABLE IF EXISTS ap_check_in, sign_in_config, user_sign_in_summary;