-- ============================================
-- AI Memory 持久化模块（Memory & State）建表脚本
-- 执行环境: PostgreSQL (192.168.44.128:5432, 密码: 123456)
-- 数据库: leadnews_content
-- 说明: 本脚本幂等（IF NOT EXISTS），UserMemoryService 首次使用时也会执行同样的兜底建表，
--       手动执行本脚本与运行时自动建表二选一即可。
-- ============================================

-- 语义长期记忆表：沉淀用户兴趣轨迹（如提问过的问题），按用户 + 余弦相似度检索
-- 注意：维度必须与 application.yml 中 spring.ai.openai.embedding.options.dimensions 一致（1024 维）
CREATE TABLE IF NOT EXISTS ap_user_memory (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1024),
    created_time TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 用户维度索引：清点配额（每人上限）/ 按用户拉取
CREATE INDEX IF NOT EXISTS idx_user_memory_user
ON ap_user_memory (user_id, created_time DESC);

-- 向量检索索引（IVFFlat，与 ap_article_embedding 保持一致）
CREATE INDEX IF NOT EXISTS idx_user_memory_embedding
ON ap_user_memory
USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 50);