-- 为 ap_comment 表添加 root_id 字段（根评论ID，用于层级关系）
ALTER TABLE `ap_comment`
    ADD COLUMN `root_id` bigint DEFAULT NULL COMMENT '根评论ID' AFTER `parent_id`;