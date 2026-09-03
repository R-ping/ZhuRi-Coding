# 数据模型对齐设计：向稀土掘金的对象属性架构靠拢

> 现状：2026-09-03（修订版，修正概念）
> 目标：把学习型社区项目现有的核心实体，其 **对象属性（字段 / 关系）** 逐步向稀土掘金的
> 对象属性架构靠拢，让模型结构、字段命名、聚合统计口径与真实商业技术社区一致，便于前端
> 直接按掘金式数据结构消费。

***

## 〇、概念对齐（务必先厘清，避免误解）

| 概念       | 掘金中的含义                           | 本项目对应                                       | 收费？  |
| -------- | -------------------------------- | ------------------------------------------- | ---- |
| **小册**   | 一个完整的付费知识产品，有章节、定价、试读、销量         | **课程** **`ap_course`**（已具备定价/章节/折扣/销量/分成）   | ✅ 收费 |
| **专栏**   | 作者发布的一组**文章合集**，把多篇文章组织到一起，无独立定价 | **专栏** **`ap_column`**（已具备标题/简介/封面/文章数/订阅数） | ❌ 免费 |
| **课程分栏** | 掘金 App「课程」Tab 展示的即小册列表           | 课程中心 / `ap_course`                          | ✅    |
| **会员系统** | 掘金 VIP 订阅                        | **本项目不加**                                   | —    |

**结论：**

1. **小册 = 课程**：`ap_course` 就是小册，无需新建「小册/专栏付费」概念。
2. **专栏 = 免费文章合集**：`ap_column` 保持免费，**不做付费化**，不挂订单/结算。
3. **不加会员订阅体系**：`member_plan` / `member_subscription` 一律不建。

因此本次对齐的工作重心，**不再是商业化体系**，而是把现有实体的**字段属性**向掘金的对象结构拉齐。

***

## 一、Why：为什么要对齐对象属性

掘金是以「作者（User）+ 内容对象（Article / Pin / 小册 / 专栏）+ 互动对象（Comment）」为核心
的统一对象模型。每个对象都有约定的字段与统计口径。本项目若想让前端、作者中心、搜索、
推荐都按掘金式数据结构工作，核心实体应具备**同一套可识别的属性集**。

对齐的好处：

1. 作者主页 / 文章详情 / 沸点详情的数据结构与掘金一致，前端可直接映射渲染。
2. 统计字段去重、命名统一（如 `views`、`likes`、`followers`），避免同概念多字段。
3. 方便扩展推荐、搜索、创收看板——因为这些都依赖统一的计数与标签字段。

***

## 二、现状盘点：现有核心实体字段

> 下表为已纳入对比的核心实体及其当前属性（基于 `heima-leadnews-model` 实体类核实）。

### 2.1 用户对象（掘金 `User`）

| 掘金对象属性                       | 本项目字段                                    | 位置                                   | 状态          |
| ---------------------------- | ---------------------------------------- | ------------------------------------ | ----------- |
| avatar（头像）                   | `image` / `avatar_url`                   | `ap_user` / `user_profile`           | ✅           |
| username（用户名）                | `nickname` / `username`                  | `ap_user` / `user_profile`           | ✅           |
| description（一句话简介）           | `bio`                                    | `user_profile`                       | ⚠️ 仅有但分散    |
| position（职位）                 | `position`                               | `user_profile` / `ap_author_profile` | ✅           |
| company（公司）                  | `company`                                | `user_profile`                       | ✅           |
| level（等级/掘力值）                | 逐力值/逐友等级                                 | 等级体系表                                | ⚠️ 未冗余到用户对象 |
| got\_digg\_count（获赞数）        | —                                        | —                                    | ❌ 缺         |
| got\_view\_count（获阅读数）       | —                                        | —                                    | ❌ 缺         |
| got\_follower\_count（粉丝数）    | —                                        | —                                    | ❌ 缺         |
| got\_follow\_count（关注数）      | —                                        | —                                    | ❌ 缺         |
| got\_article\_count（文章数）     | ap\_author\_profile 维度                   | —                                    | ⚠️ 未聚合到用户对象 |
| got\_pin\_count（沸点数）         | —                                        | —                                    | ❌ 缺         |
| region/city（地区）              | —                                        | —                                    | ❌ 缺         |
| education/school（教育经历）       | —                                        | —                                    | ❌ 缺         |
| skills/work\_year（技术标签/工作年限） | `career_direction` / `career_start_date` | `user_profile`                       | ⚠️ 部分       |

### 2.2 文章对象（掘金 `Article`）

| 掘金对象属性                       | 本项目字段                          | 状态         |
| ---------------------------- | ------------------------------ | ---------- |
| title（标题）                    | `title`                        | ✅          |
| brief\_content / summary（摘要） | `summary`                      | ✅          |
| cover\_image（封面）             | `cover_image`                  | ✅          |
| content（正文）                  | `ap_article_content.content`   | ✅          |
| tags（标签）                     | `tags`(JSON)                   | ✅          |
| view\_count（浏览）              | `views`                        | ✅          |
| collect\_count（收藏）           | `collection`                   | ✅          |
| digg\_count（点赞）              | `likes`                        | ✅          |
| comment\_count（评论）           | `comment`                      | ✅          |
| **share\_count（分享数）**        | —                              | ❌ 缺        |
| **hot\_index（热度排名）**         | `score`（权重分）                   | ⚠️ 可映射但无语义 |
| status（发布状态）                 | `status`                       | ✅          |
| author\_user\_info（作者冗余）     | `author_name` / `author_image` | ✅          |
| column 归属                    | `column_id`                    | ✅          |

### 2.3 沸点对象（掘金 `Pin`）

| 掘金对象属性          | 本项目字段                    | 状态 |
| --------------- | ------------------------ | -- |
| content         | `content`                | ✅  |
| images          | `image_urls`(JSON)       | ✅  |
| topic（话题/圈子）    | `topic_id` / `circle_id` | ✅  |
| digg\_count（点赞） | `likes`                  | ✅  |
| comment\_count  | `comment`                | ✅  |
| view\_count     | `views`                  | ✅  |
| share\_count    | `share_count`            | ✅  |
| status          | `status`                 | ✅  |

**沸点对象已基本完全对齐，无需改动。**

### 2.4 专栏对象（掘金 `Column` 合集）

| 掘金对象属性                        | 本项目字段                                    | 状态 |
| ----------------------------- | ---------------------------------------- | -- |
| author                        | `author_id / author_name / author_image` | ✅  |
| title                         | `title`                                  | ✅  |
| description                   | `description`                            | ✅  |
| cover                         | `cover_image`                            | ✅  |
| entries / article\_count（文章数） | `article_count`                          | ✅  |
| subscribe\_count（订阅数）         | `subscribe_count`                        | ✅  |
| created / updated             | `created_time` / `updated_time`          | ✅  |

**专栏保持免费，字段已对齐，不新增任何 pricing / settlement 字段。**

### 2.5 课程/小册对象（掘金 `小册`，即 `ap_course`）

已具备定价、原价、章节数、销量、销售额、分成、申报、出版时间等，**已对齐掘金小册，无需改动**。

***

## 三、对齐动作（字段级，按优先级）

> 原则：**存量表做增量补字段 + 增加聚合统计口径**，不改已上线业务；不改动既有字段语义。

### P0 —— 用户对象补齐「掘金式个人主页」属性

目标：用户/作者对象的统计口径与掘金一致，前端个人主页 / 作者卡片可直接渲染。

- `user_profile` 新增：

  - `region`（地区，掘金 `city`）

  - `education`（学历/学校，掘金 `education`）

  - `skills`（JSON，技术标签，掘金 `skills`）

  - `level`（等级快照，用于对外展示，可定时回填）

- 新增**聚合统计入用户对象**（对应掘金 `got_digg_count` / `got_follower_count` / `got_follow_count` / `got_article_count` / `got_pin_count` 等）：

  - 方案 A（推荐，轻量）：在**用户主页 / 作者卡片查询 VO** 中实时聚合（`COUNT`），不加冗余字段。

  - 方案 B（对齐掘金，较重）：在 `user_profile` 冗余 `got_digg_count`、`got_follower_count`、`got_follow_count`、`got_article_count`、`got_pin_count` 快照，互动事件异步更新。

> 建议首期采用 **方案 A（查询聚合）**，足够支撑个人主页；若后续要做首页排名/排行卡再上冗余快照。

### P1 —— 文章对象补齐互动统计

- `ap_article` 新增：

  - `share_count`（分享数，掘金 `share_count`）

  - `hot_index`（热度指数，由 `score`/阅读/互动综合，明确语义，可供排名与作者中心展示）

### P2 —— 命名/口径统一（可选，低风险）

- 统一「浏览量/点赞/收藏/评论」在文章与沸点上的命名习惯（`views/likes/collection/comment` 已基本一致），仅做口径校验，不强制改名。

***

## 四、明确「不做」清单

> 根据最新业务口径，以下内容**不纳入**：避免方向性返工。

- ❌ **专栏付费化**：`ap_column` 不做定价/版本/结算，维持免费文章合集。

- ❌ **会员订阅体系**：不新增 `member_plan` / `member_subscription`。

- ❌ **小册与课程重复建模**：`ap_course` 即小册，不另建「小册」概念表。

- ❌ **为对齐而新建大表**：除非 P0 统计确需（待决策），否则优先用查询聚合，不造冗余表。

***

## 五、落地节奏建议

1. 文档评审通过后，按 **P0（用户对象属性）→ P1（文章统计）→ P2（口径统一）** 顺序执行。
2. 每项走「新增 migrations SQL + 实体字段 + 查询 VO 组装」，完成即更新 `docs/CHANGELOG.md`。
3. P0 首期先做**查询聚合（方案 A）**，用户主页 / 作者卡片直接可用；快照方案留作后续按需优化。

***

## 六、待你决策的点

- **P0 用户统计**：首期用「查询聚合（方案A，轻）」还是「字段冗余快照（方案B，贴近掘金但需异步维护）」？

- **P0 范围**：本轮是否一并补齐 `region / education / skills / level` 等个人资料字段？

- **P1 文章热度**：`hot_index` 是加字段由任务/事件更新，还是由查询时按 `score`+互动动态计算即可？

