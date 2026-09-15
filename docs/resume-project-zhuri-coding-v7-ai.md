# 逐日 Coding · 简历版项目经历 v7（AI 增强 · 可直接粘贴）

> 版本 v7（2026-09-15）：在 v6（AI 5 + Java 4 = 9 条）基础上**融合 9-14/9-15 完成的 AI 增强 8 项**——
> ① token 计量 9 处接入 + 额度包按 token 计费 + 成本报表（P0-2）
> ② 向量与正文指纹绑定（P0-1）
> ③ LLM/Embedding 熔断（P1-1）
> ④ 反馈回灌（P1-2）
> ⑤ 评测门禁 fail-closed + 评测集 12→24 条（P1-3）
> ⑥ Prompt 版本注册表（灰度/回滚/归因，P2-1）
> ⑦ 语义缓存升级三重失效（P2-2）
> ⑧ 冷启动画像 / 会话摘要压缩 / 流式取消+TTFT（P2-3）
>
> **沿用的拍板口径**：结构 AI 5 + Java 4 = 9 条；新链路写"设计并实现"，不写"已上线创收"；措辞尺度与 v6 一致。
> **数字口径变更**（覆盖 v6 旧说法）：评测集 ~~12 条~~ → **24 条**；语义缓存 ~~双失效~~ → **三重失效**；"AI 商业化工程" → **"成本工程与商业化闭环"**。
> **配套阅读**：`docs/interview-ai-enhancement-handbook.md`（每项功能的实现原理与追问 Q&A，防钻细节）。

---

## 一、可直接粘贴版（主推 AI 5 + Java 4 = 9 条）

**逐日 Coding｜Java 开发（AI 应用方向）｜2025.09-至今**

**项目描述：** 面向程序员的内容社区与创作者变现平台（头条/掘金/知识付费综合体）。业务侧覆盖内容发布与审核、搜索索引、双等级激励与成就、课程购买/打赏、签到兑换、站内信 IM；AI 侧以同一套后端基座承载大模型应用：RAG 社区问答、AI 发布助手（Agent 工具调用）、文章 AI 速览、内容治理与诚信体系（AIGC 检测 + 申诉复核）、语义搜索与推荐、AI 商业化（配额/额度包/反馈/评测）。按业务拆 user/content/search/reward/notification 五个微服务 + 统一网关（各服务独立库），独立完成核心链路架构设计与 90%+ 核心模块。

**技术栈：** Java 17 · Spring Boot 3.5.16 · Spring Cloud Alibaba 2025（Nacos/OpenFeign/Gateway）、MyBatis-Plus、MySQL 8、Redis+Redisson、Elasticsearch、**PostgreSQL+pgvector（父子分块向量检索）**、**Spring AI 1.1.8（OpenAI compatible）**、**qwen 大模型（文本/Embedding）**、**SSE 流式**、阿里云 OSS、支付宝沙箱、STOMP WebSocket、Zipkin/JaCoCo

### AI 侧（5 条，面试主线）

1. **自研 RAG 社区问答（检索质量与新鲜度工程）**：文章按**父子分块**入 pgvector(1024)——段落级子块（500 字 + 80 字重叠、块数封顶 30 控成本）承担检索粒度，整篇父块提供回答上下文，解决长文后半段召不回；召回走 **BM25 与向量双路 + RRF 倒数排名融合**（只融合排名、不融合分数——两路量纲不可比），任一路不可用自动退化单路；口语问题先 Query Rewrite → 宽召回 15 候选 → LLM Rerank 精排 TopK → 答案逐句标注 `[n]` 引用溯源，SSE 逐字流式 + 多轮会话记忆解析指代；**向量与正文 SHA-256 指纹绑定**——embedding 行存内容指纹，每日游标回填按"缺向量补写/指纹不一致重算/一致跳过"三态处理并清理下架残留，杜绝"内容已更新、检索还是旧语义"的隐性失效；**相似问题语义缓存**（余弦 ≥0.95 直返，按用户隔离防画像外泄）升级**三重失效**：TTL 6h + 引用文章存活校验 + **语料指纹逐篇比对**（文章被编辑即驱逐，指标单独计数）；**长会话摘要压缩**——会话达阈值把最早 20 条对话 LLM 压成一条摘要异步放回头部（SETNX 互斥 + 原子命令只动头部，与并发写入不冲突），上下文不随轮次线性膨胀；**检索改动一律以 24 条黄金问答集（含无答案拒绝类）的 recall@5/10 回归，离线评测与线上共用同一召回入口**。
2. **AI 发布助手（LLM Agent + Function Calling 驱动）**：发布前预检由 Agent 编排——把「内容安全规则引擎、pgvector 相似内容检索」注册为 Spring AI 原生工具，模型自主决策调用后输出违规判定/质量分/标签/摘要的结构化预检报告；工具调用或模型异常时自动降级直答，发布主链路不阻塞。
3. **AI 内容治理与诚信体系（自动处置 → AI 预审 → 人工终审闭环）**：**UGC 治理**——评论/沸点评论"先展示后审核"，任务落库可靠队列（CAS 抢占、指数退避、超限降级放行、定时任务拉起），红线违规物理删除，温和违规经 LLM 二次判定折叠；**AIGC 水文检测**——三层判定（四维信号快检 / 与作者历史向量均值偏离度画像 / LLM 复核高分触发且判正常即纠偏），一处检测多点消费（打赏闸门、课程订单拦截、向量库与检索过滤、对外"疑似 AI 生成"标识）；**申诉闭环**——AI 按类型复核给建议但不终决，人工终审一键解除，幂等 + 防自审护栏。
4. **语义搜索与相似推荐（向量能力二次复用）**：搜索侧 BM25 + 向量两路召回，仅在关键词命中不足时触发跨服务语义召回兜底（控成本），Feign 不可用降级纯关键词不阻断主链路；详情页"相似文章"复用同一 pgvector 库最近邻（无向量回退同标签）——一套向量基建同时服务问答检索、搜索兜底与内容推荐。
5. **AI 成本工程与商业化闭环（可衡量 → 可控 → 可变现）**：**可衡量**——token 级计量收口统一网关 **9 处模型调用接入点**（真实 usage 优先、缺失按字符估算并标 `estimated`，成本报表不留空洞），成本报表按功能×模型归因；**可控**——LLM/Embedding **双目标熔断**（60s 窗口 5 连败打开 30s：快速失败防超时雪崩、半开试探恢复、Redis 计数多实例共享、安全拦截不计失败），SSE **流式取消**（客户端断开即中断生成省 token，取消不计熔断失败、已生成部分估算计量后丢弃）+ **TTFT 首 token 延迟指标**，**评测门禁**（阈值判定 fail-closed：报告缺失/异常一律视为不通过，端点限流最严档）；**可变现**——每日免费配额（异常 fail-open）+ AI 额度包钱包**按 token 成本计费**（支付宝回调按订单号前缀分发、金额服务端为准、`WHERE balance>=N` 原子扣减防超扣）；**可迭代**——Prompt 版本注册表（DB 版本化 + 按 userId 取模灰度放量 + 关停即回滚 + 代码兜底 fail-open，60s 懒刷新，回答携带 `promptVersions` 可归因 A/B），用户 👎 反馈自动导出为评测集候选回灌（差评率超 20% 告警、最小样本防误报），兴趣画像**三层冷启动**（收藏聚合 → 首问召回标签即时沉淀 24h → 全站热门兜底，首问后第二问起即有个性化）。

### Java 侧（4 条，稳定盘）

1. **内容"发布→AI 审核→定时上线→搜索索引"最终一致**：审核通过后按发布时刻经 Redisson 延迟队列调度（无 MQ）；到点先落本地消息表 → 条件更新置发布态 → 一步携带发布态同步 ES → 完成清理；消息表**单 status 状态机**（DB 置位失败幂等自愈、ES 同步失败 20s 定时扫描重试、超限死信）。
2. **自研行为事件总线 + @Order 后置处理器责任链**：一次行为按类型路由，主操作落库后有序触发等级积分、文章热度、站内信通知、成就解锁等副作用，处理器独立降级；"行为记录门控 + 唯一索引"双保险防重复；文章热度单条原子 SQL 重算消除读改写竞态。
3. **统一支付与资损兜底（课程购买 + 文章打赏双变现）**：金额全部服务端计算；异步回调 RSA2 验签+app_id+实付金额多重校验后以"条件更新原子抢占订单状态"幂等放权/入账；Redisson 延迟队列超时关单，对"支付成功但已关单"竞态自动退款（订单号幂等键防重复退，失败入重试任务）；7:3 月度结算、结算表唯一索引防重；**AI 额度包按 token 计费复用同一回调分发与幂等入账链路，验证了支付侧扩展性**。
4. **双 Token 无感续期 + 社交登录 + 网关信任链 + 分层限流**：JWT Access(1h)+UUID Refresh(7d)，刷新用 Redis Lua 原子"取即删"轮换；GitHub/微博 OAuth 社交登录（绑定双向防重、client_secret 环境变量注入）；网关校验失败返 444 驱动前端自动刷新重放；网关与下游 HMAC 内部签名、身份仅取网关透传；IP 固定窗口 + @RateLimit（Redis+Lua 滑动窗口多维叠加）+ 拦截器令牌桶三层防刷。

---

## 二、篇幅紧张精简版（9 条，每条一句话）

1. **自研 RAG 问答**：父子分块 + BM25/向量 RRF 混合召回 + Rewrite/Rerank + `[n]` 引用溯源 + SSE 流式；向量与正文 SHA-256 指纹绑定每日三态回填（防"内容改了检索还是旧语义"）；语义缓存三重失效（TTL + 存活 + 语料指纹）；长会话 LLM 摘要压缩；24 条黄金问答集 recall@5/10 回归。
2. **AI 发布助手**：Agent + Function Calling 编排安全规则与相似检索工具，异常降级直答。
3. **AI 内容治理与诚信体系**：红线删除 + LLM 折叠（可靠队列 CAS/退避/补偿）；AIGC 三层检测联动打赏/课程/检索过滤；申诉 AI 预审 + 人工终审。
4. **语义搜索与相似推荐**：两路召回、命中不足才语义兜底；详情页相似文章复用同一向量库。
5. **AI 成本工程与商业化闭环**：token 计量 9 处接入（估算标 estimated）+ 额度包按 token 计费 + 成本报表；双目标熔断（快速失败/半开恢复）+ 流式取消省 token + TTFT；评测门禁 fail-closed + 差评回灌评测集 + 差评率告警；Prompt 注册表（灰度/回滚/归因/代码兜底）；画像三层冷启动。
6. **发布→上线→搜索最终一致**：Redisson 延迟队列 + 本地消息表单 status 状态机（自愈/扫描重试/死信），无 MQ 双写最终一致。
7. **行为事件总线 + @Order 责任链**：有序触发积分/热度/通知/成就，门控+唯一索引防重，热度原子 SQL。
8. **支付资损兜底**：服务端计价 + RSA2 验签 + 条件更新幂等放权；超时关单 + 自动退款幂等；AI 额度包复用同一链路。
9. **双 Token + 社交登录 + 网关信任链 + 分层限流**：OAuth 登录统一发双 Token；Lua 原子轮换；HMAC 防伪造 userId；三层防刷。

---

## 三、代码证据与面试口径备注（v7 新增部分加粗）

| # | 面试可展开的证据点 | 对应代码位置 |
|---|---|---|
| AI-1 | v6 全部保留（父子分块/RRF/缓存/评测同口径）。**v7 新增**：① 指纹绑定——`content_hash` 列 + 三态判定（补写/重算/跳过）+ 残留清理，回填游标分页批 200；② 缓存三重失效——写入时拍 `{articleId: contentHash}` 快照、命中逐篇比对、存量 NULL 退化为存活校验随 TTL 淘汰、`ai_semcache_evict_stale` 单独计数；③ 摘要压缩——44 条触发压最早 20 条、同步段只做检查+SETNX 锁、LLM 异步独立单线程池、LTRIM+LPUSH 只动头部不丢消息；④ 评测集 24 条（事实/多跳/expectNoAnswer 三类） | `ArticleEmbeddingServiceImpl.refreshIfStale/backfillEmbeddings`、`AiSemanticCacheServiceImpl`（sources_hash_json 快照）、`RedisConversationMemoryService`（compressIfNeeded/doCompress）、`ai-eval/eval-questions.json` |
| AI-5 | **v7 核心**：① token 计量——`AiTokenMeter.record(feature, model, prompt, completion, estimated)`，9 处接入统一收口 `AiLlmGateway`；额度包按 token 扣费，条件更新原子防超扣；② 熔断——`AiCircuitBreaker`（阈值 5/窗口 60s/打开 30s 全可配，Redis 计数，LLM 与 Embedding 独立，fail-open 查询异常放行）；③ 流式取消——SseEmitter 回调置位 AtomicBoolean → onDelta 抛 CancellationException 中断流迭代；网关捕获取消**不计熔断失败**（cause 链识别包装异常）+ 已生成部分估算计量后丢弃；指标 `aiask_stream_ttft` / `aiask_stream_cancelled`；④ 门禁——`runGate()` + `evaluateGate` 纯函数（阈值 60/50/30/50/10，fail-closed），`POST /api/v1/ai/eval/gate`；⑤ 回灌——`badCases/exportEvalCandidates/statsByFeature`（告警 0.2、最小样本 5）；⑥ 注册表——表 `ap_ai_prompt`（key+version 唯一，7 个种子），灰度 `floorMod(userId,100)<rollout`，正式版取 version 最大，static 常量兜底 version=0，60s 懒刷新（刷新失败也推进时间戳防雪崩），响应带 `promptVersions`；⑦ 冷启动——L1 收藏（30 天 Top5）→ L2 即时兴趣（Redis 24h，首问召回标签沉淀）→ L3 全站热门（likes 排序），每层 fail-open 降级，日志记来源 | `AiTokenMeterImpl`、`AiLlmGateway`（allowByCircuit/recordCancelledUsage）、`AiCircuitBreaker`、`AiAskController.askStream`（cancelled 标志）、`AiAskServiceImpl.streamFastAsk`（TTFT/取消分支）、`AiEvalServiceImpl.runGate`、`AiFeedbackServiceImpl`、`AiPromptRegistryImpl`、`UserInterestServiceImpl`（buildInterestTags/learnFromQuery）、迁移 `ai_prompt_registry_setup.sql` / `ai_prompt_registry_add_memory_compress.sql` |
| AI-2~4 | 同 v6，无变化 | 同 v6 表 |
| J-1~4 | 同 v6，无变化 | 同 v6 表 |
| J-5（口述） | 同 v6：异步任务可靠性横向体系（任务落库状态机 + CAS + 退避 + 扫描补偿 + 超限降级/死信）——**v7 可加一句**：摘要压缩、向量回填两处新异步任务沿用同一套范式 | 同 v6 + `RedisConversationMemoryService`、`AiAskServiceImpl.backfillEmbeddings` |

---

## 四、可选第 10 条（版面允许且投 AI 岗时）

把 AI-5 的"工程化基建"拆出独立一条（拆完 AI-5 收窄为"配额计费 + 额度包 + 成本报表"）：

> **AI 工程化基建（稳定性 · 质量门禁 · 迭代效率）**：LLM/Embedding 双目标熔断（窗口计数快速失败 + 半开恢复，多实例 Redis 共享）与流式取消（客户端断开中断生成省 token、取消不计熔断、TTFT 观测）构成稳定性层；评测门禁 fail-closed（报告跑不出来视为不通过）+ 差评自动回灌评测集构成质量闭环；Prompt 版本注册表（DB 多版本 + 按用户灰度 + 关停即回滚 + 代码兜底 + 回答携带版本归因）让提示词像代码一样可发布、可回滚、可 A/B。

---

## 五、v7 相对 v6 的验证状态

| 项 | 验证程度 |
|---|---|
| AI 增强 8 项 | 代码全部实现；**content 全量回归 973/973 全绿**（新增 16 例：兴趣画像 7 / 缓存压缩 4 / 取消计量 2 / 指标 3）；MySQL 迁移（prompt 注册表 7 种子）本地执行验证 |
| 未闭环（诚实边界） | 线上真实流量数据 / 评测对比基线 / Prompt 实际灰度 / CI 定时门禁——**均未跑**，被问照实说 + 接下一步（话术见手册 §12） |
| 追问防守 | `docs/interview-ai-enhancement-handbook.md`：主链路地图 + 每项"是什么/为什么/怎么实现/数字为什么/Q&A" |

> **被问"最近在推进什么"的标准答法（v7 版）**：
> "我在给 RAG 补生产化短板：先解决两个'隐性失效'——向量与内容指纹绑定、语义缓存加语料指纹比对；再做两个'省钱'——token 级计量计费闭环、客户端断开即中断生成；最后补'迭代安全'——评测门禁 fail-closed、差评回灌、Prompt 灰度注册表。8 项全部落地，973 个用例全绿，现在缺的是真实流量数据，门禁和指标的地基已经打好了。"
