-- ============================================================================
-- 下线 publish_precheck_agent 提示词（Agent 兜底已从预检链路移除）
-- ============================================================================
--
-- 【背景】发布预检原降级链为「DAG 工作流 → 主编 Agent 循环 → 一次性直答」。
--   复盘发现 Agent 分支调用的是同一批专家工具、同一个模型、同一个线程池，
--   对同源失败没有信息增益；且其提示词允许「某专家异常返回 error 时仍输出完整
--   FINAL」，会把安全结论缺失伪装成「不违规」。
--
-- 【现状】降级链已收敛为「DAG 工作流 → 一次性直答 → null」，
--   代码中不再读取 prompt_key = 'publish_precheck_agent'。
--
-- 【处理】保留历史行，仅置 enabled=0：既能追溯旧版本，也可通过
--   `UPDATE ap_ai_prompt SET enabled=1 WHERE prompt_key='publish_precheck_agent' AND version=2`
--   临时回滚（需同时恢复代码中的 Agent 分支）。
--   AiPromptRegistry 只读取 enabled=1 的行，因此置 0 后该 prompt 不再生效。
--
-- 【执行】一次即可；语句幂等，可重复执行。
-- ============================================================================

UPDATE ap_ai_prompt
SET enabled = 0
WHERE prompt_key = 'publish_precheck_agent'
  AND enabled = 1;
