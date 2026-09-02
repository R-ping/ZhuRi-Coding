-- =============================================================
-- 清理孤儿表：leadnews_article 内容库
-- 说明：以下表在正式代码中零引用（无实体/Mapper/XML/原生SQL），
--       属历史遗留，故批量 DROP。对应 schema.sql DDL 已同步移除。
-- =============================================================
DROP TABLE IF EXISTS ap_author, ap_behavior, ap_comment_reply,
       ap_course_enrollment, ap_course_lesson, ap_notification,
       ap_pins_comment_like, ap_course_category, ap_course_order_item,
       taskinfo;