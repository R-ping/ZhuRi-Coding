# 社区 AI 问答（RAG）设计 v1 —— A 线

> 目标：把项目已有的"AI 审核/相似度查重"内部资产，升级为 C 端可交互的 **RAG 问答**（可 demo、可进简历、贴近 AI 应用/agent JD）。
> 一句话形态：用户提问 → 从**已发布文章**向量库召回 TopK → LLM 基于资料回答并**带来源引用**。

---

## 一、复用资产（全部现成，已核实）

| 资产 | 位置 | 说明 |
|---|---|---|
| 向量生成 | `ArticleEmbeddingServiceImpl.generateEmbedding(content)` | DashScope embedding，返回 double[] |
| 向量检索 | `ArticleEmbeddingServiceImpl.findSimilarArticles(emb, topK, threshold)` | pgvector `1-(embedding <=> ?) `余弦 TopK，`LIMIT ?` |
| LLM 客户端 | `DashScopeClient` + `StructuredOutputInvoker`（BailianAiServiceImpl 依赖） | 百炼调用已有封装（现用于审核），扩展 chat 即可 |
| pgvector 建表 | `content/.../db/migrations/pgvector_setup.sql` | `ap_article_embedding(article_id, embedding float8[])` |
| 文章元数据 | `ap_article`（title/author_name/content/status/likes…） | join 做来源卡片与"仅 PUBLISHED 可引用"过滤 |
| 防刷 | `@RateLimit`（全局/IP/USER 多维） | 复用注解直接挂新端点 |

## 二、接口与数据流

```
POST /content/api/v1/ai/ask        （登录；@RateLimit USER 5次/分 + GLOBAL 高限兜底）
req : { "question": "社区里有没有讲 MySQL 索引优化的文章？" , "topK": 5 }
resp: {
  "answer": "根据社区文章…主要有三点：…[1][2]",
  "sources": [ { "articleId": 1001, "title": "…", "author": "张三",
                 "likes": 12, "similarity": 0.82 } ],
  "latencyMs": 3421
}
```

1. 参数校验：question 非空、≤200 字；topK 1~8 默认 5
2. `generateEmbedding(question)`（失败 → 返回"知识库暂不可用"提示，不 500）
3. `findSimilarArticles(emb, topK, 0)` → 候选 article_id 列表
4. join `ap_article`：过滤 `status=9`（PUBLISHED），取 title/author_name/likes/content
5. 组装 prompt：system=“你是《逐日 Coding》社区知识助手。只能依据【参考资料】回答，引用用 [序号] 标注来源；资料不足必须明说不知道；禁止编造”；user=资料列表（每篇 截前 ~1500 字）+ 问题
6. 调 LLM 自由文本生成 → 返回 answer + sources（按相似度降序）

## 三、代码改动清单

**content 服务（后端）**
- 新增 `controller/v1/ai/AiAskController.java`：`POST /api/v1/ai/ask`
- 新增 `service/ai/AiAskService(+Impl).java`：编排 检参→向量→召回→join→prompt→生成
- `BailianAiService(+Impl)`：新增 `String ask(String system, String user)`（复用 DashScopeClient；与审核的结构化输出调用并存）
- `ArticleEmbeddingServiceImpl` 不变（直接复用两个方法）
- 新增一次性回填任务 `AiAskBackfillJob`（可选）：分页扫描 PUBLISHED 且无向量的文章 → 截前 N 字 → `saveEmbedding`（保证 demo 召回；跑一次后停用）
- DTO/VO：`AiAskDto` / `AiAnswerVo` / `AiSourceVo`
- 安全：system 与资料强隔离、长度截断、仅 PUBLISHED 可见（不泄露未发布内容）

**SQL（可选 v2）**：`ai_ask_log(question/answer/latency/feedback)` —— 记录 badcase，面试讲"LLM 评估闭环"用

**前端**
- 全局/详情页"问社区 AI"浮层：输入框 → 调 `/content/api/v1/ai/ask` → answer 渲染（来源 [n] 可点）→ sources 卡片列表
- 新 api 文件 + 组件（约 1 页工作量）

## 四、前置环境（需要你确认能否提供）

1. **PostgreSQL + pgvector**：本地需有 pg 实例，执行 `pgvector_setup.sql`，并在 content 服务配置 `pgVectorDataSource`（`PgVectorConfig` 现为可选装配——不配则检索返回空）
2. **百炼 key**：embedding 与 chat 均走 DashScope（与现审核共用 key）

> 若两者暂缺：功能仍可开发，但 **demo 无法闭环**——请先确认本地是否有 pg 与 key（决定我是否先做"接口+降级提示"再补数据）。

## 五、风险与取舍

| 项 | 处理 |
|---|---|
| 存量文章无向量 → 召回空 | 回填 Job 预热；demo 前对代表文章跑一遍 |
| 长文成本 | 上下文每篇截 1500 字、topK≤5，单次约可控 |
| Prompt 注入 | system 强约束 + 资料区与指令区分离 + 输入限长 |
| 引用幻觉 | 强制要求只引用给定资料并标 [n]，资料不足明确拒绝 |
| 并发/成本 | @RateLimit 用户级限频 |

## 六、面试叙事（30s）

> “我把服务里现成的 LLM+pgvector 基建做成 C 端 RAG 问答：问题 embedding → pgvector 余弦 TopK 召回已发布文章 → 组织带来源的上下文让大模型生成回答并逐条标引用，前端可点回原文。防注入与限流与审核链路同套基建。”
