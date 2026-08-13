# CHANGELOG

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