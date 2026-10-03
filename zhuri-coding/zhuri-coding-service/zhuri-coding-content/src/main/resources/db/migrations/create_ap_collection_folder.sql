-- 收藏夹（F4）—— 收藏归属自定义收藏夹，未指定归属"默认收藏夹"
-- 目标库：MySQL leadnews_article（与 ap_collection 同库）
-- 说明：本脚本仅需在 leadnews_article 库上执行一次，重复执行会因表/列/索引已存在而报错。
--
-- 设计说明：
-- 1) ap_collection_folder 只存用户自定义收藏夹；"默认收藏夹"不落表，用 ap_collection.folder_id IS NULL 表示，
--    避免为历史收藏数据做无意义的数据迁移；
-- 2) 名称 1-20 字、同用户不可重名：应用层校验 + uk_folder_user_name 唯一索引兜底并发；
-- 3) 单用户收藏夹数量上限（50）由应用层控制，不建数据库约束（上限属运营规则，随时可能调整）；
-- 4) "收藏夹是否公开"属第二期（F8）范围，本期表结构不包含；
-- 5) 收藏列表"按收藏夹筛选 + 按时间线分页"查询形如
--    where user_id = ? and folder_id = ? order by created_time desc，
--    ap_collection 现有索引 uk_collection_user_article(user_id, article_id) 与
--    idx_user_type(entry_id, article_id) 均不覆盖 folder_id，故补 (user_id, folder_id, created_time) 组合索引。

CREATE TABLE `ap_collection_folder` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` INT NOT NULL COMMENT '所属用户ID',
    `name` VARCHAR(20) NOT NULL COMMENT '收藏夹名称（1-20字）',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序值（升序，越小越靠前）',
    `created_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_folder_user_name` (`user_id`, `name`),
    KEY `idx_folder_user_sort` (`user_id`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户收藏夹（F4）';

ALTER TABLE `ap_collection`
    ADD COLUMN `folder_id` BIGINT DEFAULT NULL COMMENT '所属收藏夹ID（NULL=默认收藏夹）',
    ADD INDEX `idx_user_folder_created` (`user_id`, `folder_id`, `created_time`);