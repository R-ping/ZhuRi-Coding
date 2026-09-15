-- =====================================================
-- AI 问答语义缓存：相似问题直返缓存答案（按用户隔离）
-- 库：PostgreSQL（pgvector），与 ap_article_embedding / ap_user_memory 同库，执行一次
-- 目的：命中即跳过 Query Rewrite + LLM Rerank + 生成 三次模型调用，降本降延迟
-- 失效策略：TTL（默认 6h）+ 引用文章存活校验（下架/删除/AIGC 则整条失效）
--           + 语料指纹比对（sources_hash_json vs ap_article_embedding.content_hash，
--             内容变了即失效；存量 NULL 行退化为仅存活校验，随 TTL 淘汰）
-- =====================================================
CREATE TABLE IF NOT EXISTS ap_ai_semantic_cache (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           INT          NOT NULL,
    question          TEXT         NOT NULL,
    question_vec      vector(1024) NOT NULL,
    answer            TEXT         NOT NULL,
    sources_json      TEXT         NOT NULL DEFAULT '[]',
    sources_hash_json TEXT         DEFAULT NULL,
    hit_count         INT          NOT NULL DEFAULT 0,
    created_time      TIMESTAMP    NOT NULL DEFAULT now(),
    last_hit_time     TIMESTAMP    DEFAULT NULL
);

COMMENT ON TABLE ap_ai_semantic_cache IS 'AI 问答语义缓存（按用户隔离；TTL + 引用留存校验 + 语料指纹三重失效）';
COMMENT ON COLUMN ap_ai_semantic_cache.sources_hash_json IS '落缓存时引用文章的内容指纹快照 JSON（articleId->SHA-256）；NULL 表示旧数据仅存活校验';

-- 检索固定带 user_id 过滤 + 时间窗，走该复合索引即可；
-- 表为「单用户近期 N 条」量级（默认 ≤50），精确扫描成本极低，
-- 故不建 IVFFlat/HNSW 向量索引（小数据量下 ANN 反而可能漏召回）。
CREATE INDEX IF NOT EXISTS idx_ai_semcache_user_time
    ON ap_ai_semantic_cache (user_id, created_time DESC);
