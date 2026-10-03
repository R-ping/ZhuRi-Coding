-- 已发布文章修订（编辑）能力 —— 增量字段
-- 目标库：MySQL leadnews_article（与 ap_article / ap_article_draft 同库）
-- 说明：本脚本仅需在 leadnews_article 库上执行一次，重复执行会因字段已存在而报错。
--
-- 设计要点：
--   1) ap_article.update_time 是「最后实质更新时间」，语义为"修订幅度达到实质更新阈值时才刷新"，
--      与 ap_article 既有的 updated_time（ON UPDATE CURRENT_TIMESTAMP，任意一次 UPDATE 都会刷新）互不干扰；
--      项目未配置 MyBatis-Plus 的 MetaObjectHandler，故 update_time 不会被自动填充，命名无冲突。
--   2) ap_article.pending_revision_id 指向待审核的修订草稿（ap_article_draft.id），为空表示无待审修订。
--   3) ap_article_draft.source_article_id 非空表示该草稿是一条「修订草稿」，为空表示普通草稿。

-- 文章表：新增最后实质更新时间 / 更新说明 / 待审修订草稿ID
ALTER TABLE `ap_article`
    ADD COLUMN `update_time` datetime DEFAULT NULL COMMENT '最后实质更新时间（仅修订幅度达实质更新阈值时写入）' AFTER `publish_time`,
    ADD COLUMN `update_note` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '更新说明' AFTER `update_time`,
    ADD COLUMN `pending_revision_id` bigint DEFAULT NULL COMMENT '待审核修订草稿ID（ap_article_draft.id）' AFTER `update_note`;

-- 草稿表：新增修订来源文章ID / 实质更新标记 / 更新说明
ALTER TABLE `ap_article_draft`
    ADD COLUMN `source_article_id` bigint DEFAULT NULL COMMENT '修订来源文章ID，NULL 表示普通草稿' AFTER `article_id`,
    ADD COLUMN `revision_significant` tinyint DEFAULT NULL COMMENT '本次修订是否达实质更新阈值 1是 0否' AFTER `status`,
    ADD COLUMN `update_note` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '更新说明' AFTER `revision_significant`;

-- 修订来源索引：按文章反查其修订草稿
ALTER TABLE `ap_article_draft`
    ADD INDEX `idx_source_article` (`source_article_id`);