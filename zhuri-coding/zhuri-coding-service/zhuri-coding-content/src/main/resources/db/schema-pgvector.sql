-- =====================================================
-- PostgreSQL / pgvector 结构基线（leadnews_content）
-- 说明：本文件是向量相关表的唯一结构基线，替代历史上的 migrations 增量脚本。
--       表结构以当前数据库为准；变更后直接更新本文件并同步数据库。
--       执行顺序：先启用 vector 扩展，再建表/索引。
-- =====================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- -----------------------------------------------------
-- 文章整篇向量
-- 维度固定 1024，必须与 application.yml 中
-- spring.ai.openai.embedding.options.dimensions 保持一致。
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS ap_article_embedding (
    id              BIGSERIAL PRIMARY KEY,
    article_id      BIGINT    NOT NULL,
    embedding       vector(1024),
    content_hash    VARCHAR(64),
    src_updated_time TIMESTAMP,
    created_time    TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_article_embedding UNIQUE (article_id)
);

COMMENT ON COLUMN ap_article_embedding.content_hash IS '来源正文内容哈希（SHA-256）；与当前正文不一致说明向量过期需重算';
COMMENT ON COLUMN ap_article_embedding.src_updated_time IS '来源文章内容更新时间（写入向量时的 ap_article.updated_time）';

CREATE INDEX IF NOT EXISTS idx_article_embedding_vector
    ON ap_article_embedding
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

-- -----------------------------------------------------
-- 文章分块向量（small-to-big 的子块）
-- 检索在子块上做，上下文仍取整篇父块。
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS ap_article_chunk (
    id              BIGSERIAL    PRIMARY KEY,
    article_id      BIGINT       NOT NULL,
    chunk_index     INT          NOT NULL,
    content         TEXT         NOT NULL,
    embedding       vector(1024) NOT NULL,
    content_hash    VARCHAR(64),
    src_updated_time TIMESTAMP,
    created_time    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_article_chunk UNIQUE (article_id, chunk_index)
);

COMMENT ON TABLE ap_article_chunk IS '文章分块向量（子块）：检索粒度，父块上下文仍取 ap_article_content';
COMMENT ON COLUMN ap_article_chunk.content_hash IS '来源正文内容哈希（SHA-256）；分块与正文同版本';
COMMENT ON COLUMN ap_article_chunk.src_updated_time IS '来源文章内容更新时间（写入分块时的 ap_article.updated_time）';

CREATE INDEX IF NOT EXISTS idx_article_chunk_article ON ap_article_chunk (article_id);

-- -----------------------------------------------------
-- 用户语义长期记忆
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS ap_user_memory (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT    NOT NULL,
    content      TEXT      NOT NULL,
    embedding    vector(1024),
    created_time TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_memory_user
    ON ap_user_memory (user_id, created_time DESC);

CREATE INDEX IF NOT EXISTS idx_user_memory_embedding
    ON ap_user_memory
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 50);

-- -----------------------------------------------------
-- AI 问答语义缓存（按用户隔离）
-- 失效策略：TTL + 引用文章存活校验 + 语料指纹 + prompt 版本 + 作用域
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS ap_ai_semantic_cache (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           INT          NOT NULL,
    question          TEXT         NOT NULL,
    question_vec      vector(1024) NOT NULL,
    answer            TEXT         NOT NULL,
    sources_json      TEXT         NOT NULL DEFAULT '[]',
    sources_hash_json TEXT         DEFAULT NULL,
    prompt_stamp      TEXT         DEFAULT NULL,
    scope_key         VARCHAR(64)  NOT NULL DEFAULT 'legacy',
    hit_count         INT          NOT NULL DEFAULT 0,
    created_time      TIMESTAMP    NOT NULL DEFAULT now(),
    last_hit_time     TIMESTAMP    DEFAULT NULL
);

COMMENT ON TABLE ap_ai_semantic_cache IS 'AI 问答语义缓存（按用户隔离；TTL + 引用留存校验 + 语料指纹 + prompt 版本 + 作用域多重失效）';
COMMENT ON COLUMN ap_ai_semantic_cache.sources_hash_json IS '落缓存时引用文章的内容指纹快照 JSON（articleId->SHA-256）；NULL 表示旧数据仅存活校验';
COMMENT ON COLUMN ap_ai_semantic_cache.prompt_stamp IS '落缓存时生成所用 prompt 版本签名（ai_ask_system@version）；NULL 表示旧数据不做版本校验';
COMMENT ON COLUMN ap_ai_semantic_cache.scope_key IS '缓存作用域：global:{fast|deep}:anchor:{none|articleId}；legacy=改造前旧数据，不参与新链路命中';

CREATE INDEX IF NOT EXISTS idx_ai_semcache_user_time
    ON ap_ai_semantic_cache (user_id, created_time DESC);

CREATE INDEX IF NOT EXISTS idx_ai_semcache_user_scope_time
    ON ap_ai_semantic_cache (user_id, scope_key, created_time DESC);
