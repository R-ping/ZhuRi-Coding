-- P2-2 语义缓存与语料脱钩：缓存条目绑定语料指纹（content_hash 快照）
-- 库：PostgreSQL（pgvector）leadnews_content，与 ap_ai_semantic_cache 同库，执行一次（幂等）。
-- 背景：命中校验此前只做「文章存活」（PUBLISHED/未删/非 AIGC），不校验「内容没变」——
--       文章编辑后缓存仍返回旧答案 + 失效引用。本列存 store 时的 {articleId: contentHash} 快照，
--       lookup 命中时与 ap_article_embedding.content_hash 逐篇比对，任一不一致即视为 miss 并删除。
-- 兼容：存量行本列为 NULL → 退化为仅存活校验，随 TTL 自然淘汰。
ALTER TABLE ap_ai_semantic_cache ADD COLUMN IF NOT EXISTS sources_hash_json TEXT;
COMMENT ON COLUMN ap_ai_semantic_cache.sources_hash_json IS '落缓存时引用文章的内容指纹快照 JSON（articleId->SHA-256）；NULL 表示旧数据仅存活校验';
