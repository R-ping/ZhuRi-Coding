# 数据库迁移入库核对与修复（2026-09-12）

> 背景：检查"哪些表/脚本没成功入库"，并补齐缺失对象。
> 方法：从各服务 `src/main/resources/**/*.sql` 抽取 `CREATE TABLE` 目标名，与 `information_schema` 实际表比对（MySQL 用 CLI，PostgreSQL 无 psql 客户端 → 用 JDBC 直连）。

## 一、盘点结果（修复前）

| 库 | 期望表 | 实有 | 缺失 |
|---|---|---|---|
| `leadnews_article`（content，MySQL） | 72（已排除 PG 脚本） | 71 | **`ap_pins_comment_audit_task`** |
| `leadnews_notification`（notification，MySQL） | 4 | 3 | `system_notifications` — **误报**：`db/migrations/drop_orphan_tables.sql` 明确以"零代码引用"为由 DROP，属预期不存在 |
| `leadnews_reward`（reward，MySQL） | 12 | 12 | 无 |
| `leadnews_user`（user，MySQL） | 6 | 6 | 无 |
| `leadnews_content`（pgvector, PostgreSQL 17.10） | 4 | 1 | **`ap_user_memory`**、**`ap_article_chunk`**、**`ap_ai_semantic_cache`** |

> ⚠️ 比对坑：Windows 版 `mysql.exe` 输出带 CRLF，与 LF 文本比对会让"全部表"误判为缺失。必须 `tr -d '\r'` 后再 `comm`。

## 二、本次已执行

| 对象 | 库 | 脚本 | 结果 |
|---|---|---|---|
| `ap_pins_comment_audit_task` | MySQL `leadnews_article` | `create_ap_pins_comment_audit_task.sql` | 建表成功（14 列，中文注释正常） |
| `ap_user_memory` | PG `leadnews_content` | `ai_memory_setup.sql` | 建表 + 2 索引成功 |
| `ap_article_chunk` | PG | `ai_article_chunk_setup.sql` | 建表 + 唯一键 `(article_id, chunk_index)` + article 索引 |
| `ap_ai_semantic_cache` | PG | `ai_semantic_cache_setup.sql` | 建表 + `(user_id, created_time DESC)` 索引 |

PG 侧建表结构已复核：向量列均为 `vector`（1024 维约束），索引与脚本一致。

## 三、顺带发现并修复：注释乱码（双重编码）

**现象**：5 张表的表注释 + 26 个列注释在库内就是乱码（`AI 鍙嶉?` 这类），且含不可逆的 `?` 丢字——说明写入时连接字符集不对（UTF-8 字节被按 GBK 解释后二次编码）。
**影响面**：`ap_ai_feedback`、`ap_ai_topup_order`、`ap_ai_wallet`、`ap_aigc_record`、`ap_content_appeal`（AI 相关新表）以及 `ap_article` / `ap_pins` / `ap_course_chapter` 的 `is_aigc`、`aigc_score` 列。**数据本身正常**（抽查 `ap_achievement.name` 中文可读）。
**修复方式**：以**迁移脚本里的原始 COMMENT 为真源**（比从乱码反解可靠），用 `SHOW CREATE TABLE` 的逐字列定义改写，只替换 COMMENT 部分 → 生成并执行 32 条 ALTER。
**验证**：
- 执行前后列定义快照 `diff` **无差异**（类型/可空/默认值/键全部未变，只有注释变化）；
- 全库乱码计数 **表 0 / 列 0**；
- 数据行数未变（`ap_ai_topup_order`=3、`ap_article`=228）。

## 四、规范（避免复发）

1. **执行 SQL 必须指定字符集**：`mysql --default-character-set=utf8mb4 ... < x.sql`。不加这个参数就是本次乱码的成因。JDBC 侧 `application.yml` 的 URL 已带 `characterEncoding=UTF-8`，运行时无此问题。
2. **PG 无 psql 时用 JDBC**：驱动在 `D:\apache-maven-3.9.10\mvn_repo\org\postgresql\postgresql\`，可直接 `java -cp <driver>.jar PgTool.java`（单文件运行，见临时工具）。
3. **迁移脚本只增不改**：`CREATE TABLE IF NOT EXISTS` 幂等，重复执行不会更新注释——注释写错时需显式 `ALTER`。

## 五、遗留建议（未处理，需你决定）

- `ap_user_memory` 的 **IVFFlat 索引是在空表上创建的**（`lists=50`）；pgvector 默认 `ivfflat.probes=1`，在数据量很小时**可能召回不到结果**（长期记忆静默失效）。两种处理：① 数据量小时直接 `DROP INDEX idx_user_memory_embedding`，等写入量上来再建；② 会话里 `SET ivfflat.probes = 10`。本次未动，保持与脚本一致。
- `ap_article_chunk` / `ap_ai_semantic_cache` 均按设计**不建 ANN 索引**（精确扫描），脚本内有 HNSW 启用语句注释，语料增长后再开。
