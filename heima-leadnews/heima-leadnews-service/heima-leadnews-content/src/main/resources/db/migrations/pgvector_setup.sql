-- ============================================
-- PostgreSQL pgvector 向量存储设置脚本
-- 执行环境: PostgreSQL (192.168.44.128:5432, 密码: 123456)
-- 数据库: leadnews_article
-- ============================================

-- 启用pgvector扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 创建文章向量嵌入表
CREATE TABLE IF NOT EXISTS ap_article_embedding (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT NOT NULL,
    embedding vector(1536),
    created_time TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_article_embedding UNIQUE (article_id)
);

-- 创建向量索引（IVFFlat，用于近似最近邻搜索）
-- 注意：需要先有足够数据再创建索引，或使用以下语句
CREATE INDEX IF NOT EXISTS idx_article_embedding_vector 
ON ap_article_embedding 
USING ivfflat (embedding vector_cosine_ops) 
WITH (lists = 100);