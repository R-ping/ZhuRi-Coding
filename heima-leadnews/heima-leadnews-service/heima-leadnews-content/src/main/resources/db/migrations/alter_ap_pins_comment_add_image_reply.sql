-- 沸点评论功能增强：支持评论发图片/表情、二级评论继续回复
-- 执行库：leadnews_article
-- 日期：2026-08-12

ALTER TABLE `ap_pins_comment`
    ADD COLUMN `image_urls` varchar(1000) DEFAULT '' COMMENT '评论图片URL列表，逗号分隔' AFTER `content`,
    ADD COLUMN `reply_to_user_id` int DEFAULT NULL COMMENT '被回复用户ID，回复二级评论时使用' AFTER `image_urls`,
    ADD COLUMN `reply_to_user_name` varchar(50) DEFAULT '' COMMENT '被回复用户昵称' AFTER `reply_to_user_id`;
