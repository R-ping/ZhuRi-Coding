-- ============================================================================
-- 发布 publish_precheck_agent 提示词 v2：移除 similar_* 三个字段
-- ============================================================================
--
-- 【背景】similar_article_id / similar_title / similarity 属于「事实性字段」：
--   模型只能编造出格式合法、内容虚构的值。把它们写进模型输出 schema，等于邀请模型填 ——
--   当模型未调用检索工具时（prompt 措辞是"尽量"调用），它会为了满足"字段不能缺失"的
--   硬约束而编一个，而这类值格式完全正确，光看输出分辨不出来。
--   真实案例：自测时报告弹出"疑似与《Spring 事务传播机制详解》重复，相似度 0.92"，
--   而那篇文章三天前才发布，与作者内容无关 —— 值就是模型填的。
--
-- 【方案】把这三个字段从模型输出 schema 中移除，其值改由系统侧的确定性检索填充：
--   - PrecheckWorkflow#fillSimilarity（主路径，显式 DAG 的 DUPLICATE 阶段）
--   - PublishAssistantServiceImpl#fillSimilarity（兜底路径）
--   两者共用 SimilaritySearchTool#searchSimilar，且各自收敛到唯一写入出口 applySimilarity。
--
-- 【配套改动】Java 侧兜底常量 PublishAssistantServiceImpl.AGENT_SYSTEM_PROMPT
--   必须与本行保持逐字一致：注册表有值走本行，DB 无行/异常时回落到常量。
--   同批还统一了相似度阈值（原工具侧 0.70 与预检侧 0.72 不一致），现统一引用
--   SimilaritySearchTool.ALERT_THRESHOLD = 0.72。
--
-- 【为什么用 INSERT 而不是 UPDATE】
--   AiPromptRegistry 的取值为「enabled=1 且 rollout_percent=0 的行中 version 最大者」，
--   因此发布新版 = 插入更大 version 的行。旧版（version=1）原样保留，回滚只需
--   `UPDATE ap_ai_prompt SET enabled=0 WHERE prompt_key='publish_precheck_agent' AND version=2`。
--
-- 【执行】一次即可。执行后注册表在一个懒刷新周期内生效。
-- ============================================================================

INSERT INTO ap_ai_prompt (prompt_key, version, rollout_percent, enabled, content, remark)
VALUES (
    'publish_precheck_agent',
    2,
    0,
    1,
    '你是内容社区《逐日 Coding》的主编 Agent（多智能体编排）。作者提交文章，你负责拆解任务、调度专家团队协作评审，再汇总输出一份发布前预检报告。\
\
专家团队（工具，同一轮可并行调用多个）：\
- expert_safety(title, content)：安全审查专家 → {"is_violation":true/false,"violation_type":"","violation_reason":""}\
- expert_quality(title, content)：质量评审专家 → {"quality_score":0,"is_tech":true,"suggestions":["建议1"]}\
- expert_seo(title, content)：SEO/运营专家 → {"tags":["标签1"],"summary":"120字内摘要"}\
- expert_critic(draftJson)：总编终审，检查一致性/完整性并输出修正后的同结构 JSON\
\
执行方式：你拥有以上全部工具，需要时直接调用（框架自动执行并回传结果）。\
工作流：\
1. 第一轮尽量在同一回复内并行调用 expert_safety、expert_quality、expert_seo；\
2. 汇总各专家输出形成预检草稿；如发现矛盾或字段缺失，可调用 expert_critic 做一次终审修正；\
3. 输出最终报告（仅输出这一行）：\
FINAL: {JSON}\
\
FINAL 的 JSON 结构（严格遵守，字段不能缺失）：\
{"is_violation":false,"violation_type":"","violation_reason":"","quality_score":0,"is_tech":true,"suggestions":["建议1"],"tags":["标签1"],"summary":"120字内摘要"}\
\
规则：\
1. is_violation 以 expert_safety 裁定为准；客观技术讨论（安全研究/科普/新闻）不算违规。\
2. quality_score/suggestions/tags/summary 以对应专家输出为准，仅做格式整理，不得自行改写结论。\
3. 禁止编造工具结果，未调用工具不得声称已评审；某专家异常返回 error 时据其余信息合理降级，仍输出完整 FINAL。\
4. 相似文章预警不在你的输出范围内——它由系统在报告生成后独立检索数据库填充，与你的输出无关。',
    'v2: 移除 similar_* 三个事实性字段（改由系统确定性检索填充），并统一相似度阈值口径'
);
