# 残留接口审计报告（content 服务）· 2026-09-07

> 目的：回答"是否还有无主/越权残留接口"。方法：静态全量比对（5 服务 252 端点 vs 前端字面量/模板/Feign/白名单）+ 高危写口穷尽 + 逐 controller 人工复核。**结论先行：高危越权写口已清零；未发现需要立即删除的无主端点。**

---

## 一、分层结论

| 层 | 结论 |
|---|---|
| 写操作 + 可指定他人身份参数（越权口，穷尽扫描 5 服务） | 仅 2 个命中且均安全：`FollowController /follow/do`（网关认证身份优先，入参仅 Feign 内部用）；`NotificationController /feign/incr-unread`（Feign 内部）。**越权写口 = 0** |
| user 服务（12 controller 全端点人工核对） | 全部在用；顺带修复登出未吊销 refresh 的缺口（store.js 接通 `/token/logout`） |
| content LevelController（16 端点人工核对） | 删除 7 个（见下），剩余 9 个均验证在用 |
| content 43 个 controller（自动 + 人工复核） | 无"高置信无主且孤立"端点；38 个"非精确字面量命中"端点复核后绝大多数在用（/content 网关前缀 + `${API_PREFIX}` 变量拼接导致字面量漏判，见附录） |

## 二、本次已删除端点（累计 8 个）

1. `POST /api/v1/level/check-in`（签到发逐日分，双入口残留 + `@RequestParam userId` 越权；签到已收敛 reward `/sign/*`）
2. `POST /api/v1/level/action`（任意 userId 加分越权口）
3. `POST /api/v1/level/action/with-limit`（同上）
4. `POST /api/v1/level/power`（任意 userId/articleId 加逐力值越权口）
5. `GET /api/v1/level/daily-privileges`（无调用者）
6. `GET /api/v1/level/user/{id}/benefits`（无调用者）
7. `GET /api/v1/level/user/{id}`（裸实体，含 diamond_balance，无调用者 + 隐私）
8. `POST /api/v1/level/user/{id}/assign-basic-permissions`（HTTP 口无主；基础权限下发已走内部 service）

## 三、38 个"非精确命中"端点复核结果

- **在用（creator 前端 + /content 前缀）**：`course/manage/*`、`course/chapter/*`、`course/discount/*`、`course/order/*`、`course/author/*` —— 前端 `src/apis/course.js` 以 `const API_PREFIX='/content/api/v1/course'` + 模板拼接调用，creator 页面（list/edit/discount/settlement.vue）在用。
- **在用（外部/定时）**：`course/pay/notify`（支付宝异步回调）、`course/settlement/monthly`（月度结算 Job/管理触发）、`follow/isFollowing`（IM 互关检测等 Feign/内部）。
- **在用（详情页评论）**：`comment/.../comment/{id}/like|reply`、`article/{id}/column|featured|related`、`behavior/browse`（上报）—— 均为详情页/浏览链路，页面 JS 拼接变量调用。
- **保留（平台运营向，无运营后台时属超前实现，非越权）**：`course/review/*`（审批流 8 个端点，await 平台端接入）、`draft/manage/count`、`tip/notify|summary`、`tag/by-category`、`pins/upload-image`、`level/growth-tasks` 等 —— 建议待"运营/审核后台"接入或删除时再评估，无安全风险。

## 四、方法局限与建议

- 静态分析无法替代运行时证据：前端大量 `${base}` 变量拼接 + `/content|/user` 网关前缀 + conf.js 间接引用，字面量正则必然漏判/误判；已用 2-gram 段引用 + 逐簇人工复核补足。
- **建议收口手段**：联调/灰度期间导出网关访问日志，对"连续 N 天零访问"的端点做最终清理清单（可用脚本：按 `/api/v1/{service}/{path}` 统计命中次数）。
- 本次审计未改动 reward / notification / search 服务的任何端点（V3 全量扫描其端点均有 Feign/前端引用）。

---
_附录：/content 前缀说明 —— content 服务经网关挂 `/content` 与裸路径双入口（SSR 详情 SEO 需裸路径白名单），creator 前端统一走 `/content/api/v1/...`，故大量 content 端点的前端字面量带 `/content` 前缀，裸 `/api/v1/...` 字面量反而不存在。_
