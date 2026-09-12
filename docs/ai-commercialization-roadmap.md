# AI 商业化路线图（从"AI 赋能 demo"到"商业化 AI 产品"）

> 定位：基于现有 AI 基建（Agent 预检 / RAG 问答 / 单篇问答 / 向量库 / Prompt 三层安全 / 内容治理 / 打赏-课程支付闭环）的**商业化差距评审 + P0~P2 排期**。
> 一句话总结：已解决"AI 能不能用"，缺"AI 怎么挣钱、怎么衡量、怎么迭代"。
> 代码事实核实：本文档已按真实源码校准（2026-09-09），含两处对评审原文的事实修正。

---

## 1. 事实修正（排期前必读，避免白做）

| 评审原文判断 | 真实代码事实 | 修正结论 |
|---|---|---|
| "AI 全部免费**无限用**，token 纯亏损" | AI 接口（ask/ask-article/precheck/summary/related-questions）已全量接 **@RateLimit：USER 5/min + IP 20/min** | 缺的不是防滥用频控，是**计量/每日额度/订阅解锁**；现有限频框架可直接扩展成配额 |
| "AI 摘要/写作伴侣都还没有"；"预检摘要/标签**写回草稿了吗**？" | **AI 摘要已上线**（SSR 详情页顶部卡片 + Redis 24h）；**Step1 已闭环**：预检标签/摘要经发布表单真实写回 `ap_article.tags/summary`（服务推荐引擎与列表摘要） | P1"摘要回填元数据"已完成；剩"把元数据做成公开 SEO/AI 速读入口"，属产品钩子非技术债 |

## 2. 商业化差距评审（五维，按代码校准）

### 2.1 商业化价值层：AI 没变成付费点
| 差距 | 现状证据 | 动作 |
|---|---|---|
| 无消耗型定价 | 全部免费 + 仅频控 | P0：Redis 每日配额 → P2：订阅/功能包接支付 |
| AI 不是增长引擎 | AI 入口埋在发布页/悬浮球 | P2：AI 产出物公开页（优质摘要/话题广场兼 SEO） |
| 无 B 端/创作者经济 | 付费全 C 端 | P2：作者复盘报告/涨粉诊断（Agent 编排商业化出口） |

### 2.2 产品体验层：场景割裂、上下文不通
| 差距 | 动作 |
|---|---|
| 入口分散无统一心智 | P2：收敛"AI 助手"统一品牌入口（聚合页） |
| 上下文不打通（记忆仅 session 级，身份画像未进 prompt） | P1：把用户关注频道/标签（recommend:interest:{userId}）注入 AiAsk system，个性化回答 |
| AI 产出物沉淀 | 已完成（tags/summary 回填），剩公开复用 |

### 2.3 技术架构层：缺"生产级"半档
| 差距 | 动作 |
|---|---|
| **无评测体系（最致命）** | P1：eval 集（20~30 条黄金问答对+期望召回）+ recall@k/答案命中/违规漏检脚本 |
| 无成本观测/配额/统一兜底文案（限流 403 裸报错） | P0：计量+每日配额+友好超限文案 |
| 无双模型/灰度路由 | P2：模型路由层（低成本兜底高成本） |
| 多模态缺口 | P2（AI 配图/封面，尊重"图片仅合规"产品边界，需产品决策） |
| AI 专用可观测指标缺 | P1：Agent 工具调用数/token 数/采纳率指标（Micrometer 自定义） |

### 2.4 数据与运营层：无反馈闭环（商业化灵魂）
| 差距 | 动作 |
|---|---|
| 无 👍/👎 反馈 | **P0（先行落地）**：ai_feedback 表 + 前端问答 👍/👎 |
| 无"发起→完成→采纳"漏斗埋点 | P0：反馈落库 + 关键行为日志（全链路 BI 待 P1） |
| 无冷启动 AI 运营（空圈子种子内容） | P2 |

### 2.5 安全合规层（相对领先，补两块）
| 差距 | 动作 |
|---|---|
| AIGC 内容标识（深度合成规定） | P1 门槛：AIGC 检测已产出 is_aigc，对外加"疑似 AI 生成"角标（成本≈前端角标，兼做差异卖点） |
| AI 回答责任边界 | P1：摘要/问答附"AI 生成内容，仅供参考"；违规兜底回调（ComplianceGuard 已有） |

---

## 3. P0~P2 排期与验收

### P0-A AI 每日配额计量（成本止血，先于付费）
- 实现：`AiQuotaService`（Redis incr+首日 expire，key `ai:quota:{userId}:{yyyyMMdd}`）；免费每日 N 次（问答类 N=20，可配置）；Controller 层接入 ask/ask-article 等问答类入口，超限返回 429 + 引导文案。
- 验收：同账号第 21 次问答返回"今日免费 AI 问答次数已用完"；次日重置；不阻塞有缓存的摘要/读完想问（或单独低额度）。

### P0-B AI 反馈闭环（👍/👎）
- 实现：`ap_ai_feedback` 表 + `POST /api/v1/ai/feedback`（feature/question/answer 摘要/feedback±1/登录 user）；前端两处问答入口（AiAskFloating / SSR 单篇浮层）回答尾部加 👍/👎。
- 验收：点赞/踩落库；重复点击幂等（同用户同条一次）。

### P1（按含金量排序）
1. **AI 评测集**：手工 20~30 条黄金问答对（含检索期望命中文章）+ eval 脚本（recall@k、答案命中、预检违规漏检率）——面试与迭代双用。
2. 身份画像进 Prompt（关注频道/标签 → AiAsk system 个性化）。
3. AI 专用指标（Agent 步数/token/采纳率，Micrometer 自定义 + 日志埋点）。
4. 合规两块：AIGC 对外角标 + AI 生成免责文案。

### P2
统一 AI 助手入口聚合页 / AI 产出物公开页（SEO）/ 作者复盘报告（接支付）/ 订阅-配额打通（新商品类型复用现支付幂等链路）/ 模型路由层 / 冷启动种子内容。

---

## 4. 面试弹药（30s 商业化思考）

> "从商业化角度我会补三件事：**一、让 AI 可衡量**——现在 AI 功能没有评测集和反馈闭环，我先加 👍/👎 和每日额度计量，让模型一改好坏有感知、成本不失控；**二、让 AI 进付费闭环**——把每日免费额度作为获客钩子，订阅/功能包接到现有支付幂等链路；**三、把 AI 产出物沉淀成资产**——预检的标签摘要已回写元数据，下一步做成公开的 AI 速读页兼 SEO 流量入口。合规上补 AIGC 内容标识。我的判断是：先能衡量、再能变现，而不是先做收费功能。"

---

## 5. 落地状态
- [x] 文档（2026-09-09）
- [x] P0-A 配额计量（2026-09-09：AiQuotaService Redis 日配额，ask/ask-article/askStream 接入，超限 429/error 事件，fail-open）
- [x] P0-B 反馈闭环（2026-09-09：ap_ai_feedback + /api/v1/ai/feedback 幂等；AiAskFloating 与 SSR 单篇浮层 👍/👎）
- [x] P1 评测集（2026-09-09：ai-eval/eval-questions.json 12 条真实 golden 问答对 + AiEvalService recall@5/10 + POST /api/v1/ai/eval/run）
- [x] P1 合规标识（2026-09-09：详情页标题"疑似 AI 生成"角标 is_aigc + 摘要/问答免责文案；AiAskFloating legal 文案此前已有）
- [x] P1 画像注入 Prompt（2026-09-09：UserInterestService 收藏 30 天聚合标签 Top5 → AiAsk buildUser 注入【用户兴趣参考】，无关可忽略且依据仍限资料）
- [x] P1 AI 专用指标（2026-09-09：AiMetricsCollector 内存计数 + GET /api/v1/ai/metrics + 反馈 up/down 统计；各 AI 端点已埋 incr）
- [x] P2 作者复盘报告（2026-09-09：CreatorReportService 近 N 天发布聚合+环比+Top/Bottom+标签分布 → LLM Markdown 复盘；Redis 6h 缓存；POST /api/v1/ai/creator/report）
- [x] P2 模型路由层（2026-09-09：AiModelRouter 全 Bean 注册+feature→model 映射+路由决策日志/指标；CreatorReport 示范使用；GET /api/v1/ai/router/config）
- [x] P2 订阅-配额打通（2026-09-10：AI 额度包后端全链路——目录 q200/q1000/q5000、ap_ai_topup_order(前缀 ai 回调分发于 PayController notify)、ap_ai_wallet 钱包、消费优先于每日免费、收银台 HTML 复用 AlipayService；沙箱验证待联调）
- [x] P2 AI 速读广场页（2026-09-10：SSR /content/article/ai-reading 聚合有 AI summary 元数据的非水文文章，SEO 直出）
- [ ] P2 统一 AI 品牌入口（SPA Hub 重构，需 dev 环境联调后实施）/ 冷启动种子内容（缺空圈审核链路，挂起）
- [ ] P2 应用小结：交付待沙箱联调（AI 额度包支付）+ creator 前端入口（充值/复盘/广场导航）
