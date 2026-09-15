-- =====================================================
-- P2-3b 会话记忆摘要压缩：注册表补 memory_compress prompt 种子
-- 库：MySQL leadnews_article；INSERT IGNORE 幂等，可重复执行
-- 代码兜底常量 = RedisConversationMemoryService.COMPRESS_SYSTEM_FALLBACK（两处内容同步）
-- =====================================================
INSERT IGNORE INTO ap_ai_prompt (prompt_key, version, content, rollout_percent, enabled, remark) VALUES
('memory_compress', 1,
 '你是会话记忆压缩器。把下面的 AI 问答对话压缩为要点摘要，保留：用户关注的主题方向、提及的特定技术/事实、未解决或有后续倾向的问题。不评价、不扩展、不编造，300 字以内，直接输出摘要正文。',
 0, 1, 'v1 种子：P2-3b 会话摘要压缩，与 RedisConversationMemoryService.COMPRESS_SYSTEM_FALLBACK 一致');
