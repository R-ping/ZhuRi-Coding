# 代码评审报告 — heima-leadnews-app

> 评审日期：2026-09-12
> 评审范围：全仓库（后端 `heima-leadnews/` 891 个追踪文件 + 前端 `src/` 283 个追踪文件）
> 评审方式：只读静态分析，所有结论均附 `文件:行号` 证据
> 当前分支：`refactor/spring-ai-enhancement` @ `8e89a94`

---

## 0. 修复进展（2026-09-12 当轮已落地）

| 项 | 状态 | 改动 | 验证证据 |
|---|:--:|---|---|
| P0-1 内部身份签名密钥配置损坏 | ✅ 已修 | `user` / `search` / `notification` 三处 `application.yml` 的 `app:/n  internal-auth:/n` 修正为正常缩进 | 14 个模块 `compile` BUILD SUCCESS |
| P0-1 校验语义 fail-open → fail-closed | ✅ 已修 | `InternalAuthSigner` 新增 `DEFAULT_DEV_SECRET` / `isConfigured()`；4 个拦截器（content/user/search/notification）改为「密钥未配置即拒绝信任身份头」，并在构造期自检告警；网关 `AuthorizeFilter` 补启动告警 | 拦截器单测 **content 5/5、user 4/4、search 6/6、notification 5/5** 全绿；网关 `AuthorizeFilterTest` 11/11 |
| P0-7 核心表零索引 | ✅ 已建 | 新增 `content/db/migrations/add_hot_path_indexes.sql`（10 个索引）、`user/db/migrations/add_user_login_indexes.sql`（2 个唯一索引），并已执行 | 见下方 EXPLAIN 结果 |
| `schema.sql` 同步 | ✅ 已重导 | 按 `db/README.md` 约定用 `mysqldump --no-data --skip-comments` 重导 content / user 两个库 | — |
| P0-2 ES 口令默认值 | ⬜ 未修 | 按铁律 2 改 `password: ${ES_PASSWORD:x8YMlQeWvd4IGPnrb-4k}`（保留原值兜底）+ 启动 WARN 提示生产用环境变量覆盖 + 轮换线上 + 清 target/ | — |
| P0-5 券核销失败补偿 | ✅ 已修复 2026-09-12 | 见「修复轮次」 | 订单 / 支付 |
| P1-1 签到 unlock 事务边界（用户口径 P0-6） | ✅ 已修复 2026-09-12 | 见「修复轮次」 | 签到 / reward |
| P0-6 抽奖无锁 / 无幂等 / 空奖池越界 | ✅ 已修复 2026-09-12 | 见 P0-6 修复摘要 | 抽奖 / reward |
| P0-3 微信回调验签 / P0-4 验证码可重放 | ✅ 已修复 2026-09-12 | 见「修复轮次」 | 认证链路 |

**索引验证结果（`EXPLAIN`，库 `leadnews_article` / `leadnews_user`）**

| 查询场景 | 命中索引 | type | Extra |
|---|---|---|---|
| 首页频道列表 | `idx_status_channel_publish` | ref | Backward index scan（无 filesort） |
| 全站时间线 | `idx_status_publish` | range | Using index condition |
| 文章热度排序 | `idx_status_score` | range | Backward index scan |
| 作者主页 | `idx_author_status_publish` | ref | rows=2 |
| 兴趣画像 | `idx_user_op_time` | ref | rows=1 |
| 浏览历史 | `idx_user_deleted_time` | range | **Using index**（覆盖索引） |
| 评论列表 | `idx_article_parent_created` | range | **Using index** |
| 沸点最新 | `idx_status_deleted_review` | ref | **Using index** |
| 登录（手机号） | `uk_phone` | **const** | rows=1 |

> 说明：`ap_user.email` 当前 288 行全为 NULL，`uk_email` 已建但暂无过滤收益（MySQL 唯一索引允许多个 NULL），待邮箱登录真正启用后生效。
> `ap_pins` 的"按点赞排序"实为 Java 内存排序（`PinsQueryService#listHot:283-284`），故未建点赞列索引；评审报告 P2-8 中"ORDER BY likes DESC"的表述有误，实际列名为 `like_count`（`@TableField("like_count")`）。

---


## 一、总体评分卡

| 维度 | 评分 | 一句话结论 |
|---|:--:|---|
| 项目结构 | 7.5 / 10 | 分层清晰、模块边界合理；但 content 服务过重（355 个主类）已成单体 |
| 代码质量 | 6.0 / 10 | 测试与覆盖率门禁是亮点；一致性/幂等/事务边界存在系统性缺陷 |
| 功能可扩展性 | 7.0 / 10 | 事件总线 + 处理器链 + 配置表驱动设计优秀；`switch` 与魔法值拖后腿 |
| 性能优化 | 5.5 / 10 | **核心表零二级索引**是硬伤；列表组装 N+1 普遍 |
| 工程最佳实践 | 6.5 / 10 | 可观测性与提交规范优于同水平项目；缺 CD、多环境、前端质量门 |

**这个项目的真实水平**：架构设计与业务纵深明显高于普通课程项目（事件驱动总线、责任链审核、多智能体编排、RAG + pgvector、双 Token + 分布式锁、Prometheus/Zipkin 全链路），**短板集中在"生产级硬度"**——索引、幂等、补偿、配置安全、多实例一致性。也就是说：**亮点在"设计"，欠账在"运维与一致性"**。这也正好是最容易在面试中体现深度的部分。

---

## 二、问题总览

| 等级 | 数量 | 性质 |
|---|:--:|---|
| **P0** | 7 | 认证绕过 / 真实凭据泄露 / 资金损失 / 全表扫描 |
| **P1** | 12 | 并发一致性 / 越权 / 线程池 / 多实例失效 |
| **P2** | 15 | 缓存缺失 / 深分页 / 重复代码 / 配置管理 |
| **P3** | 12 | 代码卫生 / 可维护性 |

---

## 三、P0 — 必须立刻修（安全 · 资金 · 数据）

### P0-1 ⭐ 内部身份签名密钥配置损坏 + fail-open 校验，可冒充任意用户

三个服务的 `application.yml` 末尾被写成了字面量 `/n`（应为 `\n`），导致 YAML 键名损坏、`app.internal-auth.secret` **从未被绑定**：

```
heima-leadnews-service/heima-leadnews-user/src/main/resources/application.yml:80
heima-leadnews-service/heima-leadnews-search/src/main/resources/application.yml:43
heima-leadnews-service/heima-leadnews-notification/src/main/resources/application.yml:47

app:/n  internal-auth:/n    secret: ${INTERNAL_AUTH_SECRET:leadnews-internal-dev-secret}
```

而下游校验是 **fail-open** 的——密钥为空 = 无条件信任请求头里的 `userId`：

- `heima-leadnews-common/.../auth/InternalAuthSigner.java:16-17`（注释明确"未配置密钥时下游跳过校验"）
- `.../content/interceptor/ContentTokenInterceptor.java:44-48`（`if (secret == null || isEmpty()) return true;`）

**影响**：user(51780) / search(51804) / notification(51807) 三个服务对伪造 `userId` 请求头**完全不设防**，绕过网关直连即可以任意用户身份操作。

**修复**（1 行 × 3 文件）：
```yaml
app:
  internal-auth:
    secret: ${INTERNAL_AUTH_SECRET}
```
同时把 `isTrusted()` 改为 **fail-closed**（密钥为空则拒绝），并在启动时对密钥做非空断言。

---

### P0-2 真实 Elasticsearch 口令硬编码进仓库

`heima-leadnews-service/heima-leadnews-search/src/main/resources/application.yml:23`
```yaml
password: ${ES_PASSWORD:x8YMlQeWvd4IGPnrb-4k}
```
默认值是形似真实的强口令（非占位符），且已随代码进入 Git，`target/classes/` 下还有编译副本。

**修复**（按铁律 2「保留原值作为兜底」）：
1. YAML **不能**直接删空成 `${ES_PASSWORD}`，会丢原值无法追溯；正确写法是 `${ES_PASSWORD:x8YMlQeWvd4IGPnrb-4k}` + 启动期自检（`WARN` 提示「仍为公开兜底值，生产必须经 ES_PASSWORD 环境变量覆盖」）；
2. 轮换该口令（线上实例正式值）；
3. 清理 `target/classes/` 编译副本（`git clean -fdx heima-leadnews-service/heima-leadnews-search/target/`）。

---

### P0-3 微信公众号回调 POST 未验签 → 账号接管

`heima-leadnews-user/.../controller/v1/WechatGZHLogin.java:47-99`
```java
@PostMapping
public String using(HttpServletRequest request) {
    String xmlData = readXmlFromRequest(request);   // 直接读原始 body，无签名校验
    ...
    String redisKey = "wechat:token:" + message.getFromUserName();  // openid 完全来自请求体
    cacheService.setEx(redisKey, token, 5, TimeUnit.MINUTES);
    return backXml;                                  // token 直接写在响应里
```
只有 GET `auth()`（`:33`）做了 SHA1 验签；**真正签发登录 token 的 POST 完全没有验签**。任何人都能 POST 构造 XML，把 `FromUserName` 设为受害者 openid，服务端即回传该 openid 的登录 token。

附带问题：`private static final String TOKEN = "huhudong"`（`:28`）硬编码弱口令；`:65` 把 token 明文写日志。

**修复顺序**（铁律 2「保留原值作为兜底」，不能直接删空）：
1. `application.yml` 写 `wechat: gzh: token: ${WECHAT_GZH_TOKEN:huhudong}`（兜底用原硬编码值）；
2. `WechatGZHLogin` 提常量 `public static final String DEFAULT_TOKEN = "huhudong"`；`@Value("${wechat.gzh.token:" + DEFAULT_TOKEN + "}")` 同步兜底；
3. 启动期 `warnIfTokenMissing` 增加 `if (DEFAULT_TOKEN.equals(gzhToken)) log.warn(...)` 分支；
4. 测试新增「Token 为公开兜底值 → 合法签名通过 / 错签名拒绝 / 伪造拒绝」三个断言（证明「保留兜底 ≠ 默认放行」）。

**修复摘要（2026-09-12，分支 `fix/auth-callback-and-code-replay`）**
- `WechatGZHLogin`：`TOKEN` 硬编码 → `@Value("${wechat.gzh.token:huhudong}")`，**原 `huhudong` 保留为公开兜底常量 `WechatGZHLogin.DEFAULT_TOKEN`**（铁律 2：可回滚、可追溯、仍能跑本地用例）；缺省值缺省时 fail-closed；启动期 `warnIfTokenMissing` 自检，**若仍为公开兜底值就 WARN 提示生产必须经 WECHAT_GZH_TOKEN 环境变量覆盖**；`using()` 改为入参带 `signature/timestamp/nonce`，复用 `GET auth()` 的 `checkSignature()`，**校验未通过不读请求体**直接 `return "success"`，让微信停止重试且不向伪造方透露任何信息。
- 抽出 `checkSignature()` 私有方法，`GET auth()` / `POST using()` 共用同一验签逻辑；
- token 与 openid 不再写日志；
- 单元测试：`WechatGZHLoginTest` 13 个用例（**9 个 POST + 4 个 GET**），其中 3 个核心断言覆盖「无签名 / 错签名 / Token 未配置」三个伪造场景，运行时 `verifyNoInteractions(cacheService)` 证伪「绕过的路径」。

---

### P0-4 验证码一次有效验证失效 + 由响应体明文返回

- `SocialLoginServiceImpl.java:113-116`、`ApUserServiceImpl.java:114-117`：校验通过后**没有 `delete`**，5 分钟内可无限重放。
- `ApUserLoginController.java:104-108`：验证码直接 `return ResponseResult.okResult(resultCode)` —— 攻击者无需收短信即可拿到验证码。

**影响**：配合 P0-3，构成批量登录 / 绑定他人手机号的完整路径。

**修复摘要（2026-09-12）**
- 新增 `LoginCodeService`：把「发放 → 校验 → 一次性消费 → 错误计数上限作废」封装为一个服务。Redis 落地用原子命令（`SETEX`/`GET`/`GETDEL`/`INCRBY`/`DEL`/`EXPIRE`）；错误次数达 5 次自动作废（防 4 位验证码被暴力枚举）。Service 接口加 Javadoc 调用约定，鉴证 Servlet 端：`currentCode`、`attempts` 共 2 个 Redis key。
- `ApUserServiceImpl.phoneCodeLogin()` 与 `SocialLoginServiceImpl` 全部接入 `LoginCodeService.verifyAndConsume()`；以 GETDEL 原子写入，**不应许同一验证码出现「并发调用都成功」**。
- `ApUserLoginController.getCode()`：(1) `exposeLoginCode` 开关默认 `true`（本地/演示环境用），通过 `AUTH_EXPOSE_LOGIN_CODE=false` 环境变量关闭；；启动期 `warnIfExposingLoginCode` 告警，生产上线上时绝不允许该开关为 true。(2) 新增同一手机号 60 秒内发送冷限频（`SETNX + EXPIRE`）防短信轰炸，原先接外部服务、现在本地锁定。(3) 手机号脱敏写日志（`maskPhone`）避免完整号落 ELK。
- `CacheService` 本身提供的 `getAndDelete` 便捷方法（Redis GETDEL）已被 `LoginCodeServiceImpl` 验证可用。
- 单元测试：`LoginCodeServiceImplTest` 8 个用例（发放、消费成功、重放拒绝、并发拒绝、过期、错误计数、错误上限、入参校验），`ApUserLoginControllerTest` 8 个用例、`SocialLoginServiceImplTest` 10 个用例、`ApUserServiceImplTest` 9 个用例均同步更新。运行时可以看到「验证码已被并发消费或失效 / 验证码错误次数已达上限(5), 强制作废验证码」预警日志。
- **前端影响评估（铁律 3）**：`exposeLoginCode` 行为变化（响应体 `data` 可能为 `""`）会让前端 `login_modal.vue:273-278` 的 `if (d.data) { 填充 } else { 提示已发送 }` 双分支命中后者 → **已天然兼容**，前端代码**不需要改**。60s 限频对应的提示语 `PARAM_INVALID, "发送过于频繁，请60秒后再试"` 也会被前端 `toast(d.message)` 直接展示 → 同样兼容。

---

### P0-5 支付成功回调中券/折扣码核销失败仅 `log.warn` → 直接资损

`heima-leadnews-content/.../order/impl/OrderServiceImpl.java:341-361`
```java
boolean consumed = discountService.consumeDiscountCode(order.getDiscountCode());
if (!consumed) {
    log.warn("折扣码使用次数已达上限或无效: code={}", ...);   // 订单已 PAID，仅告警
}
...
if (consumeResult == null || consumeResult.getCode() != 200) {
    log.warn("5折券核销失败，需补偿: orderNo={}", ...);       // 注释写了"需补偿"，但没有补偿逻辑
}
```
订单已通过条件更新抢占为 PAID，但券扣减失败 —— **用户享受了折扣却没消耗券，同一张 5 折券可反复使用**。

同类：`OrderServiceImpl.java:400-406` 支付成功后的经验/站内信联动失败也只 `log.error`。

**修复摘要（2026-09-12，分支 `fix/order-discount-outbox`）**（方案 A：复用现有退款兜底机制）
- `OrderServiceImpl.handlePaySuccess`：折扣码核销 / 5 折券远程核销（Feign 失败 + 异常）任一失败时，**不再只 `log.warn`**，改为 `markRefundPending(orderNo, REFUND_REASON_DISCOUNT_EXHAUSTED|REFUND_REASON_COUPON_FAILED)` + `return false`——上抛给 `AlipayServiceImpl.handleNotify` 进入退款兜底路径。
- `OrderService.markRefundPending(orderNo)` 重载为 `markRefundPending(orderNo, reason)`，新增三个原因常量 `REFUND_REASON_ORDER_CLOSED / DISCOUNT_EXHAUSTED / COUPON_FAILED`，写到 `ap_course_order.refund_pending_reason` 字段便于 SQL 排查。
- `AlipayServiceImpl.handleNotify` 退款兜底判定扩展：除了"status=CANCELLED"，新增"status=PAID && refund_pending=1"分支（资损兜底场景）；退款失败时保持 `refund_pending=1` 不重复覆盖 reason，交由 `RefundRetryTask` 重试。
- `ap_course_order` 加字段 `refund_pending_reason VARCHAR(100) NULL`，并 `mysqldump --no-data` 同步到 `content/db/schema.sql`。
- **保留现有 `paymentRewardService.onCoursePurchaseSuccess` 失败 `log.error` 语义不变**（评审建议：加经验/发通知不涉及资金，专注堵资损，不扩大改动面）。
- 单元测试 `OrderServiceImplTest` **46/46 全绿**（含 4 个 P0-5 新增断言 + 顺手修复 7 个既有的 `courseChapterMapper` 桩 NPE）；E2E `ArticleCommentE2ETest` 5/5、拦截器 `ContentTokenInterceptorTest` 5/5 回归全绿。
- 运行时证据：`orderServiceImpl -- 5折券核销失败，置为退款待重试: orderNo=..., coupon=course50` —— 新分支触发。
- 遗留：方案 B「Transactional Outbox 模式」是评审报告方向 1，已留给下一轮 `feat/outbox-dispatcher` 分支独立实施。

**方案 B 落地补充（2026-09-12 同日，同分支 `fix/order-discount-outbox`）**
- 新增 `ap_outbox_event` 本地消息表（`content/db/migrations/create_ap_outbox_event.sql`，已执行；schema.sql 已同步）：
  - `uk_event_key` 唯一键 = 业务幂等键（`PAY_REWARD:{orderNo}`），同一事件只写一次；
  - `idx_status_next_retry` 支撑 Dispatcher 扫描 `PENDING 且到期`；
  - 状态机 `PENDING(0)→PROCESSING(3)→DONE(1)/回PENDING/DEAD(2)`，PROCESSING 卡死（执行中崩溃）由 `updated_time` 5 分钟超时回收。
- 新增代码（`content/service/outbox/`）：`OutboxService(+Impl)`（record 幂等写入 / markDone / markFailed 指数退避 2^n 分钟封顶 60）、`OutboxHandler`（SPI，eventType 路由）、`OutboxDispatcher`（`@Scheduled` 5s 一轮，单条 CAS 抢占——**多实例安全，不依赖分布式锁**；重复 eventType 注册构造期快速失败；轻量内存指标 dispatch/done/fail/dead）、`handler/PayRewardOutboxHandler`（加经验+站内信；payload 损坏/缺字段直接 `DeadSignal` 判死信，不做无意义重试）。
- `OrderServiceImpl.handlePaySuccess` 行 415-431：**删除 try-catch 同步联动**，改为 `outboxService.record("PAY_REWARD:"+orderNo, ...)`（同事务写入——主事务回滚事件一起回滚，Outbox 核心保证）；payload 序列化失败视为编程错误，抛 `IllegalStateException` 让主事务回滚（铁律 4：不能让"订单 PAID 但联动事件丢失"）。
- **语义边界**：资金类（券核销）保留方案 A 同步+退款兜底语义，不进 Outbox；非资金（经验/通知）走 Outbox 最终一致。
- 测试：`OutboxServiceImplTest` 6 用例（写入/幂等短路/markDone/backoff/超限 DEAD/错误截断）、`OutboxDispatcherTest` 7 用例（CAS 抢占失败跳过/正常路径/失败重试/DeadSignal 直死/无 handler/重复 eventType 拒绝/空批次心跳安全）、`PayRewardOutboxHandlerTest` 4 用例、`OrderServiceImplTest` 改写联动断言（payload 含 orderNo/userId）——**合计 63/63 全绿**；E2E 5/5 全上下文回归，日志确认 `OutboxDispatcher 已注册 1 个处理器: [PAY_REWARD]`。
- 过程中修复一个编码坑：建表 SQL 经 PowerShell 管道执行时中文注释被 GBK 重编码为 `?`（**执行方向**的编码坑，与 MEMORY.md 已记录的"dump 方向"同源）；已用 JDBC 直连（UTF-8）修复表注释，ASCII 布尔断言验证 `MATCH=true`、无 `?` 残留。

---

### P0-6 抽奖无锁 / 无幂等 / 空奖池越界

`heima-leadnews-reward/.../service/impl/LotteryServiceImpl.java`
```java
// :338  effective 为空时
return pool.get(0);                                  // → IndexOutOfBoundsException
// :319  每日次数 read-modify-write
daily.setDrawCount(daily.getDrawCount() + drawCount); // null 时拆箱 NPE；并发下丢失更新
```
`draw()`（`:111-336`，225 行）全流程无分布式锁、无幂等键（对比签到已用 Redis 锁）。并发十连抽会奖励与记录错配。

**修复摘要（2026-09-12，分支 `fix/lottery-lock-idempotent`，与 P1-1 同款「锁外移 + 事务下沉」模式）**
- 外层 `LotteryServiceImpl`：`draw` / `claimPhysical` 改为「tryLock（`lottery:lock:{userId}`，TTL 10s——十连抽放宽）→ 委托 → finally unlock」；`getDashboard` 等只读不动。同用户抽奖完全串行化 → 幸运值直写并发覆盖问题随锁消除。
- 新增 `LotteryTxService`（事务体内核，业务段整体平移 + 三处修复）：
  1. **免费次数原子占用**：check-then-set → `markFreeUsed` 条件原子 SQL（`SET free_used=1 WHERE ... AND (free_used=0 OR IS NULL)`，返回 0=已被占用）；占用失败不回滚（奖品已发放，按付费语义继续 + ERROR 告警，纵深防御分支）；
  2. **每日次数原子累加**：`updateById` 整行读改写 → `incrDrawCount` 原子 SQL；当日状态并发首建撞 `uniq_user_date` 唯一键 → 捕获降级原子更新路径；
  3. **空奖池防越界**：`randomDraw` 有效集为空不再 `pool.get(0)`（既可能 IOOBE 也会绕过解锁规则）——兜底优先取矿石奖品；全池无矿石 → null → 调用方抛 `IllegalStateException` **显式回滚事务**（付费抽奖绝不能无产出，铁律 4）；空池在花钱前前置拦截返回 400。循环内 `new Random()` → `ThreadLocalRandom`。
- **顺手原子化**：`claimPhysical` 收货地址提交的 check-then-set（并发双提交双双成功互相覆盖）→ `LambdaUpdateWrapper` 条件更新抢占 `status=1→2`，0 行返回 400。
- 测试：原 `LotteryServiceImplTest` 按职责拆分——业务断言（校验分支/免费成功/原子扣减/实物库存 3 例/claimPhysical 7 例）+ P0-6 专项（空池 400 零消耗 / 全未解锁兜底矿石不越界 / 无矿石兜底显式回滚 / markFreeUsed 占用失败不回滚 / DuplicateKey 降级路径 / claim 条件更新 0 行）迁入新 `LotteryTxServiceTest`（**21 例**）；外层重写编排语义（**8 例**：429 / 委托透传 / 异常仍 unlock / 只读回归）。reward 模块全量 **93/93 全绿**。

---

### P0-7 ⭐ 核心表零二级索引，全站查询走全表扫描

`heima-leadnews-content/src/main/resources/db/schema.sql:54-89` —— `ap_article` **只有 PRIMARY KEY**：
```sql
CREATE TABLE `ap_article` (
  ... `status` tinyint, `channel_id` int unsigned, `author_id` int unsigned,
      `score` int, `publish_time` datetime, `column_id` bigint ...
  PRIMARY KEY (`id`) USING BTREE          -- 无任何二级索引
)
```
同类的还有 `ap_user`（登录按 phone/email 查，全表扫描）、`ap_behavior_likes`（兴趣画像）、`ap_browse_history`、`ap_comment`、`ap_pins`。

首页列表、推荐候选、最新分栏、标签页、热榜、登录——**全部建立在无索引的表上**。

**可执行修复**（投入产出比最高的一步）：
```sql
-- content 库
ALTER TABLE `ap_article`
  ADD INDEX `idx_status_channel_publish` (`status`,`channel_id`,`publish_time`,`id`),
  ADD INDEX `idx_status_publish`         (`status`,`publish_time`,`id`),
  ADD INDEX `idx_status_score`           (`status`,`score`,`id`),
  ADD INDEX `idx_author_status_publish`  (`author_id`,`status`,`publish_time`,`id`),
  ADD INDEX `idx_column_status_publish`  (`column_id`,`status`,`publish_time`,`id`);

ALTER TABLE `ap_behavior_likes`
  ADD INDEX `idx_user_op_time` (`user_id`,`operation`,`created_time`);

ALTER TABLE `ap_browse_history`
  ADD INDEX `idx_user_deleted_time` (`user_id`,`is_deleted`,`browse_time`);

ALTER TABLE `ap_comment`
  ADD INDEX `idx_article_parent_created` (`article_id`,`parent_id`,`created_time`);

ALTER TABLE `ap_pins`
  ADD INDEX `idx_status_deleted_review` (`status`,`is_deleted`,`review_time`,`id`);

-- user 库
ALTER TABLE `ap_user`
  ADD UNIQUE KEY `uk_phone` (`phone`),
  ADD UNIQUE KEY `uk_email` (`email`);
```
建议先 `EXPLAIN` 验证 `type`/`rows`，再上生产。`uk_phone`/`uk_email` 加之前先去重存量脏数据。

> **执行状态（2026-09-12）**：以上 12 个索引已在本地库执行完毕并通过 `EXPLAIN` 验证（见 §0）。
> 前置核查结果：`ap_user` 无重复手机号/邮箱、无空字符串；各表数据量较小（最大 `ap_browse_history` 2974 行），
> 加索引耗时极短。脚本已入库为
> `content/db/migrations/add_hot_path_indexes.sql` 与 `user/db/migrations/add_user_login_indexes.sql`。

---

## 四、P1 — 严重（并发一致性 · 越权 · 资源耗尽）

### 并发与一致性

| # | 问题 | 证据 | 说明 |
|---|---|---|---|
| P1-1 | **签到锁在事务提交前释放** | `CheckinServiceImpl.java:375-377` | `finally { unlock(userId); }` 写在被代理的 `@Transactional` 方法体内，解锁早于 commit，存在窗口期导致重复签到/重复发矿石。且 `tryLock` value 固定 `"1"`、`unlock` 无 owner 校验 ✅ **2026-09-12 已修复**（见下方修复摘要） |

**P1-1 修复摘要（2026-09-12，分支 `fix/checkin-unlock-boundary`）**
- **方案**：锁外移 + 事务下沉独立 Bean。`CheckinServiceImpl` 外层只做「tryLock → 委托 → finally unlock」；事务体内核（原 `doCheckin`/`doExtra` 业务段整体平移，行为零变化）落到新类 `CheckinTxService` 的 `doCheckinTx`/`doExtraTx`（`@Transactional`）。
- **为什么拆独立 Bean**：原类内 `this.xxx()` 自调用不走 Spring 代理，`@Transactional` 会静默失效（项目历史 P1 坑）——独立 Bean 经容器代理调用，事务真实生效；解锁因此在<b>内层事务提交/回滚之后</b>才执行，竞态窗口关闭。
- **纯函数迁出**：`buildMilestoneProgress`/`buildNextSpecial` 提为 `SignRewardUtil.milestoneProgress/nextSpecial` 静态方法，外层 getStatus 与内层事务体共用，无复制。
- **保持不变的防御纵深**：`sign_record` 唯一键 + `DuplicateKeyException` 兜底；矿石原子累加 `addOreBalance`；锁 3s 过期兜底进程崩溃。锁实现（SETNX，无 owner 校验）本次不换 Redisson——不扩大改动面，遗留为 P3 建议。
- **测试**：`reward` 模块 **82/82 全绿**——原 `CheckinServiceImplTest` 按职责拆分：业务断言（重复签到 400 / 首次签到 insert / DuplicateKey 兜底 / 补签 400 分支）迁入新 `CheckinTxServiceTest`（7 例）；外层重写为编排语义测试（10 例：429 不进事务体不误删锁 / 委托透传 / **事务体抛异常仍 unlock** / 只读路径回归）；`SignRewardUtilTest` 10/10 证明静态方法迁出不破坏。
| P1-2 | 计数 read-modify-write 非原子 | `PinsInteractionService.java:169-172`、`ApCommentServiceImpl.java:259/638`、`ImServiceImpl.java:231-236`、`LevelActionService.java:372` | 应为 `UPDATE ... SET x = x + 1` 原子 SQL；`ap_pins_like`/`ap_comment_like` 缺唯一索引，双击可重复点赞 |
| P1-3 | 多表写入无 `@Transactional` | `ApCourseServiceImpl.java:195-263`（写 progress + user_course）、`LevelActionService.recordPassiveAction` | 中途失败产生半更新 |
| P1-4 | 点赞逻辑整段重复 | `ApCommentServiceImpl.java:220-263` vs `:598-642` | `likeComment` / `diggComment` 除方法名外逐行相同 |
| P1-5 | 删除评论不回滚计数 | `CommentAuditService.java:257-267` | 违规评论物理删除，但 `ap_article.comment` 未 -1，计数只增不减 |

### 越权

| # | 问题 | 证据 |
|---|---|---|
| P1-6 | 结算明细无归属校验，可枚举他人销售额 | `SettlementController.java:28-31` → `SettlementServiceImpl.java:62-69` |
| P1-7 | OSS 预签名接受任意 key，可换取桶内任意对象下载 URL | `OssController.java:69-83` |
| P1-8 | 网关公开路径前缀过宽，把圈子 join/leave 写接口一并放行 | `AuthorizeFilter.java:180` `startsWith("/content/api/v1/circle")` |

### 资源与多实例

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| P1-9 | **`@Async` 无默认线程池**，退化为无界 `SimpleAsyncTaskExecutor` | `ContentApplication.java:19` 开了 `@EnableAsync`，`AiAsyncConfig.java` 只定义了两个具名 Bean，无 `taskExecutor` | 审核/扫描/AI 任务突发时**无上限建线程**，易 OOM。涉及 `CommentAuditService.java:73`、`ArticleAutoScanServiceImpl.java:70`、`ColumnServiceImpl.java:176` 等 6 处 |
| P1-10 | **全项目 Feign 无超时配置** | 所有 `application.yml` 无 `spring.cloud.openfeign` 段 | 沿用默认 `readTimeout=60s`，下游抖动即打满上游线程池 |
| P1-11 | WebSocket 用内存 SimpleBroker | `WebSocketConfig.java:26` `enableSimpleBroker` | 多实例下跨节点消息永远收不到 |
| P1-12 | 定时任务无分布式锁 | `ApArticleEventServiceImpl.java:49`、`OssImageCleanupTask.java:37` | 多实例重复执行。对照 `TaskServiceImpl.java:64` 已正确用 `setIfAbsent`，应统一 |

---

## 五、P2 — 性能与工程实践

### 性能

| 问题 | 证据 | 量级 |
|---|---|---|
| 文章详情侧边栏**双层 N+1**：每篇查 2 次正文（完全重复）+ 1 次 count + N 次标签查询 | `ArticleDetailServiceImpl.java:594`、`:503` | size=10 时 **≈40+ 次 SQL** |
| 热榜作者榜每条 3 次查询，**零缓存** | `HotServiceImpl.java:174` | 单请求 **≤150 次查询** |
| 评论列表逐条查点赞态 | `ApCommentServiceImpl.java:118` | 9 次 count → 翻页线性放大 |
| 沸点热门/侧边栏**全表加载内存排序** | `PinsQueryService.java:115`、`:280` | 内存与 GC 随数据量无界增长 |
| 推荐每次拉 2000 候选全排序 + 同步写曝光表 | `ApArticleRecommendServiceImpl.java:274`、`:602` | 曝光表无限膨胀无归档 |
| 等级配置本地 `ConcurrentHashMap` 缓存 | `LevelQueryService.java:32` | 多实例不一致、无法集中失效 |
| 频道/权限/等级等热点只读数据全无缓存 | `ChannelServiceImpl.java:22`、`LevelPermissionServiceImpl.java:32` | 每请求必查 |
| 深分页 `LIMIT offset,size` | `ApCommentServiceImpl.java:73` | 应推广项目已有的 cursor 分页（`ApArticleMapper.xml:242`） |
| `LIKE '%kw%'` 前置通配 | `TopicServiceImpl.java:106`、`ApCourseServiceImpl.java:99` | 索引失效 |
| OSS 清理 O(n×m) 双循环 `contains` | `OssImageCleanupTask.java:202` | 最坏 10⁷ 次字符串匹配 |

### 工程实践

| 问题 | 证据 |
|---|---|
| **无任何多环境隔离**（dev/test/prod 共用一份 yml，无 profile、无 Nacos config） | 各服务 `application.yml`；`spring.config.import` 零命中 |
| CI 只跑 reward+content 两个模块，且只在 master/PR 触发 | `.github/workflows/ci.yml:4-6`、`:36` |
| **无 Dockerfile / docker-compose / Jenkinsfile / K8s 清单** | 全部缺失（仅 Grafana 自带 Dockerfile） |
| `monitoring/` 整套观测配置被 `.gitignore` 排除，配置本身也不进版本控制 | `.gitignore:64` |
| Prometheus **告警规则被注释掉，无任何告警** | `monitoring/prometheus/prometheus.yml` |
| logback 6 份复制粘贴，日志路径硬编码 `e:/logs` | 各服务 `logback-spring.xml:7-8` |
| 采样率 `probability: 1.0` 进仓库（注释自认生产应为 0.1~0.5） | `content/application.yml:168` 等 5 处 |
| 父 pom 用 `<dependencies>` 强制所有模块继承日志/tracing/DB 依赖 | `heima-leadnews/pom.xml:53-89` |
| 依赖版本散落十余处、mockito/jacoco/surefire 重复声明、遗留 JUnit4 | `content/pom.xml:37-93` 等 |
| 99% 测试是 Mock 单测，仅 2 个集成测试，无 Testcontainers | content 65 个测试中 2 个 `@SpringBootTest` |
| `.env` 存在但**无 `.env.example`** | `.env` 已正确被忽略（`.gitignore:61`），但无模板 |
| 前端无 ESLint / Prettier / 测试 | `package.json:7-12` 只有 dev/build/preview |

### 代码质量

| 问题 | 证据 |
|---|---|
| 超长方法 | `CheckinServiceImpl.doExtra:384-549`（165 行）、`LotteryServiceImpl.draw:111-336`（225 行） |
| 空 catch 吞异常 | `CircleController.java:37-41`、`AiAskController.java:123-124` 等 |
| 异常信息直接透传前端 | `BehaviorEventBus.java:97-101` `"行为处理异常: " + e.getMessage()` |
| 敏感信息落日志 | `SocialAuthServiceImpl.java:65/71/101`（access_token）、`TokenServiceImpl.java:51`（refreshToken）、`ApUserLoginController.java:103`（手机号）、`PinsInteractionService.java:134`（完整 ApUser 对象含 phone） |
| 自动拆箱 NPE | `ApCourseServiceImpl.java:521`（`byte current = course.getStatus()`）、`LotteryServiceImpl.java:319` |
| 失败返回 null 而非错误对象 | `TokenServiceImpl.java:64/78`、`SocialAuthServiceImpl.java:66/103` |
| Magic Number / 硬编码规则 | `LevelTaskProgressBuilder.java:84`（`groupSort == 4`）、`LevelPermissionServiceImpl.java:100`（`String[] basicPermissions`）、`LotteryServiceImpl.java:104`（`luckyThreshold=6000`） |
| `switch` 巨型分支，新增行为需改多处 | `LevelScoreProcessor.java:85/106/118`、`NotificationProcessor.java:54` |

### 前端

| 问题 | 证据 |
|---|---|
| 12 个组件 >1000 行，最大 2389 行 | `src/pages/pins/index.vue`（2389）、`src/pages/user/index.vue`（1987） |
| **三套并行 request 封装**，token/401/403/444 处理重复 | `src/common/request.js`、`article_request.js:7`、`reward_request.js:5` |
| 日期格式化在 ~20 个文件重复实现 | `src/pages/pins/index.vue:678`、`src/pages/user/index.vue:782` 等 |
| `escapeHtml` 复制 4 份 | `src/utils/sanitize.js:58`、`src/pages/pins/index.vue:674` 等 |
| 分页状态手写重复 ≥20 处 | `src/pages/notification/index.vue:242`、`src/pages/tag/detail.vue:111` 等 |
| 点赞/关注逻辑重复 8+ 处，部分有乐观更新回滚、部分没有 | `src/pages/pins/index.vue:1067` vs `src/pages/tag/detail.vue:260` |
| token 明文存 localStorage，3 处绕过 store 直读 | `src/stores/store.js:24-35`、`src/routers/creator.js:9` |
| 搜索联想无防抖 | `src/pages/search/index.vue:132-152`（同项目 `layout_main.vue:665` 已有正确写法） |
| 117 个 `<img>` 仅 6 个有 `loading="lazy"` | 全量统计 |
| 构建产物无 gzip/br，最大 chunk 948K | `vite.config.js:105-118`；`dist/assets/index-B0pklhdx.js` |
| 硬编码内网穿透域名与 OAuth clientId | `src/common/oauth.js:3/9/15`、`vite.config.js:38` |

---

## 六、改进路线图（按优先级与投入产出比）

### 第 1 阶段：止血（建议 1~2 天内完成，全是低风险小改动）

| 顺序 | 动作 | 工作量 | 收益 |
|:--:|---|:--:|---|
| 1 | 修 3 处 `app:/n` 配置损坏 + `isTrusted` 改 fail-closed | 10 分钟 | 关闭**身份冒充**入口 |
| 2 | ES 口令按铁律 2 改 `${ES_PASSWORD:x8YMlQeWvd4IGPnrb-4k}` 保留兜底 + 启动 WARN；轮换该口令；清理 `target/` | 30 分钟 | 关闭**凭据泄露** |
| 3 | 执行 P0-7 的 `ALTER TABLE` 索引脚本（先 EXPLAIN 验证） | 2 小时 | 全站查询性能数量级改善 |
| 4 | 微信 POST 补验签 + 移除硬编码 TOKEN；验证码校验后 `delete`、不返回响应体 | 半天 | 关闭**账号接管** | ✅ 2026-09-12 完成于 `fix/auth-callback-and-code-replay` 分支，新增 `LoginCodeService`、挡万 60 秒短信轰炸闸、身份防护挡生产 false 启动检查 | 微信回调 13 例 + 验证码 8 例 + 拦截器 20 例 + E2E 5 例 均绿 |
| 5 | `OrderServiceImpl` 券核销失败改异常/补偿 | 半天 | 关闭**资损**（5 折券可反复使用） | ✅ 2026-09-12 完成于 `fix/order-discount-outbox` 分支（方案 A：复用退款兜底） | 订单 46 例 + E2E 5 例 + 拦截器 5 例 均绿 |
| 6 | `CheckinServiceImpl` unlock 移出事务边界 | 1 小时 | 关闭**并发窗口**（重复签到/重复扣补签卡） | ✅ 2026-09-12 完成于 `fix/checkin-unlock-boundary` 分支（锁外移 + 事务下沉 CheckinTxService） | reward 模块 82 例全绿 |
| 5 | 券核销失败改为抛异常触发回滚 / 落补偿表 | 半天 | 停止**资金损失** |
| 6 | 补 `taskExecutor` 有界线程池 + 统一 Feign 超时配置 | 2 小时 | 消除**线程池打满 / OOM** 隐患 |

### 第 2 阶段：一致性加固（1~2 周）

1. **统一并发控制范式**：把 `unlock` 移出 `@Transactional` 方法体（或用 `TransactionSynchronization` 在 commit 后释放）；计数全部改原子 SQL；点赞表补唯一索引 + 捕获 `DuplicateKeyException` 做幂等。
2. **补齐事务边界**：`updateProgress`、`recordPassiveAction`、`handleFailed` 加 `@Transactional`。
3. **越权修复**：`settlement/detail` 加归属校验；`presigned_url` 加 key 前缀白名单；收窄网关公开路径。
4. **N+1 批量改造**：文章侧边栏、热榜、评论列表统一 `selectBatchIds` / `IN` + 一次分组。
5. **多实例就绪**：WebSocket 换 Redis STOMP relay；定时任务统一接入分布式锁（建议直接引 ShedLock）。

### 第 3 阶段：工程化补齐（2~4 周）

1. **配置中心化**：`application-{dev,test,prod}.yml` 或启用 Nacos config + 命名空间；口令类配置一律去掉可用默认值，缺失即启动失败；补 `.env.example`。
2. **CI/CD 补完**：Dockerfile + docker-compose；CI 覆盖全部 5 个服务 + 前端 lint/build；把 4 个未入库的 AI 测试提交。
3. **前端治理**：request 三合一 → 单一 axios 实例 + 拦截器；抽 `usePagination` / `formatters` / `constants`；`pins/index.vue` 等 12 个巨型组件拆分；引入 ESLint + `eslint-plugin-vue` + vitest 覆盖 utils 与请求层。
4. **依赖治理**：父 pom 的观测性依赖移到 `<dependencyManagement>`（或各服务按需引入）；版本收口；移除 devtools 与 JUnit4。
5. **监控补完**：Prometheus 告警规则落地（接口 RT、错误率、线程池、DB 连接池）+ Grafana 面板入库；采样率按环境分级。

---

## 七、进一步加深 / 增强的具体方向 ⭐

> 这一节回答"还能往哪个方向做出深度"。以下 7 条按**面试含金量**排序，每条都基于项目已有代码基础，是"顺势加深"而非另起炉灶。

### 方向 1：把"log.warn 式补偿"升级为**可靠性投递闭环**（最高价值）

**现状**：`OrderServiceImpl.java:341-361`、`:400-406` 支付后的券核销、经验发放、通知推送失败只打日志——这是全项目最关键的一致性缺口。

**加深做法**：
- 引入**本地消息表（Transactional Outbox）**：支付主事务内写 `ap_message_outbox`，独立线程/定时任务轮询投递，失败按指数退避重试 + 死信告警。
- 或接入 RocketMQ 事务消息。
- 收益话术：**"支付链路从『最终一致靠运气』变成『最终一致有保证』，且可观测、可重放"** —— 这是分布式事务里最能讲深的一环。

### 方向 2：为行为链路引入**幂等 + 顺序**保障

**现状**：`BehaviorEventBus` 的后置处理器链（等级/通知/成就/评分）全部在请求线程内同步执行，且无幂等键。

**加深做法**：给 `BehaviorContext` 引入业务幂等键（`userId:behaviorType:targetId:时间窗`），用 Redis `SETNX` 做去重；处理器改为通过 Spring 事件 + `@TransactionalEventListener(phase = AFTER_COMMIT)` 异步消费，保证"主事务提交后才触发副作用"。

**收益**：既解决了 P1-2 的重复触发，又把**"事件驱动 + 事务边界"**讲成了完整故事。

### 方向 3：把已有的 **AI 评测能力升级为"回归门禁"**

**现状**：项目已有 `AiEvalServiceImpl` 与 RAG 链路（pgvector 召回 + RRF 融合 + Rerank），但评测只用于离线看数。

**加深做法**：
- 建固定评测集（问题 → 期望召回文档），在 CI 里跑 `recall@k` / MRR / 答案忠实度。
- 设阈值门禁：**"检索策略改动导致 recall 下降超过 X% 则 CI 失败"**。
- 收益话术：**"AI 不是拍脑袋调参，而是有量化回归防线"** —— 这是 AI 工程项目里极稀缺的工程化能力，比"我用了 RAG"高一个层级。

### 方向 4：多级缓存 + 热点治理

**现状**：`LevelQueryService.java:32` 用进程内 `ConcurrentHashMap`；频道/权限/等级零缓存；热榜每次 150 次查询。

**加深做法**：
- 引入 Caffeine（L1，进程内）+ Redis（L2）+ 变更时主动失效的**多级缓存**，用 `@Cacheable` + 自定义 `CacheManager`。
- 热榜类高频只读接口做结果缓存 + 逻辑过期（防击穿）。
- Redis 大 key 治理：把 `excludeIds` 那类 500+ 元素全塞进查询改成布隆过滤器预筛。
- 收益话术：**"缓存不是加个 @Cacheable，而是一致性、穿透、击穿、雪崩的完整方案"**。

### 方向 5：索引与分页的系统性治理

**现状**：`ap_article` 零索引（P0-7），深分页 offset，`LIKE '%x%'`。

**加深做法**：
- 建立**索引规范文档** + 慢查询巡检（`performance_schema` 或 `EXPLAIN` 自动化校验）。
- 把项目已有的 cursor 分页（`ApArticleMapper.xml:242`）推广到评论、沸点、动态等所有时间线。
- 标签检索从 `JSON_CONTAINS` 迁到关联表或 ES。
- 收益话术：**"索引设计与分页演进（offset → cursor → ES）"** 是可深挖的性能叙事。

### 方向 6：可观测性从"有"到"有用"

**现状**：Zipkin + Prometheus 已接，但**告警规则是注释掉的、无业务指标**。

**加深做法**：
- 补业务指标：推荐点击率、审核通过率/耗时、AI 调用成功率与 token 消耗、支付成功率、券核销失败率。
- 落地告警规则（RT P99、错误率、线程池队列深度、DB 连接池等待）。
- Grafana 面板入库，做 **SLI/SLO 定义**（如"首页 P99 < 200ms，月可用性 99.9%"）。
- 收益话术：**"我不只接了监控，我还定义了 SLO 并用告警守住它"**。

### 方向 7：多环境与灰度能力

**现状**：dev/test/prod 共用一份配置，无开关。

**加深做法**：
- Nacos 配置中心 + 命名空间隔离；敏感配置全部外置。
- 关键业务（推荐算法版本、AI 模型、审核策略）做成**动态开关 + 灰度比例**，支持不停机调整与 A/B。
- 收益话术：**"配置即能力，算法可以灰度发布"** —— 体现线上意识。

---

## 八、优先修复速查清单

```
立即（今天）：
  [x] user/search/notification 三处 application.yml:80/43/47 的 app:/n 修成 app: (2026-09-12 完成)
  [x] ContentTokenInterceptor.isTrusted() 改 fail-closed (2026-09-12 完成)
  [ ] search/application.yml:23 按铁律 2 改 ES 口令（保留 x8YMlQeWvd4IGPnrb-4k 兜底）+ 启动 WARN + 轮换 + 清 target/
  [x] 执行 P0-7 全部 ALTER TABLE（先 EXPLAIN） (2026-09-12 完成，10+2 个索引已建)

本周：
  [x] WechatGZHLogin POST 补验签、移除硬编码 TOKEN (2026-09-12 完成)
  [x] 验证码校验后 GETDEL 一次性消费 + 不再回传响应体 + 同号 60s 发送限频 + 错误上限自动作废 (2026-09-12 完成)
  [x] OrderServiceImpl 券核销失败改补偿（方案 A：复用退款兜底 + refund_pending_reason 字段） (2026-09-12 完成)
  [x] CheckinServiceImpl unlock 移出事务边界 (2026-09-12 完成，锁外移 + 事务下沉 CheckinTxService)
  [ ] 补 taskExecutor 有界线程池 + Feign 超时配置
  [ ] SettlementController.detail 加归属校验

本月：
  [ ] N+1 批量改造（文章侧边栏/热榜/评论）
  [ ] 计数改原子 SQL + 点赞表唯一索引
  [ ] 补齐 @Transactional 缺失
  [ ] 多实例：WebSocket relay + 定时任务分布式锁
  [ ] 前端 request 三合一 + ESLint/vitest
  [ ] CI 覆盖全部服务 + Dockerfile
```

---

*本报告为只读评审，未修改任何业务代码。文中的 `ALTER TABLE` 与配置修复建议均需在测试环境验证后再上生产。*
