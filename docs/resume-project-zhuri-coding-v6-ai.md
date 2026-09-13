# 逐日 Coding · 简历版项目经历 v6（AI 版·包含关系，可直接粘贴）

> 版本 v6（2026-09-10 晚）：在 v5（AI 5 + Java 4 = 9 条）基础上**补入三块已验证的新增源码能力**——① RAG **父子分块检索**（small-to-big）② **BM25 + 向量 RRF 混合召回** ③ **相似问题语义缓存**；并把 J-4 补上**社交登录**。
>
> **用户拍板口径（沿用）**：
>
> - 结构：**AI 5 + Java 4 = 9 条**；AI-3（UGC 治理）与 AI-4（AIGC 检测）已合并为「AI 内容治理与诚信体系」；
> - 措辞尺度：新链路一律写「**设计并实现**」，**不写"已上线创收/已产生收入"**；
> - 面试主线：**AI-1（检索质量与成本工程）→ AI-3（治理与诚信）→ AI-5（商业化工程）**；Java 岗前置 J-1/J-3。
>
> ⚠️ 待定稿项（文档不替你做决定）：
>
> - **时间线**：按 PDF 口径 `2025.09-至今`，投递前统一；
> - **框架口径**：真实链路为 DashScope 专属网关 + 自研 OpenAI compatible 通道演进到 **Spring AI**（LangChain4j 仅评估未采用）→ 一律讲 Spring AI；JD 点名 LangChain4j 时答"概念等价 + 手写编排更可控"。

---

## 一、可直接粘贴版（主推 AI 5 + Java 4 = 9 条）

**逐日 Coding｜Java 开发（AI 应用方向）｜2025.09-至今**

**项目描述：** 面向程序员的内容社区与创作者变现平台（头条/掘金/知识付费综合体）。业务侧覆盖内容发布与审核、搜索索引、双等级激励与成就、课程购买/打赏、签到兑换、站内信 IM；AI 侧以同一套后端基座承载大模型应用：RAG 社区问答、AI 发布助手（Agent 工具调用）、文章 AI 速览、内容治理与诚信体系（AIGC 检测 + 申诉复核）、语义搜索与推荐、AI 商业化（配额/额度包/反馈/评测）。按业务拆 user/content/search/reward/notification 五个微服务 + 统一网关（各服务独立库），独立完成核心链路架构设计与 90%+ 核心模块。

**技术栈：** Java 17 · Spring Boot 3.5.16 · Spring Cloud Alibaba 2025（Nacos/OpenFeign/Gateway）、MyBatis-Plus、MySQL 8、Redis+Redisson、Elasticsearch、**PostgreSQL+pgvector（父子分块向量检索）**、**Spring AI 1.1.8（OpenAI compatible）**、**qwen 大模型（文本/Embedding）**、**SSE 流式**、阿里云 OSS、支付宝沙箱、STOMP WebSocket、Zipkin/JaCoCo

### AI 侧（5 条，面试主线）

1. **自研 RAG 社区问答（检索质量与成本工程）**：文章按**父子分块**入 pgvector(1024)——段落级子块（500 字 + 80 字重叠、块数封顶 30 控成本）承担检索粒度，整篇父块提供回答上下文，解决"整篇一条向量 + 截断 2000 字"导致长文后半段召不回；召回走 **BM25 与向量双路 + RRF 倒数排名融合**（只融合排名、不融合分数——两路量纲不可比），术语/代码类查询靠 BM25 补齐、口语化提问靠向量补齐，任一路不可用自动退化为单路；口语问题先 Query Rewrite → 宽召回 15 候选 → LLM Rerank 精排 TopK → 答案逐句标注 `[n]` 引用可溯源原文，SSE 逐字流式 + 最近 6 轮会话记忆滑窗解析指代；**相似问题语义缓存**（问题向量余弦 ≥0.95 直返缓存答案，按用户隔离防画像外泄，TTL 6h + 引用文章存活校验双失效，命中即省 rewrite/rerank/生成 3 次模型调用，流式按 chunk 回放保持前端协议不变）；**检索改动一律以 12 条黄金问答集的 recall@5/10 回归，且离线评测与线上共用同一召回入口**；另做"只答本文"的文章级单篇问答与 AI 摘要（Redis 缓存 24h、长文截断 1.2 万字符控 token 成本）。
2. **AI 发布助手（LLM Agent + Function Calling 驱动）**：发布前预检由 Agent 编排——把「内容安全规则引擎、pgvector 相似内容检索」注册为 Spring AI 原生工具，模型自主决策调用后输出违规判定/质量分/标签/摘要的结构化预检报告；规则类与检索类能力收敛为本地工具（相似检索排除自身、≥0.72 阈值预警），让模型做编排与生成、而非凭记忆作答；工具调用或模型异常时自动降级直答，发布主链路不阻塞。
3. **AI 内容治理与诚信体系（自动处置 → AI 预审 → 人工终审闭环）**：**UGC 治理**——评论/沸点评论"先展示后审核"，任务落库可靠队列（CAS 抢占、指数退避、超限降级放行不误伤、崩溃由定时任务拉起），红线违规物理删除并通知，温和违规（引战/阴阳/软广）经 LLM 二次判定折叠（文章折叠条"仅自己可见"、沸点全局隐藏），一级评论折叠整树无痕、二级逐条治理；**AIGC 水文检测**——三层判定（L1 四维信号快检 45 触发复核 / 70 触发标注、L2 与作者历史向量均值偏离度画像、L3 LLM 复核仅高分触发且判正常即纠偏清标防误伤），一处检测多点消费（打赏闸门、课程订单拦截、向量库与检索过滤、对外"疑似 AI 生成"标识）；**申诉闭环**——被处置内容可申诉，AI 按类型复核给"解除/维持"建议但不终决，人工终审一键解除，幂等 + 防自审护栏，落地"平台不当裁判、误伤可纠正"。
4. **语义搜索与相似推荐（向量能力二次复用）**：搜索侧以 BM25 关键词 + 向量语义两路召回，**仅在关键词命中不足时**触发跨服务语义召回兜底（控成本），Feign 不可用时降级为纯关键词、不阻断搜索主链路；详情页"相似文章"用本文向量在 pgvector 的最近邻（无向量回退同标签），推荐与 RAG 问答共用同一向量库与内容过滤口径——一套向量基建同时服务问答检索、搜索兜底与内容推荐。
5. **AI 商业化工程（可衡量 → 可变现 → 可复用）**：**可衡量**——问答 👍/👎 反馈闭环（幂等落库）+ 12 条真实黄金问答对的 recall@5/10 离线评测集（检索改动前后回归基线）+ 功能级调用指标（含语义缓存命中率 `ai_semcache_hit`）与用户兴趣画像注入；**可变现**——每日免费配额（Redis 自然日计数、异常 fail-open）+ AI 额度包钱包（支付宝回调按订单号前缀分发、金额以服务端为准防篡改、`UPDATE … WHERE balance>0` 原子扣减防超扣、消费优先于免费额度）；**可复用**——发布预检产出的摘要/标签已回填文章元数据，进一步复用为公开 AI 速读页（SEO 入口）与详情页 AI 摘要卡。

### Java 侧（4 条，稳定盘）

1. **内容"发布→AI 审核→定时上线→搜索索引"最终一致**：审核通过后按发布时刻经 Redisson 延迟队列调度（无 MQ）；到点先落本地消息表 → 条件更新置发布态 → 一步携带发布态同步 ES → 完成清理；消息表用**单 status 状态机**（DB 置位失败幂等自愈、ES 同步失败 20s 定时扫描重试、超限死信），DB 与搜索双写以"本地消息表+定时补偿"收敛，不引入 MQ。
2. **自研行为事件总线 + @Order 后置处理器责任链**：点赞/关注/评论等一次行为按类型路由，主操作落库后有序触发等级积分、文章热度、站内信通知、成就解锁、统计等副作用，处理器独立降级互不拖垮；"行为记录门控 + 唯一索引"双保险防重复加分/重复通知；文章热度单条原子 SQL 重算（点赞×3+评论×3+收藏×6+浏览）消除读改写竞态。
3. **统一支付与资损兜底（课程购买 + 文章打赏双变现）**：金额全部服务端计算（折扣码+抽奖 5 折券；打赏限 1~10000 元并拦截自赏）；异步回调 RSA2 验签+app_id+实付金额一致多重校验后，以"条件更新原子抢占订单状态"幂等放权/入账，重复通知与并发回调不重复发奖/不重复流水；Redisson 延迟队列超时关单，对"支付成功但已关单"竞态自动退款（订单号幂等键防重复退，失败入重试任务）；课程 7:3 月度结算、打赏按流水结算，结算表唯一索引防重；**AI 额度包复用同一回调分发与幂等入账链路，验证了支付侧的扩展性**。
4. **双 Token 无感续期 + 社交登录 + 网关信任链 + 分层限流**：JWT Access(1h)+UUID Refresh(7d)，刷新用 Redis Lua 原子"取即删"轮换 refresh_token，并发仅一人成功；社交登录支持 GitHub/微博 OAuth 授权码流程（client_secret 走环境变量注入），绑定关系落 ap_user_social 并做社交账号、手机号双向防重校验，已绑定直发双 Token、未绑定引导绑定，平台 uid AES 加密回传；网关校验失败返 444 驱动前端自动刷新重放、登出服务端吊销；网关与下游 HMAC 内部签名、身份仅取网关透传（防伪造 userId 串号），ThreadLocal 请求结束即清理；网关 IP 固定窗口 + 自定义 @RateLimit（Redis+Lua 滑动窗口、全局/IP/用户多维叠加、NOSCRIPT 自愈、可配降级）+ 拦截器令牌桶三层防刷。

---

## 二、篇幅紧张精简版（9 条，每条一句话，适合一页纸）

**逐日 Coding｜Java 开发（AI 应用方向）｜2025.09-至今**

**项目描述 / 技术栈：** 同上（技术栈可截断为：Boot 3.5 · Spring Cloud Alibaba · MyBatis-Plus · MySQL · Redis/Redisson · ES · pgvector · **Spring AI + qwen** · SSE · OSS · 支付宝沙箱）

1. **自研 RAG 问答（检索质量与成本工程）**：父子分块（500 字子块 + 80 重叠、封顶 30 块）入 pgvector，**BM25 + 向量 RRF 混合召回**，Query Rewrite → 宽召回 → LLM Rerank → `[n]` 引用溯源 + SSE 流式 + 多轮记忆；**相似问题语义缓存**（余弦 ≥0.95、按用户隔离、TTL + 引用存活双失效）省 3 次模型调用；离线 recall@5/10 与线上同口径。
2. **AI 发布助手：LLM Agent + Function Calling**：内容安全规则引擎与 pgvector 相似检索注册为 Spring AI 原生工具，模型自主编排输出违规判定/质量分/标签/摘要；异常降级直答、发布不阻塞。
3. **AI 内容治理与诚信体系**：红线删除 + 温和违规 LLM 折叠（可靠队列：CAS/退避/超限降级/定时补偿）；AIGC 三层检测（四维快检 + 作者向量画像 + LLM 纠偏）联动打赏闸门/课程拦截/向量过滤/对外标识；申诉 AI 预审 + 人工终审（幂等、防自审）。
4. **语义搜索与相似推荐**：BM25 + 向量两路召回、命中不足才语义兜底，Feign 降级不阻断；详情页相似文章复用同一向量库（无向量回退同标签）。
5. **AI 商业化工程**：反馈闭环 + recall@k 评测集 + 指标/画像；每日免费配额 + 额度包钱包（回调前缀分发、金额校验、原子扣减、消费优先）；预检摘要标签回填元数据并复用为 AI 速读页。
6. **发布→上线→搜索最终一致**：Redisson 延迟队列 + 本地消息表单 status 状态机（DB 失败自愈 / ES 失败 20s 扫描重试 / 超限死信），无 MQ 的 DB 与 ES 双写最终一致。
7. **行为事件总线 + @Order 责任链**：一次行为有序触发积分/热度/通知/成就，处理器独立降级；行为记录门控 + 唯一索引防重，热度原子 SQL 重算。
8. **支付资损兜底**：服务端计价 + RSA2 验签 + 条件更新幂等放权；超时关单 + "已关单却支付成功"自动退款（幂等键），7:3 月度结算防重；AI 额度包复用同一链路。
9. **双 Token + 社交登录 + 网关信任链 + 分层限流**：GitHub/微博 OAuth 登录（绑定双向防重、统一发双 Token）；Redis Lua 原子轮换防竞态、444 驱动刷新重放；HMAC 内部签名防伪造 userId；IP 固定窗口 / @RateLimit 滑动窗口 / 令牌桶三层防刷。

---

## 三、代码证据与面试口径备注（每条可被深挖到文件/机制）

| #    | 面试可展开的证据点 | 对应代码位置 |
| ---- | ---- | ---- |
| AI-1 | **父子分块**：子块 target=500 / overlap=80 / 单篇 ≤30 块，四级降级切分（段落→行→句→定长硬切）、尾部重叠落在句边界；召回 `GROUP BY article_id + MAX(1-(embedding<=>?))` 取每篇最相似子块，父块上下文仍取整篇正文。**RRF 混合召回**：`score = Σ 1/(k+rank)`、k=60、只融合排名、融合序即候选序（BM25 独有命中用本地余弦补相似度，零模型调用）；BM25 端点刻意不按 publishTime 排序（要 `_score` 序）、不做语义兜底（防服务间回环）。**语义缓存**：余弦 ≥0.95、`ai.semantic-cache.*`（ttl-hours=6 / max-per-user=50）、TTL + 引用文章存活校验双失效、指代类/过短问题不缓存；指标 `ai_semcache_hit/store/evict`。**评测同口径**：离线评测与线上共用 `HybridRecallService`。**其它**：Query Rewrite→宽召回 15→Rerank→`[n]` 引用；SSE delta/done/error；memory 滑窗 ≤12；摘要 key `ai:article:summary:{id}` TTL 24h、截断 1.2 万字符 | `TextChunker`、`RrfFusion`、`HybridRecallServiceImpl`、`ArticleEmbeddingServiceImpl.recallArticles`、`AiSemanticCacheServiceImpl`、`AiAskServiceImpl.retrieveAndAssemble`、`ISearchClient.bm25Recall` → search 服务 `feign/SearchClient`、`ArticleQaServiceImpl`、`ai-eval/eval-questions.json` |
| AI-2 | 工具=确定性规则 + 向量检索（排除自身、≥0.72 预警）；模型只做编排与生成输出结构化终态；异常降级直答。口径：不展开开发过程，答"先轻量通道验证能力，再框架化收敛工具维护成本"，最终态 Spring AI 原生 Function Calling | `AgentRunner`、`ai/agent/tools`、`PublishAssistantServiceImpl` |
| AI-3 | **治理**：`ap_comment_audit_task` / `ap_pins_comment_audit_task` 双表（源表自增不共用唯一键）；CAS PENDING→PROCESSING；退避 60s×2^n 封顶 5 次降级放行；折叠语义（文章灰条本人可见 / 沸点全局隐藏 / 一级整树无痕）。**AIGC**：阈值 REVIEW=45 / FLAG=70，权重 重复 .30/模板 .25/爆发 .25/锚点 .20，作者画像 3~10 篇、权重 .30；L3 仅 final≥70 触发且判 normal 纠偏清标。**联动**：打赏 is_aigc==1 拒、课程订单 is_aigc=1 拦截、RAG/向量入库过滤、详情页"疑似 AI 生成"角标。**申诉**：`ap_content_appeal`（type 1 评论/2 文章 AIGC，ai_verdict 存建议不终决，status 待审/解除/驳回），reviewer≠applicant 防自审 | `CommentAuditService`、`AigcDetectServiceImpl`、`ContentAppealServiceImpl`、`TipServiceImpl`、`OrderServiceImpl`、`AiAskServiceImpl.retrieveAndAssemble`（已发布 + 非 AIGC 过滤）、`article.ftl` |
| AI-4 | 搜索：仅当 `pageNum==1 && list.size()<pageSize` 才调语义兜底（TopK≤20、宽召回×3、过滤已发布非 AIGC）；Feign fallback 返回 503 → 降级纯关键词。推荐：本文向量 topK（×2 宽召回）排除自身、无向量回退 `JSON_OVERLAPS` 同标签 | `SemanticSearchServiceImpl`、`ISemanticSearchClient(Fallback)`、`ArticleSearchServiceImpl`、`ArticleDetailServiceImpl.findSemanticSimilarArticles` |
| AI-5 | 配额：`ai:quota:daily:{userId}:{date}`，首计设当日过期、Redis 异常 fail-open、DAILY_QUOTA=20；额度包：orderNo=`ai`+雪花作 out_trade_no，`PayController.notify` 前缀分发（不动课程逻辑）、金额以订单分为准、`WHERE balance>0` 原子扣、入账幂等；反馈：`ap_ai_feedback` 唯一键 user+feature+scene+question_hash；评测：12 条真实 golden + recall@5/10；画像：收藏 30 天聚合标签 Top5 注入 Prompt；路由：`AiModelRouter` 全 Bean 注册 + feature 映射（当前单模型恒默认，第二模型零改动接入） | `AiQuotaServiceImpl`、`AiTopupServiceImpl`、`PayController`、`AiFeedbackServiceImpl`、`AiEvalServiceImpl`、`UserInterestServiceImpl`、`AiModelRouter` |
| J-1 | `article_event` 单 status 状态机常量（INIT/DB_SET_FAIL/ES_SYNC_FAIL/DONE），删除旧双状态位与事件监听双写 | `ApArticleEventServiceImpl`、`ArticleConstants` |
| J-2 | BehaviorEventBus + `List<Handler>` @Order；行为记录门控+唯一索引；热度原子 SQL | `BehaviorEventBus`、`comment/behavior`、`ApArticleMapper` |
| J-3 | 条件更新 `UPDATE … WHERE status=待支付` 幂等；退款幂等键；结算唯一索引；AI 额度包复用 notify 分发 | content 支付服务、`PayController`、reward 结算 |
| J-4 | Redis Lua 取即删轮换；网关 HMAC 内部签名；@RateLimit 注解实现；社交登录：GitHub/微博 OAuth 授权码换 token/uid（client_secret 走环境变量）、ap_user_social 绑定双向防重、登录与绑定统一 `TokenService.generateDualToken`、平台 uid AES 加密回传（`need_bind` 流程） | user 认证（`SocialAuthServiceImpl`、`SocialLoginServiceImpl`、`SocialAuthCallBack`、`TokenService`）、网关过滤器、common 限流 |
| J-5（口述） | 异步任务可靠性横向体系：审核任务 / 发布消息表 / 退款重试任务 / 申诉 AI 预审四处统一为"任务落库状态机 + CAS 抢占 + 指数退避 + 定时扫描补偿 + 超限降级或死信"，崩溃可恢复、不误伤、不重复——作为横向能力回答"你怎么保证异步可靠性" | `AbstractAuditService`、`CommentAuditRecoveryTask`、`RefundRetryTask`、`ContentAppealServiceImpl.aiPreReview` |

> 面试主战线：**AI-1（检索质量与成本工程）→ AI-3（治理与诚信）→ AI-5（商业化工程）→ J-3（支付兜底）**；AI 岗前置 AI 1~5，Java 岗前置 J-1/J-3。
> 2 分钟 STAR 锚点：一句话业务 + "AI 是第二引擎" + 主链路一致性 + 一个真实问题解决实例收尾。

### 检索类追问弹药（新增，被深挖时用）

| 追问 | 口径 |
|---|---|
| 为什么用 RRF 而不是加权分数融合？ | BM25 的 `_score` 与余弦相似度量纲不可比，加权需先归一化且要调参；RRF 只用名次、超参只有 k（默认 60），更稳且无需训练 |
| 分块后答案上下文会不会不完整？ | 检索在子块（细颗粒命中）、上下文仍取整篇父块（small-to-big），引用仍指向文章级 |
| 为什么要按用户隔离缓存？ | 答案 prompt 注入了长期记忆与兴趣画像，跨用户复用等于泄露画像；宁可降命中率也不能串号 |
| 缓存怎么保证不过期答案？ | TTL 6h + 命中时校验引用文章仍「已发布 + 未删 + 非 AIGC」，不满足就删条目回退正常链路 |
| 怎么证明检索改动有效？ | 12 条黄金问答集 recall@5/10 前后对比，**且评测与线上共用同一召回入口**（否则跑了也不算数） |
| 这套改动上线了吗？ | 如实答：代码已实现并通过编译 + 分块/RRF 单测；**评测集对比数据与线上冒烟尚未跑**（本地需 ES + pgvector + 服务全启） |

---

## 四、投递双版排序（同一份内容，两种开头）

- **投 Java 岗**：按"一"的顺序直接贴（AI 在后半场当亮点），求职意向写"Java 开发"。
- **投 AI 应用开发岗**：把"AI 侧 5 条"整体提到 Java 侧之前（AI-1~5 → J-1~4），项目描述首句强调"大模型应用已落地于真实业务（RAG 问答 / Agent 预检 / 治理与诚信 / 语义搜索 / 商业化闭环）"，求职意向写"Java/AI 应用开发"；技术栈把 Spring AI、pgvector、SSE 提到最前。

---

## 五、可选第 10 条（仅在版面允许、且投 AI 岗时使用）

**拆法 A（推荐，突出成本工程这条新能力）**：把语义缓存从 AI-1 拆出，独立成 AI-6：

> **AI 问答语义缓存（成本工程）**：为相似问题建立向量级缓存——问题向量余弦 ≥0.95 直返历史答案，命中即省掉 Query Rewrite + LLM Rerank + 生成三次模型调用（代价仅一次 embedding）；**按用户隔离**（prompt 注入了长期记忆与兴趣画像，跨用户复用等于泄露画像）；失效用 TTL 6h + 引用文章存活校验双保险（下架/被判 AIGC 即整条删除）；指代类问题（"它/上面提到"）不缓存，避免语义相似但意图不同；流式命中按 chunk 回放，前端 delta/done 协议不变。

拆出后 AI-1 收窄为"RAG 检索工程：父子分块 + BM25/向量 RRF 混合召回 + 引用溯源 + 评测同口径"。

**拆法 B（原方案）**：把"单篇问答 + AI 速览 + 读完想问"拆出为独立条目，AI-1 收窄为"RAG 社区问答（多篇检索）"。

---

## 六、本轮改动清单与验证状态（2026-09-10 晚）

| 改动 | 代码 | 验证到什么程度 |
|---|---|---|
| 相似问题语义缓存 | `AiSemanticCacheServiceImpl`、`ai_semantic_cache_setup.sql`、`AiAskServiceImpl` 三处接入 | 编译通过；**建表 SQL 需手动执行**；未起服务冒烟 |
| 父子分块检索 | `TextChunker`、`ai_article_chunk_setup.sql`、`ArticleEmbeddingServiceImpl.saveChunks/findSimilarChunks/recallArticles` | 编译通过 + 分块器 6 项断言全过（含修掉 1 个真 bug：块尾恰好是句边界时重叠失效）；**未跑评测集** |
| BM25 + 向量 RRF 混合召回 | `RrfFusion`、`HybridRecallServiceImpl`、search 服务 `bm25Recall` 端点 + Feign 契约/降级 | 编译通过（content + search BUILD SUCCESS）+ RRF 8 项断言全过；**未跑评测集对比** |
| 社交登录补入 J-4 | `SocialAuthServiceImpl`、`SocialLoginServiceImpl` | 代码已核对（OAuth 授权码流程 / 绑定双向防重 / 统一发双 Token） |

> 面试被问"最近在推进什么"时的标准答法：**"我在补 RAG 的检索质量——先做父子分块解决长文召不回，再用 RRF 把 BM25 和向量融合起来；同时给相似问题加了按用户隔离的语义缓存降本。这三块都走同一套离线评测口径，下一步就是用 recall@5/10 把前后数据拉出来对比。"**（如被追问数据，如实说还没跑，别编。）
