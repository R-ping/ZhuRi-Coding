-- =====================================================
-- RAG 数据新鲜度：向量与内容版本解耦修复（P0-1）
--
-- 问题：回填任务只判断"向量是否存在"（getEmbedding()==null），不看内容是否变化 ——
--       文章编辑/修订后向量仍是旧内容的，RAG 召回错配、语义检索返回过时结果；
--       文章删除/下架也不清理向量（残留被检索到）。
-- 修复：向量行记录来源内容的 content_hash 与 src_updated_time：
--       - 回填/增量任务比对 hash，不一致即重算（内容真变了才算，标题/标签微调不误重算）；
--       - 非 PUBLISHED 的残留向量由任务清理；
--       - src_updated_time 便于运维排查"这个向量对应哪一版内容"。
-- 库：PostgreSQL（pgvector，与 ap_article_chunk 同库），可重复执行
-- =====================================================

ALTER TABLE ap_article_embedding ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
ALTER TABLE ap_article_embedding ADD COLUMN IF NOT EXISTS src_updated_time TIMESTAMP;

ALTER TABLE ap_article_chunk ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
ALTER TABLE ap_article_chunk ADD COLUMN IF NOT EXISTS src_updated_time TIMESTAMP;

COMMENT ON COLUMN ap_article_embedding.content_hash IS '来源正文内容哈希（SHA-256）；与当前正文不一致说明向量过期需重算';
COMMENT ON COLUMN ap_article_embedding.src_updated_time IS '来源文章内容更新时间（写入向量时的 ap_article.updated_time）';
COMMENT ON COLUMN ap_article_chunk.content_hash IS '来源正文内容哈希（SHA-256）；分块与正文同版本';
COMMENT ON COLUMN ap_article_chunk.src_updated_time IS '来源文章内容更新时间（写入分块时的 ap_article.updated_time）';
