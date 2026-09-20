-- 文章表新增"简要内容/摘要"字段，用于列表页标题下方展示
-- 对应 ApArticle.summary、ApArticleDraft.summary 发布时的冗余落库
ALTER TABLE `ap_article`
    ADD COLUMN `summary` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '摘要' AFTER `title`;