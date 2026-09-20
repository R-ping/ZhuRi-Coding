-- 文章表新增"是否开放评论"字段，用于创作者中心评论管理（关闭后读者不可发表评论）
ALTER TABLE `ap_article`
    ADD COLUMN `comment_open` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否开放评论 1开放 0关闭' AFTER `comment`;