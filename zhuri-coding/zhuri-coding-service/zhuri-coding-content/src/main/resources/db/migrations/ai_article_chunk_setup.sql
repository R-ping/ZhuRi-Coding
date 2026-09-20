-- =====================================================
-- 父子分块检索（small-to-big）子块表
-- 库：PostgreSQL（pgvector），与 ap_article_embedding / ap_ai_semantic_cache 同库，执行一次
-- 目的：父块=文章（提供上下文），子块=段落（提供检索粒度）。
--       检索在子块上做（一个具体技术点就能命中），上下文仍取整篇父块 → 长文不再因
--       整篇单向量 + 首 2000 字截断而丢失后半部分信息。
-- =====================================================
CREATE TABLE IF NOT EXISTS ap_article_chunk (
    id           BIGSERIAL    PRIMARY KEY,
    article_id   BIGINT       NOT NULL,
    chunk_index  INT          NOT NULL,
    content      TEXT         NOT NULL,
    embedding    vector(1024) NOT NULL,
    created_time TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_article_chunk UNIQUE (article_id, chunk_index)
);

COMMENT ON TABLE ap_article_chunk IS '文章分块向量（子块）：检索粒度，父块上下文仍取 ap_article_content';

CREATE INDEX IF NOT EXISTS idx_article_chunk_article ON ap_article_chunk (article_id);

-- 当前召回按「文章聚合最相似子块」做精确扫描，数据量级（文章数 × ≤30 块）可接受，故先不建 ANN 索引。
-- 语料增长后可启用（HNSW 支持增量插入，比 IVFFlat 更适合持续写入）：
-- CREATE INDEX CONCURRENTLY idx_article_chunk_vec ON ap_article_chunk USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
