# 缺陷清单（来自代码审查的可批判点 → 修复计划）

> 来源：对 `interview-project-points-v2.md` 中全部"面试可深挖/可批判"点的归类与排期。
> 分级：**P0** 确凿 bug（行为错误/安全隐患）｜**P1** 安全/一致性增强｜**P2** 健壮性/体验优化｜**P3** 架构级改造（本轮不落代码，单独迭代）。

---

## P0 —— 确凿 Bug（✅ 2026-08-31 已全部修复并编译通过）

| # | 模块 | 缺陷 | 修复方案 | 状态 |
|---|---|---|---|---|
| P0-1 | content | `ImageScanProcessor` 第一张图审核通过即 `return true`，**后续图片全部漏审** | 遍历全部图片，全部通过才返回 true | ✅ `ImageScanProcessor.java` |
| P0-2 | content | `ImageScanProcessor` 服务异常（map==null）时**降级通过**（fail-open），与文本审核 fail-closed 矛盾，违规图片可能上架 | 改为 fail-closed：服务异常/空结果 → 驳回 | ✅ 同上 |
| P0-3 | notification | `AuthHandshakeInterceptor` 读 `claims.get("id")`，但签发端写的是 `"userId"` → **所有 WebSocket 握手被拒**，IM 实时推送不可用 | 改读 `claims.get("userId")`，并兼容 `"id"` 兜底 | ✅ `AuthHandshakeInterceptor.java` |
| P0-4 | common | `DashScopeClient.init` 日志**明文打印 apiKey**（安全红线） | 去除明文，改为掩码/不打印 | ✅ `DashScopeClient.java`（maskKey） |
| P0-5 | search | 搜索历史 `ApUserSearchServiceImpl.insert` **无生产调用点**（断链），历史列表永远为空 | 在 `ArticleSearchServiceImpl.search` 成功后异步记录（仅登录用户） | ✅ `ArticleSearchServiceImpl.java` |

## P1 —— 安全 / 一致性（✅ 2026-08-31 已全部修复并编译通过）

| # | 模块 | 缺陷 | 修复方案 | 状态 |
|---|---|---|---|---|
| P1-1 | user/common | `refreshToken` 的"删旧 + 建新"非原子，**并发刷新可同时消费同一 refresh_token**（轮换语义被破坏） | CacheService 新增 Lua `GET+DEL` 原子方法，一次只能消费一次 | ✅ `CacheService.getAndDelete` + `TokenServiceImpl` |
| P1-2 | content | 课程订单**无超时关闭**（PENDING 永久存在，CANCELLED 状态无赋值） | 复用 Redisson 延迟队列：下单后 N 分钟条件更新关单 | ✅ 新增 `OrderTimeoutTask` + `OrderService.closeExpiredOrder` |
| P1-3 | content | 支付回调**未校验 app_id/seller_id**，存在跨应用回调混淆风险 | 回调验签后追加 app_id 一致性校验 | ✅ `PayController` + `AlipayService.getAppId` |
| P1-4 | content | 发布/评论等写接口后端**无权限硬校验**（仅前端门面） | 在文章发布入口加 `can_publish_article` 校验 | ✅ `ApArticleDraftServiceImpl.publishFromDraft` |
| P1-5 | gateway | **网关无入口限流**（入口级防护缺失） | 网关新增基于 Redis 的 IP 维度限流 GlobalFilter | ✅ 新增 `GatewayRateLimitFilter`（Lua 固定窗口，order=-1） |

## P2 —— 健壮性 / 体验（✅ 2026-08-31 已全部修复并编译通过）

| # | 模块 | 缺陷 | 修复方案 | 状态 |
|---|---|---|---|---|
| P2-1 | notification | `SessionManager` 单用户多端在线时，**一端断开整体掉线** | 改为 `Map<Long, Set<String>>`，按 sessionId 精确移除 | ✅ `SessionManager` + `WebSocketEventListener` |
| P2-2 | reward | 幸运值回写用 `updateById` **读改写**，并发可能丢更新 | 改条件更新（SQL 直接 set） | ✅ `UserAssetsMapper.updateLuckyValue` |
| P2-3 | reward | 兑换码用 `new Random()`，可预测 | 改 `SecureRandom` | ✅ `WelfareServiceImpl` |
| P2-4 | reward | 抽奖接口**无限流注解**（仅矿石成本+每日状态） | 补 `@RateLimit`（IP 30/min + USER 20/min） | ✅ `LotteryController.draw` |
| P2-5 | user | 验证码**无同号发送间隔限制**（可被针对单号轰炸） | 加 Redis 60s 间隔校验 | ✅ `ApUserLoginController.getCode` |
| P2-6 | reward | 连续签到天数**逐日 selectOne**（60 次查询/用户） | 一次区间查询 + 内存布尔数组；矿石余额同步改原子累加 | ✅ `CheckinServiceImpl` |
| P2-7 | content | `calculateLevel` **每次查 DB**（等级配置表变更低频） | 本地缓存（5 分钟 TTL） | ✅ `LevelQueryService` |
| P2-8 | common | 图片审核工具类：bucket/region **硬编码**、static 字段并发不安全 | 配置化（`aliyun.oss.scan.*`）+ 去除 static 共享字段 | ✅ `GreenImageScanPlusForOss` + `OssConfigForImageScan` |

## P3 —— 架构级改造（⬜ 建议后续迭代，本轮不落代码）

> ✅ 注：原 **P3-6 热度分双路径口径不一致** 已于 2026-08-31 修复（删除 MQ 遗留 `updateScore`、行为路径统一走原子 SQL `recalculateScore`）；原 **P3-7 本地消息表无有效重试/死信 + refreshTaskToRedis 无锁** 已于 2026-09-01 修复（失败累计重试次数、死信清理、Redis 分布式锁），均从下表移除。

| # | 模块 | 缺陷/方向 | 建议方案 |
|---|---|---|---|
| P3-1 | gateway/user | 下游信任 header 明文身份；access_token 无法即时失效 | Feign 拦截器透传 + mTLS/签名；Redis 黑名单/短 TTL |
| P3-2 | content | 无退款、无主动查单对账（回调丢失则订单永久 PENDING） | 封装 `alipay.trade.query` 定时对账 + `alipay.trade.refund` |
| P3-3 | 全局 | 无雪花 ID（自增主键，分库分表受限） | MyBatis-Plus ASSIGN_ID / 自定义雪花 |
| P3-4 | basic | 文件存储无统一 SPI（OSS/MinIO 切换需改代码） | 定义 FileStorage 接口 + 多实现 |
| P3-5 | content | 推荐无协同过滤/向量召回、冷启动弱、候选池性能风险 | pgvector 相似召回、CF、冷启模板 |
| P3-8 | search | ES 检索 OR 语义 + 纯时间排序，热搜榜弱 | multi_match + function_score；Redis zset 热搜 |
| P3-9 | notification | SimpleBroker 内存路由无法水平扩展 | Redis Pub/Sub / 外部 Broker / 离线收件箱补推 |
| P3-10 | content | 成就查询时全量计算非事件驱动 | 解锁事件 + 落库 + 通知 |
| P3-11 | user | 微信扫码登录闭环缺失（wechat:token 无消费接口） | 补 token 换双 token 接口 |
| P3-12 | content | 审核链顺序硬编码、重试用 Thread.sleep | List<Processor> + @Order；延迟入队重试 |
| P3-13 | content | 审计表只记失败不记通过 | 全量审核轨迹落库 |

---
*生成日期：2026-08-31 ｜ 状态图例：⬜ 待修 ｜ 🟩 已修 ｜ ⬜️ P3 建议后续迭代*
