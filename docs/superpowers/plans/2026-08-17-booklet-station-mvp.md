# 小册站 MVP 实现计划（创作者中心"课程管理 → 小册站"重构）

> 日期：2026-08-17 · 阶段：MVP 优先 · 面向：作者自主运营小册
> 前置结论见 `specs/2026-08-17-booklet-system-design.md`（第一代：全屏三栏编辑器 + 简化申报 + 编辑全权）。
> 本次为一期需求：《小册站》升级——作者从"只写作 + 编辑全权"演进为"可自主运营小册"，含申请成为作家闭环、侧边栏并入、母站、管理子页、写作页锁定。

**Goal:** 把创作者中心"课程管理/课程运营"合并升级为一级"小册站"，打通"首页成为作家 → 规则 → 申请（基础信息复用）→ 小册站（状态+管理维护）→ 管理子页 → 写作页锁定"的核心闭环。

**Architecture:** 前端 Vue2.7 + ElementUI + Vite；后端 SpringBoot(content 微服务域)，小册= `ap_course`，小节= `ap_course_chapter`，不建独立小册实体。新增 `ap_author_profile`（作者基础信息，供申请回填与展示）；申请详情扩展 `ap_course.apply_content`(JSON)。复用既有状态机 `transitionTo`、编辑白名单 `EditorConfig`、`BookletReviewController`/`CourseController`。

**Tech Stack:** Vue2.7 · ElementUI · axios · MyBatis-Plus · Spring Boot · MySQL · FreeMarker(详情页静态化)

---

## 一个词说明（术语约定）

| 你表述 | 系统概念 | 现有状态 → 展示文案 |
|---|---|---|
| 小册 | `ap_course`（一门课） | — |
| 小节 | `ap_course_chapter` | — |
| 申请中 | 申报待审 | `status=1` → **申请中** |
| 申请失败 | 申报被拒 | `status=2` → **申请失败** |
| 正常 | 申报通过、写作中 | `status=4` → **正常** |
| 预售 | 上架待审/预售 | `status=5` → **预售** |
| 在售 | 已上架 | `status=9` → **在售** |
| 维护中（类似下架） | 下架 | `status=3` → **维护中** |
| 逐力值 Lv7 | 系统等级体系 | 写小册权限阈值（现 `checkAuthorPermission` 用 Lv7） |
| 编辑白名单 | `EditorConfig.isEditor` | 编辑身份账号 |

> 状态展示**只需前端映射**，不新增枚举；`3` 语义沿用"下架/维护中"。

## 新增数据模型

### `ap_author_profile`（新表，作者基础信息，一作者一条）
```sql
-- 服务库: leadnews_article
CREATE TABLE IF NOT EXISTS `ap_author_profile` (
  `id`            BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`       INT    NOT NULL COMMENT '用户ID',
  `real_name`     VARCHAR(64)  DEFAULT '' COMMENT '姓名',
  `position`      VARCHAR(128) DEFAULT '' COMMENT '个人职位/职业',
  `resume`        TEXT         DEFAULT NULL COMMENT '个人履历/简介',
  `apply_reason`  VARCHAR(500) DEFAULT '' COMMENT '申请理由',
  `contact_wechat` VARCHAR(64) DEFAULT '' COMMENT '联系方式-微信',
  `contact_email` VARCHAR(128) DEFAULT '' COMMENT '联系方式-常用邮箱',
  `blogs`         VARCHAR(500) DEFAULT '' COMMENT '掘金账号及其他博客/技术媒体',
  `personal_intro` VARCHAR(500) DEFAULT '' COMMENT '个人自我介绍（用于小册作者页展示）',
  `created_time`  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time`  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作者基础信息（供小册申请回填与作者页展示）';
```

### `ap_course.apply_content` 扩展（申请单详情，JSON 持久化）
现有字段为 TEXT，存申请单完整 JSON，键：
`title(小册主题/20字内), intro(小册介绍 why/what/how), target(目标人群), outline(大纲-一级标题，或链接), progress(写作进度与更新频率), samples(样章-链接数组或说明), channel(申请渠道), contact(微信/邮箱), blogs`, 基础信息(姓名/职位/履历)单独落 `ap_author_profile`。

## 新增/调整接口（content 服务，`CourseController` + author 子域）

作者侧（均挂 `CourseController` 或 author 子域，前缀 `/content/api/v1/course`）：
| 接口 | 方法 | 说明 |
|---|---|---|
| `/author/profile` | GET | 读作者基础信息（`ap_author_profile`），无则返回空结构体（各字段 `""`/空） |
| `/author/profile` | POST/PUT | 保存作者基础信息（申请页必填项落库） |
| `/manage/apply` | POST | 提交申请：入库 author_profile + 扩展 `apply_content`(申请单 JSON)，0→1，记 `apply_time`（复用现 applyBooklet，入参扩展申请单） |
| `/manage/my-booklets` | GET | 我的小册列表：含 status 及展示所需字段（复用现有，补 apply_reason/reason/封面/读量） |

编辑侧无需新增（沿用 `BookletReviewController`）。

> 说明：列表/详情沿用现有 `/manage/list` `/manage/detail`；申请审理（通过/拒绝）由编辑（白名单）在「小册审核」完成，作者侧仅展示状态与原因。

## 前端页面与路由

### 路由（`src/routers/creator.js` + 独立顶层）
- 申请规则页：`/booklet/rules`（独立顶层，全屏，无侧边栏）
- 申请页：`/booklet/apply`（独立顶层）
- 小册站母站：`/creator/booklet`（CreatorLayout 一级栏）
- 小册管理子页：`/booklet/manage?courseId=xxx`（独立顶层，管理维护开新窗）
- 写作页：沿用 `/booklet/edit?courseId=xxx`（独立顶层；本次改造锁定交互）

延伸 `creatorGuard` 已覆盖 `/booklet` 前缀，无需改动。

### 侧边栏（`src/pages/creator/constants/menus.js` + `CreatorLayout`/`Sidebar.vue`）
- 删除「内容管理›课程管理」「课程运营(折扣码/收入结算)」两处；新增一级项 **「小册站」**置于最末，`icon` 与样式独立（高亮/徽标区分），路由 `/creator/booklet`。
- 折扣码/收入结算入口移入小册站总览（折叠面板/按钮）。
- 「小册审核」编辑白名单菜单保留不动。

### 首页课程分栏入口（应用首页）
- 在课程分栏页顶部新增「成为作家」banner/按钮：
  - 条件：未满足 Lv7 或 无 small book = 展示入口
  - 点击 → 跳 `/booklet/rules`（规则介绍）→ 「去申请」→ `/booklet/apply`
- 已满足（Lv7 或 已是通过作家/已有小册）→ 显示「进入小册站」，跳 `/creator/booklet`。

### 申请规则页 `/booklet/rules`
静态介绍页：门槛（逐力值 Lv7 / 或申请通过即开通）、审核周期（7~15 工作日）、荣誉/收益展示文案（如"已服务 × 位读者、累计 × 收益"占位）、「去申请」按钮。

### 申请页 `/booklet/apply`
- 挂载时拉 `author/profile`，若存在则回填基础信息（可修改覆盖）。
- 表单字段（按飞书表单）：
  1. 基础信息：姓名、个人职位/职业、个人履历/简介、联系方式(微信 + 常用邮箱)、掘金账号及其他博客和技术媒体
  2. 小册信息：小册主题(20字内)、小册介绍(Why/What/How)、目标人群、小册大纲(一级标题 15~40 节 或 文档链接)、写作进度与更新频率、样章试读(≥3篇/1500字 或 链接)
  3. 申请渠道：掘金小册微信公众号 /《如何写一本掘金小册》小册文章 / 小册姐微信 / 掘金社区LV7级及以上用户 / 经推荐人介绍和推荐 / 其他
- 提交 → 保存 profile + 提交 apply → 成功跳转小册站（状态=申请中）。

### 小册站母站 `/creator/booklet`
- 顶部：作者简介卡（读 author_profile）+ 「写作」入口（开新窗 `/booklet/edit`）+ 折扣码/收入结算入口。
- 数据占位卡：当日销量、总销量、小册流水、发起结算（占位，弹"该功能后续开放"）。
- 我的小册卡片列表（`my-booklets`）：封面/标题 + 状态 tag（申请中/申请失败/正常/预售/在售/维护中）+ 读量/订阅量 + 操作：
  - 「管理维护」→ 开新窗 `/booklet/manage?courseId=xxx`（不使用编辑/上下架/删除按钮）
  - 申请失败/被拒 → 显示原因 + 「重新申请」入口（回填复用）
- 空态：无权（非 Lv7 且非作家）→ 引导"成为作家"；有权无小册 → 引导写作。

### 小册管理子页 `/booklet/manage?courseId=xxx`
- 基础信息编辑：标题/副标题/封面/价格/原始价/简介/目录(is_free 试读标记)/作者画像，存 `updateCourse`。
- 小节列表：标题、字数、状态徽标（草稿/审核中/已发布）、试读、拖拽排序、编辑(开新窗写该小册)、删除。
  - MVP 内小节提交审核：作者对某小节点「提交审核」→ 弹输入框留言（如"重新定义了 xxx / 对内容重新排版"）→ 调章节状态流转（0→审核中）；编辑反馈展示（若后端已具备）。
  - MVP 数据面板/评论管理/流水/结算：占位或复用既有（折扣码/结算）。
- 顶部：「在小册站写作」→ 开新窗 `/booklet/edit?courseId=xxx`。

### 写作页锁定改造（`/booklet/edit`，复用 `BookletToc.vue`/`edit.vue`/`ByteMdEditor.vue`）
- 左侧目录每小节展示状态徽标（草稿/审核中/已发布）。
- 审核中的小节：点击打开但中间编辑器**锁定**（只读、`readonly`/阻断键入，展示系统锁定标识）。
- 已发布（编辑审核通过）的小节：编辑区默认**锁定**；用户可点「解锁」→ 弹框提示"对已审核通过小节编辑可能混淆原有内容，编辑后需重新提交编审，推荐在预览区浏览" → 确认后解锁可编辑；编辑后需重新走提交审核。
- 新增锁定事件与状态下发：全屏页 `edit.vue` 依据当前小节 `status` 控制 `ByteMdEditor` 只读。

> MVP 决策（用户已选）：**首页入口 → 规则 → 申请 → 小册站列表(状态+管理维护) → 管理维护开新窗 → 写作页锁定** 为核心闭环；**流水/结算/当日与总销量、编辑反馈深流程、小节评论管理** 列为后续（MVP 占位或复用既有）。

---

## 迁移脚本（db/migrations）
- `heima-leadnews-service/heima-leadnews-content/src/main/resources/db/migrations/202608-create_ap_author_profile.sql`：见上 `CREATE TABLE ap_author_profile`。
- `202608-alter_ap_course_apply_content.sql`：`ALTER TABLE ap_course MODIFY COLUMN apply_content TEXT COMMENT '小册申报内容（申请单JSON：主题/介绍/目标/大纲/进度/样章/渠道）';`（若已 TEXT 则跳过）。
- 执行后按需 `mysqldump --no-data` 重新导出 `schema.sql`。

## 验收标准（MVP）
1. 首页课程分栏出现「成为作家」；未满足 Lv7/非作家时点击可进规则页→申请页。
2. 申请页可回填上次基础信息（姓名/职位/履历/联系方式等）并允许修改覆盖。
3. 提交申请后状态=申请中；再次进入申请页基础信息仍在。
4. 侧边栏出现一级「小册站」（最末、样式区分），原「课程管理/课程运营」移除。
5. 小册站列出我的小册卡片，状态文案=申请中/申请失败/正常/预售/在售/维护中；卡片有「管理维护」并新窗打开管理子页；无编辑/上下架/删除按钮。
6. 管理子页可编辑基础信息、查看小节状态，写作入口开新窗三栏页。
7. 写作页左目录小节带状态徽标；审核中小节中间区锁定；已发布小节默认锁定、可解锁（弹框提示）。
8. `mvn compile` 与 `npm run build` 通过；`.gitignore` 不包含 `.env`。

## 任务拆解（按提交原子化，逐个可单独提交）
- Task 1 后端：`ap_author_profile` 表 + 迁移 + 实体 + Mapper + Service（getProfile/saveProfile）+ CourseController 两个接口。
- Task 2 后端：申请提交扩展（apply 同时写 author_profile + apply_content 申请单JSON）+ 校验渠道/主题40字等。
- Task 3 后端：`manage/my-booklets` 返回增加状态展示所需字段（apply_reason/reason/封面/读量/订阅量）与空结构兼容；若缺失「提交小节审核」接口则补齐最小版本（含留言）。
- Task 4 前端：路由（rules/apply/manage/booklet）+ menus 侧边栏并入改造。
- Task 5 前端：首页「成为作家」入口 + 规则页 + 申请页（profile 回填/提交）。
- Task 6 前端：小册站母站（状态卡 + 管理维护 + 写作入口 + 数据占位）。
- Task 7 前端：管理子页（基础信息编辑 + 小节状态 + 写入口）。
- Task 8 前端：写作页锁定改造（目录状态徽标 + 审核中锁定 + 已发布默认锁定可解锁弹框）。
- Task 9 收尾：schema.sql 重新导出、`docs/CHANGELOG.md`、全链路回归、`mvn compile` + `npm run build`。