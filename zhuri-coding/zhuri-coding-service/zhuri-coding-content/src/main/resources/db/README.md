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

- 已修复：`pgvector_setup.sql` 原误写 `vector(1536)`，与 1024 维写入/检索不匹配 → 已改为 `vector(1024)`，并留档 `alter_ap_article_embedding_dim_1024.sql`

- 新增/变更向量列时，务必保持 `vector(N)` 中 N 与上述配置一致。

## 目录结构

```
db/
├── schema.sql        # 全量建表 DDL（汇总，由 mysqldump --no-data 导出）
├── migrations/       # 增量变更脚本（按需执行）
│   ├── add_root_id_to_ap_comment.sql
│   ├── ai_review_migration.sql
│   ├── create_ap_article_comment.sql
│   ├── growth_level_tables.sql
│   ├── init_creator_benefits.sql
│   └── pgvector_setup.sql
└── README.md
```

## 约定

- **schema.sql**：全量建表结构汇总，**只读，勿直接改动**。需要重建/查看结构时读取此文件。

- **migrations/**：表结构变更脚本，按时间/用途命名（如 `alter_ap_pins_add_view_count.sql`），执行一次即可。

- 新增表或字段时，先写 migration，再（可选）重新导出更新 schema.sql。

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

