# 成就勋章系统设计

- 日期：2026-08-14
- 状态：已评审通过，待实现
- 方案：仅荣誉展示 + 实时统计判定（计算型，不新增埋点）

## 背景与目标

社区已有完整的成长激励体系（签到 / 幸运抽奖 / 福利兑换 / 成长等级 / 掘力值），但缺少「荣誉感」层面的载体。本次新增**成就勋章系统**，通过勋章量化用户的内容产出、影响力与等级成就，在个人主页对外展示，增强成就感与留存。

## 设计原则

- **仅荣誉展示**：勋章解锁不发放矿石 / 掘友分等奖励。
- **实时计算判定**：解锁状态在个人主页被访问时由后端实时统计得出，**不建用户解锁记录表**，保证与数据永远一致、实现简单。
- **复用现有数据**：判定数据源全部来自既有行为/统计表（`ap_article` / `ap_pins` / `ap_behavior_likes` / `ap_follow` / 签到连续天数 / `ap_user_level`），不新增埋点、不新增前端依赖。
- **展示位置**：个人主页头部（替换现有 `badgeCount=0` 占位）。

## 勋章清单（共 13 枚）

### 新人成长类（2 枚）

| 勋章 | 解锁条件 | 数据源 |
|---|---|---|
| 初来乍到 | 首次发布内容：发布过 ≥1 篇文章 **或** ≥1 条沸点 | `ap_article` / `ap_pins`（authorId，is_deleted=false）|
| 连续签到30天 | 连续签到 ≥30 天 | 签到连续天数（复用既有签到统计）|

### 活跃成就类（9 枚）

| 勋章 | 解锁条件 | 数据源 |
|---|---|---|
| 笔耕不辍 | 累计发布文章 ≥10 篇 | `ap_article` 计数（authorId，is_deleted=false）|
| 创作达人 | 累计发布文章 ≥50 篇 | `ap_article` 计数 |
| 大神作家 | 累计发布文章 ≥100 篇 | `ap_article` 计数 |
| 初获认可 | 所有文章累计获赞 ≥100 | `ap_behavior_likes`（entryId ∈ 用户文章 & type=0 & operation=0）|
| 广受好评 | 所有文章累计获赞 ≥1000 | `ap_behavior_likes` |
| 万人追捧 | 所有文章累计获赞 ≥10000 | `ap_behavior_likes` |
| 小有名气 | 粉丝数 ≥100 | `ap_follow`（follow_user_id = 用户）|
| 人气爆棚 | 粉丝数 ≥500 | `ap_follow` |
| 顶流作家 | 粉丝数 ≥1000 | `ap_follow` |

### 等级身份（2 枚，最重要的两枚，动态展示当前等级）

| 勋章 | 说明 |
|---|---|
| 逐友等级 | 展示当前逐日等级（逐友等级），如「逐友 Lv.X / 等级名」，随等级动态变化 |
| 逐力值等级 | 展示当前逐力值等级，如「掘力值 Lv.Y」，随等级动态变化 |

## 数据模型

- 新增**勋章定义表** `ap_achievement`（`leadnews_article` 库，content 服务）：
  - 字段：`id`、`code`（唯一编码）、`name`（名称）、`category`（1=新人成长，2=活跃成就）、`icon`（图标名/字符）、`description`（解锁条件文案）、`trigger_type`（触发类型，如 `publish_article`/`publish_pins`/`likes`/`followers`/`checkin_streak`）、`threshold`（阈值，如 10/50/100/100/1000/10000/30）、`sort_order`、`is_active`
  - 通过迁移 SQL 写入 11 枚静态勋章种子数据（新人 2 枚 + 活跃 9 枚）
- **不建**用户解锁记录表；解锁状态访问时实时计算。
- 2 枚等级徽章不落库，由服务复用 `getUserLevelInfo` 动态构造（含当前等级值与等级名）。

## 后端设计

### 接口

新增（content 服务，`com.heima.content`）：

```
GET /content/api/v1/user/{userId}/achievements
```

返回结构（字段值不返回 null，遵循全局序列化规范）：

```json
{
  "code": 200,
  "data": {
    "unlockedCount": 3,
    "totalCount": 13,
    "list": [
      { "code": "first_content", "name": "初来乍到", "category": 1, "icon": "xx",
        "description": "首次发布文章或沸点", "unlocked": true, "progress": 1, "threshold": 1 }
    ],
    "levels": [
      { "type": "daily", "name": "逐友等级", "level": 2, "levelTitle": "见习掘友" },
      { "type": "power", "name": "逐力值等级", "level": 1, "levelTitle": "xx" }
    ]
  }
}
```

- `list`：11 枚静态勋章，`progress` 为当前进度值（如发布文章数 / 获赞数 / 粉丝数 / 连续签到天数），`threshold` 为解锁阈值，前端用于进度展示。
- `levels`：2 枚等级徽章，动态展示当前等级。
- 未登录 / 匿名访问他人主页时同样可查看（社区展示属性），接口放行或携带可选用户态。

### 修改既有接口

- `ArticleStatisticsServiceImpl.getUserStatistics()`：`badgeCount` 由恒 `0` 改为真实已解锁勋章数（对 `list` 中 `unlocked=true` 计数）。

### 判定实现

新建 `AchievementService`（content 服务）：

- `getUserAchievements(userId)`：汇总以下统计，与 `ap_achievement` 定义表比对得出 `unlocked` 与 `progress`：
  - 发布文章数：`ap_article`（authorId、is_deleted=false）count
  - 发布沸点数：`ap_pins`（authorId、is_deleted=false）count
  - 所有文章获赞数：`ap_behavior_likes`（entryId ∈ 用户文章 & type=0 & operation=0）count
  - 粉丝数：`ap_follow`（follow_user_id = 用户）count
  - 连续签到天数：复用既有签到统计逻辑
  - 逐友 / 逐力值等级：复用 `getUserLevelInfo`
- 查询为批量聚合（一次查出用户文章集合再统计获赞），避免 N+1。

## 前端设计

仅改动**个人主页头部**（`src/pages/user/index.vue`）：

- **等级徽章**（最重要的两枚）：头部现有「掘友等级 Lv.X」占位替换为逐友 / 逐力值两枚等级徽章（含当前等级与等级名），最醒目展示。
- **勋章入口**：头部统计区新增「勋章」入口，显示已解锁数（如 N/11），点击打开勋章墙弹窗。
- **勋章墙弹窗**：展示全部 13 枚勋章网格：已解锁彩色 / 未解锁置灰并显示解锁条件；等级徽章置顶突出。

- 接口封装走既有 `request.js` 体系（`src/apis/`），字段值直接使用（后端已保证非 null）。

## 交付节奏

1. 后端：`ap_achievement` 定义表 + 种子 SQL + `AchievementService` + 接口 + `badgeCount` 改造
2. 前端：个人主页头部等级徽章 + 勋章入口 + 勋章墙弹窗
3. 联调验证：浏览器回归（他人主页 / 本人主页 / 未登录浏览 / 进度显示）

## 技术要点

- 遵循全局规则：接口字段值严禁为 `null`；集合初始化为空集合。
- 判定统计须与个人主页现有数据口径一致（获赞用 `ap_behavior_likes`、粉丝用 `ap_follow`）。
- 数据库变更脚本写入 `heima-leadnews-content/src/main/resources/db/migrations/`，并按需更新 `schema.sql`。
- 新功能在 `feat/` 分支开发，完成后按约定提交。

## 非目标

- 不发奖励、不做解锁分享弹窗、不做独立勋章墙页面、不做实时解锁推送。
- 不新增埋点 / 埋点表、不新增前端依赖。
- 本次不涉及创作者侧（掘力值）勋章规则扩展，仅展示既有逐力值等级。
