# zhuri-coding-content 数据库脚本

本目录集中管理 `zhuri-coding-content` 服务连接的数据库脚本。

## 数据库

- 库名：`leadnews_article`（内容库，MySQL）

- 包含：文章、沸点帖、话题、圈子、评论、专栏、课程、逐友等级、逐日值等级等

- 向量库（pgvector，PostgreSQL）：`leadnews_content`（库名与 MySQL 内容库不同，见 `application.yml` 的 `pgvector.datasource.jdbc-url`）

## 向量维度约定（重要）

`ap_article_embedding.embedding` 列维度必须与 embedding 模型输出一致：

- 模型：`qwen3.7-text-embedding`（`spring.ai.openai.embedding.options.model`）

- 维度：**1024**（`spring.ai.openai.embedding.options.dimensions=1024`，也是该模型默认维度）

- 已修复：原脚本误写 `vector(1536)`，与 1024 维写入/检索不匹配，已统一为 `vector(1024)`。

- 向量表结构基线：`db/schema-pgvector.sql`（PostgreSQL），新增/变更向量列时直接改基线并同步数据库。

- 历史上按次维护的增量脚本已移除，结构一律以当前数据库实际结构为准。

## 目录结构

```
db/
├── schema.sql          # MySQL 全量建表 DDL（由 mysqldump --no-data 导出）
├── schema-pgvector.sql # PostgreSQL/pgvector 向量表结构基线
├── data/               # 初始化/回填数据脚本（纯 DML，新环境按需执行）
└── README.md
```

## 约定

- **schema.sql**：MySQL 唯一结构基线。表结构变更直接以数据库为准，随后重新导出；不再维护 `migrations/` 增量 DDL 脚本。

- **schema-pgvector.sql**：PostgreSQL 向量表结构基线（`CREATE EXTENSION / TABLE / INDEX`）。

- **data/**：只放纯数据脚本（`INSERT / UPDATE / DELETE`），禁止包含 `CREATE / ALTER / DROP`；脚本应可重复执行（`INSERT IGNORE`、条件 `UPDATE`）。

## 主键（id）设计规范

铁律：**主键列类型与 MyBatis-Plus 主键策略必须成对使用，禁止混搭。**

| 数据库 `id` 列类型                 | 实体 id 字段类型         | @TableId 主键策略                        | 说明                  |
| ---------------------------- | ------------------ | ------------------------------------ | ------------------- |
| `BIGINT` / `BIGINT UNSIGNED` | `Long`             | `IdType.ASSIGN_ID`                   | 雪花/自分配大 id，约 2^63 内 |
| `INT` / `INT UNSIGNED`       | `Integer` 或 `Long` | `IdType.AUTO`（依赖 `auto_increment` 列） | 数据库自增，值在 INT 范围内    |

> 完整规则、背景与现状核对清单见 AGENTS.md「4.5 主键（id）设计规范」。

## 重新生成 schema.sql

```bash
mysqldump -h 127.0.0.1 -u root -p --no-data --skip-comments \
  --skip-add-drop-table --default-character-set=utf8mb4 leadnews_article \
  > src/main/resources/db/schema.sql
```

