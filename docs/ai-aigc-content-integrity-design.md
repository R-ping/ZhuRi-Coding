# Step4 方案 · 内容诚信治理（AIGC 水文检测）设计文档

> 状态：方案设计（未编码）｜关联分支：refactor/spring-ai-enhancement 后续
> 定位：内容治理从「安全（红线/折叠）」升级到「质量与诚信」——**打击"用 AI 水文冒充人写去变现"的欺诈，以及低质 AI 文本对 RAG 向量库的污染**。
> 类比：起点/阅文对小说作品做 AI 检测治理（防 AI 水文骗订阅）。
>
> 配套面试话术见文末（技术面/开放题可直接用）。

---

## 1. 问题定义（为什么值得做）

| 问题 | 后果 | 对应资产受损 |
|---|---|---|
| 作者用 AI 批量水文冒充人写，骗取用户**打赏** | 用户花钱买到"看似有干货其实空转"的内容 → 信任流失、投诉、退款纠纷 | 打赏/变现生态 |
| 课程小节同样用 AI 凑数，用户**付费**后货不对板 | 付费信任崩塌 | 课程售卖 |
| 低智 AI 文本被 Embedding 进 pgvector | RAG 检索召回的"资料"是**模型生成的幻觉文本**，问答引用它们 → 答案质量连锁劣化 | RAG 向量库 |

> 产品立场（正面叙事，面试别讲成"反 AI"）：**不反对作者用 AI 辅助创作，反对"AI 水文冒充人写去赚用户钱"与"以量充质污染内容生态"**。

---

## 2. 检测定位与边界（先讲清"做不到什么"）

- **没有金标准**：AIGC 检测是概率判定，不是证据判定；真人写的流畅文可能误伤，AI 文经改写（换词/混写/人润色）可绕过。
- **LLM 自检测不可靠**：让同族模型判"像不像 AI"是弱信号，只能作复核佐证。
- **本方案定位 = "低智水文过滤器"**：针对批量、模板化、无个人经验的低质 AI 文本（用户痛点），不追求学术级检测。
- 核心矛盾不在检测器，而在**处置策略与误伤治理**（所以方案 60% 篇幅给处置/申诉/观测）。

---

## 3. 检测信号体系（P0 全部本地可算，零新增模型依赖）

### L1 文本统计特征（同步、免费、全量）
| 信号 | 计算 | 水文特征 |
|---|---|---|
| **突发度**（burstiness） | 句子长度方差 / 标准差（按句号/换行切句）；相邻句长度差分布 | AI 文句长均匀、方差小 |
| **重复度** | 连续 n-gram（n=4~8）重复率；高频词 Top20 占比 | AI 文空转、车轱辘话多 |
| **模板化** | "首先/其次/最后/总而言之/需要注意的是"等连接词密度；小标题(`##/###`)密度；"列表项"密度 | AI 文结构千篇一律 |
| **实例密度** | 是否有代码块/数据/具体数字/项目名等"干货锚点"（正则统计） | 水文无锚点、纯论述 |

### L2 作者画像对比（P0，复用已有向量基建）
- 已有 `ArticleEmbeddingServiceImpl`（qwen embedding，1024 维）与 `ap_article_embedding` 表。
- **作者画像向量** = 该作者历史文章向量平均（不足 3 篇时跳过此信号）。
- **可疑度** = 1 - cos(新文向量, 作者画像向量)：与作者一贯风格显著偏离 + 偏"公共 AI 腔" → 加分。
- 补充对照：与全站内容平均向量的距离（识别"与谁都无关的模板腔"）。

### L3 LLM 复核（L1+L2 命中"高疑似"时才调用，控制成本）
- 用现有 qwen（Spring AI ChatClient）复核，Prompt 草案：

```
你是内容诚信审核员。判断下面这篇文章是否疑似"AI 批量生成的水文"（无个人经验、
模板化空转、车轱辘话、信息密度低）。真人撰写但文风流畅不算；代码教程等
结构化内容不算。只输出 JSON：{"verdict":"normal"|"suspicious","score":0-100,
"reason":"不超过 40 字的理由"}
```

- 仅作高置信候选的复核 + 申诉复核，**单独使用会被同族模型误判**（诚实边界）。

### L4 分类器（P2 远期）
- 中文 AIGC 检测微调模型（RoBERTa 类）打分；需要训练/推理基建，本期不做，预留 score 列。

> P0 阈值策略：**保守起步**——只有 L1+L2 综合分进入高分段且 L3 复核仍 suspicious 才处置，宁漏勿伤；上线后按人工复核样本调阈值（见 §7）。

---

## 4. 处置矩阵（只标不删，延续平台温和治理哲学）

| 级别 | 判定 | 处置 |
|---|---|---|
| normal | 综合分低 | 正常：可打赏、入向量库、可售课 |
| **flagged**（高疑似） | L1+L2 高分 且 L3 suspicious | ① 打赏入口关闭（打赏按钮提示"该内容疑似 AI 生成，暂不支持打赏"）② **不入/移出 RAG 向量库** ③ 课程小节不可上架售卖 ④ 详情页可选轻标注（产品决策项） |
| 复审申诉 | 作者申诉 | 走 L3 复核 + 人工终审（复用治理申诉通道思想） |

- **不删除、不判违规**：保留作者解释权，避免误伤；屡犯者（≥3 次 flagged）才升级为账号级提示/限制（运营规则，不在本方案自动执行）。

---

## 5. 数据模型（遵循项目主键规范与 migrations 目录）

```sql
-- migrations/alter_content_tables_add_aigc.sql（内容库 leadnews_article，执行一次）
ALTER TABLE ap_article
  ADD COLUMN is_aigc       TINYINT  NOT NULL DEFAULT 0 COMMENT '0-正常 1-疑似AI水文(内容诚信治理)',
  ADD COLUMN aigc_score    TINYINT  NOT NULL DEFAULT 0 COMMENT 'AI水文疑似分 0-100(越高越疑似)',
  ADD COLUMN aigc_checked_at DATETIME NULL COMMENT '检测时间';

ALTER TABLE ap_pins
  ADD COLUMN is_aigc       TINYINT  NOT NULL DEFAULT 0 COMMENT '0-正常 1-疑似AI水文',
  ADD COLUMN aigc_score    TINYINT  NOT NULL DEFAULT 0 COMMENT 'AI水文疑似分 0-100';

ALTER TABLE ap_course_chapter
  ADD COLUMN is_aigc       TINYINT  NOT NULL DEFAULT 0 COMMENT '0-正常 1-疑似AI水文(禁止售卖)',
  ADD COLUMN aigc_score    TINYINT  NOT NULL DEFAULT 0 COMMENT 'AI水文疑似分 0-100';

-- 检测明细（信号可审计、支撑阈值调优），主键遵循 INT AUTO_INCREMENT 规范
CREATE TABLE IF NOT EXISTS `ap_aigc_record` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT,
  `content_type`  TINYINT     NOT NULL COMMENT '1-文章 2-沸点 3-课程小节',
  `content_id`    BIGINT      NOT NULL COMMENT '内容ID',
  `author_id`     INT         NOT NULL COMMENT '作者用户ID',
  `score`         TINYINT     NOT NULL COMMENT '综合疑似分 0-100',
  `signals_json`  VARCHAR(2000) DEFAULT '' COMMENT '信号明细 JSON(burst/repeat/template/author_cos/llm_verdict)',
  `method`        VARCHAR(32) NOT NULL DEFAULT 'stat_v1' COMMENT '检测版本/方法',
  `status`        TINYINT     NOT NULL DEFAULT 0 COMMENT '0-仅记录 1-flagged 2-申诉中 3-人工复核放行 4-人工确认水文',
  `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_type_content` (`content_type`,`content_id`),
  KEY `idx_author` (`author_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AIGC 水文检测记录(内容诚信治理)';
```

---

## 6. 接入点与执行时序（全部落到现有代码）

### 6.1 两段式执行（打赏资格要即时，向量库污染要尽快清）
```
发布/上架（作者提交）──┬─ P0-快检(L1 统计, 毫秒级, 同步)
                      │    └─ 高疑似 → 打赏资格即刻置灰 + 课程小节禁止上架
                      └─ 异步复核(快检高分才触发)
                           └─ L1 全量 + L2 作者画像 + L3 LLM 复核
                                └─ flagged → 写 is_aigc=1 → 移出向量库 / 关闭打赏 / 通知作者可申诉
```

### 6.2 接入点（文件级，写实）
| 环节 | 落点 | 说明 |
|---|---|---|
| 文章发布 | `ApArticleDraftServiceImpl`（发布转换方法，tags/summary 同处附近） | 发布成功后触发快检 + 异步复核 |
| 沸点发布 | `PinsPublishService.publish`（或 `PinsAuditService.handlePassed` 之后） | 与现有异步审核并列一个后置任务 |
| 课程小节 | course 模块章节创建/上架接口 | 小节内容 + 标题 |
| RAG 入库过滤 | `ArticleEmbeddingServiceImpl.backfillEmbeddings` | 回填时跳过 `is_aigc=1` |
| RAG 检索过滤 | `AiAskServiceImpl.retrieveAndAssemble`（现有 `Status.PUBLISHED` 过滤旁） | 检索条件加 `is_aigc=0`（治理后历史脏数据即刻不参与检索） |
| 打赏资格 | `PaymentRewardServiceImpl`（创建打赏订单前） | 校验文章 `is_aigc=1` → 拒绝并提示（防骗钱闸门） |
| 课程购买 | 课程订单创建处 | 小节 `is_aigc=1` 的课程禁止上架（前置在第 6.1） |

### 6.3 作者侧申诉（防误伤闭环）
- flagged 后作者在"创作中心"可见原因（复用质量分展示区）并可发起申诉。
- 申诉 → L3 复核 + 明细人工查看（`ap_aigc_record.signals_json` 可审计）→ 人工终审改状态 3/4。
- 该通道与评论治理"本人可见可申诉"为同一治理思想，产品交互可复用。

---

## 7. 上线观测与调参（P0 的验收标准）

| 指标 | 目标 | 手段 |
|---|---|---|
| 人工复核一致率 | flagged 中 ≥80% 被人工确认是水文 | 前 2 周对全部 flagged 人工复核 |
| 误伤申诉率 | 申诉后放行占比 <10% | 申诉通道计数 |
| 向量库水文占比 | 持续下降（治理存量可跑一次批量复核） | backfill 脚本重扫存量 |
| 打赏相关投诉 | 下降 | 客服/举报入口 |
| 单篇检测成本 | 快检≈0，复核(LLM)只在高疑似触发 | 日志采样 |

阈值起步：flagged = 快检综合分 ≥ 70 且 L3 suspicious；上线 2 周后按混淆矩阵调。

---

## 8. 风险与局限（面试主动讲 = 加分）

1. **对抗**：AI 文加个人经历/人工润色可绕过 → 定位"水文过滤器"，持续更新模板库与信号。
2. **误伤真作者**：技术文/教程本身低困惑、结构模板化 → 用"作者画像偏离度 + 实例密度 + 申诉"三重缓冲，**只标不删**。
3. **无金标准**：所有"疑似分"是启发式 → 处置永远保留人工终审。
4. **成本**：L3 只在高分候选触发，控制在可忽略量级。
5. 产品边界问题（是否详情页标注"疑似 AI 生成"）属产品决策，技术侧预留 `is_aigc` 即支持。

---

## 9. 面试话术（30 秒版）

> "我会再往平台加一层**内容诚信治理**：AI 水文检测。动机有两个——平台有打赏和付费课程，AI 水文冒充人写去变现是欺诈用户；而且低质 AI 文本会污染 RAG 向量库，问答引用了它就质量崩坏。做法上：本地统计特征（突发度/重复度/模板化）加作者历史文风画像做主判——这些零模型成本；命中高疑似再让 LLM 复核，避免同族模型自检的不可靠。处置是**只标不删**：关闭该文打赏、移出向量库、禁止课程售卖，作者可申诉人工复核。和起点治理 AI 水文一个逻辑，从内容安全升级到内容诚信。"

**被追问"检测怎么保证不误伤"** → 补两句：真人技术文本身低困惑，所以用"与作者历史风格偏离度"而非绝对阈值；且所有处置可申诉、人工终审，flagged 不下架不删除。

---

## 10. 落地分期建议
- **P0（本期若做）**：L1 统计 + 作者画像 + L3 复核 + 处置矩阵 + `ap_aigc_record` + 三张内容表加列 + RAG 入库/检索过滤 + 打赏闸门 + 申诉（人工）。工作量：表 1 + 检测服务 1 + 接入点 5 处 + 申诉接口 1。
- **P1**：上线观测调阈值、模板库对抗更新、存量批量重扫。
- **P2（远期）**：L4 分类器微调；多模态（AI 配图占位识别）不做，尊重"图片只做合规判定"产品边界。

> 面试优先级提示：后天面试不落代码；本方案作为"还能加什么 AI"开放题的完整弹药，背 §9 话术 + §8 两点防御即可。
