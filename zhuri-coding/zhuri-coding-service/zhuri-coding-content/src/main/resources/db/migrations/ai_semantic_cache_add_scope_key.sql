-- AI 问答语义缓存：增加范围/模式/锚点作用域键
-- 库：PostgreSQL（pgvector），与 ap_ai_semantic_cache 同库，执行一次（幂等）。
-- 背景：原缓存只按 user_id + 问题相似度命中，fast/deep 与不同锚点文章会互相串答案。
-- 策略：新链路写入 global:{fast|deep}:anchor:{none|articleId}；旧数据标记 legacy 并清理。
ALTER TABLE ap_ai_semantic_cache
    ADD COLUMN IF NOT EXISTS scope_key VARCHAR(64) NOT NULL DEFAULT 'legacy';

COMMENT ON COLUMN ap_ai_semantic_cache.scope_key IS
  '缓存作用域：global:{fast|deep}:anchor:{none|articleId}；legacy=改造前旧数据，不参与新链路命中';

CREATE INDEX IF NOT EXISTS idx_ai_semcache_user_scope_time
    ON ap_ai_semantic_cache (user_id, scope_key, created_time DESC);

-- 旧数据没有模式/锚点维度，继续保留只会带来跨模式命中风险，缓存为收益项，直接清理
DELETE FROM ap_ai_semantic_cache WHERE scope_key = 'legacy';
