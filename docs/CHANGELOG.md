# CHANGELOG

## 2026-08-17 — Code Review 安全加固（数据泄露修复/越权修复/幂等性/自动保存竞态）

### 变更

1. **🔴 公开课程列表数据泄露修复**（`ApCourseServiceImpl.findList`）：移除 `@RequestParam(required=false) Byte status` 参数，服务端强制过滤 `status=9（已上架）` 且 `is_deleted=0`，防止匿名用户遍历草稿/审核中/已下架课程。
2. **🔴 无鉴权状态变更漏洞修复**（`CourseController`）：
   - 移除 `PUT /api/v1/course/status`（无作者校验，任何人可改任意课程状态）。
   - 移除 `DELETE /api/v1/course/{id}`（硬删除，与软删除策略不一致，且无归属校验）。
   - `/manage/submit` 改为调用 `submitApply`（走状态机 `transitionTo`，含作者归属校验），不再绕过状态机。
   - `/manage/unpublish` 改为调用 `authorUnpublish`（走状态机 `transitionTo`，含作者归属校验）。
3. **公开课程详情数据泄露修复**（`getPublicDetail`）：非已上架课程返回 `404`；章节只返回 `status=1（已发布）` 的小节，未发布小节不外泄。
4. **状态机补充作者下架路径**：`transitionTo` 作者分支新增 `9→3（已上架→已下架）`，作者可下架自己的已上架课程。
5. **月度结算幂等性保护**（`SettlementServiceImpl.executeMonthlySettlement`）：执行前检查同一月份是否已有结算记录，有则跳过，防止重复触发产生重复结算。
6. **前端 course.js 重复常量清理**：删除 `COURSE_API_PREFIX`（与 `API_PREFIX` 值完全相同），全文件统一使用 `API_PREFIX`。
7. **小册编辑器自动保存竞态修复**（`edit.vue`）：
   - 拆分课程/章节为独立定时器（`_courseSaveTimer`/`_chapterSaveTimer`），避免互相覆盖。
   - 新增 `_dirty` 脏标记跟踪未保存内容，`beforeunload` 同时检查 `_dirty` 与 `saveStatus`。
   - 切换小节前先调用 `flushPendingChapterSave` 落盘当前小节，防止防抖窗口内切换导致内容丢失。
8. **调试文件清理**：删除 `diffstat.txt`。

### 验证

- 后端 `mvn compile` 通过；前端 `npm run build` 通过。
- 分支 `fix/code-review-security`，等待提交确认。

---

## 2026-08-17 — 修复结算作者ID与折扣码并发超卖

### 变更

1. **结算作者ID Bug 修复**（`SettlementServiceImpl`）：月度结算时按课程ID分组，从 `ap_course` 表查询真实作者ID，而非错误使用买家（order.userId）作为作者。
2. **折扣码并发超卖修复**：新增 `incrementUsedCountAtomic` 原子SQL更新（`UPDATE ... WHERE used_count < max_uses`），防止高并发下单多个请求同时扣减导致折扣码超卖。

---

## 2026-08-17 — 小册系统上线（独立全屏三栏编辑器 + 简化申报/编辑审核全流程 + 账号白名单编辑入口）

### 变更

1. **独立全屏三栏编辑器**（`src/pages/creator/booklet/edit.vue` + `BookletToc.vue` + `BookletTopBar.vue`）：
   - 独立顶层路由 `/booklet/edit`（不嵌套 CreatorLayout，避免侧边栏），新窗口打开（`CreatorDropdown.handleCourseClick` 改为 `handleNavigate('/booklet/edit', true)`）。
   - 布局：顶部工具栏（标题/自动保存状态/操作按钮）+ 左侧小节目录（可折叠隐藏，`tocCollapsed`）+ 中间 Markdown 编辑器（复用例 `ByteMdEditor`）+ 右侧实时预览（可折叠），折叠按钮在编辑器底部工具栏两端（复刻掘金小册参考图）。
   - 新建无 courseId 自动建草稿（`createCourse`），有 courseId 经 `manageDetail` 加载小册与全部小节；内容变更防抖自动保存。
2. **ApCourse.Status 状态机扩展**（`heima-leadnews-model/.../ApCourse.java`）：
   - 新增 `WRITING(4)`（申报通过、写作中）/ `REVIEW(5)`（上架待审）；现有 `NORMAL(0)` 语义扩展为「草稿/申报前」，`SUBMIT(1)` 细化为「申报待审」，复用 `OFFLINE(3)`/`PUBLISHED(9)`。
   - 状态机：作者 `0→1（提交申报）→4（编辑通过）/ 2（拒绝，改后重提）→5（提交上架审核）→9（编辑上架）/ 4（驳回）`；编辑 `1→4/2、5→9/4、9→3（下架）、3→9（重新上架）`。
   - `ApCourseServiceImpl.transitionTo` 集中校验状态迁移合法性，禁止非法跳转。
3. **编辑白名单**（前后端双常量，不落库）：
   - 后端 `heima-leadnews-content/.../config/EditorConfig.java`：`EDITOR_USER_IDS = [4]`（admin 账号）。
   - 前端 `src/utils/permission.js` 新增 `isEditor()`；`menus.js` 新增「小册审核」菜单（`isEditorOnly` 标记），`Sidebar.vue` 对非编辑过滤该菜单。
4. **后端接口**：
   - 作者侧（`CourseController` 扩展）：`POST /manage/apply`（提交申报 0→1）、`GET /manage/my-booklets`（我的小册列表）。
   - 编辑侧（新增 `BookletReviewController`，`/api/v1/course/review`，全部校验编辑白名单）：申报待审列表/通过/拒绝、上架待审列表/上架（含批量发布小节）/驳回、发布小节、下架。
   - 申报内容独立存储 `apply_content` 字段，**不覆盖** `description`（小册介绍），避免两处共用字段互相覆盖。
5. **数据库迁移**（`heima-leadnews-service/.../db/migrations/`）：
   - `alter_ap_course_add_booklet_fields.sql`：新增 `apply_reason` / `apply_time` / `review_time`。
   - `alter_ap_course_add_apply_content.sql`：新增 `apply_content`。
6. **前端 API 封装**（`src/apis/course.js`）：新增 `applyBooklet` / `getMyBooklets` / `getApplyReviewList` / `approveApply` / `rejectApply` / `getPublishReviewList` / `approvePublish` / `rejectPublish` / `publishSection` / `reviewUnpublish`。
7. **审核页面**（`src/pages/creator/booklet/review/`）：`ApplyReview.vue`（申报审核，通过/拒绝）、`PublishReview.vue`（上架审核，上架/驳回），编辑白名单可见。

### 验证

- 后端 `mvn compile`（离线模式）通过；前端 `npm run build` 通过（期间修复 `src/routers/creator.js` 中 `bookletRoutes` 重复声明导致的构建失败）。
- 已推送远端并创建 PR #37（`feat/booklet-system` → `master`），22 文件变更（+3933/-6）。
- 遗留：PR 待项目负责人合并；合并后需 `git fetch` 同步本地并清理分支。

## 2026-08-15 — 前端发布课程「稀土掘金社区产品功能深度剖析」（12篇精选文章 + 12章节）

### 变更

1. **前端操控发布课程**（账号 `11111111111`，作者 user_id=1700683778）：
   - 从参考资料精选 12 篇文档生成课程章节（`build_course_chapters.cjs`）：沸点功能深度剖析、沸点详情页（架构/交互）、沸点圈子（功能/分类）、话题功能前后端实现、文章列表与详情响应结构、推荐服务配额设计、掘友等级、掘力值体系、创作话题与创作活动。
   - 课程信息：标题「稀土掘金社区产品功能深度剖析」、副标题、摘要、封面（OSS material 图）、分类「开发工具」、免费（¥0）。
   - 通过浏览器完成：新建课程 → 填信息 → 创建 12 章节（含标题与内容）→ 提交审核（`POST /manage/submit` → 200）。
2. **数据库清理与模拟审批**：
   - `cleanup_duplicate_course_chapters.sql`：清理自动化重复创建的 60 条重复/空章节，保留正确 12 章并修正排序与 `chapter_count=12`。
   - `simulate_course_review_pass.sql`：模拟后台审批通过，`ap_course` 置为 `status=9`（已发布）、`published_at=NOW()`。
3. **雪花ID精度修复**（课程/章节 ID 为 19 位 Long，`parseInt` 丢精度导致查询不到数据）：
   - `src/pages/course/detail.vue`：移除所有 `parseInt(this.$route.params.id)`，保留字符串形式（loadCourseDetail / checkPurchaseStatus / createOrder / validateDiscount）。
   - `src/pages/course/read.vue`：`loadChapterDetail` 中章节 ID 不再 `parseInt`。
4. **GET 参数序列化修复**（`src/common/request.js`）：
   - `objToQueryString` 跳过 `undefined`/`null` 值，避免序列化为 `status=undefined&keyword=undefined` 导致后端 `Byte` 参数转换失败返回 500（课程管理列表页加载失败）。

### 验证

- 数据库：`ap_course` id=2088642484839063553 `status=9`、`chapter_count=12`、`published_at=2026-08-15 23:43:51`；`ap_course_chapter` 12 条（sort 1~12，内容完整）。
- 前端：
  - 课程管理列表（`/creator/course/list`）：显示「稀土掘金社区产品功能深度剖析｜免费｜12｜0｜已上架」，修复后正常加载（不再 500）。
  - 公开课程详情页（`/course/2088642484839063553`）：封面/标题/副标题/作者/12 小节/课程简介/目录完整渲染，「免费」「立即购买」。

## 2026-08-15 — 解锁写小册权限（逐力值 Lv.7）+ 课程创作入口修复

### 变更

1. **数据库解锁**（`leadnews_article.ap_user_level`）：
   - 账号 `11111111111`（用户422067，user_id=1700683778）`power_level` 置为 `7`、`power_value` 置为 `5000`（满足 `ap_level_config` 中逐力值 Lv.7 最低分 5000），解锁「创建小册」权限（`ap_level_privilege` 中 `create_course`）。
2. **前端「写小册」入口修复**（`src/components/layouts/CreatorDropdown.vue`）：
   - 修复首次挂载时 `refreshKey` 已递增导致 `watch` 不触发、`loadCoursePermission` 从不执行、权限恒为锁定的问题：新增 `mounted()` 主动加载一次课程创作权限。
   - 修复「写小册」跳转路径：原指向不存在的 `/course/publish`（被 `/course/:id` 路由吞掉渲染成课程详情空页），改为创作者中心课程编辑器 `/creator/course/edit`（无 courseId 即新建课程）。

### 验证

- 后端 `GET /content/api/v1/course/author/check-permission`（携带 accToken）返回 `{hasPermission: true, requiredLevel: 7, powerLevel: 7}`。
- 浏览器实测（账号 11111111111）：
  - 悬浮「创作者中心」下拉，「写小册」由锁定态（disabled + 锁图标）变为可用态。
  - 点击「写小册」跳转 `/creator/course/edit`，课程编辑器完整渲染（标题/副标题/摘要/封面/价格/分类/章节目录）。
  - 课程管理列表页正常（状态筛选/搜索/新建课程）；「新建课程」创建草稿成功落库（`ap_course` 新增 id=2088605792027414529，author_id=1700683778）。
  - 课程运营：折扣码管理、收入结算页面均正常访问。

## 2026-08-15 — 个人主页全分栏匿名浏览（动态/关注/收藏集/赞/课程公开接口）

### 变更

1. **动态时间线公开访问**（`UserDynamicController` + 网关）：
   - 网关白名单新增 `path.startsWith("/content/api/v1/user/dynamic")`；带 `userId` 时未登录也可读取他人动态（不带则取当前登录用户，未登录返回 401）。
   - 修复匿名访问动态分栏仍 444 的问题。
2. **新增个人主页公开只读接口**（`UserHomeController`，路径 `/api/v1/user/home/{userId}`）：
   - `GET /api/v1/user/home/{userId}/following`：该用户关注的用户列表（分页）。
   - `GET /api/v1/user/home/{userId}/followers`：该用户的关注者列表（分页）。
   - `GET /api/v1/user/home/{userId}/collections`：该用户收藏的文章列表（分页）。
   - `GET /api/v1/user/home/{userId}/likes?type=article|pins`：该用户点赞的文章/沸点列表（分页，可过滤类型）。
   - `GET /api/v1/user/home/{userId}/courses`：该用户创作的已发布课程列表（分页）。
   - 均以 `profileUserId` 查询，未登录可浏览；与个人中心私有 manage 接口区分，仅返回已发布内容。
3. **前端公开 API**（`src/apis/author.js`）：新增 `getUserHomeFollowing` / `getUserHomeFollowers` / `getUserHomeCollections` / `getUserHomeLikes` / `getUserHomeCourses`。
4. **个人主页分栏适配**（`src/pages/user/index.vue`）：
   - `fetchFollowData` 改用公开接口按 `profileUserId` 加载关注/关注者列表（原调用私有 `/api/v1/data/fans/list`，匿名 444）。
   - 新增 `fetchCollections` / `fetchCourses` / `fetchLikes`（文章 + 沸点子分栏），接入 `loadTabContent`，修复课程/收藏集/赞分栏不发请求的问题。
   - 课程分栏模板渲染 `coursesList`（课程卡片：封面/标题/副标题/章节/在学/价格）；赞-沸点子分栏渲染 `likedPinsList`。
   - 文章/沸点/点赞列表的 `id` 为雪花大数，json-bigint 解析为 BigNumber 对象，作为 Vue key 触发「非原始值 key」警告；在 `fetchArticles` / `fetchPins` / `fetchLikes` 中统一 `String(item.id)` 转字符串，消除控制台警告。

### 验证

- `mvn compile/package`（heima-leadnews-content + app-gateway 模块，-am，skipTests）通过（exit 0）。
- 网关 + 内容服务以新 jar 重启，全部公开接口匿名请求返回 HTTP 200 + code 200（原 444/未发请求）。
- 浏览器实测（未登录，`/user/1`）：
  - 动态 4 条、文章 2 篇、沸点 10 条、课程 1 门（卡片正常渲染）、关注者 1 人、赞-沸点 1 条均正常加载渲染；专栏/收藏集/赞-文章为空态（该用户暂无数据）。
  - 全部分栏无 444/401 报错、无登录弹框。

## 2026-08-15 — 未登录浏览他人主页分栏信息（公开个人主页接口）

### 变更

1. **新增个人主页公开只读接口**（`UserHomeController`，路径 `/api/v1/user/home/{userId}`）：
   - `GET /api/v1/user/home/{userId}`：主页头部聚合数据（基本信息：昵称/头像/简介/职位/公司 + 统计 + 等级）。
   - `GET /api/v1/user/home/{userId}/articles`：该用户已发布文章列表（分页）。
   - `GET /api/v1/user/home/{userId}/columns`：该用户已发布专栏列表（分页）。
   - `GET /api/v1/user/home/{userId}/pins`：该用户已发布沸点列表（分页）。
   - 与个人中心 manage 接口（需登录、含草稿/审核态）区分：仅返回已发布内容，供公开主页展示。
2. **网关白名单放行**：`AuthorizeFilter.isPublicPath` 新增 `path.startsWith("/content/api/v1/user/home/")`，未登录也可访问他人主页分栏数据。
3. **前端公开 API**（`src/apis/author.js`）：新增 `getUserHomeData` / `getUserHomeArticles` / `getUserHomeColumns` / `getUserHomePins`。
4. **个人主页适配匿名浏览**（`src/pages/user/index.vue`）：
   - 数据加载由登录态私有接口（`getUserStatistics`）改为公开接口（`getUserHomeData`），以 `profileUserId`（路由参数优先）加载头像/昵称/统计/等级。
   - `fetchArticles` / `fetchColumns` / `fetchPins` 改用 `profileUserId` + 公开接口。
   - 新增 `isOwnProfile` 计算属性：仅本人主页展示「设置」「新建专栏」等操作按钮。

### 验证

- `mvn install`（heima-leadnews-content + app-gateway 模块，-am，skipTests）通过（exit 0）。
- 网关 + 内容服务以新 jar 重启，匿名请求 `GET /content/api/v1/user/home/1` 返回 200（原 444）。
- 浏览器实测（未登录）：
  - 文章详情页作者信息区头像/昵称可见，昵称旁展示 `Lv.3`（逐力值等级）徽章，作者链接 `href=/user/1` 可跳转作者主页。
  - 进入 `/user/1` 个人主页：头部昵称/头像/等级徽章/统计正常加载。
  - 分栏切换：文章 tab 2 篇、沸点 tab 10 条、专栏 tab 空态（该用户暂无专栏）、关注/赞 tab 均正常渲染，无登录弹框、无 444/401 报错。

## 2026-08-15 — 文章详情页体验升级（作者信息区 / 登录弹框 / 右侧边栏）

### 变更

1. **作者信息区改为掘金风格水平布局 + 头像昵称可点击跳转作者主页**：
   - 顶部作者信息区：头像、昵称改为 `<a href="/user/{authorId}">`，点击直达作者主页；昵称旁新增「逐力值等级（创作等级）」徽章 `Lv.{powerLevel}`（title 展示等级名）。
   - 右侧边栏作者卡片：由「头像/昵称/职位/等级 上下排列」改为「头像 + 昵称+等级徽章 + 职位·公司」水平布局（掘金风格），头像昵称同样可点击跳转作者主页。
   - 后端 `ArticlePageController.fillAuthorExtras` 通过 `LevelService.getUserLevelInfo` 注入逐力值等级（`powerLevel`/`powerTitle`）、职位、公司、文章数与粉丝数。

2. **修复详情页登录弹框**：
   - 社交登录（微博 / GitHub / 微信）图标由 FontAwesome 字符改为内联 SVG（FTL 页面未加载 FontAwesome，原字符渲染为空导致「不完整、按钮位置不对、缺少图标」），颜色与主页登录弹框一致（微博红 / GitHub 黑 / 微信绿），悬停反色。

3. **右侧边栏目录固定高度 + 滚动条**：
   - `.toc-list` 固定最大高度（360px）+ `overflow-y: auto`，标题级数再多也在内部滚动，不再把下方「相关推荐 / 精选内容」挤出视口。
   - `.toc-sidebar` 改为 `position: sticky; top: 80px; max-height: calc(100vh - 100px)`，整栏超高时内部滚动。

4. **侧边栏随阅读滚动切换内容阶段**（`updateSidebarStage`）：
   - 阅读进度 p<0.3 → 只显示目录；0.3≤p<0.6 → 相关推荐；0.6≤p<0.85 → 精选内容；0.85≤p<1.0 → 目录+相关推荐（目录高亮定位到当前标题级数）；p≥1.0（读完）→ 相关推荐+精选内容（目录隐藏）。
   - 通过 `data-stage` 属性 + CSS 阶段选择器控制卡片显隐，切换带淡入动画。

5. **相关推荐策略（后端）**：
   - `ArticleDetailServiceImpl.getRelatedArticles`：优先取作者本人其他已发布文章（最多 3 篇），不足 3 篇时依次用**同频道文章**补齐、仍不足再**全局兜底**（排除已加入文章）补齐到 size（默认 5），保证侧边栏始终有足够内容。
   - 移除侧边栏「作者作品」卡片（由相关推荐承担该作者其他文章的曝光）。

### 验证

- `mvn compile`（heima-leadnews-content 模块，-am）通过（exit 0）。
- `node --check article-static.js` 语法通过。
- 浏览器实测通过：
  - 作者信息区水平布局（`flex-direction: row`），头像/昵称 `href=/user/1` 可点击跳转作者主页（实测点击后进入 `/user/1`）。
  - 逐力值等级徽章展示 `Lv.3 中级创作者`。
  - 登录弹框含微博 / GitHub / 微信 3 个社交登录按钮且均带内联 SVG 图标（22px）。
  - 目录 `.toc-list` 固定 `max-height: 360px; overflow-y: auto`，超高内部滚动。
  - 侧边栏阶段切换（滚动/派发 scroll 事件实测）：p<0.3 `toc` → 0.3-0.6 `related` → 0.6-0.85 `featured` → 0.85-1.0 `toc-related` → ≥1.0 `end`（related+featured）全部正确切换。
- 注：隐藏后台标签页时浏览器会抑制原生 scroll 事件（视口 0×0），此为浏览器限制，不影响线上正常滚动触发。

## 2026-08-15 — 作者悬浮卡片交互修复（几何悬浮区域，参考站内信）+ 头像昵称跳转个人主页

### 变更

1. **彻底修复作者信息悬浮卡片在鼠标移向卡片时消失**（文章列表/沸点/搜索结果页）：
   - 参考站内信悬浮框「触发区 ∪ 下拉面板位于同一容器，鼠标移动不触发中间隐藏」的思路，重写 `authorHoverCardMixin.js`：
     - 由「document mouseover + DOM 包含关系」改为「document mousemove + 几何判定」；
     - 悬浮区域 = 触发元素外扩区 ∪ 卡片本体外扩区 ∪ 二者之间的竖直桥接通道，只要指针坐标落在任一区域内卡片就保持显示，与鼠标下方是哪个 DOM 元素无关；
     - 鼠标真正离开悬浮区域后延迟 400ms 隐藏（只启动一次定时器，不因路过其他元素抖动重置）。
   - `AuthorHoverCard.vue` 保留 `mouseenter` / `mouseleave` → 派发 `card-enter` / `card-leave` 作为兜底。
   - 定位增强：卡片默认显示在触发元素下方 8px；下方空间不足（接近视口底部）时自动翻转到触发元素上方（箭头朝下），保证卡片始终可见、鼠标可达。
   - 修复后实测：悬浮头像/昵称 → 卡片出现；鼠标移向卡片并停留 → 卡片不消失，可点击「关注 / 私信」；鼠标移出区域 → 卡片延迟关闭。
2. **点击头像/昵称跳转目标用户个人主页**：
   - `article_0.vue` / `article_1.vue` / `article_3.vue`：头像新增悬浮展示卡片 + 点击事件；头像、昵称点击（`@click.stop` 阻断打开文章）派发 `author-click`。
   - `home/index.vue` 新增 `onAuthorClick` → `$router.push('/user/{authorId}')`。
   - `pins/index.vue`：头像、昵称点击新增 `goToUserPage` → 跳转 `/user/{userId}`，并先关闭悬浮卡片。
   - `search_result/index.vue`：同步补齐卡片保持逻辑（复用同一组件，避免同类问题）。

### 验证

- 浏览器实测（首页/沸点页）：悬浮触发 → 卡片出现 → 鼠标移至卡片保持显示 → 移出后关闭；底部触发自动翻转朝上。
- `npm run build` 构建通过（exit 0）。

## 2026-08-14 — 成就勋章系统联调验证 + 匿名 444 误登出修复

### 变更

1. **成就勋章系统联调验证通过**（后端四个服务已在 IDEA 重启）：
   - 登录 → 个人主页：等级徽章正常展示（「逐友等级 Lv.3 · 新星逐友」「逐力值等级 Lv.2 · 初级创作者」）
   - 勋章入口「2/11」计数正确，勋章墙弹窗完整展示 2 枚等级徽章 + 11 枚静态勋章，解锁状态与进度（12/50、12/100、1/100 等）实时计算准确
   - `GET /content/api/v1/user/{userId}/achievements` 登录态与匿名态均返回 200，网关公开路径放行生效
   - 匿名（无 token 无 cookie）可浏览个人主页及勋章墙，等级徽章由公开接口正常驱动
2. **修复匿名访问个人主页被误跳回首页**：`article_request.js` / `reward_request.js` 的 444 处理缺少 `_usedUserToken` 守卫，匿名请求（未登录无 token）命中网关 444 时误进 `refreshTokenAndRetry`，因无 refreshToken 触发 `sessionExpired` 整页跳回首页，导致匿名无法浏览个人主页。已为两处 444 分支补齐 `_usedUserToken` 守卫，匿名/游客请求静默 reject，与 `request.js` 语义对齐。

### 验证
- 登录态个人主页等级徽章 / 勋章入口 / 勋章墙弹窗全部正常
- 匿名态（清空 storage + cookie）访问 `/user/{userId}`：停留在个人主页不再跳回首页，勋章墙正常
- 匿名请求 `/content/api/v1/user/{userId}/achievements` 返回 200

## 2026-08-14 — 双 Token 机制语义修正（444 刷新 / 401 登出）

### 变更

1. **明确双 Token 语义，修正前端处理**：
   - **444** = access token 过期（1 小时）→ 携带 refresh token 请求 `/user/api/v1/token/refresh` 刷新双 token 并重放原请求（无感续期）。刷新成功后旧 refresh token 在服务端删除、生成新双 token（一次性），因此只要用户持续使用，refresh token 的 7 天有效期会一直滚动保持。
   - **401** = 最终认证失败的信号（如刷新失败说明 refresh token 也已过期）→ **不再刷新**，直接 `sessionExpired` 清除登录态并跳回首页（不弹登录框）。
   - 纠正上一版「401 也触发刷新」的错误实现：`request.js` / `article_request.js` / `reward_request.js` 的 401 分支恢复为 `sessionExpired`，仅 444 分支走刷新流程。
   - 匿名/游客请求返回 401 时静默 reject（`_usedUserToken` 守卫），不影响基础浏览。
2. **流程闭环验证**（后端）：网关对受保护接口 accToken 缺失/过期返回 444（`AuthorizeFilter.java`）；`TokenServiceImpl.refreshToken` 校验 refresh token（Redis 7 天）、删除旧值、返回新双 token；刷新失败返回 `TOKEN_INVALID`（体 code=50），前端 `tokenManager` 据此执行登出。

### 验证
- 修改涉及 `src/common/request.js`、`article_request.js`、`reward_request.js`
- 待外部浏览器验证：① acc token 过期（写入过期值）→ 请求返回 444 → 自动刷新并重放，页面数据正常、登录态保持；② 将 refresh token 也改为无效 → 刷新失败 → 跳回首页、token 清除、无登录框

## 2026-08-14 — Token 过期跳页优化 + 个人设置页完善

### 变更

1. **Token 过期不再弹登录框，改为跳回首页**：用户登录态失效时（401/444/刷新失败），不再弹出登录弹窗，改为清空登录态后 `window.location.href = '/'` 整页跳回首页。未登录状态下用户可正常基础浏览所有公开页面。修改涉及 4 个文件共 8 处触发点：
   - `store.js` 新增 `sessionExpired` action（`logout` + 跳首页），保留原 `logout` 供登录流程正常使用
   - `request.js`：401 分支、`__refreshAndRetry` 无 refreshToken 分支
   - `tokenManager.js`：`refresh()` 无 refreshToken 分支、`handleRefreshInvalid()` 分支
   - `article_request.js`：401 分支（增加 `_usedUserToken` 守卫）、`refreshTokenAndRetry` 无 refreshToken 分支
   - `reward_request.js`：401 分支（增加 `_usedUserToken` 守卫）、`refreshTokenAndRetry` 无 refreshToken 分支
   - **验证**：外部浏览器写入无效 token → 刷新设置页 → 自动跳回 `/home`，token 已清除，无登录框弹出，首页内容正常加载

2. **修复设置页区块切换失效（根因）**：Element UI 原先仅在 `CreatorLayout.vue` 局部注册，导致设置页 `el-dialog` / `el-upload` / `el-button` / `el-switch` 渲染为未知组件，Vue DOM patch 阶段报错、切换侧边栏区块不刷新。已将 Element UI 全局注册至 `src/entry.js`（`Vue.use(ElementUI)`），并移除 CreatorLayout 重复注册。验证：6 个区块切换全部正常，控制台无报错。
3. **补齐头像弹窗缺失方法**：`triggerAvatarUpload` 改为打开「更换头像」弹窗；新增 `handleAvatarChange`（本地预览）与 `uploadAvatar`（确认后上传）方法；删除冗余的原生 file input 上传路径，统一走弹窗流程。
4. **修复头像上传请求封装缺陷**：`src/common/request.js` 的 `__fetch` 原本固定 `Content-Type: application/json`，且 `post` 第三参数被拼入 query（`?headers=[object Object]`），导致 FormData 上传失败（500/444）。已增加 FormData 自动识别（移除手动 Content-Type，让浏览器自动设置 boundary），并修正 `apis/user.js` 的 `uploadAvatar` 调用。验证：上传 200，头像 URL 落库 OSS。
5. **新增「返回个人主页」入口**：设置页侧边栏顶部增加「返回个人主页」（对齐掘金），点击跳转 `/user/{userId}`。

### 验证
- Token 失效跳首页：写入无效 token → 刷新设置页 → 自动跳回 `/home`，token 清除，无登录框，首页正常浏览
- 外部浏览器（Chrome 插件）实测：6 个区块切换正常、头像弹窗预览+上传成功（OSS 落库）、返回个人主页跳转正常
- 对照掘金设置页 6 区块（个人资料/账号设置/通用设置/消息设置/屏蔽管理/标签管理），功能项与当前系统已对齐

## 2026-08-14 — 上线前修复（P0 六项全部完成）

### 修复内容
1. **P0.4 导航无效入口**：`layout_main.vue` 顶部「数据标注 / AI Coding / 更多」加 `handleUnreleasedNav` toast 兜底（「该功能即将上线，敬请期待」）
2. **P0.8 404 路由兜底**：新增 `src/pages/not_found/index.vue`（404 图标 + 回首页/返回按钮）；全局 catch-all 注册于 `routers/index.js`；修正 `creator.js` 顶层 `path:'*'` 误拦截（改为 `/creator` 子路由内兜底，未知路径不再进入创作者中心布局）
3. **P0.1 SEO meta**：`index.html` 补 description/keywords/theme-color/robots/canonical/og:*/twitter:*/apple-touch-icon，title 改为「逐日Coding - 开发者技术社区」，移除 bootcss 字体 CDN（`font.js` 本地打包 font-awesome ttf）
4. **P0.6 卡片信息密度**：`feedMixin.js` 透传 `likes`/`authorImage`；`article_0/1/3.vue` 增加点赞数 + 作者头像展示（摘要待后端补字段）
5. **P0.2 空状态与错误兜底**：`pins/index.vue` 增加 `pinsError` 状态（503 显示「加载失败，点击重试」而非「暂无内容」）；`course/index.vue` 增加 `loadError` 状态 + 重试按钮
6. **P0.5 监听清理复测**：抽查 17 处定时器/监听器均正常清理；`ByteMdEditor.vue` 补 MutationObserver disconnect

### 验证
- `npm run build` 通过（21s，2432 modules）
- Playwright 冒烟 5/5：点赞数显示、导航 toast、404 页、沸点错误态、课程错误态
- 沸点接口已恢复（10 条帖子）；课程接口仍 503，错误态正确展示

### 待办
- 课程接口 503 需后端排查（`/content/api/v1/course/list`）
- 文章卡片摘要需后端补 `description` 字段
- canonical/og:url 占位域名需替换为正式域名
- 清理 `dist_bak_20260814`（旧构建备份）

## 2026-08-14 — 上线审查报告更新（范围澄清）

### 变更
- **P0.3 作者昵称乱码 → 已解决**：项目负责人确认系历史入库数据所致，数据已修正，该项移出 P0 阻塞清单（仅保留前端「匿名用户」兜底建议）
- **确认本项目无移动端业务**：移动端相关项全部移出上线范围（P0.7 骨架屏/App 按钮、P1.9 iPad 适配、P2 4.2 改为编辑器体积优化、QA 5.4 移动断点）
- 调整后上线范围：P0×6、P1×9、P2×6，总工作量预估 3-5 个工作日
- 更新文件：`docs/pre_launch_audit_report.md`（含附录二：范围澄清）

## 2026-08-13 — 上线前全面审查（与稀土掘金对标）

### 审查产出
- 完整报告：`docs/pre_launch_audit_report.md`
- 页面截图：`docs/audit_shots/pc_home.png` / `mobile_home.png` / `pc_pins.png` / `pc_course.png`
- 截图脚本：`_tmp_audit_shots.py`（基于 Playwright + 系统 Edge）

### 阻塞上线项（P0，8 项）
1. `index.html` 缺少 description/keywords/og/twitter/theme-color/canonical/manifest
2. 首页/课程页可见空状态，疑似接口返回空（移动端整屏骨架）
3. 作者昵称显示乱码 `??422067`（DB 字符集或后端序列化问题）
4. 顶部导航「更多 / 数据标注 / AI Coding」点击无反应
5. 顶层定时器清理分支需复测，避免内存泄漏
6. 内容卡片信息密度低于掘金：缺摘要/点赞/作者头像
7. 移动端骨架屏长驻 + 「App内打开」占位按钮未发布即渲染
8. `vue-router` history 模式无 catch-all 404 兜底

### 体验优化项（P1，10 项）
- 空状态/错误文案统一
- 搜索入口缺热搜词
- 右侧栏内容单薄（仅签到+推荐话题）
- Tab 数据未预加载相邻 tab
- 阅读行为上报需复测
- 性能：图片懒加载/Gzip/ImageMin/element-ui 按需引入未做
- 控制台 Vue key 警告（`home/index.vue`）
- 可访问性：缺 ARIA/键盘焦点/alt
- iPad/平板断点粗糙
- 多环境/部署配置硬编码

### 运营增强项（P2，7 项）
- 空数据运营位/新人指南/版本日志
- bytemd 移动端按需加载
- 文章详情 SEO（预渲染/SSR）
- 埋点与监控（Sentry + Web Vitals）
- 暗色模式
- 文章目录/大纲
- 键盘快捷键

### QA 验收 Checklist
- 功能闭环：未登录浏览 + 登录互动 + 创作中心 + 课程
- 数据完整性：≥30 篇文章/20 沸点/5 课程/10 话题，UTF-8 正常，测试账号已清理
- 性能：LCP<2.5s, gzip<1.5MB, Lighthouse≥80, TTI<3s
- 兼容：Chrome/Edge/Safari/Firefox 最新两版 + iOS Safari 14+/Android Chrome 90+，分辨率 1024/1280/1440/1920 + 375/390/414/768
- 安全：OSS 签名不泄露、XSS 复测、CSRF token、验证码
- 运营：导航无效入口处理、App 入口可隐藏、footer 链接可达

### 结论
P0+P1 共 18 项，建议 5-7 个工作日内完成后再上线。

## 2026-08-13 — 支付成功联动等级经验与系统通知（经验值=实际支付金额）

### 功能变更
- **支付成功联动等级体系 + 站内信**：课程购买、文章打赏在收到支付宝异步回调确认支付成功后，自动给付款用户增加逐日等级经验，并发送「系统通知」类型站内信
- **经验值 = 实际支付金额**：打赏 2.5 元加 2.5 经验、购买课程实付 23.45 元加 23.45 经验（金额即经验值，支持小数），受全局每日 200 分上限控制
- 新增行为类型：`purchase_course`（购买课程）、`reward_article`（打赏文章）；等级服务新增 `recordPaymentAction(userId, actionType, amount, detail)` 按金额动态加分
- 逐日经验相关字段由 `int` 升级为 `decimal(10,2)`：`ap_user_action_log.score_change`、`ap_user_level.daily_score`、`ap_user_level.daily_score_today`（实体 `ApUserActionLog.scoreChange`、`ApUserLevel.dailyScore/dailyScoreToday` 同步改为 `BigDecimal`）
- 新增 `PaymentRewardService` 聚合联动逻辑：调用等级服务 `recordPaymentAction` 加经验、调用站内信服务 `sendActivityNotification` 发通知；任一联动失败仅记录日志，不影响支付主流程
- 站内信内容包含订单信息、实付金额、获得的经验值及跳转链接（课程详情/文章详情），前端「系统通知」tab 直接展示

### 变更文件
- 新增：`content/.../service/payment/PaymentRewardService.java`、`impl/PaymentRewardServiceImpl.java`
- 新增：`content/.../resources/db/migrations/alter_score_decimal.sql`（字段升级 decimal）
- 修改：`content/.../constants/LevelScoreConstants.java`（新增支付行为类型）
- 修改：`content/.../service/level/LevelService.java`、`impl/LevelActionService.java`、`impl/LevelQueryService.java`、`impl/LevelPrivilegeService.java`（金额加分与 BigDecimal 适配）
- 修改：`model/.../level/pojos/ApUserLevel.java`、`model/.../user/pojos/ApUserActionLog.java`（字段类型改 BigDecimal）
- 修改：`content/.../service/order/impl/OrderServiceImpl.java`（课程支付成功联动）
- 修改：`content/.../service/tip/impl/TipServiceImpl.java`（打赏支付成功联动，补 import）
- 修改：`heima-leadnews-basic/heima-file-starter/pom.xml`（库模块跳过 spring-boot repackage，修复全量构建）
- 修改：`content/.../resources/db/schema.sql`（同步 decimal 字段定义）

## 2026-08-13 — 课程订单详情页 + 支付回跳修复 + 列表新开标签

### 功能变更
- **新增课程订单详情页**：课程详情页「立即购买」创建订单后跳转到订单详情页（`/course/order/:orderNo`），展示课程信息、订单信息、金额明细（课程价/折扣/实付）与订单状态
- **立即支付入口**：订单页点击「立即支付」新开标签页跳转支付宝收银台（`/content/api/v1/course/pay/page`），并每 3s 轮询订单状态，支付成功后展示成功横幅并支持「继续阅读」
- **修复支付回跳地址**：课程支付成功后的回跳地址不再使用后端网关 `return-url`，改为使用前端对外地址拼装 `{web-base-url}/course/{courseId}` 回跳到前端课程详情页
- **课程列表新开标签**：课程列表点击课程卡片改为新开标签页打开课程详情页（`window.open`）
- 后端新增 `alipay.web-base-url` 配置（`ALIPAY_WEB_BASE_URL`），默认 `http://localhost:9901`，生产指向前端内网穿透映射地址

### 变更文件
- 新增：`src/pages/course/order.vue`（课程订单详情页）
- 修改：`src/routers/home.js`（新增 `/course/order/:orderNo` 路由）
- 修改：`src/pages/course/index.vue`（列表进入详情新开标签页）
- 修改：`src/pages/course/detail.vue`（创建订单后跳转订单页，移除原直接支付/轮询逻辑）
- 修改：`content/.../controller/v1/pay/PayController.java`（回跳地址指向前端课程详情页）
- 修改：`content/.../resources/application.yml`、`.env`（新增 `web-base-url`/`ALIPAY_WEB_BASE_URL` 配置）

## 2026-08-13 — 文章打赏（赞赏）功能

### 功能变更
- **文章阅读页赞赏卡片**：在文章正文末尾、专栏区域之前新增「赞赏」卡片，展示已获赞赏次数与总金额，以及公开感谢名单（打赏人昵称/头像/留言/金额）
- **打赏弹窗**：支持固定档位（1/5/10/50 元）+ 自定义金额（1~10000 元）+ 留言（选填，≤200 字），复用支付宝沙箱支付流程
- **打赏闭环**：创建订单 → 生成支付页 → 模拟支付回调 → 订单标记已支付并写入打赏流水 → 更新文章打赏汇总（`tip_count`/`tip_amount`）
- **打赏金额入平台账户**供作者结算；创作中心「收入结算」页新增「文章打赏收入」汇总卡片
- 后端新增 `GET /api/v1/tip/summary`、`GET /api/v1/tip/list`（公开只读，网关白名单放行，利于 SEO）；`POST /api/v1/tip/create`、`GET /api/v1/tip/my-revenue`（需登录）
- `AlipayService` 增加自定义通知地址/回跳地址的重载，支持打赏独立支付页与回调

### 数据库
- 新增表 `ap_article_tip_order`（打赏订单）、`ap_article_tip_record`（打赏流水/感谢名单）
- `ap_article` 新增字段 `tip_count`、`tip_amount`
- 变更脚本：`content/src/main/resources/db/migrations/create_ap_article_tip_tables.sql`，并同步更新 `schema.sql`

### 变更文件
- 新增：`heima-leadnews-model/.../article/pojos/ApArticleTipOrder.java`、`ApArticleTipRecord.java`
- 新增：`content/.../mapper/tip/ApArticleTipOrderMapper.java`、`ApArticleTipRecordMapper.java`
- 新增：`content/.../service/tip/TipService.java`、`impl/TipServiceImpl.java`
- 新增：`content/.../controller/v1/tip/TipController.java`
- 修改：`content/.../service/pay/AlipayService.java`、`impl/AlipayServiceImpl.java`
- 修改：`content/.../templates/article.ftl`（赞赏卡片 + 弹窗 + 样式）、`content/.../static/article-static.js`（打赏逻辑）
- 修改：`gateway/.../filter/AuthorizeFilter.java`（打赏公开只读路径白名单）
- 修改：`src/apis/course.js`、`src/pages/creator/course/settlement.vue`（打赏收益展示）

## 2026-08-13 — 个人主页动态分栏 + 话题详情浏览/参与数聚合

### 功能变更
- **个人主页动态分栏**：个人主页「动态」分栏不再为空，按时间线降序展示用户动作记录（点赞文章/沸点、关注用户、发布文章/沸点）
- 新增后端聚合接口 `GET /api/v1/user/dynamic`，从 `user_behavior_record` 查询有效行为记录，批量关联 `ap_article`/`ap_pins`/用户信息，组装目标标题、封面、跳转地址、浏览量与时间
- 前端动态分栏按动作分类（点赞/关注/发布）展示图标、行为描述、目标内容与时间，支持跳转文章/沸点/用户主页

### 话题详情页
- 浏览数改为话题关联内容浏览量总和（沸点 `view_count` + 文章 `views`），参与数 = 沸点数 + 文章数（统一用「参与」表示帖子/帖子文章数量）
- 前端话题详情移除「帖子」计数项，仅保留「阅读」「参与」

### 变更文件
- 新增：`heima-leadnews-model/.../user/vo/UserDynamicVO.java`
- 新增：`heima-leadnews-service/heima-leadnews-content/.../controller/v1/user/UserDynamicController.java`
- 修改：`src/apis/author.js`
- 修改：`src/pages/user/index.vue`

## 2026-08-13 — 优化：文章 AI 审核由 4 次调用合并为 1 次综合审核

### 变更
- 文章审核流程原先依次调用违规检测、标题相关性、内容质量、技术相关性共 **4 次** AI 调用，每次都重复传输完整标题与内容，token 消耗大
- 现合并为 **1 次** 综合审核调用（`BailianAiService.comprehensiveAudit`），一次返回违规、标题相关性、内容质量、技术相关性 4 项结果，仅传输一次标题与内容，token 消耗降至约 1/4
- 提示词精简：将原 4 段提示词合并精简为 1 段综合提示词（`COMPREHENSIVE_AUDIT_PROMPT`），去除大量冗余评分细则描述
- `AIViolationProcessor` 改为只调用一次综合审核；违规仍作为硬性门槛，违规即终止审核流程；其余结果写入 `aiAnalysisResult` 供后续 `PowerBonusProcessor` 等使用（`qualityScore`/`success` 等 key 保持兼容）
- 持久化统一为 `saveComprehensiveAudit`（先删旧记录再插入完整审核数据）
- 保留通用 `checkViolation(Long, title, content)` 方法，供沸点/评论等其他审核流程使用，不受影响

### 变更文件
- 修改：`heima-leadnews-service/heima-leadnews-content/.../article/BailianAiService.java`
- 修改：`heima-leadnews-service/heima-leadnews-content/.../article/impl/BailianAiServiceImpl.java`
- 修改：`heima-leadnews-service/heima-leadnews-content/.../article/processor/AIViolationProcessor.java`

## 2026-08-13 — 修复：OSS 封面/图片 URL 过期签名参数导致图片无法加载

### 问题
- 后端返回的图片 URL 携带 OSS 签名参数（`?Expires=...&OSSAccessKeyId=...&Signature=...`），签名过期（`AccessDenied: Request has expired`）导致文章列表封面图等无法显示

### 修复
- 桶内 `avatar/*` 与 `material/*` 已配置为公共读（`oss:GetObject`），签名参数不再需要。新增 `src/common/ossUrl.js` 工具，递归清洗响应数据中所有 `aliyuncs.com` URL 上的签名查询参数，仅保留可公开访问的 URL
- `src/common/request.js`：在 `__fetch` 响应处理中调用 `normalizeResponseData`（含纯字符串响应，如文章内容中的内嵌图片 URL），全局生效，无需改动各接口/页面

### 变更文件
- 新增：`src/common/ossUrl.js`
- 修改：`src/common/request.js`

## 2026-08-13 — 私信功能：作者卡片「私信」跳转站内信私信分栏并打开聊天区

### 功能变更
- **作者卡片「私信」跳转**：沸点页、文章列表页（`home/index.vue`）、搜索结果页的作者信息悬浮卡片点击「私信」，跳转到站内信页私信分栏，自动选中该用户并打开聊天区
- **聊天区默认提示**：新会话默认展示「由于对方并未关注你，在收到对方回复之前，你最多只能发送1条文字消息」（后端 `can_send_unlimited` 控制），并在对方未关注时限制最多发送1条文字消息；再次发送被拦截并提示
- **发送限制规则（不对称）**：a 私信 b 时，若 **b（接收方）关注了 a（发送方）** 则 a 无限发送（与 a 是否关注 b 无关）；若 b 未关注 a，则 a 在收到回复前最多发送 1 条，再次发送被拦截；b 回复 a 后（`is_active`）a 无限发送。由 Feign `IFollowClient.isFollowing(receiver, sender)` 判定，关注服务降级时按普通 1 条限制处理
- **会话列表补全**：私信会话列表/新开会话返回对方昵称与头像（此前仅显示「用户+ID」、头像为空）

### 后端变更（notification 服务）
- `ImService` / `ImServiceImpl`：新增 `getOrCreateSession(userId, peerId)`，对指定用户获取（不存在则创建）会话，返回昵称/头像/最后消息/未读/激活状态；`listSessions` 补充对方昵称与头像（通过 Feign `IUserClient.getPublicInfo` 解析）
- `ImController`：新增 `GET /api/v1/im/session?peer_id=xxx`

### 前端变更
- `src/common/conf.js`：新增 `im_session` 接口地址
- `src/components/search/AuthorHoverCard.vue`：`message` 事件携带 `{userId, name, avatar}`
- `src/pages/notification/index.vue`：新增 `openPeerConversation`，支持按 `peer_id` 打开/新建会话并选中聊天区；挂载与路由 watcher 处理跳转；会话列表使用后端返回的昵称/头像
- `src/pages/pins/index.vue`、`src/pages/home/index.vue`、`src/pages/search_result/index.vue`：`onAuthorMessage` 由占位提示改为跳转站内信私信分栏

### 变更文件
- 后端：`ImService.java`、`ImServiceImpl.java`、`ImController.java`、`ImStateMachine.java`、`IFollowClient.java`、`IFollowClientFallback.java`、`FollowController.java`
- 前端：`conf.js`、`AuthorHoverCard.vue`、`notification/index.vue`、`pins/index.vue`、`home/index.vue`、`search_result/index.vue`

## 2026-08-13 — 作者信息悬浮卡片：文章页 / 沸点页昵称头像悬浮展示

### 功能变更
- **作者信息悬浮卡片**：在文章列表页与沸点页，鼠标悬浮在作品项的作者昵称／头像上，弹出作者信息卡片，包含：头像、昵称、职位（无职位时显示「暂无简介」）、逐日等级、逐力值等级、关注数、粉丝数，以及「关注」「私信」按钮
- **覆盖范围**：沸点列表页（头像 + 昵称）、文章列表页三种卡片样式（`article_0/1/3` 的作者昵称），经 `home/index.vue` 统一挂载悬浮卡片；文章详情页右侧边栏已有作者信息区，不重复处理

### 后端新增
- **user 服务**：`UserFeignController` 新增 `GET /api/v1/user/feign/public-info`，返回昵称/头像/职位/公司/简介（Feign 接口）
- **feign-api**：`IUserClient` 新增 `getPublicInfo` 方法及降级实现
- **content 服务**：新增 `AuthorInfoController` 聚合接口 `GET /api/v1/author/info`，一次返回作者基本信息、职位、逐日等级、逐力值等级、关注数、粉丝数、是否已关注

### 前端变更
- `src/apis/author.js`：新增 `getAuthorInfo` API 封装
- `src/components/search/AuthorHoverCard.vue`：重构，支持按 `userId` 自动拉取聚合数据，新增职位、双等级、关注粉丝数渲染与「关注/私信」按钮、空职位「暂无简介」兜底
- 沸点页 `src/pages/pins/index.vue`：头像 + 昵称接入悬浮卡片，实现关注/私信交互
- 文章列表 `src/components/cells/article_0.vue`、`article_1.vue`、`article_3.vue`：作者昵称触发 `author-hover` / `author-leave` 事件
- 首页 `src/pages/home/index.vue`：统一挂载 `AuthorHoverCard`，处理位置计算、关注与私信交互

### 变更文件
- 后端：`UserFeignController.java`、`IUserClient.java`、`IUserClientFallback.java`、`AuthorInfoController.java`（新增）
- 前端：`src/apis/author.js`（新增）、`src/components/search/AuthorHoverCard.vue`、`src/pages/pins/index.vue`、`src/components/cells/article_0.vue`、`article_1.vue`、`article_3.vue`、`src/pages/home/index.vue`

## 2026-08-12 — 登录态修复：刷新失败不再强制登出

### Bug 修复
- **频繁掉登录**：`src/common/tokenManager.js` 刷新逻辑重构。此前网络超时/服务 5xx/瞬时异常都会触发 `logout`，导致 access_token 每次过期刷新时若服务重启即被踢下线。现调整为：
  - 仅当服务器**明确返回 token 无效**（如 refresh_token 已失效）时才登出并弹窗；
  - 网络异常/超时/5xx 做**有限重试（2 次，间隔 800ms）**，仍失败仅驳回当前请求、**保留登录态**，由下次请求再触发刷新。

### 变更文件
- 前端：`src/common/tokenManager.js`

## 2026-08-12 — 沸点列表增强：圈子显示、定时刷新、话题/圈子跳转

### 功能变更
- **圈子名称显示**：`PinsVO` 新增 `circleId` / `circleName` / `topicId` 字段；`PinsQueryService.convertToVO` 通过 `ap_circle` 批量查询圈子名称（避免 N+1），前端列表与详情页可正常展示所选圈子
- **15s 定时轮询**：新增前端定时刷新（15s），对「最新」分栏静默拉取第一页并将新沸点插入顶部、按 id 去重，不打断滚动加载；发布成功（异步审核）后亦触发一次轮询，审核通过的沸点会自动出现，他人沸点帖无需手动刷新
- **话题 / 圈子点击跳转**：列表与详情页的圈子标签、话题标签均可点击，分别跳转 `/pins/circle/:id` 与 `/pin/topic/:id` 详情页

### Bug 修复
- 沸点列表 `pins-circle` 标签由 `v-if="pins.circleName"` 改为 `v-if="pins.circleId"`，避免仅凭名称判断导致跳转链接缺失

### 变更文件
- 后端 model：`PinsVO.java`
- 后端 content：`PinsQueryService.java`
- 前端：`src/pages/pins/index.vue`、`src/pages/pins/detail.vue`

## 2026-08-12 — 沸点发布图片上传报错修复

### Bug 修复
- **沸点发布附带图片报错**：前端发布沸点时 `imageUrls` 以数组（`List<String>`）提交，但 `PinsPublishDTO` 中该字段为 `String`，导致 Jackson 反序列化失败（`HttpMessageNotReadableException: Cannot deserialize value of type java.lang.String from Array value`）。修复：将 `PinsPublishDTO.imageUrls` 改为 `List<String>`，发布服务入库时以逗号拼接（与 `topicTags` 一致），数据库仍存逗号分隔字符串，查询/审核拆分逻辑不变

### 变更文件
- 修改：`PinsPublishDTO.java`（`imageUrls` `String` → `List<String>`）
- 修改：`PinsPublishService.java`（`getApPins` 中 `String.join(",", dto.getImageUrls())` 入库）

## 2026-08-12 — 评论站内信 / 文章评论 / 圈子弹窗 / 站内信计数 / 文章列表 / 网关编码

### 功能变更
- **评论站内信对齐**：`NotificationProcessor` 生成的评论通知 content 与前端对接，字段统一为 `trigger_user{name,avatar}`、`action_type`、`message`（评论摘要）、`target_title`、`target_type`、`target_id`、`comment_id`；`ApCommentServiceImpl` 文章评论/回复三处补齐触发 `COMMENT_ARTICLE` 行为事件（含自评论过滤），沸点评论触发 `COMMENT_PIN` 正常，目标用户均能收到「评论」站内信
- **文章评论增强**（`article-static.js` / `article.ftl`）：二级回复入口、评论可发图片/表情（复用 OSS `post_signature` 直传）、字数限制 1000、时间分钟级显示
- **站内信按类型清除计数**：新增 `POST /api/v1/notifications/mark-type-read?type=...`，将指定类型未读置为已读并从 Redis 总未读计数扣除；前端进入某类型通知页时调用，不影响其他类型计数
- **圈子弹窗修复**：左侧分类去重（不再重复出现「推荐圈子」）；选中圈子高亮 + 勾选标识；「不选择圈子」清除已选并关闭弹窗（父组件同步清空）
- **首页文章列表**：推荐分栏不显示时间、最新分栏显示分钟级时间；卡片右侧补封面区、展示阅读计数与发布关联标签
- **网关请求头中文编码**：网关写 nickName 时 `URLEncoder.encode`，各服务拦截器（content/user/notification/search）读取时 `URLDecoder.decode`，解决中文昵称乱码

### 变更文件
- 后端 content：`NotificationProcessor.java`、`ApCommentServiceImpl.java`（文章评论行为触发）
- 后端 notification：`NotificationController.java`、`NotificationServiceImpl.java`、`NotificationService.java`、`NotificationMapper.java`、`NotificationMapper.xml`（按类型清除计数）
- 网关/拦截器：`AuthorizeFilter.java`（编码）、content/user/notification/search 四服务 `*TokenInterceptor.java`（解码）
- 前端：`src/pages/pins/detail.vue`、`src/pages/creator/pins/components/PinsCircleSelector.vue`、`src/pages/creator/pins/index.vue`、`src/pages/notification/index.vue`、`src/components/layouts/layout_main.vue`、`src/components/bars/home_bar.vue`、`src/common/conf.js`、`src/pages/home/index.vue`、`src/pages/home/mixins/feedMixin.js`、`src/components/cells/article_0/1/3.vue`

## 2026-08-12 — 沸点评论功能增强（二级回复、图片/表情、1000字、分钟级时间）

### 功能变更
- **二级评论继续回复**：每条二级回复项新增「回复」按钮，点击进入回复该二级回复状态；提交时 `parentId` 指向其所属顶级评论，并携带 `replyToUserId`/`replyToUserName` 用于「回复 @xxx」样式展示；评论框顶部显示回复目标提示栏，可取消
- **评论发图片/表情**：评论输入框新增表情选择面板与图片上传按钮，图片复用阿里云 OSS 直传方案（`oss_upload.js` 的 `uploadFile`），图片 URL 以 `imageUrls` 列表随评论提交，评论与回复内容均可展示图片
- **字数限制 1000 字**：前端评论框 `maxlength=1000` 并实时字数统计（超限变红提醒），后端 `createComment` 校验内容长度 ≤1000（超限返回参数错误）
- **分钟级时间**：新增分钟级 `formatTime`（1分钟内「刚刚」，向下取整到 59 分钟为「X分钟前」，满 1 小时「X小时前」，满 1 天「X天前」，满 1 月「X个月前」），评论列表与二级回复项统一使用
- **后端模型**：`ap_pins_comment` 表新增 `image_urls`、`reply_to_user_id`、`reply_to_user_name` 三列，关联实体/ DTO / VO 同步扩展

### 变更文件
- 修改：`src/pages/pins/detail.vue`（二级回复、图片/表情上传、1000字、分钟级时间）
- 修改：`PinsInteractionService.java`（createComment 校验 1000 字、图片/回复字段写入）
- 修改：`PinsQueryService.java`（convertCommentToVO 输出 imageUrls / replyTo 字段）
- 修改：`ApPinsComment.java` / `PinsCommentDTO.java` / `PinsCommentVO.java`（新增字段）
- 新增：`docs/alter_ap_pins_comment_add_image_reply.sql`（表结构变更脚本）

## 2026-08-12 — 沸点详情页：发布浏览、评论交互与推荐侧边栏

### 功能变更
- **沸点详情页（新增 `detail.vue`）**：进入 `/pins/detail/:id`，左侧展示沸点原文卡片（作者头像/昵称/发布时间/内容/图片/链接/标签/操作栏），右侧展示「推荐沸点」侧边栏
- **评论区**：支持「最新 | 热门」分栏切换（默认最新）；评论输入框悬浮背景加深、点击展开多行输入区与「发送」按钮、点击页外自动折叠；支持评论点赞与二级回复展示、加载更多
- **推荐沸点侧边栏**：从沸点页「热门」分栏取前 3 条（排除当前沸点），鼠标悬浮约 300ms 后弹出完整内容悬浮框（头像/昵称/完整内容/图片/点赞评论数），移开即关闭，点击可跳转对应详情
- **沸点列表页时间跳转**：沸点卡片上的「时间」文字悬浮高亮（变蓝加下划线），点击跳转到对应沸点详情页
- **后端接口**：新增 `GET /api/v1/pins/{pinsId}` 沸点详情接口；评论列表接口 `GET /api/v1/pins/comment/list` 支持 `sort=latest|hot` 排序分页
- **网关放行**：将沸点详情 `/pins/{pinsId}` 与评论列表 `/pins/comment/list` 只读接口加入公开白名单（利于 SEO 与未登录浏览），写接口（发布/点赞/发评论/分享）仍须登录

### 修复
- 修复「点击评论框不展开」：`.comment-input-wrap` 原缺少点击展开事件，新增 `@click="expandCommentBox"` 与 `expandCommentBox`/`focusCommentTextarea` 方法
- 修复内容服务启动时 `NoSuchMethodError: ApPins.getShareCount()`：本地仓 model jar 过期，重新 `install` 模型模块后生效

### 变更文件
- 新增：`src/pages/pins/detail.vue`（沸点详情页）
- 修改：`src/pages/pins/index.vue`（时间悬浮高亮与点击跳转）
- 修改：`src/apis/pins.js`（新增 `getPinsDetail`）
- 修改：`src/routers/home.js`（注册 `/pins/detail/:id` 路由）
- 修改：`PinsPublicController.java` / `PinsPublicService.java` / `PinsPublicServiceImpl.java` / `PinsQueryService.java`（详情接口、评论排序）
- 修改：`AuthorizeFilter.java`（网关放行沸点详情/评论列表只读接口）

## 2026-08-12 — 修复沸点发布 topicTags 类型不匹配

### 问题描述
沸点发布失败，报 `HttpMessageNotReadableException: Cannot deserialize value of type java.lang.String from Array value`，链路为 `PinsPublishDTO["topicTags"]`。

### 根因分析
前端 `PinsPublishBox.vue` 发送的 `topicTags` 是数组（`[topicDisplayName]`），而后端 `PinsPublishDTO.topicTags` 声明为 `String`，导致 JSON 反序列化失败。

### 修复方案
- `PinsPublishDTO.topicTags` 由 `String` 改为 `List<String>`（标签语义为数组，与 `PinsVO.topicTags` 一致）
- `PinsPublishService.getApPins` 中改为 `String.join(",", dto.getTopicTags())` 逗号拼接入库，与读取端 `parseStringList`（逗号拆分）一致

### 变更文件
- 修改：`PinsPublishDTO.java`（`topicTags` 改为 `List<String>`）
- 修改：`PinsPublishService.java`（逗号拼接入库）

## 2026-08-12 — 文章推荐服务重构：全局公平 + 多样性配额

### 功能变更
- **推荐策略重构**：从「质量排序 + 随机洗牌」改为「全局公平 + 多样性配额」——不做个性化，所有用户在同一频道/标签下看到一致、横向覆盖多技术栈的推荐
- **候选池策略**：`selectRecommendCandidates` 增加时间窗口（默认最近 7 天），排序改为 `score DESC, publish_time DESC`，让优质内容优先进入候选，老优质内容不再被「最新 500 篇」排除
- **全局配额贪心算法**：对评分降序候选做一次遍历，同一标签累计最多 `max-per-tag`（默认 2）篇、同一作者累计最多 `max-per-author`（默认 3）篇，配额计数跨页累计，避免「本页与下页同标签」；标签过集中时按评分追加补齐，保证列表可填满
- **对数归一化**：`views/likes/comment/collection` 由「池内最大值线性归一化」改为「对数归一化 `log(1+x)/log(1+max)`」，弱化爆款压制，让长尾内容有生存空间
- **确定分页**：同 seed + 稳定候选池 → 同一推荐序列 → 翻页稳定，解决原有跨页重复/遗漏
- **可配置**：新增 `recommend.*` 配置段（`max-candidates`/`window-days`/`max-per-tag`/`max-per-author`/`untagged-bucket`），调参无需改代码

### 变更文件
- 修改：`ApArticleRecommendServiceImpl.java`（配额贪心、对数归一化、配置读取，替换随机洗牌）
- 修改：`ApArticleMapper.java`（`selectRecommendCandidates` 增加 `windowDays` 参数）
- 修改：`ApArticleMapper.xml`（时间窗口 + 评分排序）
- 修改：`application.yml`（新增 `recommend.*` 配置段）
- 新增（此前）：`参考资料/文章推荐服务——全局公平与多样性配额设计方案.md`（设计依据，不入版本控制）

## 2026-08-12 — 话题/圈子详情页交互与布局优化

### 功能变更
- **话题详情页两级分栏**：混合话题（type=2）新增「文章 | 沸点」一级分栏，其下各有「热门 | 最新」二级分栏；文章分栏渲染完整文章卡片（标题/封面/作者/频道/阅读量/评论数），点击新窗口打开文章详情
- **纯沸点话题发布入口**：type=1 话题的发布入口改为深蓝色卡片，仅显示当前话题名（不再显示 😊📷 图标），点击弹出发布沸点弹窗而非内联展开
- **骨架白底卡片**：话题详情页信息区、沸点主体区已加白底卡片，与页面背景区分
- **圈子详情页**：发布入口改为深色卡片、固定文案「快和逐友一起分享新鲜事！」，点击弹出发布框；「推荐话题」移入右侧边栏
- **沸点页评论框**：评论输入框改为多行，新增表情、图片图标与 1000 字字数提示
- **圈子广场**：加入/退出圈子成功后立即刷新「我的圈子」列表；「我的圈子」卡片样式网格与「圈子广场」统一

### 变更文件
- 修改：`src/pages/pin/topic/detail.vue`（两级分栏、发布弹窗、文章卡片、骨架）
- 修改：`src/pages/pins/circle/detail.vue`（弹窗发布、侧边栏推荐话题、深色入口）
- 修改：`src/pages/creator/pins/components/PinsPublishModal.vue`（遮罩改为 fixed 定位，作为公共发布弹窗）
- 修改：`src/pages/pins/index.vue`（评论框表情/图片/字数提示）
- 修改：`src/pages/pins/circles.vue`（加入后刷新我的圈子、卡片样式统一）
- 修改（此前）：`TopicServiceImpl.java`（话题文章 Feed 返回完整文章卡数据并支持热门/最新排序）

## 2026-08-11 — 修复圈子选择弹框数据模型错误

### 问题描述
沸点页发布框中"选择圈子"弹框显示有误：左侧本应显示圈子分类（技术、职场等），却错误地显示了具体圈子名称。

### 根因分析
后端 `PinsQueryService.circles()` 方法使用了错误的数据模型：
- 错误地将 `parent_id` 作为父子关系来区分"分类"和"圈子"
- 查询 `parent_id IS NULL` 的记录作为"分类"，`parent_id IS NOT NULL` 的记录作为"具体圈子"
- 但实际数据模型是：`ap_circle_category` 存储分类，`ap_circle` 通过 `category_id` 关联分类

### 修复方案
修改 `PinsQueryService.circles()` 方法：
1. 查询 `ap_circle_category` 表获取所有分类（按 `sort_order` 排序）
2. 查询 `ap_circle` 表获取所有圈子（`category_id IS NOT NULL`）
3. 按 `category_id` 分组，将圈子归入对应分类
4. 返回正确的数据结构：`[{ id, name, circles: [{ id, name, icon, memberCount, pinsCount }] }]`

### 变更文件
- 修改：`PinsQueryService.java`（添加 `ApCircleCategoryMapper` 注入，重写 `circles()` 方法）

## 2026-08-11 — 圈子数据初始化（已更正）

### 功能变更
- **重要更正**：首次初始化时误将圈子数据插入到 `ap_topic`（话题表），现已回滚并更正插入到 `ap_circle`（圈子表）
- 新增"推荐圈子"分类（ID: 13）
- 初始化 47 个圈子数据到 `ap_circle` 表，覆盖所有分类：
  - **推荐圈子**：鸿蒙开发者社区、源码共读、逐友请回答、掘金官方、反馈&建议（5个）
  - **技术**：鸿蒙开发者社区、源码共读、VibeLaunch、Vibe编程交流圈、数据标注专家社区（5个）
  - **互动交流**：逐友请回答、上班摸鱼、青训营-快乐出发、AI 聊天室、Coze 交流群、飞书项目开发者社区（6个）
  - **职场**：内推招聘广场、打工人的日常（2个）
  - **吃喝玩乐**：下班去哪儿玩、舌尖上的沸点、什么值得买、活动推荐、游戏玩家俱乐部（5个）
  - **资讯**：应用安利、今日新鲜事、科技交流圈（3个）
  - **理财**：理财交流圈（1个）
  - **书影音**：读书会、好文推荐、一起看片、值得收藏的歌曲（4个）
  - **生活**：照片展览馆、今天学到了、萌宠报道、体育运动俱乐部（4个）
  - **搞笑**：搞笑段子、沙雕表情包（2个）
  - **许愿池**：定个小目标（1个）
  - **情感**：逐日相亲角、树洞一下、情感互助协会（3个）
  - **掘金一下**：反馈&建议、掘金官方、我&掘金、沸点福利、程序员搬砖人生、掘金公益角（6个）
- 圈子通过 `category_id` 字段直接关联分类
- `member_count`（逐友数）和 `pins_count`（沸点帖数）均初始化为 0

### 数据模型说明
- `ap_topic` 表：存储**话题**（如"每日精选"、"AI编程"等可关联多篇沸点帖的主题）
- `ap_circle` 表：存储**圈子**（用户加入的社群，包含成员数、沸点帖数等属性）
- `ap_circle_category` 表：存储圈子分类（技术、职场等）
- `topic_circle_relation` 表：话题与圈子的多对多关系（标记某个话题属于哪个圈子）

## 2026-08-11 — 文章评论接口端到端集成测试

### 功能变更
- 新增端到端集成测试 `ArticleCommentE2ETest`（5 个用例）：`@SpringBootTest + @AutoConfigureMockMvc` 加载完整上下文，通过真实 HTTP 请求走 Controller → Service → Mapper → MySQL 全链路，并校验数据库真实落库
- 覆盖：发表→回复→点赞→查询列表→取消点赞全链路、未登录拦截、空内容校验、回复/点赞不存在的评论
- 隔离策略：登录态经请求头 userId/nickName 注入（匹配 ContentTokenInterceptor）；`@MockBean` 屏蔽 `CommentAuditService` 异步审核以隔离 AI/行为上报/站内信副作用；独立测试文章ID与用户ID，`@AfterEach` 清理测试数据
- 至此评论接口具备完整三层测试：Service 单测（13）+ Controller 层（10）+ 端到端集成（5）

## 2026-08-11 — 文章评论接口分层测试

### 功能变更
- 新增控制器层测试 `ArticleCommentControllerTest`（10 个用例）：覆盖评论列表游标分页参数绑定、发表评论、回复评论、点赞评论的 HTTP 路由绑定、请求体解析及登录态拦截
- 与既有 Service 层单测 `ApCommentServiceImplTest`（13 个用例）共同构成评论接口的分层测试（Controller 层 + Service 层）

## 2026-08-11 — 文章详情页 FTL 服务端渲染（方案②）

### 功能变更
- 文章详情页由 Vue SPA 改为 **FreeMarker 服务端渲染**（方案②），正文 HTML 由服务端渲染，利于 SEO 与首屏速度
- 新增 `ArticlePageController`（MVC），读取正文 Markdown 并渲染为 HTML、提取目录 TOC，填充 `article.ftl` 模板
- `article.ftl` 集成与主页一致的顶栏与登录弹窗（混合方案），顶部栏与登录功能与 Vue SPA 保持一致
- 移除每篇文章重复上传共用 JS 到 MinIO 的冗余逻辑，`article-static.js` 作为内容服务静态资源由网关 `/content/article-static.js` 统一提供

### 修复问题
- **详情 API 交互状态恒为 false**：网关 `AuthorizeFilter` 对公开只读路径（文章详情等）不再静默放行，改为一并有有效 accToken 时解析并注入 `userId`/`nickName` 头，使下游详情服务能识别登录用户、正确返回 `isDigg/isCollect/isFollow`。需重建并重启网关生效
- **文章 ID 带千分位逗号**：`article.ftl` 中 `window.ARTICLE_ID` 使用 FreeMarker `?c` 强制计算机格式输出，避免大整数渲染为 `2,087,071,...` 导致 API URL 失效
- **Token 读取与请求头不匹配**：`article-static.js` 统一从 `localStorage.ACCESS_TOKEN` 读取并在 `accToken` 头携带，保证与 Vue SPA 及后端一致
- **日期显示 NaN**：`article-static.js` 的 `formatTime` 兼容 10 位/13 位时间戳并做校验
- **未登录交互无反馈**：点赞/收藏/关注/评论输入在未登录时唤起登录弹窗而非无效操作

### 变更文件
- `heima-leadnews-content/.../controller/page/ArticlePageController.java`（新增）
- `heima-leadnews-content/src/main/resources/templates/article.ftl`（修改）
- `heima-leadnews-content/src/main/resources/static/article-static.js`（修改）
- `heima-leadnews-content/.../service/article/impl/ArticleFreemarkerServiceImpl.java`（修改，移除冗余 JS 上传）
- `heima-leadnews-app-gateway/.../filter/AuthorizeFilter.java`（修改，公开路径注入用户上下文）
- `docs/qa_test_report_ftl_article_detail_20260811.md`（新增，测试报告）

---

## 2026-08-01 — 后端服务架构重构

### 架构变更

#### ScheduleApplication → 合并入 Article 服务
- **BREAKING**: 删除 `heima-leadnews-schedule` 独立微服务模块
- 将 TaskService、TaskinfoMapper、TaskDelayConsumer 等全部迁移至 article 模块的 `com.heima.article.schedule` 包
- 使用 Redisson 延迟队列（`RBlockingQueue` + `RDelayedQueue`）替代 RabbitMQ 延迟插件，消除外部 RabbitMQ 依赖
- 移除 `IScheduleClient` Feign 接口，远程调用改为本地 Service 方法调用
- 新增 `schedule.sql` DDL 文件，用于在 `leadnews_article` 库中创建 `taskinfo` 和 `taskinfo_logs` 表

#### BehaviorApplication → 合并入 Article 服务并重构
- **BREAKING**: 删除 `heima-leadnews-behavior` 独立微服务模块
- 将 LikesBehavior、ReadBehavior、UnlikesBehavior 的 Controller 和 Service 全部迁移至 article 模块的 `com.heima.article.behavior` 包
- **移除 Redis 缓存用户行为逻辑**（`LIKE_BEHAVIOR`、`READ_BEHAVIOR`、`UN_LIKE_BEHAVIOR`），改为直接数据库持久化
  - 点赞/取消点赞：原子更新 `ap_article.likes` 字段，并记录到 `ap_user_action_log` 表
  - 阅读行为：原子更新 `ap_article.views` 字段，并记录到 `ap_browse_history` 表
  - 不喜欢行为：记录到 `ap_user_action_log` 表
- 移除 AOP 切面（`ReadLikeUnLikeAspect`）和 `@UserBehavior` 注解，不再发送 Kafka 消息

#### 移除 Kafka 技术栈
- 删除 `KafkaStreamConfig.java`（Kafka Streams 配置）
- 删除 `HotArticleStreamHandler.java`（Kafka Streams 聚合处理器）
- 删除 `ArticleIncrHandleListener.java`（Kafka 消费者）
- 删除 `ArticleIsDownListener.java`（Kafka 消费者）
- 从 `pom.xml` 移除 `spring-kafka`、`kafka-streams`、`kafka-clients` 依赖
- 从 `application.yml` 移除 Kafka 配置
- 行为热度更新改为直接调用 `ApArticleService.updateScoreByBehavior()`，无需消息队列中转

#### 沸点服务决策
- 沸点（Pins）保持现状，不拆分为独立微服务。与文章共享基础设施，符合"去微服务化"趋势

### 数据库变更
- 新增 `taskinfo` 和 `taskinfo_logs` 表（`leadnews_article` 库），参考 `schedule.sql`

### 基础架构变更
- 从 `heima-leadnews-service/pom.xml` 中移除 `heima-leadnews-schedule` 和 `heima-leadnews-behavior` 模块
- 网关路由中移除 `/schedule/` 和 `/behavior/` 路由
- 减少 2 个微服务实例、消除 Kafka 和 RabbitMQ 外部依赖

### 变更文件列表

#### 删除（模块）
- `heima-leadnews-service/heima-leadnews-schedule/`（完整模块）
- `heima-leadnews-service/heima-leadnews-behavior/`（完整模块）

#### 删除（Feign接口）
- `heima-leadnews-feign-api/.../schedule/IScheduleClient.java`

#### 删除（Kafka相关）
- `heima-leadnews-article/.../config/KafkaStreamConfig.java`
- `heima-leadnews-article/.../stream/HotArticleStreamHandler.java`
- `heima-leadnews-article/.../listener/ArticleIncrHandleListener.java`
- `heima-leadnews-article/.../listener/ArticleIsDownListener.java`

#### 新增（article模块）
- `heima-leadnews-article/.../schedule/service/TaskService.java`
- `heima-leadnews-article/.../schedule/service/impl/TaskServiceImpl.java`
- `heima-leadnews-article/.../schedule/listener/TaskDelayConsumer.java`
- `heima-leadnews-article/.../schedule/mapper/TaskinfoLogsMapper.java`
- `heima-leadnews-article/.../schedule/mapper/TaskinfoMapper.java`
- `heima-leadnews-article/.../behavior/controller/v1/ApLikesBehaviorController.java`
- `heima-leadnews-article/.../behavior/controller/v1/ApReadBehaviorController.java`
- `heima-leadnews-article/.../behavior/controller/v1/ApUnlikesBehaviorController.java`
- `heima-leadnews-article/.../behavior/service/ApLikesBehaviorService.java`
- `heima-leadnews-article/.../behavior/service/ApReadBehaviorService.java`
- `heima-leadnews-article/.../behavior/service/ApUnlikesBehaviorService.java`
- `heima-leadnews-article/.../behavior/service/impl/ApLikesBehaviorServiceImpl.java`
- `heima-leadnews-article/.../behavior/service/impl/ApReadBehaviorServiceImpl.java`
- `heima-leadnews-article/.../behavior/service/impl/ApUnlikesBehaviorServiceImpl.java`
- `heima-leadnews-article/.../config/RedissonConfig.java`
- `heima-leadnews-article/src/main/resources/schedule.sql`
- `heima-leadnews-article/src/main/resources/mapper/TaskinfoMapper.xml`

#### 修改
- `heima-leadnews-article/.../ArticleApplication.java`（MapperScan 增加 schedule 包）
- `heima-leadnews-article/.../service/ApArticleService.java`（新增 updateScoreByBehavior 方法）
- `heima-leadnews-article/.../service/impl/ApArticleServiceImpl.java`（实现 updateScoreByBehavior）
- `heima-leadnews-article/.../service/impl/ArticleTaskServiceImpl.java`（Feign 改为本地调用）
- `heima-leadnews-article/pom.xml`（移除 Kafka 依赖，保留 Redisson）
- `heima-leadnews-article/src/main/resources/application.yml`（移除 Kafka 配置）
- `heima-leadnews-service/pom.xml`（移除 schedule 和 behavior 模块引用）
- `heima-leadnews-gateway/.../application-gateway.yml`（移除 schedule 和 behavior 路由）
- `heima-leadnews-common/.../constants/BehaviorConstants.java`（添加废弃注释）

### 新增功能

#### 课程微服务 (heima-leadnews-course)
- 新增 `heima-leadnews-course` 微服务模块（端口 51803），独立处理课程交易、营销、结算、审核逻辑
- 网关路由：`/course/**` → 课程微服务

#### 课程创作与章节管理
- 课程 CRUD 接口（创建、更新、列表、详情、软删除），支持状态管理（草稿/待审核/已上架/已下架）
- 章节 CRUD 接口，支持 Markdown 内容编辑、试读章节设置、排序管理
- 前端创作者中心课程管理页面（列表/新建/编辑），含状态筛选和搜索功能
- 前端章节编辑器，支持 Markdown 编辑与实时预览

#### 课程购买与支付
- 订单创建接口，支持从数据库获取真实课程价格
- 支付宝沙箱环境支付集成，含支付页面生成、异步通知回调处理
- 支付后处理：更新折扣码使用次数、课程学习人数、用户课程权限
- 前端课程详情页：购买流程、折扣码实时验证、折后价格展示
- 前端课程阅读页：章节切换加载完整内容、XSS 安全处理
- 前端"我的课程"页面：展示已购买课程列表

#### 课程营销活动
- 折扣码创建/列表/停用/验证接口，支持固定金额和百分比两种折扣类型
- 前端折扣码管理页面，支持创建、查看、停用操作

#### 收入结算
- 月度结算计算逻辑（作者分成 70%，平台分成 30%）
- 结算明细查询接口
- 前端收入结算看板页面，展示结算列表与明细

#### 课程权限控制
- 基于逐力值等级（Lv5+）控制课程创作权限
- 前端导航栏根据权限显示"创作者中心"入口
- 路由守卫控制创作者中心访问权限

### 修复问题

- **沸点接口 404 错误**：修复网关 `StripPrefix= 1` 空格导致前缀未正确剥离的问题，以及前端接口路径重复 `/article/` 前缀问题
- **444 错误后登录状态丢失**：`article_request.js` 实现请求队列机制，修复并发刷新 token 时登录态丢失
- **沸点页/课程页顶栏不统一**：将沸点页和课程页路由纳入 Layout 组件，统一复用桌面端顶栏
- **课程价格硬编码**：`OrderServiceImpl` 改为从数据库获取真实课程价格
- **支付后处理不完整**：补充折扣码使用次数更新、课程销售数据更新、用户课程权限添加
- **API 返回格式不统一**：课程列表、订单列表等接口统一使用 `data.list` 和 `data.total` 格式
- **课程服务编译错误**：修复 `javax.servlet` → `jakarta.servlet` 适配 Spring Boot 3.x，修复 `ApUser` 导入路径错误

### 数据库变更

#### 新增表
- `ap_course_order`：课程订单表
- `ap_course_discount`：课程折扣码表
- `ap_course_review`：编辑审核记录表
- `ap_course_invitation`：邀请编辑表
- `ap_course_settlement`：收入结算表
- `ap_course_chapter_comment`：章节评论表

#### 扩展现有表
- `ap_course`：新增 `is_deleted`、`version`、`sales_count`、`total_revenue` 字段
- `ap_course_chapter`：新增 `status`、`estimated_minutes`、`comment_count` 字段

### 基础架构变更

- 新增 `heima-leadnews-course` 模块到 `heima-leadnews-service/pom.xml`
- Vite 配置添加 `/course` 代理路由
- 创作者中心菜单和路由更新（折扣码管理、收入结算入口）
- 课程详情页/阅读页移除静态 Mock 数据，全部改为 API 调用
- 删除 `src/pages/course/mockData.js`

### 变更文件列表

#### 后端（新增）
- `heima-leadnews-service/heima-leadnews-course/`（完整微服务模块）
- `heima-leadnews-model/.../dtos/CourseDto.java`
- `heima-leadnews-model/.../dtos/ChapterDto.java`
- `heima-leadnews-model/.../dtos/ChapterSortDto.java`
- `heima-leadnews-model/.../dtos/CourseDiscountDto.java`
- `heima-leadnews-model/.../pojos/ApCourseDiscount.java`
- `heima-leadnews-model/.../pojos/ApCourseOrder.java`
- `heima-leadnews-model/.../pojos/ApCourseReview.java`
- `heima-leadnews-model/.../pojos/ApCourseInvitation.java`
- `heima-leadnews-model/.../pojos/ApCourseSettlement.java`
- `heima-leadnews-model/.../pojos/ApCourseChapterComment.java`
- `heima-leadnews-article/.../controller/v1/CourseChapterController.java`
- `heima-leadnews-article/.../service/ApCourseChapterService.java`
- `heima-leadnews-article/.../service/impl/ApCourseChapterServiceImpl.java`
- `heima-leadnews-gateway/.../dto/`（网关新增 DTO）
- `sql/init_course_extended.sql`

#### 前端（新增）
- `src/apis/course.js`
- `src/apis/circle.js`
- `src/apis/pins.js`
- `src/pages/creator/course/list.vue`
- `src/pages/creator/course/edit.vue`
- `src/pages/creator/course/discount.vue`
- `src/pages/creator/course/settlement.vue`
- `src/pages/user/courses/index.vue`
- `src/pages/user/courses/components/CourseListItem.vue`
- `src/pages/user/courses/components/CourseGridCard.vue`

#### 前端（修改）
- `src/pages/course/detail.vue`（API 调用、折扣码验证、购买流程）
- `src/pages/course/read.vue`（API 调用、章节切换、进度更新）
- `src/pages/course/index.vue`（动态数据）
- `src/pages/pins/index.vue`（顶栏适配）
- `src/pages/pins/circles.vue`（顶栏适配）
- `src/pages/creator/constants/menus.js`（菜单更新）
- `src/routers/creator.js`（路由更新）
- `src/common/request.js`（请求拦截器修复）
- `src/common/wemedia_request.js`（请求拦截器修复）
- `src/common/conf.js`（配置更新）
- `src/components/bars/home_bar.vue`（顶栏权限控制）
- `vite.config.js`（代理配置）
- `src/apis/home/api.js`
- `src/apis/topic.js`
- `src/apis/user.js`