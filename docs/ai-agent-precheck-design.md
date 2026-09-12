# AI 发布助手 v2：自研 ReAct Agent（工具调用）设计

> 目标：把 precheck 从"一次拼 prompt 输出 JSON"升级为"模型自主决策 → 调工具 → 再作答"，产出可面试讲述的 Agent 案例。
> 状态：✅ 已实现并真实验证（steps=2 工具链、违规/正常两类样例通过）。

## 一、为什么自研而非 LangChain4j

- 探测：`langchain4j-community-dashscope` 仅 beta 版线（与主版本不同步），且目标模型经 DashScope **专属网关**暴露，原生 function-calling 协议兼容不可控。
- 方案：**模型无关的文本协议 ReAct**（ACTION/ARGS + FINAL）。执行侧完全可控、网关全兼容；与 LangGraph/LC4j 概念等价，可讲原理。

## 二、结构

| 类 | 职责 |
|---|---|
| `ai/agent/AgentTool` | 工具接口：name/description/execute(args)→JSON 字符串 |
| `ai/agent/AgentRunner` | 循环编排：解析 ACTION→执行工具→回填 tool_result→再次询问；FINAL 结束；maxSteps 护栏 |
| `ai/agent/AgentResult` | 结果（finalAnswer/steps/completed） |
| `ai/agent/tools/ContentSafetyTool` | 本地规则引擎违规检测（色情/赌博/诈骗/毒品/暴力 + 技术豁免词，确定性可离线） |
| `ai/agent/tools/SimilaritySearchTool` | pgvector 余弦查重（复用 A 线检索，不写库） |
| `PublishAssistantServiceImpl` | 主路径走 Agent；异常/超步/解析失败降级为原一次性直答；并做相似度兜底填充 |

## 三、协议与 Prompt 要点

- System 明确：工具清单与 JSON 参数、一次一步、ACTION:/ARGS:/FINAL: 格式、FINAL JSON schema（与 AiPrecheckVo 一致）、禁止编造工具结果。
- AgentRunner 每轮把历史（assistant ACTION + tool_result）拼回 user 上下文（文本协议天然适合；步数少无上下文压力）。
- 护栏：maxSteps=4；工具名不存在 → 返回 error 由模型自行纠正；循环异常/未收敛 → 降级直答，接口永不不可用。

## 四、验证（51803 临时实例）

| 样例 | 结果 |
|---|---|
| 赌博推广文 | steps=2（content_safety_check + search_similar_article）→ is_violation=true、type=违法赌博、reason 含"命中词：赌博平台"（工具真实输出被采信）、quality=10 |
| 正常 Java 并发文 | is_violation=false、quality=78、tech=true、4 条建议、5 标签、摘要正常、无相似命中 |

日志：`[AiPrecheck-Agent] steps=2, FINAL 解析成功`；`viaAgent` 标记。

## 五、局限与后续

- 工具集仅 2 个（安全/查重）；可扩展标签推荐/频道匹配等本地工具。
- 单轮文本协议：每步全量重发上下文（步数少 OK）；如需长链可改 Streaming/历史窗口。
- 原生日志/可观测（step 轨迹）后续可补 trace 字段给前端展示"agent 思考过程"。
- 面试口径：Agent 概念等价 LangGraph/OpenAI Agents，本实现贴合 DashScope 网关更可控；若 JD 强调框架可补 Spring AI 抽象层（多模型可切）。
