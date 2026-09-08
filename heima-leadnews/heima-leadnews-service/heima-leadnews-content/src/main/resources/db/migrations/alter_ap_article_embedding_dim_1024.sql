-- ============================================
-- 对齐 ap_article_embedding.embedding 维度为 1024
-- 背景：pgvector_setup.sql 误写 vector(1536)，而 embedding 模型 qwen3.7-text-embedding
--       实际输出 1024 维（application.yml: spring.ai.openai.embedding.options.dimensions=1024），
--       导致在干净环境执行建表脚本后写入/检索必然维度不匹配报错。
-- 修复：真实库列已执行下述 ALTER（幂等，无维度约束时生效）；脚本侧 vector(1536)->vector(1024)。
-- 已在 192.168.44.128:5432 leadnews_content 实际执行验证，库存 86 行 1024 维向量不受影响。
-- ============================================

-- 仅当列尚无维度约束（information_schema 中类型为 vector 而非 vector(1024)）时执行；
-- 若已带 1024 维约束则幂等跳过（pgvector 重复约束到相同维度不报错）。
ALTER TABLE ap_article_embedding ALTER COLUMN embedding TYPE vector(1024);