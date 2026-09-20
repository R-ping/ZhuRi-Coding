-- ============================================================
-- 增量变更：ap_article_config.article_id 增加唯一索引兜底
--
-- 背景（L2 低风险项）：
--   SimilarityProcessor / PowerBonusProcessor 采用"先查后插"创建 ap_article_config，
--   并发首次发布同一配置时可能插入重复行。原 idx_article_id 为普通索引无法兜底。
--
-- 方案：
--   1) 将 article_id 升级为唯一索引 uk_article_id（业务上每篇文章仅一条配置）；
--   2) 应用层配合 ApArticleConfigMapper.insertOrUpdateRecommend（INSERT ... ON DUPLICATE KEY UPDATE）幂等写入。
--
-- 注意：执行前请确保不存在同 article_id 的重复历史数据（若有需先合并去重）。
-- ============================================================

ALTER TABLE `ap_article_config`
    DROP INDEX `idx_article_id`,
    ADD UNIQUE KEY `uk_article_id` (`article_id`) USING BTREE;