# AI 增强增量盘点（在商业化路线图之上）

> 基线：`docs/ai-commercialization-roadmap.md` 的 P0/P1 已全部落地、P2 落地过半（配额/反馈/评测/画像/指标/复盘/路由/额度包/速读广场）。
> 本文只写**路线图之外的增量缺口**，每条均附代码证据（2026-09-14 核实）。
> 结论：AI"能用、能衡量、能变现"三件事已成立，下一阶段的价值在 **可信度（数据新鲜度 + 熔断）** 与 **迭代效率（反馈回灌 + 评测门禁 + Prompt 版本化）**。

---

## 落地进度

| 项 | 状态 | 说明 |
|---|:--:|---|
| **P0-2 token 计量** | ✅ 2026-09-14 | `AiTokenMeter`（Redis 日 Hash 按 `feature:model:prompt\|completion` + 内存指标 + 缺失/估算计数）+ `AiLlmGateway`（统一 LLM 出口，调用即计量）；端点 `GET /api/v1/ai/metrics/tokens?days=7` |
| **token 接入点补齐（9 处）** | ✅ 2026-09-14 | 申诉初审 / AIGC 检测 / 发布预检 / 创作复盘 / 单篇问答 / 忠实度复核 / 评论治理 ×2 全部改走 gateway；AgentRunner 逐轮打点（ReAct 多轮成本可见）。~~唯一残留 frame-ping/tools-ping 裸调~~ → **2026-09-15 已收口**：`AiLlmGateway` 新增 `probeOrNull` / `probeWithToolsOrNull` 探针方法（不计 token、不结算配额、不挂护栏，保留探针语义，仍走熔断计数），`AiAskController` 两探针端点全部改走网关 —— 全仓库 LLM 调用已无非网关路径 |
| **成本报表（把计量变成钱）** | ✅ 2026-09-14 | `AiModelRouter` 新增定价表（元/千 token，key = 计量上报的模型名）+ `costReport()`；端点 `GET /api/v1/ai/router/cost?days=7`（按 feature/model 金额 + `costPer1kTokens` 横向比较 + `unpricedModels` 显式暴露未配价模型） |
| **额度包 次数 → tokens 配额** | ✅ 2026-09-14 | 双轨并存：`ap_ai_wallet.token_balance` + `ap_ai_topup_order.token_added`（迁移已执行、schema 已同步）；套餐 q200/q1000/q5000 同时给次数与 tokens；每日免费 tokens **可配置**（`ai-quota.daily-tokens`，默认 2 万）；**预扣语义 = 请求前 precheck + 响应后按真实用量结算**（免费额度优先、超出扣 token 钱包，余额不足扣光并告警） |
| **P1-1 熔断** | ✅ 2026-09-14 | `AiCircuitBreaker`（轻量自研，Redis 状态机：连续失败 5 次/60s 窗口 → 打开 30s → 半开试探；**llm 与 embedding 独立熔断**；自身异常 fail-open 不成为新故障点）；挂载 `AiLlmGateway`（同步+流式，护栏命中不计失败）与 embedding（`generateEmbedding` 单入口）；观测 `GET /api/v1/ai/metrics/circuit` |
| **P1-2 反馈回灌** | ✅ 2026-09-14 | `GET /api/v1/ai/feedback/badcases`（👎 样本导出 + eval 集同构候选，人工补 golden 后并入评测集）+ `GET /api/v1/ai/feedback/stats`（按 feature 的 👍/👎/差评率，**差评率 ≥20% 且样本 ≥5 列入 alerted** 并打指标告警）；闭环后半段补齐：👎 不再是死数据 |
| **P0-1 向量新鲜度** | ✅ 2026-09-14 | 向量指纹：两表加 `content_hash`（SHA-256）+ `src_updated_time`；回填/增量任务按指纹比对增量刷新（`ap_article` 无 updated_time → **Redis 游标轮转扫描**每 10 分钟一批 500，延迟 24h → 小时级）；结尾自动清理非 PUBLISHED 残留向量（PG 游标 + MySQL 批量状态校验，跨库无 JOIN） |
| **P1-3 评测门禁 + 扩样本** | ✅ 2026-09-14 | `runGate()` 组合检索+答案评测，按 `ai.eval.gate.*` 阈值（百分数口径可配置）判定 pass/fail + 逐项 checks 明细；**fail-closed**（报告缺失/带 error/召回题数 < min-cases 按 0 分判定）；评测集 **12 → 24 条**（新增事实问答 8 + 多跳 2 + `expectNoAnswer` 无答案拒绝 2，`runAnswerEval` 单独统计 refusalRate 不参与打分）；端点 `POST /api/v1/ai/eval/gate`（限流最严：USER 1/5min） |
| P2 三项（~~缓存一致性~~ / ~~Prompt 版本~~ / ~~冷启动~~） | ✅ 全部 2026-09-15 完成 | 见下 |

### ✅ 前端契约变更（已随本次一并实施，2026-09-14）

`GET /api/v1/ai/quota/status` **新增字段**（老字段全部保留 → 老前端不崩）：

| 新增字段 | 含义 |
|---|---|
| `freeTokens.dailyLimit` / `.usedToday` / `.remainToday` | 今日免费 **tokens** 额度与用量 |
| `walletTokenBalance` | 已购 **tokens** 余额 |
| `packages.<code>.tokenQuota` | 该套餐到账 **tokens** 数 |

**前端改动（3 个文件）**
| 文件 | 改动 |
|---|---|
| `src/pages/user/ai_quota/index.vue` | 概览改 tokens（今日免费/已购）、套餐卡片主显 tokens、折合单价改「¥X/百万 tokens」、页头副标题改"按 tokens 用量计费"；新增 `formatTokens`（1.5万/50万/300万）与 `formatPerMillion` |
| `src/components/ai/AiAskFloating.vue` | 面板额度条改 tokens；`quotaExhausted` 判定改 tokens；**保留次数口径降级分支**（老后端只返回 `freeQuota` 时不报错） |
| `src/apis/ai.js` | JSDoc 更新（tokens 为主口径，次数为兼容口径） |

> 每日免费次数闸门仍在生效（与 tokens 双轨），前端概览以 tokens 为主、次数作为"≈N 次问答"提示。

### ⚠️ 定价校准结论：**现有套餐 tokens 额度严重倒挂**（2026-09-14 查证官方价）

主模型 `qwen3.7-max` 官方价：输入 12 元/百万、输出 36 元/百万（加权约 **0.0168 元/千**）。

| 项 | 现值 | 真实成本 | 结论 |
|---|---|---|---|
| 一次完整问答（改写+精排+生成 ≈3.2k tokens） | — | ≈ **0.063 元** | — |
| 每日免费（原 50k tokens） | 0 | ≈ 0.84 元/用户/天 | 过于慷慨，已下调为 **20k（≈0.34 元/天）** |
| q200 ¥1.99 / 50 万 tokens | ¥1.99 | ≈ **8.4 元** | ❌ 亏 4.2 倍 |
| q1000 ¥8.99 / 300 万 | ¥8.99 | ≈ **50 元** | ❌ 亏 5.6 倍 |
| q5000 ¥39.99 / 2000 万 | ¥39.99 | ≈ **336 元** | ❌ 亏 8.4 倍 |

**建议（产品决策，二选一或组合）**
- **A. 降额度**：q200→6 万 tokens、q1000→30 万、q5000→150 万（成本 ≈ 售价的 50%，毛利 50%）。
- **B. 分 feature 路由（推荐）**：低价值高频功能（改写/精排/评论治理/AIGC 检测/预检摘要）切 `qwen3.8-flash`（0.0008/0.0027，便宜 15 倍），高价值低频（完整问答生成/创作复盘）保留 `qwen3.7-max`。整体成本预计降 60~80%，现有套餐额度可保留。配置只需在 `ai.model-router.features` 里加映射。**✅ 2026-09-15 已落地**：新增 `qwenFlashChatModel` Bean（`AiModelConfig`，`ai.model-router.flash-model` 默认 qwen3.8-flash，无 Key 不装配 fail-open）；`features` 启用（rewrite/rerank/faithfulness/comment_audit/pins_comment_audit/aigc_detect/memory_compress/appeal_audit → flash，ask/ask_stream/creator_report/agent_expert 等走强模型）；修正 `default: openAiChatModel`（原 `primary` 指向不存在的 key）；评论治理去掉显式模型改由网关按 feature 路由。
- **C. 调价**：保持 tokens 额度，售价上调到成本 1.5~2 倍。

---

## 一、已具备能力（13 项，避免重复建设）

| # | 能力 | 关键实现 |
|---|---|---|
| 1 | RAG 问答（完整/fast/流式三路） | `AiAskServiceImpl` |
| 2 | 混合召回（向量 + BM25 + RRF 融合） | `HybridRecallServiceImpl`、`RrfFusion` |
| 3 | 父子分块检索 small-to-big | `ArticleEmbeddingServiceImpl.saveChunks/findSimilarChunks` |
| 4 | **LLM Rerank 精排** | `AiAskServiceImpl:78 RERANK_PROMPT`、`rerankCandidates:772` |
| 5 | Query Rewrite | `AiAskServiceImpl`（`:167` 注释说明管线） |
| 6 | 语义缓存（相似问题直返） | `AiSemanticCacheServiceImpl` |
| 7 | **答案忠实度三级校验** | `AnswerFaithfulnessServiceImpl`（确定性→向量预筛→LLM 复核） |
| 8 | 离线评测（recall@k + 引用精确/召回 + 未溯源率） | `AiEvalServiceImpl`、`AiEvalController` |
| 9 | 配额 + 钱包计费（日免费 + 额度包支付） | `AiQuotaServiceImpl`、`AiWalletServiceImpl`、`AiTopupServiceImpl` |
| 10 | 反馈采集（👍/👎）+ 指标 | `AiFeedbackServiceImpl`、`AiMetricsCollector` |
| 11 | 用户兴趣画像注入 Prompt | `UserInterestServiceImpl` |
| 12 | 模型路由层 | `AiModelRouter` |
| 13 | Agent 编排（发布预检 4 专家） | `AgentRunner`、`ExpertWorkerBase` 等 |

---

## 二、增量缺口（按投入产出排序）

### ✅ P0-1 RAG 数据新鲜度：内容变了，向量没变（2026-09-14 已完成）

**原缺口**：回填任务只判断"向量是否存在"（`getEmbedding()==null`），**完全不比较内容是否变化** → 文章编辑后向量仍是旧内容的（RAG 召回错配）；文章删除/下架也不清理向量（残留被检索到）；忠实度校验也检不出（它校验"引用是否支持答案"，不校验"引用是否最新"）。

**落地**
1. **PG 迁移**（`alter_article_embedding_add_content_hash.sql`，已执行）：`ap_article_embedding` / `ap_article_chunk` 各加 `content_hash`（SHA-256）+ `src_updated_time`（便于排查"向量对应哪一版内容"）。
2. **写入侧**（`ArticleEmbeddingServiceImpl`）：`saveEmbedding/saveChunks` 新增带指纹的重载（旧签名保留兼容）；新增 `contentHash()`（SHA-256）、`isStale()`（过期判定纯函数）、`getEmbeddingMeta()/getChunksMeta()`（轻量读元信息，不必拉 1024 维向量）、`deleteEmbedding()/deleteChunks()`、`listEmbeddedArticleIds()`（游标式分页，规避边扫边删的 OFFSET 偏移）。
3. **发布/编辑链路**：`ArticleSimilarityService.checkSimilarity` 写入时一并落指纹与 `publish_time`。
4. **全量回填改造**（每日 03:30）：不再"存在即跳过"，改为**指纹比对**——缺向量/缺分块补写、指纹不一致重算、一致则跳过（不做无谓 embedding 调用）；**顺带清理非 PUBLISHED 的残留向量**。
5. **新增轮转同步**（每 10 分钟）：因 `ap_article` **没有 updated_time 列**（只有 created_time/publish_time），无法按"最近修改"筛选 → 改为**Redis 游标轮转扫描**（每次 500 篇，扫完归零重来），把"编辑 → 向量更新"的延迟从 24 小时压到小时级，且不依赖任何时间列。编辑后即时生效由发布链路保证，本任务兜底。

**为什么这是 RAG 最难察觉的失效模式**：检索质量指标（recall@k）看不出来——召回的确实是"相关文章"，只是内容已经过时；用户看到的答案"有据可依"却与最新内容不符。

**验收**：`ArticleEmbeddingServiceImplTest` 8 例（指纹稳定性/敏感性/格式/空值 + `isStale` 四象限）+ `AiAskServiceImplTest` 回填三场景（缺向量补写/指纹不一致重算/指纹一致跳过，均断言落库时带上指纹）——content 全量 **904/904 全绿**。

### ✅ P0-2 AI 成本观测：token 计量（2026-09-14 已完成）

**原缺口**：全仓 `getUsage|totalTokens|promptTokens` 零匹配；`AiQuotaServiceImpl` 只按**次数**计量 → 无法回答"哪个功能最烧钱"，模型路由无法按成本决策，额度包只能按次数卖。

**落地**
1. `AiTokenMeter` + `AiTokenMeterImpl`：按 `feature:model:prompt|completion` 落 Redis 日 Hash（TTL 40 天，跨进程持久）+ 内存快照；
   - **usage 缺失（null 或 0/0）计入 `missingUsageCalls`**，不静默当 0 成本；
   - 流式网关不回传 usage 时按字符估算并计 `estimatedCalls`（口径可追溯）；
   - **Redis 故障 fail-open**，计量失败绝不影响 LLM 主链路。
2. `AiLlmGateway`：统一 LLM 出口（装配 `PromptSafetyAdvisor` + 可选会话记忆 advisor + 调用后自动计量）。
   - 顺带修掉两处隐患：流式路径 `onDelta` 为 null 的 NPE 风险；会话记忆 `CONVERSATION_ID` param 的装配收敛为单一实现（漏传会导致记忆静默失效）。
3. 接入 `AiAskServiceImpl` 5 处（ask / ask_fast / ask_stream / rewrite / rerank）——**一次完整问答的成本可拆到子步骤**（改写 vs 精排 vs 生成，谁贵一目了然）。
4. 端点：`GET /api/v1/ai/metrics/tokens?days=7` → 按 feature / 按天聚合 + 进程内快照。

**验收**：`AiTokenMeterImplTest` 11 例 + `AiLlmGatewayTest` 9 例 + E2E/拦截器回归 10 例，共 **30/30 全绿**。

**后续（赚钱的那一步，不在本轮范围）**：额度包从"次数"升级为"tokens 配额"；`AiModelRouter` 引入"成本 + 质量"双维度决策。

### 🥈 P1-1 无熔断：LLM 挂了每个请求都走满超时

**证据**：`heima-leadnews-service/pom.xml:72` Sentinel 依赖**被注释**；全仓无 resilience4j / CircuitBreaker。
**影响**：AI 链路全靠 try-catch fail-open（正确），但**故障期间每个请求仍会建立连接→等超时**，Tomcat 线程被拖满，进而**拖垮非 AI 接口**（AI 故障扩散成全站故障）。
**落地**（半天）
- 轻量自研（推荐，零新依赖）：Redis 记连续失败数，阈值触发「打开 30s → 半开单请求试探 → 成功即关闭」；LLM 与 embedding **分别独立熔断**（embedding 挂了不代表 LLM 挂）；
- 打开期间：RAG 直接降级为「仅返回检索原文片段」或走语义缓存，不调模型。

### 🥈 P1-2 反馈只采集不回灌（闭环缺后半段）

**证据**：`AiFeedbackService` 仅 `record(...)` 一个方法，无任何消费端（无导出、无回归集生成、无告警）。
**影响**：路线图说"反馈是商业化灵魂"，但现在 👎 数据是**死数据**——不会让模型变好，也无法据它定位问题。
**落地**（半天）
1. `GET /api/v1/ai/feedback/badcases?feature=&limit=` 导出 👎 样本（question + answer + sources）；
2. 一键转 eval 集条目（人工过一遍补期望答案/期望命中文章）；
3. 👎 率按 feature 打指标 + 阈值告警（如问答类 👎 率 > 20% 告警）。

### ✅ P1-3 评测无门禁、样本偏少（2026-09-14 落地）

**证据**：`ai-eval/eval-questions.json` 12 条；`AiEvalController` 暴露手动端点，**未接入任何自动流程**。
**影响**：改 prompt / 调阈值 / 换模型时，没有自动回归——质量回退无人知晓（已有评测能力但没被约束住）。
**落地结果**：
1. ✅ 评测集 **12 → 24 条**：事实问答 8（iOS WKWebView / Android Activity 通信 / Python 路径 / Kotlin 协程 / 卡顿定位 / Skill 治理 / RHCE / Phosphor Icons）+ 多跳 2（SKILL.md 坑×治理、AI 成本×本地化部署）+ `expectNoAnswer` 无答案拒绝 2（语料外 Rust 问题 + 越界茅台问题）。60~100 条的终态目标待语料增长后继续扩；
2. ✅ **门禁**：`AiEvalService.runGate()` 组合检索评测 + 答案级评测，阈值可配置（`ai.eval.gate.min-avg-recall:60` / `min-cited-precision:50` / `max-unsupported-rate:30` / `min-refusal-rate:50` / `min-cases:10`，百分数口径与报告一致）；**fail-closed**：报告缺失/带 error/召回有效题数不足按 0 分判定，评测跑不出来 ≠ 通过；评测集无对应样本类型的检查项 skipped 视为通过；端点 `POST /api/v1/ai/eval/gate`（登录 + 限流最严 USER 1 次/5min）；
3. ✅ `runAnswerEval` 支持 `expectNoAnswer` 条目：答案有内容但引用为空 = 拒绝成功（refusal hit），单独统计 `refusalRate` 不参与精确率/召回率打分（均值分母改 `scored`）；
4. ⬜ 剩余：CI 定时跑 gate（需先在联调环境跑通真实 embedding + LLM 后落 `docs/` 时间序列报告）；越界拒答类样本依赖安全护栏（PromptSafetyAdvisor 已有，不重复建设）。

### ✅ P2-1 Prompt 版本管理（注册表 + 灰度 + 代码兜底，2026-09-15 落地）

**证据**：`AiAskServiceImpl:78 RERANK_PROMPT`、SYSTEM/REWRITE/忠实度复核 prompt 均为 `static final String`。
**影响**：调 prompt 要改代码 → 重新构建部署；**无法回滚、无法灰度、无法 AB**；出问题也难归因（日志里没有 prompt 版本）。
**落地结果**（2026-09-15）：
1. ✅ **注册表**：DB 表 `ap_ai_prompt`（`prompt_key + version` 唯一键，`rollout_percent` / `enabled`，迁移 `ai_prompt_registry_setup.sql`，已在本地 MySQL 执行验证）；`AiPromptRegistryImpl` 本地快照 60s 懒刷新（`synchronized reloadIfStale`，刷新失败也推进 loadedAt 防每请求重试雪崩）；base 取 version 最大者（不依赖 SQL 排序），灰度按 version 降序逐试；
2. ✅ **三层解析语义**：灰度版（`floorMod(userId,100) < rollout_percent` 命中，userId 为 null 跳过灰度）→ 正式版（rollout=0 中 version 最大）→ **代码兜底**（static 常量视为 version=0，registry 未注入 / 开关关闭 / DB 异常均 fail-open 回落）；
3. ✅ **6 个消费点接入**：`AiAskServiceImpl` 三入口（ask / streamFastAsk / askFast）+ queryRewrite + rerankCandidates（`{maxN}` 占位符运行时替换）、`AnswerFaithfulnessServiceImpl.llmReview`（faithfulness_review）、`ArticleQaServiceImpl`（qa_summary / qa_questions）；调用日志与 `AiAnswerVo.promptVersions` 记录实际生效版本，便于对比归因；
4. ✅ **前端契约结论**：`AiAnswerVo` 新增 `promptVersions: Map<String,Integer>` 为**纯新增可选字段**，前端不读取未知字段天然兼容，无需前端改动；
5. ✅ **划界**：审核链路 `BailianAiServiceImpl` 不接入（fail-closed 合规链路，prompt 热更有审批风险）、Agent workers 的 4 个 SYSTEM_PROMPT 与 `PublishAssistantServiceImpl` 留待下一批；
6. ✅ 测试：`AiPromptRegistryImplTest` 11 例（fallback / 开关关 / base 最高 version / 灰度命中与回落 / 多灰度降序 / null 用户 / DB 异常 fail-open / 懒刷新 / 空值防御），相关测试 30/30 全绿。

### ✅ P2-2 语义缓存与语料脱钩（2026-09-15 落地）

**证据**：`AiSemanticCacheServiceImpl` 缓存 question 向量 → answer + sources；MEMORY 记录"缓存命中路径不重复校验忠实度"。
**影响**：来源文章被删/改后，缓存仍返回旧答案 + 失效引用；无"语料变更 → 缓存失效"联动。
**落地结果**：
1. 缓存表加 `sources_hash_json` 列（迁移 `ai_semantic_cache_add_sources_hash.sql`，已在本地 PG 执行验证）；
2. `store()` 落缓存时快照每篇引用文章的 `content_hash`（复用 P0-1 的 `getEmbeddingMeta` 指纹接口）；`lookup()` 命中时逐篇比对当前指纹，任一不一致 / 向量缺失 / 快照缺 key → evict（引用必须来自当前可检索语料）；
3. **兼容**：存量行快照为 NULL → 退化为仅存活校验，随 6h TTL 自然淘汰，不误杀；
4. 可观测：新增 `ai_semcache_evict_stale` 指标（语料更新驱逐单独计数）；
5. 测试 15 例全绿（新增指纹一致 / 不一致 evict / 向量缺失 evict / 快照缺 key / 存量 NULL 兼容 / 落缓存快照 6 例）。

### ✅ P2-3 冷启动画像 / 多轮摘要压缩 / 流式取消（2026-09-15 落地）

**落地结果**：
1. ✅ **兴趣画像冷启动（三层兜底）**：`UserInterestServiceImpl.buildInterestTags` 改为 L1 收藏画像（近 30 天收藏，老用户精准）→ L2 即时兴趣（`learnFromQuery` 把本次提问召回文章的标签沉淀到 Redis `ai:interest:instant:{userId}`，24h TTL——新用户首问后第二问起即有画像）→ L3 全站热门兜底（PUBLISHED 按点赞排序取标签 TopN）。每层失败降级下一层，全链路 fail-open；日志记录画像来源（collection/instant/hot）便于观察冷启动命中率。沉淀入口挂 `persistMemory`，仅在回答成功后触发（取消/失败不污染画像）；
2. ✅ **多轮摘要压缩（token 膨胀治理）**：`RedisConversationMemoryService` 达到 44 条（`COMPRESS_THRESHOLD_MSGS`，约 22 轮）时，把最早 20 条用 LLM 压成一条「【早期对话摘要】」消息放回头部（role=assistant，前端按普通助手消息渲染天然兼容）。两段式：同步段只做长度检查 + Redis SETNX 互斥锁（60s 过期兜底），LLM 调用在独立单线程池 `aiMemoryCompressExecutor` 异步执行；写回用 `LTRIM(batch,-1)` + `LPUSH` 两条原子命令且只动头部，与并发 appendTurn（RPUSH 尾部）天然不冲突、不丢消息。压缩 prompt 走 P2-1 注册表（key=memory_compress，迁移 `ai_prompt_registry_add_memory_compress.sql` 已执行验证），代码兜底常量同步；开关 `ai.memory.compress.enabled`（默认开）。**注**：backlog 原列的「会话结束抽取长期事实入 UserMemoryService」此前已由每轮 `remember()` 沉淀实现，本轮不重复建设；
3. ✅ **流式客户端取消 + TTFT**：`AiAskController` 注册 `onCompletion/onTimeout/onError` 置位取消标志，`onDelta` 发送失败同样置位并抛 `CancellationException`——异常沿回调一路中断 gateway 流迭代（Flux 提前终止，已生成的 token 不再白烧）；`AiLlmGateway` 捕获取消异常：**不计熔断失败**（连环断连不会误开熔断）、已生成部分按字符估算计量（成本面板不留空洞）后丢弃（cause 链识别覆盖响应式框架包装场景）；`streamFastAsk` 取消分支丢弃结果：不落语义缓存、不写记忆、不做护栏复核；`AiMetricsCollector` 新增 `record()` 计时口径，采集 `aiask_stream_ttft`（首 token 延迟）与 `aiask_stream_cancelled`（取消次数）；
4. ✅ 测试：`UserInterestServiceImplTest` 7 例（三层兜底/沉淀合并/null 短路/fail-open）+ `RedisConversationMemoryServiceTest` 5→9 例（阈值/抢锁/写回/摘要失败放弃）+ `AiLlmGatewayTest` 13→15 例（取消不计熔断 + 估算计量/包装异常识别）+ `AiMetricsCollectorTest` 3 例（新）；相关 44/44 全绿。

---

## 三、推荐执行顺序（合计约 5 天）

| 顺序 | 项 | 工时 | 收益 |
|:--:|---|:--:|---|
| ~~1~~ | ~~**P0-2 token 计量**~~ | ~~1 天~~ | ✅ 2026-09-14 完成 |
| ~~1.5~~ | ~~token 接入点补齐 + 成本报表 + 额度包 tokens 化~~ | ~~1 天~~ | ✅ 2026-09-14 完成（计量 → 金额 → 计费闭环打通） |
| ~~2~~ | ~~P1-1 熔断 + P1-2 反馈回灌~~ | ~~1 天~~ | ✅ 2026-09-14 完成（故障隔离 + 闭环补齐） |
| ~~1~~ | ~~**P0-1 向量新鲜度**~~ | ~~1 天~~ | ✅ 2026-09-14 完成 |
| ~~2~~ | ~~P1-3 评测门禁 + 扩样本~~ | ~~1 天~~ | ✅ 2026-09-14 完成（gate 阈值判定 + 样本 24 条） |
| ~~3~~ | ~~P2 三件（Prompt 版本 / 缓存一致性 / 冷启动）~~ | ~~1 天~~ | ✅ 全部 2026-09-15 完成（P2-1 注册表 / P2-2 缓存指纹 / P2-3 冷启动+压缩+取消） |
| ~~4~~ | ~~定价校准（按云厂商账单）+ 模型路由按成本调优（强模型只留给高价值 feature）~~ | ~~半天~~ | ✅ 2026-09-15 完成（qwen3.8-flash 注册 + features 路由启用 + 探针收口） |

**不做**（明确划界）：AI 配图/封面生成（多模态）——属产品决策，且与"图片仅合规"的现有边界冲突；AI 冷启动种子内容——缺空圈审核链路（路线图已挂起）。

---

## 四、面试口径（30s 版）

> "我的 AI 增强分三层：**能跑**（RAG + 混合召回 + rerank + 分块检索）、**可信**（忠实度三级校验 + 熔断降级 + 语料新鲜度）、**能经营**（配额计费 + 反馈闭环 + 评测门禁 + 模型路由）。下一步我做两件最值钱的事：一是**把按次计费升级为按 token 成本计费**，让模型路由能按成本决策；二是**把向量与内容做哈希绑定**，解决 RAG 最难察觉的失效模式——内容更新了但检索还是旧语义。这两个都是'AI 从 demo 走向生产'的标志性工作。"
