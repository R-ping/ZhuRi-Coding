# 小册（写小册）系统设计

- 日期：2026-08-17
- 状态：待评审
- 方案：方案 A（独立全屏三栏编辑器 + 账号白名单编辑入口 + 简化申报 + 编辑全权）

## 背景与目标

当前「写小册」入口（创作者中心下拉 [CreatorDropdown.vue](file:///e:/heima-leadnews-portal/heima-leadnews-app/src/components/layouts/CreatorDropdown.vue#L78-L85)）进入 CreatorLayout 内嵌的课程编辑页，与创作者侧边栏、课程信息栏挤在同一屏，体验差且不贴合掘金小册编辑器的形态。

本次目标是：

1. **独立全屏编辑器**：新建 `/booklet/edit` 全屏三栏编辑器（顶部工具栏 + 左侧小节目录 + 中间 Markdown 编辑 + 右侧实时预览），新窗口打开，不再嵌套在 CreatorLayout（含侧边栏）内；左侧小节目录可折叠隐藏。
2. **小册业务流程**：对齐掘金小册"申报 → 审核 → 写作 → 上架 → 发布小节"链路，按当前个人开发阶段做**简化申报**：作者填一张申报表单，编辑（账号白名单身份）审核通过后开通该小册写作权限。
3. **编辑全权**：作者只负责写作存稿与提交审核；小册上架、小节发布、下架全部由编辑（白名单账号）操作，作者侧不提供这些入口。
4. **复用课程数据链路**：小册就是课程的一种形态，复用 `ap_course` / `ap_course_chapter`，不新建表，复用已具备的付费/订单/折扣/结算能力。

## 设计原则

- **复用课程表**：整册 = `ap_course`，小节 = `ap_course_chapter`，不新增小册独立实体。
- **简化申报**：单个申报表单（选题 / 大纲 / 简介 / 样章），编辑审核一次，通过即开通写作权限，不做多轮（大纲→样章分阶段）审核。
- **编辑全权**：作者侧只有「存稿 / 提交审核 / 查看状态」；「通过申报 / 上架 / 发布小节 / 下架」均在编辑入口完成。
- **账号白名单**：用指定账号充当编辑身份，前端按 userId 白名单显隐审核菜单，后端接口同样校验白名单，不引入完整 RBAC。
- **新窗口打开**：与写文章一致（`handleNavigate(path, true)` → `window.open(path, '_blank')`），保证编辑器全屏、与侧边栏隔离。

## 页面与交互设计

### 写小册编辑器（全屏三栏，参考掘金参考图）

```
┌────────────────────────────────────────────────────────────────┐
│ 顶部工具栏：小册标题 | 自动保存状态 | [发布/更新] | 头像          │
├────────────┬──────────────────────────┬────────────────────────┤
│ 左：目录栏   │ 中：Markdown 编辑器       │ 右：实时预览            │
│ ─────────  │                          │                        │
│ ▸ 小册介绍  │  # 小节标题               │  (渲染后正文)           │
│ 1 什么是..  │  正文编辑区...            │  试读标记              │
│ 2 如何使用..│                          │                        │
│ 3 如何选题..│  ┌─────────┐ ┌─────────┐ │                        │
│ ...        │  │收起目录 │ │收起预览  │ │  ← 折叠按钮在底部工具栏  │
│ [+ 添加章节]│  └─────────┘ └─────────┘ │                        │
└────────────┴──────────────────────────┴────────────────────────┘
```

- **顶部工具栏**：左侧小册标题（可编辑，默认"未命名小册"）；中部「自动保存中 / 已保存」状态提示；右侧下拉「发布/更新」（作者：提交申报 / 提交上架审核；编辑：上架/下架）、用户头像。
- **左侧目录栏**：
  - 首项固定「小册介绍」（简介独立一节，Markdown 编写）。
  - 后续为小节列表：标题、试读标记（`is_free`）、拖拽排序、改名、删除。
  - 底部「+ 添加章节」按钮。
- **折叠交互**：折叠按钮放在**编辑器底部工具栏两端**（左端「收起目录」，右端「收起预览」，复刻参考图）。收起目录后左侧栏整体隐藏，编辑器与预览占满整行；再点「展开目录」恢复。
- **复用**：中间编辑器复用现有 [ByteMdEditor.vue](file:///e:/heima-leadnews-portal/heima-leadnews-app/src/pages/creator/components/editor/ByteMdEditor.vue)，预览复用其内置预览能力。
- **自动保存**：内容变更防抖后自动调用 `updateChapter`，状态显示"保存中/已保存/保存失败"。

### 路由

- 新增**独立顶层路由** `/booklet/edit`（不嵌套在 CreatorLayout 内，避免侧边栏），携带 `courseId` 查询参数定位小册：
  - `/booklet/edit`（无 courseId）→ 新建小册草稿后进入
  - `/booklet/edit?courseId=xxx` → 编辑已有小册
- 在 [creator.js](file:///e:/heima-leadnews-portal/heima-leadnews-app/src/routers/creator.js) 中作为独立 route 注册；同步扩展 [creatorGuard](file:///e:/heima-leadnews-portal/heima-leadnews-app/src/routers/creator.js#L7-L16) 的路径判断：`to.path.startsWith('/creator') || to.path.startsWith('/booklet')`（登录守卫由 [router.js](file:///e:/heima-leadnews-portal/heima-leadnews-app/src/router.js#L38) 全局 `beforeEach` 调用）。
- 写小册入口改造：[CreatorDropdown.vue](file:///e:/heima-leadnews-portal/heima-leadnews-app/src/components/layouts/CreatorDropdown.vue#L78-L85) 中 `handleCourseClick` 改为 `handleNavigate('/booklet/edit', true)`（新窗口），保持逐力值 Lv7 权限校验不变。

## 数据模型

### `ap_course` 状态枚举扩展

现有 [ApCourse.Status](file:///e:/heima-leadnews-portal/heima-leadnews-app/heima-leadnews/heima-leadnews-model/src/main/java/com/heima/model/course/pojos/ApCourse.java#L86-L101) 枚举与**现有已使用状态 3=已下架 冲突**，不能直接占用 3，新增值从 4 起：

| code | 枚举名 | 语义 | 谁可进入 | 谁可离开 |
|---|---|---|---|---|
| 0 | NORMAL | 草稿 / 申报未提交 | 作者创建 | 提交申报 |
| 1 | SUBMIT | 申报待审 | 作者提交申报 | 编辑通过 / 拒绝 |
| 2 | FAIL | 申报被拒 | 编辑拒绝 | 作者修改后重提 |
| 4 | WRITING | 申报通过、写作中（**新增**） | 编辑通过申报 | 作者提交上架审核 |
| 5 | REVIEW | 上架待审（**新增**） | 作者提交上架审核 | 编辑上架 / 驳回 |
| 9 | PUBLISHED | 已上架 | 编辑上架 | 编辑下架 |
| 3 | OFFLINE | 已下架（沿用现有值） | 编辑下架 | 编辑重新上架 |

> 说明：`NORMAL(0)` 语义从"草稿"扩展为"草稿 / 申报前"，`SUBMIT(1)` 语义从"审核中"细化为"申报待审"，新增 `WRITING(4)` / `REVIEW(5)` 区分"申报"与"上架"两次审核。

### `ap_course` 新增字段

迁移脚本：
- `migrations/alter_ap_course_add_booklet_fields.sql`：申报状态相关
- `migrations/alter_ap_course_add_apply_content.sql`：申报内容独立字段

```sql
-- 申报待审/被拒相关
ALTER TABLE ap_course
  ADD COLUMN apply_reason VARCHAR(500) DEFAULT '' COMMENT '申报审核拒绝原因',
  ADD COLUMN apply_time DATETIME DEFAULT NULL COMMENT '申报提交时间',
  ADD COLUMN review_time DATETIME DEFAULT NULL COMMENT '编辑审核时间';

-- 申报内容独立字段（避免与 description（小册介绍）互相覆盖）
ALTER TABLE ap_course
  ADD COLUMN apply_content TEXT DEFAULT NULL COMMENT '小册申报内容（JSON：选题/大纲/简介/样章）';
```

- 复用现有 `reason` 字段存上架审核驳回原因，`published_at` 存上架时间。
- 申报内容存入 `apply_content`（JSON 串），**不覆盖** `description`（小册介绍）。

### `ap_course_chapter`（小节）状态

现有 `status` 字段（Integer）扩展语义：

| code | 语义 |
|---|---|
| 0 | 草稿（作者写作中，读者不可见） |
| 1 | 已发布（编辑发布，读者可见） |

- 试读标记沿用现有 `is_free` 字段。
- 作者只能将小节保持在草稿态（0）；编辑在「发布小节」时置为 1。
- 上架审核时小节 `status` 不变（仍为草稿），编辑通过上架后才逐个/批量发布。

## 后端设计

### 权限

- **编辑白名单**：前后端同时维护一份 `EDITOR_USER_IDS` 常量集合（`[4]`，即 admin 账号 phone 13511223456），前端 userId 在集合中则渲染审核菜单，后端接口同样校验该集合。`leadnews_user` 库跨库改表成本高，不新增 `is_editor` 列，白名单通过配置类 + 前端常量硬编码管理，适合个人开发阶段。
- **作者申报入口**：复用 `checkAuthorPermission`（逐力值 Lv7）。

### 接口（content 服务，CourseController 扩展 + 新增审核接口）

作者侧：

| 接口 | 方法 | 说明 |
|---|---|---|
| `/manage/apply` | POST | 作者提交申报（绑定课程 + 申报内容），0→1，记 apply_time |
| `/manage/my-booklets` | GET | 我的小册列表（复用 manage/list 扩展返回审核状态/原因） |

编辑侧（新 `BookletReviewController`，`/api/v1/course/review`，全部校验编辑白名单）：

| 接口 | 方法 | 说明 |
|---|---|---|
| `/review/apply-list` | GET | 申报待审列表（status=1，分页/关键词） |
| `/review/apply-approve` | POST | 通过申报：1→4 |
| `/review/apply-reject` | POST | 拒绝申报：1→2，写 apply_reason |
| `/review/publish-list` | GET | 上架待审列表（status=5） |
| `/review/publish-approve` | POST | 上架：5→9，写 published_at；并可批量发布小节（0→1） |
| `/review/publish-reject` | POST | 驳回上架：5→4，写 reason |
| `/review/publish-section` | POST | 发布单个/批量小节：0→1 |
| `/review/unpublish` | POST | 下架：9→3 |

### 状态机约束

在 `ApCourseServiceImpl` 中集中做状态迁移校验（禁止非法跳转，如 0 直接→9），新增 `transitionTo(course, targetStatus, userId, isEditor)` 辅助方法统一处理。

## 前端设计

### 目录结构

```
src/pages/creator/booklet/
├── edit.vue               # 全屏三栏编辑器主页面
├── components/
│   ├── BookletTopBar.vue  # 顶部工具栏
│   ├── BookletToc.vue     # 左侧目录栏（可折叠）
│   └── (复用) ByteMdEditor.vue / 预览
└── review/
    ├── ApplyReview.vue    # 编辑：申报审核列表 + 通过/拒绝
    └── PublishReview.vue  # 编辑：上架审核列表 + 上架/驳回 + 发布小节
```

### 写小册编辑器 `edit.vue`

- 挂载时：无 courseId → 调 `createCourse({title:'未命名小册'})` 建草稿后跳转；有 courseId → 调 `manageDetail` 加载小册与全部小节。
- 三栏布局 + 折叠状态（`tocCollapsed` / `previewCollapsed`），折叠按钮在底部工具栏两端。
- 小节编辑：选中小节 → 编辑标题/正文/试读 → 防抖自动保存（`updateChapter`）。
- 作者操作：申报未提交（0）→「提交申报」弹窗填申报表单（选题/大纲/简介/样章说明）；写作中（4）→「提交上架审核」。申报被拒（2）/驳回（5→4）时顶部横幅展示 `apply_reason` / `reason`。
- 编辑身份下额外展示「上架 / 发布小节 / 下架」操作（按 userId 白名单显隐）。

### 编辑入口（账号白名单）

- [permission.js](file:///e:/heima-leadnews-portal/heima-leadnews-app/src/utils/permission.js) 新增 `isEditor()`：判断当前 userId 是否在编辑白名单。
- [menus.js](file:///e:/heima-leadnews-portal/heima-leadnews-app/src/pages/creator/constants/menus.js) 新增「小册审核」菜单（申报审核 + 上架审核两个子项），`v-if="isEditor"` 显隐。
- CreatorLayout 侧边栏 [Sidebar.vue](file:///e:/heima-leadnews-portal/heima-leadnews-app/src/pages/creator/layout/components/Sidebar.vue) 过滤时仅对编辑白名单渲染该菜单。

### API 封装

[course.js](file:///e:/heima-leadnews-portal/heima-leadnews-app/src/apis/course.js) 新增：`applyBooklet`、`getMyBooklets`、`getApplyReviewList`、`approveApply`、`rejectApply`、`getPublishReviewList`、`approvePublish`、`rejectPublish`、`publishSection`、`reviewUnpublish`。

## 业务流程（状态机全景）

```
作者:创建小册(0) → 提交申报(1) → [编辑:通过(4) | 拒绝(2)→作者改后重提]
写作中(4) → 提交上架审核(5) → [编辑:上架(9) | 驳回(4)→继续写作]
已上架(9) → [编辑:下架(3) → 可重新上架(9)]
小节: 作者草稿(0) → 编辑发布(1)
```

- 作者无"上架 / 发布小节 / 下架"入口。
- 编辑仅在白名单账号下可见审核入口与操作。

## 验收标准

1. 「写小册」新窗口打开全屏编辑器，无创作者侧边栏；左目录可折叠/展开，折叠后编辑区铺满。
2. 新建小册自动建草稿，标题/正文/试读可编辑并自动保存。
3. 作者提交申报后状态 0→1，作者侧显示"申报审核中"；编辑通过后 1→4，作者可继续写作。
4. 编辑拒绝申报后 1→2，作者侧展示原因并可修改重提。
5. 作者提交上架审核 4→5；编辑上架 5→9 且小节被发布；编辑驳回 5→4 并附原因。
6. 编辑入口仅白名单账号可见，非白名单账号前端无入口、后端接口返回 403。
7. 后端所有状态迁移有约束，非法跳转被拒绝。

## 迁移脚本

- `heima-leadnews-service/heima-leadnews-content/src/main/resources/db/migrations/alter_ap_course_add_booklet_fields.sql`：新增 `apply_reason` / `apply_time` / `review_time` 字段。
- `heima-leadnews-service/heima-leadnews-content/src/main/resources/db/migrations/alter_ap_course_add_apply_content.sql`：新增 `apply_content` 字段（申报内容独立存储）。
- 编辑白名单不落库，前后端常量维护（见「后端设计-权限」）。
- 执行后按需重新导出 `schema.sql`。
