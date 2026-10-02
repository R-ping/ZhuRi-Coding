-- P1-4 语义缓存与 prompt 版本脱钩：缓存条目绑定「生成该答案的 system prompt 版本」
-- 库：PostgreSQL（pgvector）leadnews_content，与 ap_ai_semantic_cache 同库，执行一次（幂等）。
-- 背景：命中校验此前只做「引用存活」+「语料指纹」，不校验「答案由哪版 prompt 生成」——
--       DB 插入新版本 prompt 后，同一用户仍会命中旧答案（最长 TTL 6h 不生效）；
--       灰度调整时还会出现「返回 A 版答案、归因记 B 版」的实验污染。
-- 策略：store 时快照 key@version 签名；lookup 命中时与当前生效版本比对，不一致即删除并回源（evict）。
-- 兼容：存量行本列为 NULL → 跳过版本校验，随 TTL 自然淘汰（与 sources_hash_json 同一口径）。
ALTER TABLE ap_ai_semantic_cache ADD COLUMN IF NOT EXISTS prompt_stamp TEXT;
COMMENT ON COLUMN ap_ai_semantic_cache.prompt_stamp IS
  '落缓存时生成所用 prompt 版本签名（ai_ask_system@version）；NULL 表示旧数据不做版本校验';