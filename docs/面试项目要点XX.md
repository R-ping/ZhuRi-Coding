# 黑马头条 · 知识付费内容平台 —— 面试项目要点（基于代码分析）

> 本文档所有内容均基于项目实际代码与配置分析得出（非 CHANGELOG），关键类均给出路径与逻辑，
> 供面试讲述与追问准备使用。共 12 个要点 + 3 个附录。

---

## 0. 项目总览（30 秒自我介绍用）

**一句话定位**：一个 Spring Cloud Alibaba 微服务架构的「今日头条 + 掘金 + 知识付费」综合内容平台，
支持文章/沸点/圈子/专栏/课程等 UGC 内容的生产、**AI 智能审核**、个性化推荐、双等级体系、
打赏/课程支付、站内信与 IM 私信等完整业务闭环。

**技术栈速览**：

| 层面 | 选型 |
|---|---|
| 微服务框架 | Spring Boot 3.3.4 + JDK 17 + Spring Cloud 2023.0.3 + Spring Cloud Alibaba 2023.0.3.2 |
| 注册中心 | Nacos（仅注册，配置中心未启用） |
| 网关 | Spring Cloud Gateway（51601）：路由 + 全局 JWT 过滤器 + SEO 白名单 |
| 服务划分 | user(51780) / content(51802) / search(51804) / notification(51807) / reward(51905) |
| 持久层 | MySQL ×5 库 + MyBatis-Plus 3.5.7；PostgreSQL + **pgvector**（RAG 相似度）；MongoDB（搜索历史） |
| 缓存/中间件 | Redis（验证码/token/限流/未读数/延迟队列，Redisson 3.37）；Elasticsearch 9201（搜索） |
| 对象存储 | 阿里云 OSS（web 直传，MinIO 已废弃移除） |
| AI | 阿里云百炼 DashScope（qwen 大模型 + embedding）、阿里云内容安全 Green（图片审核） |
| 支付 | 支付宝 alipay-sdk（RSA2 沙箱验签） |
| 可观测 | Micrometer Tracing(Brave)+Zipkin、Prometheus+Grafana+Loki+Promtail、logstash JSON 日志（traceId/spanId）、JaCoCo 覆盖率门禁 |
| 服务通信 | OpenFeign（全部接口带 fallback 降级） |

**服务间调用链**（面试画图用）：

```
Gateway(51601)
 ├─ /api/v1/user/**      → user 服务(51780)   登录/双token/三方登录/账号
 ├─ /api/v1/article/**   → content(51802)     文章/沸点/圈子/等级/订单/支付/OSS
 ├─ /api/v1/search/**    → search(51804)      ES 搜索
 ├─ /api/v1/notify/**    → notification(51807) 站内信 + WebSocket IM
 └─ /api/v1/reward/**    → reward(51905)      钱包/签到/抽奖/兑换
 服务间：Feign（带 fallback） + 网关透传 userId/nickName 头
```

---

## 要点 1：微服务网关 —— 路由、统一鉴权与 SEO 白名单

**业务背景**：6 个服务对外统一收口，鉴权必须在网关层集中做，避免各服务重复实现。

**技术实现**：
- `heima-leadnews-gateway/.../filter/AuthorizeFilter.java`（`GlobalFilter`，order=0）：
  - 校验请求头中的 accessToken（JWT HS512，网关版 `AppJwtUtil.verifyToken` 只判过期）；
  - `isPublicPath` 白名单放行大量 SEO 只读路径（首页/详情/搜索等），其余全部校验；
  - 校验通过后把 `userId`、`nickName`（URL 编码）写入请求头下发给下游，服务端经
    `AppTokenInterceptor`/`ContentTokenInterceptor` 解析进 `AppThreadLocalUtil`（ThreadLocal）；
  - **校验失败返回自定义 HTTP 444**，前端捕获后自动走「刷新 token → 重放原请求」协议（见要点 2）。
- `application-gateway.yml`：`lb://` 负载均衡路由 + `StripPrefix=1` + 全局 CORS。

**设计亮点 / 可追问**：
1. 为什么选网关集中鉴权而不是每个服务各自鉴权？（单一职责、避免重复代码、安全策略统一变更）
2. 444 自定义状态码的用意？（与真实业务错误 401/403 区分，前端可精确识别「token 失效」语义并触发刷新重放，避免刷新逻辑误触发）
3. 下游如何信任网关头？（服务内拦截器只从网关头取值，不信任请求体，防伪造）

---

## 要点 2：双 Token 认证体系（Access JWT + Refresh 一次性令牌）

**业务背景**：移动端/PC 端需要「长时间保持登录」+「短时鉴权」，用双 token 平衡安全与体验。

**技术实现**（`user/.../service/impl/TokenServiceImpl.java`，已核实）：
- **accessToken**：JWT（HS512 + GZIP 压缩），claims 含 `userId/nickName/image`，**有效期 1h**；
- **refreshToken**：`UUID` 32 位随机串，**不落 JWT**，存 Redis `refresh_token:{uuid}`，**TTL 7 天**，
  value 为 fastjson 序列化的用户信息；
- **刷新链路**：网关 444 → 前端调 `POST /api/v1/token/refresh`（公开路径）→ 读 Redis → **先 delete
  旧 refresh token（一次性使用，防重放攻击）** → `generateDualToken` 重新签发双 token；
- **登出**：`/api/v1/token/logout` → `revokeRefreshToken` 删 Redis key；
- 刷新/登出接口均带 `@RateLimit`（全局 + IP 双维度，见要点 11）。

**设计亮点 / 可追问**：
1. 为什么 refresh token 用 Redis 存而不是再签发一个 JWT？（**可吊销**：登出/换绑能立刻失效；JWT 无状态无法吊销）
2. 一次性刷新如何防重放？（每次 refresh 先删旧 key 再发新 key，旧 token 第二次使用直接失败）
3. access token 为什么不存 Redis？（无状态、网关校验零 IO、支持水平扩展；代价是登出后 1h 内仍有效——面试要主动承认这个 trade-off，并说可优化为 Redis 黑名单/版本号互斥）
4. 多端登录怎么处理？（当前是每次登录签发新 token、旧 refresh 保留——可优化方向：登录互斥/踢人下线）

---

## 要点 3：三方社交登录（GitHub/微博 OAuth2 + 微信公众号）

**业务背景**：降低注册门槛，支持社交账号快捷登录，未绑定手机号时引导绑定。

**技术实现**（user 服务）：
- `SocialAuthCallBack`（`/oauth2/code/github|weibo`）→ `SocialAuthServiceImpl` 用 RestTemplate
  拿 code 换 access_token（**client_secret 从环境变量读，禁止硬编码**）；
- `SocialLoginServiceImpl.socialAuth`：查 `ap_user_social`（git_uid/weibo_uid/open_id 分列）：
  - 已绑定 → 直接 `generateDualToken` 发双 token；
  - **未绑定 → 返回 `status="need_bind"`**，platformUid 用 `SimpleAesECBUtil`
    （AES/ECB/PKCS5Padding）加密回传，绑定接口再解密，避免明文 uid 被篡改；
- **绑定互斥**：一个社交 uid 只能绑一个手机号；一个手机号同平台只能绑一次（唯一索引兜底）；
- 手机号验证码：4 位随机码存 Redis `socialBind:{platform}:{phone}` TTL 5min（开发期无短信通道直接返回）；
- **微信公众号**：GET 接口 `sha1 字典序排序 + MessageDigest.isEqual` 验签（防时序攻击）；POST 收到
  「登录」消息生成 6 位 token 存 `wechat:token:{openid}` 供扫码确认登录（前端消费端未接入）。

**设计亮点 / 可追问**：
1. OAuth2 的 code 为什么要后端换 token 而不是前端？（client_secret 不能暴露给前端）
2. platformUid 为什么要加密？（防止中间人/恶意构造绑定他人账号）
3. 验签为什么用 `MessageDigest.isEqual` 而不是 `equals`？（`equals` 短路比较有**时序侧信道**风险，`isEqual` 恒定时长比较）

---

## 要点 4：文章发布全流程 + AI 审核责任链（核心亮点）

**业务背景**：UGC 平台最大风险是垃圾/违规内容，人工审核成本高，用「AI 审核 + 图片安全 + 查重 +
激励」的自动化责任链替代人工初审。

**技术实现**：
- **发布入口**：`ApArticleDraftServiceImpl.publishFromDraft(draftId)`：草稿 → 建 `ap_article`
  (status=SUBMIT 审核中) + config + content → 删草稿 → **`TransactionSynchronization.afterCommit()`
  事务提交后才触发异步审核**（避免异步线程查不到未提交数据）；
- **审核链**（`ArticleAutoScanServiceImpl.autoScanArticle`，@Async 返回 CompletableFuture，
  手写顺序编排，前两环返回 false 短路终止，后三环不终止）：
  1. `AIViolationProcessor` —— 百炼 qwen `comprehensiveAudit` 一次调用完成 4 项检测
     （违规/标题相关性/内容质量/技术相关性，减少 token 消耗）；**success=false 时 fail-closed
     拒绝通过，绝不降级为放行**；
  2. `ImageScanProcessor` —— 阿里云 Green `imageScan`，high/medium 即失败；
  3. `SimilarityProcessor` —— pgvector RAG 余弦相似度（阈值 0.85），高相似 → `is_recommend=0`
     （幂等 upsert + `uk_article_id` 唯一索引兜底并发）；
  4. `PowerBonusProcessor` —— AI 质量分 ≥80 加逐力值 +3、≥60 加 +1；作者逐力等级 ≥4 自动推荐首页；
     ≥80 发「质量优秀」系统通知；
  5. `BehaviorEventProcessor` —— 发布行为事件（走等级积分体系）；
  6. `articleTaskService.addArticleToTask` —— 入延迟发布队列（见要点 6）。
- **审核失败**：`AuditFailProcessor.handleFail` → 状态置 FAIL + reason → 写 `ap_article_audit_record`
  → Feign `INotificationClient` 发系统通知告知作者原因；
- **模板方法复用**：`AbstractAuditService`（AI 违规检测 fail-closed → 图片审核 → 子类回调
  `handlePassed/handleFailed`），**沸点、评论、专栏审核均继承复用同一套骨架**。

**设计亮点 / 可追问**：
1. 为什么用责任链（顺序编排+短路）？（新增审核环节只需加一个 Processor，对现有节点零侵入；
   前面的硬性违规直接短路，省掉后续昂贵调用）
2. 事务提交后再异步审核解决了什么问题？（事务未提交时异步线程读不到数据，会误判「文章不存在」）
3. fail-closed 的设计哲学？（审核宁可误杀不可漏放，AI 服务不可用时平台安全优先；与之对比评论审核是「先展示后审核」可降级，不同业务不同策略）
4. 为什么一次调用让模型做多项检测？（减少 LLM 调用次数与 token 成本，但要求 prompt 设计能同时输出多维度结论——引出要点 5 的结构化输出）

---

## 要点 5：LLM 安全工程 —— Prompt 注入三层防御 + 结构化输出（核心亮点）

**业务背景**：把用户文章/评论文本直接拼进 prompt 调大模型审核，攻击者可能注入「忽略指令、扮演其他角色」
来绕过审核；且 LLM 输出是自然语言，必须转成可靠的结构化结果才能驱动业务流程。

**技术实现**（`common/bailian/`，已核实 `StructuredOutputInvoker.java`、`PromptSanitizer.java`）：

**三层注入防御**：
- **Layer 1 输入净化** `PromptSanitizer.sanitize()`：4 组正则（行首角色标记 `^system:|^user:`、
  注入短语「忽略之前的指令 / ignore previous instructions」、伪造分隔符 `---xxx开始---`、
  伪造 `<data-boundary-xxx>` 标签）→ 替换为中性占位符并记 warn 日志；同时 `wrapWithDelimiters`
  用 **UUID 动态分隔符**包裹用户数据（标签值每次随机，攻击者无法预知无法伪造闭合标签——思路同 CSRF Token）；
- **Layer 2 系统指令加固**：`StructuredOutputInvoker.invoke` 自动在 system prompt 末尾追加
  `ANTI_INJECTION_INSTRUCTION`（声明 data-boundary 内是**数据**不是指令）；
- **Layer 3 输出护栏**：`isComplianceResponse` 检测模型响应中的「顺从短语」
  （如「好的，我已忽略之前指令」）→ 命中直接判定为 `BLOCKED_RESPONSE`，不进入业务。

**结构化输出（无原生 JSON Mode 的可靠解析）**：
- 靠 system prompt 内嵌字段约束（枚举/布尔/分值范围）；
- `convertRawToDto`：**清洗 Markdown 代码块**（```json 包裹）→ fastjson `JSON.parseObject`；
- 失败则 `repairUnescapedQuotesInJsonStrings` **单遍字符扫描启发式修复未转义引号**再解析；
- 仍失败 → **携带上次失败原因 + 严格 JSON 指令重试**（`buildRetrySystemPrompt`），非盲目重试；
  达到上限抛 `StructuredOutputException`，上层 fail-closed；
- 模型参数 temperature=0.3 / topP=0.8（降随机性）、网络错误指数退避重试 3 次；
- 结果 DTO 用 `@JSONField(name="is_violation")` 映射 snake_case；落库 `ap_article_ai_analysis`（先删后插）；
- 全程埋 Prometheus 指标 `ai.structured.invocation`（成功/失败计数 + 耗时 timer）。

**设计亮点 / 可追问**：
1. 注入防御为什么分三层？（纵深防御：单层正则会被绕过，边界标签 + 指令声明 + 输出护栏组合；
   净化只做「直接拼接点」，避免全局误杀合法内容）
2. 为什么用 UUID 动态分隔符？（固定标签名可被伪造闭合标签闭合用户数据区，动态不可预测值使伪造失效）
3. 结构化输出遇到的最大坑？（模型输出 ````json` 包裹、字符串内引号未转义、返回解释性文字——
   对应做了清洗、启发式修复、带因重试三层兜底）
4. 为什么不直接开 JSON Mode？（接入的 DashScope 文本接口未开启原生 JSON Mode，故走本地修复路径；
   代码里预留了未来接入 Schema 校验分支——体现前瞻设计）
5. 重试为什么带失败原因？（让模型知道上次哪错了，比盲目重试有效得多；且重试次数上限防止成本失控）

---

## 要点 6：事件驱动与最终一致性 —— 行为事件总线 + 延迟发布 + 本地消息表补偿

**业务背景**：一次用户行为（点赞/评论/发布）要联动等级积分、文章热度、通知、统计等多个模块，
直接串行调用耦合重且易失败；同时「定时发布」需要可靠执行。

**技术实现**：

**A. 自研行为事件总线**（`content/.../behavior/service/BehaviorEventBus`，非 Spring 事件）：
- `BehaviorHandler` 按 `BehaviorType` 路由；
- `BehaviorPostProcessor` 按 `getOrder()` 排序的后置链：
  `LevelScoreProcessor(1)` → `ArticleScoreProcessor(2)` → `NotificationProcessor(4)` → `StatisticsProcessor(5)`；
- Handler 支持 execute / **rollback 撤销**（补偿语义）；仅 `result.isNewRecord()`（首次行为）才触发，
  天然去重。

**B. Spring 事件解耦延迟发布链路**（打破 RedissonDelayQueue ↔ ApArticleService ↔ Freemarker 循环依赖）：
- `ArticleBuildCompleteEvent` → Listener 判断是否延迟 → `ScheduleLastDelayTaskEvent` →
  `LastDelayTaskScheduler` 入 Redisson 延迟队列（`TASK_FIRST/LAST_EXECUTE_DELAY_QUEUE`）→
  消费者阻塞 `take()` 后发 `RedissonDelayTaskEvent` → Listener 分别执行
  `generateArticleEvent`（写本地消息表 + 构建 HTML + Feign 同步 ES）与 `updateArticleStatus`（置 PUBLISHED）。

**C. 延迟发布策略**（`ArticleTaskServiceImpl.addArticleToTask`）：
剩余时间 <5min 立即；<15min 提前 2min；>15min **随机提前 5~10min**，制造「陆续发布」的拟真节奏。

**D. 最终一致性核心**：**本地消息表 `ap_article_event`**（pub_status/es_status/retry_count）+
`ApArticleEventServiceImpl.processEvent()` **@Scheduled 每 20s 补偿扫描**，超重试次数标记死信；
ES 同步失败同样靠该表补偿（见要点 12）。

**设计亮点 / 可追问**：
1. 自研事件总线 vs Spring ApplicationEvent？（Spring 事件同步、单 JVM 内；自研总线支持顺序、
   rollback 撤销、行为类型路由，契合「行为驱动积分/通知」这种强业务语义）
2. 为什么用本地消息表而不是直接 MQ？（延迟队列用 Redisson 已足够；本地消息表 + 定时补偿能保证
   「DB 操作与事件投递」同事务（先落表再异步消费），避免 MQ 丢消息后无法补偿；面试可对比
   事务消息/本地消息表两种可靠投递方案）
3. 延迟发布为什么随机提前？（全在整点同时发布会造成流量尖峰，且不真实）

---

## 要点 7：文章推荐与热度算法（规则加权 + 配额多样性，非协同过滤）

**业务背景**：首页 feed 需要兼顾「优质内容优先」与「内容多样性」，避免头部内容霸屏。

**技术实现**（`ApArticleRecommendServiceImpl.doRecommend`，type=all/cate/follow）：
- **候选池**：7 天窗口、上限 2000 篇；
- **评分公式**：
  ```
  score = 基础分/10000×0.25
        + 时效因子(7天线性衰减)×0.20
        + logNorm(views)×0.15 + logNorm(likes)×0.15
        + logNorm(comments)×0.10 + logNorm(collections)×0.10
  logNorm(x) = log(1+x)/log(1+max)   // 对数归一化，弱化爆款碾压
  ```
- **全局配额贪心**：同标签 ≤2 篇、同作者 ≤3 篇，配额不足降级按分补齐 → 保证跨页稳定与多样性；
- **follow 分栏**：先查 `ap_follow` 取关注作者，再取其文章；
- **热度分**（`ApArticleServiceImpl.computeScore`，经典头条公式）：
  `热度 = likes×3 + views + comment×3 + collection×6`，行为发生时经 `ArticleScoreProcessor`
  单条**原子 UPDATE**（`updateInteractionAndScore`，字段白名单防注入）；
- **相关文章**：作者文章(≤3) → 同频道 → 全局兜底；`JSON_OVERLAPS(tags,...)` 标签匹配；
- **热门作者**：`hot_score = collection×8 + likes×3 + fans×5`（基于 user_daily_stats）。

**诚实披露（面试主动说）**：当前是**规则加权 + 配额多样性**，没有做协同过滤/向量召回；
热点榜是 SQL 按 score 直查（90 天窗口），未用 Redis ZSet —— 面试若被追问「为什么不用
ZSet」可说：数据量当前未到瓶颈，SQL 可维护性更好，后续可平滑替换。

**设计亮点 / 可追问**：
1. 为什么用 log 归一化而不是直接线性加权？（爆款阅读量 10w+，普通文章几百，直接线性会导致
   爆款永远霸榜，log 压缩量级差距）
2. 配额贪心怎么保证「跨页不重复」？（先全局配额再取数，而不是每页独立排序截取）
3. 热度分更新为什么用原子 UPDATE 而不是读改写？（并发下 read-modify-write 会丢更新，
   `SET views=views+N` 数据库行锁原子性）

---

## 要点 8：双等级体系（逐友等级 = 逐日等级 / 逐力值等级）—— 行为积分 + 悲观锁防刷

**业务背景**：社区需要两套激励：面向普通用户的活跃等级（逐友/逐日）与面向创作者的内容质量等级
（逐力值），两套等级驱动不同权益。

**技术实现**（`content/.../level/`）：
- **统一表 `ap_user_level`**（唯一索引 uk_user_id）：
  `daily_score/daily_level`（逐友=逐日）+ `power_value/power_level`（逐力值）；
- **积分来源**（`LevelScoreConstants`，经行为事件总线 `LevelScoreProcessor` order=1）：
  - 操作用户获逐日分：点赞1/评论2/发布8/关注4/分享3/签到2/登录2…… **每日上限 200 分 + 各行为
    每日次数上限**；
  - **支付行为金额即经验**（`recordPaymentAction`，支付回调联动）；
  - 目标用户（作者/被关注者）获逐力值：被赞/被评/被藏=1、被阅读=阅读量/100、被发布=10（每日限 2 次）；
  - **AI 质量分联动**：审核链 `PowerBonusProcessor` 质量分 ≥80 → 逐力值 +3，≥60 → +1；
- **防刷核心**：`lockUserLevel` 用 **`selectByUserIdForUpdate` 悲观行锁**串行化「校验 + 落库」，
  防 TOCTOU（先查后改导致的超上限/重复签到）；
- **等级阈值**（`ap_level_config`）：逐友 8 级 `0/15/30/150/500/2000/7000/25000`
  （「预备掘友」→「终身掘友」）；逐力 8 级 `0/40/280/1800/5500/28000/75000/140000`
  （「新锐创作者」→「传奇创作者」）；
- **权益与权限**：`ap_level_privilege` 逐力 LV4 自动推荐/流量包、LV5 优秀创作者、LV7 创作小册、
  LV8 社区共建者；`LevelPermissionServiceImpl.updateUserPermissions` 跨级对比 old/new
  **授权/回收权限**（`expired_at` 软回收）；`assignBasicPermissions` 7 项基础权限；
- **每日任务**：`ap_behavior_config` + `ap_user_daily_progress`（唯一 uk_user_date_action）驱动，
  `LevelTaskProgressBuilder` 组装进度。

**设计亮点 / 可追问**：
1. 为什么用悲观锁而不是乐观锁/分布式锁？（等级积分「校验+累加+升级+授权」是串行化要求高的
   短事务，行锁简单可靠；分布式锁需考虑锁粒度与 Redis 故障）
2. 每日上限 200 分解决了什么问题？（防脚本刷分——配合行为次数上限双保险）
3. 双等级为什么分开？（活跃度与内容质量是两种不同的激励目标，混在一起会误导创作者刷活跃）
4. 权限为什么要「授权/回收」？（等级可降级，权益必须可撤销，用过期时间软回收避免硬删权限记录）

---

## 要点 9：资产钱包与互动激励 —— 签到/抽奖/兑换三层防超卖

**业务背景**：平台虚拟货币「矿石」驱动签到、抽奖、福利兑换，库存类业务最大的风险是超卖与并发重复领取。

**技术实现**（reward 服务）：
- **钱包**：`UserAssets`（ore_balance 矿石 / frozen_ore 冻结 / lucky_value）；
  `addOreBalance` / `deductOreBalance` 用**条件扣减 SQL `WHERE ore_balance >= #{amount}`**，
  返回影响行数判断余额不足 → **原子防并发超扣/负余额**；
  - 服务间接口用 `AppThreadLocalUtil.getUser()==null` 判断「内部直连」，**拒绝外部用户防越权资损**；
- **签到**（`CheckinServiceImpl`）：Redis 分布式锁 `sign:lock:{userId}`（3s 过期）防重复签到 +
  **`sign_date` 唯一索引 + DuplicateKeyException** 双保险；补签重算 45 天窗口内连续奖励；
- **抽奖**（`LotteryServiceImpl.draw`，@Transactional）：矿石原子扣 → 中奖入账：现金矿原子加，
  道具走 `UserVirtualAssetMapper.credit`（`INSERT ... ON DUPLICATE KEY UPDATE quantity=quantity+count`）；
- **福利兑换**（`WelfareServiceImpl.exchange`）**三层防超卖**：
  1. Redis 预扣 `DECR welfare:stock:{id}`（负数回滚）；
  2. DB 乐观锁 `UPDATE ... WHERE stock>0`；
  3. 原子扣矿石；
  任一层失败 → 抛异常触发本地事务回滚 + catch 回滚 Redis 预扣；
- 无 Seata 分布式事务（未使用 @GlobalTransactional），靠 **Redis/DB 原子操作 + 本地事务 + 失败补偿**。

**设计亮点 / 可追问**：
1. 条件扣减 `WHERE balance >= amount` 为什么能防超扣？（UPDATE 是行级原子操作，返回影响行数
   天然判断余额，避免「先查后扣」的竞态窗口）
2. 三层防超卖每层防什么？（Redis 预扣防并发击穿 DB；DB 乐观锁兜底真实库存；原子扣矿防用户
   余额被重复扣。Redis 与 DB 不一致靠回滚补偿）
3. 签到为什么「锁 + 唯一索引」双保险？（Redis 锁防并发请求，唯一索引是数据库层最终防线，
   防锁过期/Redis 故障后的重复签到）
4. 为什么不用 Seata？（当前业务粒度原子操作可覆盖，引入分布式事务增加复杂度与性能成本——
   体现「能不用的中间件不用」的工程判断）

---

## 要点 10：支付闭环 —— 支付宝沙箱三重防护幂等 + 月度结算分成

**业务背景**：课程/专栏付费与打赏是平台核心变现路径，支付回调是安全与一致性的重灾区。

**技术实现**（content 服务 pay/order/settlement 包）：
- **下单**（`OrderServiceImpl.createOrder` @Transactional）：查课程真实价 → 折扣计算
  （① 抽奖 5 折券：Feign `IRewardClient.getVirtualAssetHold` 校验持有量；② 折扣码
  `DiscountService.validateDiscount`，FIXED/PERCENTAGE 两种）→ 订单号 `yyyyMMddHHmmss+UUID6位`
  → 落库 PENDING；`getOrderStatus` 做**订单归属校验**防越权；
- **发起支付**：`AlipayServiceImpl` `DefaultAlipayClient`(RSA2) + `AlipayTradePagePayRequest`
  电脑网站支付，`pageExecute` 返回自动提交表单；**凭据缺失时回退本地模拟支付页**（JS fetch 直打
  notify 接口，便于开发联调）；
- **回调三重防护**（`PayController.payNotify`）：
  1. **验签**：`AlipaySignature.rsaCheckV1(params, alipayPublicKey, "UTF-8", "RSA2")`，公钥未配置
     **fail-closed 拒绝**；
  2. **金额比对**：`verifyAmount` 以**服务端订单金额为准**比对回调 total_amount，防篡改；
  3. **条件更新幂等**：`LambdaUpdateWrapper` 条件更新 `status = PENDING → PAID`
     （WHERE orderNo + status），**影响行数 == 1 才继续发货** → 支付宝重复通知/并发回调天然幂等；
- **发货**（同一本地事务）：原子核销折扣码（`incrementUsedCountAtomic` 防超卖）→ Feign 核销 5 折券
  → 课程 studyCount/salesCount/totalRevenue +1 → 写 `ApUserCourse` 开通权限（accessType=1 购买）
  → `PaymentRewardServiceImpl.onCoursePurchaseSuccess` 联动加逐日等级经验 + 发系统通知
  （try-catch 兜底不影响主流程）；
- **月度结算**（`SettlementServiceImpl.executeMonthlySettlement` @Transactional）：先查幂等（存在跳过）
  → 查 PAID 订单按 `DATE_FORMAT(pay_time,'%Y-%m')` 分组 → **按课程取真实 authorId（不是买家）**
  → 分成 `AUTHOR_SHARE_RATE=0.7 / PLATFORM_SHARE_RATE=0.3`（round HALF_UP）
  → 唯一索引 `uk_author_course_month` + 捕获 `DuplicateKeyException` 做并发幂等；
- **打赏**（`TipServiceImpl`）：禁打赏自己文章、金额 1~10000；同样条件更新幂等 → 写流水
  （Feign 拉打赏人昵称头像）→ 文章 `tip_count+1 / tip_amount+` 原子 setSql → 联动等级经验 + 通知；
  金额入平台账户，作者凭流水结算（无自动分账）。

**设计亮点 / 可追问**：
1. 支付回调幂等为什么用「条件更新影响行数」而不是查状态判断？（查状态再更新有竞态窗口：
   两个回调同时进来都查到 PENDING 都去发货；条件更新只有一个能成功）
2. 为什么核销折扣码/券放在支付成功回调而不是下单时？（防止用户下单占名额不支付）
3. 金额以服务端为准校验解决了什么？（防篡改订单金额/中间人改 total_amount）
4. 结算为什么要从课程表取 authorId？（订单表存的是买家 buyerId，若直接用 buyerId 当作者会
   把钱结算给买家——这是实际踩过的坑，代码特意修正）
5. 收到重复回调 / 结算重复执行怎么办？（条件更新 / 唯一索引 + DuplicateKeyException，两种幂等姿势）

---

## 要点 11：站内信 + IM 私信（WebSocket STOMP + 发消息状态机防骚扰）

**业务背景**：平台需要两类消息：异步站内信（点赞/评论/关注/审核结果通知）与实时 IM 私信。

**技术实现**（notification 服务）：
- **站内信**：
  - 类型 `comment=1 / digg=2 / follow=3 / system=4`，content 存 JSON
    （trigger_user/action_type/target_title）；
  - **未读数方案（亮点）**：Redis `notif:unread:{userId}` 存**整包 JSON**（total + 各类型），
    **DB 为唯一事实源**（`countUnreadGroupByType` 保证 total=各类型之和）；新增通知后**整包缓存失效**
    （`evictUnreadCache`，5min TTL）→ 避免「局部减一」与 DB 不一致；
  - 已读：markAllRead / markTypeRead（先 count 再 update 再失效缓存）；列表**游标分页**（cursor=last id）；
  - **触发链路无 MQ**：Feign 同步调用 + try-catch 降级（行为后置处理器 `NotificationProcessor` order=4、
    审核结果、支付成功等触发），`INotificationClient` 带 FallbackFactory；
- **IM 私信**（后端完整，前端未接入——诚实披露）：
  - **STOMP + SockJS**，端点 `/ws`，`/topic /queue` broker、`/app` 发送、`/user` 私有前缀；
  - **握手鉴权**：`AuthHandshakeInterceptor` 用 `AppJwtUtil` 验 JWT 写 userId；
    `UserInterceptor` 在 CONNECT 帧注入 `StompPrincipal`——**不信任客户端裸 userId，防伪装订阅他人**；
  - `SessionManager`（ConcurrentHashMap）维护在线表 + 连接/断开事件登记；
  - 会话 `session_key=min_max` **唯一索引 + DuplicateKeyException 防并发建重**；
  - **发消息权限状态机 `ImStateMachine`（亮点）**：S3 对方回过消息 → 无限发；S0 对方关注你 →
    无限发；S1 未发过 → 可发 1 条；S2 已发 1 条待回复 → 拒绝（**不对称规则防骚扰**，关注关系
    服务不可用时降级走普通限制）；
  - 消息 ACK（`MESSAGE_RECEIVED`）、已读回执（`READ_RECEIPT`）、游标分页、markRead 按
    last_read_id 批量已读并清零会话未读（`im_sessions.user1_unread_count/user2_unread_count`）。

**设计亮点 / 可追问**：
1. 未读计数为什么整包缓存失效而不是 INCR/DECR？（INCR/DECR 与 DB 事实源不一致时难修复；
   整包重算简单可靠，TTL 5min 兜底）
2. 为什么用 STOMP 而不是原生 WebSocket？（STOMP 提供订阅/广播/用户私有队列语义，天然支持
   1 对 1 私信与鉴权注入点）
3. 如何防止用户伪造订阅别人频道？（UserInterceptor 用服务端解析出的 Principal 覆盖客户端
   声明的 userId）
4. 状态机为什么是不对称的？（关注你的人可以给你发第一条，你未回前对方不能再发——既保护用户
   不被骚扰，又保留陌生人破冰能力）

---

## 要点 12：高可用与可观测性 —— 自研双层限流 + 全链路监控 + OSS 直传 + ES 搜索

**业务背景**：对外接口需防刷防爆，生产环境需要可观测，文件上传要省服务器带宽，搜索要快。

### 12.1 自研双层限流（无 Sentinel，纯自研）
- **拦截器层**（`common/ratelimit/`）：单机内存令牌桶（简化版，**每分钟整桶重置**），
  登录/注册 10 次/分/IP、上传 20 次/分/IP；key=`ip:path`；超限 HTTP 429；
- **AOP 层**（`common/aspect/RateLimitAspect` + `@RateLimit` 注解 + `rate_limit_single.lua`，已核实）：
  - **Redis 滑动窗口**：Lua 单次原子完成「回收过期令牌 → 查配额 → 扣减 → 设 TTL」；
  - **Hash Tag `{类:方法}`** 把多维 key 放同一 slot 保证跨维度原子；
  - `@PostConstruct` 预加载 SHA，捕获 NOSCRIPT 自动重载（Redis 重启兼容）；
  - `@Repeatable` 支持 **GLOBAL/IP/USER 多维独立限流**，可配 fallback 降级方法；
  - 超限抛 `RateLimitExceededException` → 全局异常返回 **code 8001**；
- **实际阈值**：登录 GLOBAL 30/IP 5 每分；刷新 GLOBAL 100/IP 10；OSS 签名 GLOBAL 200/IP 20；
  文章流 GLOBAL 500/IP 30；搜索 GLOBAL 300/IP 20；
- **已知坑（面试主动说）**：拦截器多实例下不准（各实例独立计数）、ConcurrentHashMap 桶无清理
  有内存泄漏风险、`X-Forwarded-For` 可伪造绕过 IP 限流（应结合网关真实来源 IP）。

### 12.2 全链路监控与日志
- **链路追踪**：Micrometer Tracing(Brave) + Zipkin，采样率 1.0（注释建议生产 0.1~0.5），
  端点 `http://localhost:9411/api/v2/spans`；Feign 接入 `FeignObservationConfiguration` 全链路；
- **指标**：`/actuator/prometheus`（JVM/HTTP/Feign/Redis/DB 连接池 + 自定义 AI 审核指标）；
- **日志**：logback JSON（LogstashEncoder）+ **traceId/spanId 注入 MDC** + service 标识字段，
  异步 AsyncAppender(512)；Promtail 采集 `e:/logs/leadnews.*.log` → Loki，traceId 留正文
  **避免高基数标签**；Grafana 观测面板（Prometheus + Loki 双数据源）；
- **质量门禁**：JaCoCo 覆盖率 —— search 服务 pom 有 **verify 阶段 LINE ≥ 0.80 门禁**，CI（.github）
  自托管 runner 跑 reward+content 的 verify 并上传报告；实测覆盖率 content 69.3% / user 90.7% /
  search 84.1% / gateway 85.4%。

### 12.3 OSS Web 直传
- `OssController.post_signature`（GET `/api/v1/media/oss/post_signature`）：OSS SDK `generatePostPolicy`
  + 条件 **content-length-range 0~1GB** + **key 以 material/ 开头** → base64 policy →
  `calculatePostSignature` → 返回 `ossAccessKeyId/policy/signature/dir/host` 供**前端直传**
  （服务端不经过文件流，省带宽）；`presigned_url` 生成 1h 预签名下载 URL；
- 配置走环境变量（AK/SK 不硬编码），bucket `zhuri-leadnews`，签名有效期 300s。

### 12.4 ES 搜索
- `spring-data-elasticsearch`，索引 `app_info_article`，**title/content 用 ik_max_word 分词**；
- `ArticleSearchServiceImpl.search`：NativeQuery bool → query_string（title+content，默认 OR）→
  publishTime 倒序 → **title 高亮**（红色 font）；
- **ES 数据同步 = 本地消息表 + 20s 定时补偿（非 MQ）**：发布 → @Async 构建 HTML →
  Feign `syncArticle` 同步；失败置 esStatus=1 → `ApArticleEventServiceImpl.processEvent`
  @Scheduled 20s 重试（间隔 ≥5s，超 maxRetryCount 记死信）；根目录另有 `reindex_es_articles.cjs`
  全量重建脚本；
- 搜索历史 MongoDB、联想词本地表；统一入口按 idType 分发（文章/沸点→ES，课程/标签→Feign
  content，用户→Feign user）。

**设计亮点 / 可追问**：
1. 双层限流各解决什么问题？（拦截器单机内存零延迟兜底；Redis Lua 分布式精确，多实例共享配额）
2. Lua 为什么能保证原子？（单脚本单次执行、Redis 单线程；Hash Tag 保证多 key 同 slot 才能
   eval 原子——这是分布式限流的常见坑）
3. ES 同步为什么不用 MQ 而用本地消息表？（保证与 DB 同事务可补偿，避免「DB 已更新但 MQ 投递
   失败且无补偿」；定时扫表重试 + 死信标记）
4. OSS 直传为什么是「签名 + 前端直传」？（服务端不转发文件流，降低带宽与延迟；签名限定
   大小/前缀防止乱传）

---

## 附录 A：面试讲述 STAR 建议（10 分钟版）

**开头（30s）**：项目是什么、几个人做、你负责什么模块、技术栈一句话。
**主体（6-7min）**：挑 3 个最硬核的亮点按「背景 → 方案 → 难点 → 结果/反思」讲：
1. AI 审核（要点 4+5）——最能体现深度；
2. 支付幂等（要点 10）——体现严谨性；
3. 双 token / 事件总线最终一致性（要点 2+6）——体现系统设计；
其余（等级/限流/监控/推荐）放问答环节。
**结尾（1min）**：项目收获 + 一个你主动承认的不足与改进方案（见附录 C）。

## 附录 B：高频追问速查表

| 追问 | 一句话回答 |
|---|---|
| 为什么用双 token？ | 短 token 高频校验、长 token 低频刷新可吊销 |
| JWT 能吊销吗？ | access 不能（1h 内有效），refresh 存 Redis 可删 |
| 幂等怎么做？ | 条件 UPDATE 影响行数 / 唯一索引 + DuplicateKeyException |
| 分布式事务？ | 未用 Seata，原子操作 + 本地事务 + 补偿 |
| 消息队列？ | Redisson 延迟队列；ES/通知用本地消息表 + 定时补偿 |
| 限流算法？ | 拦截器令牌桶(简化) + Redis Lua 滑动窗口 |
| 推荐算法？ | 规则加权(log 归一化) + 配额贪心，非协同过滤 |
| 大模型怎么防注入？ | 净化 + UUID 边界 + 指令声明 + 输出护栏，fail-closed |
| 缓存与 DB 一致性？ | 未读计数「DB 事实源 + 整包失效重算」 |

## 附录 C：诚实披露清单（面试主动说，加分不扣分）

1. 无 Kafka/RabbitMQ 实际链路（延迟队列用 Redisson，可靠投递用本地消息表）；
2. 推荐未做协同过滤/向量召回（当前规则加权，注明 pgvector 已用于查重，可平滑演进）；
3. access token 无黑名单/踢人下线（1h 过期兜底，可优化为 Redis 版本号互斥）；
4. 限流拦截器层多实例不准、IP 可伪造（有明确的演进方向）；
5. 热点榜未用 Redis ZSet（SQL 直查，数据量上来后可平滑替换）；
6. IM 后端完整但前端未接入（如实说明边界）；
7. 微信扫码登录消费端未完成（服务端验签/发码已完成）。

---

*文档基于代码静态分析生成，关键路径均可直接到工程中核对；如有代码演进，请以最新代码为准。*
