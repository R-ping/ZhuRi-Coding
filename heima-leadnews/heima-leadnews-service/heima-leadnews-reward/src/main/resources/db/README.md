# leadnews-reward 数据库脚本

本目录集中管理 `leadnews-reward` 服务连接的数据库脚本。

## 数据库

- 库名：`leadnews_reward`（打赏/奖励库）

## 目录结构

```
db/
├── schema.sql        # 全量建表 DDL（汇总，由 mysqldump --no-data 导出）
├── migrations/       # 增量变更脚本（当前为空，按需添加）
└── README.md
```

## 约定

- **schema.sql**：全量建表结构汇总，**只读，勿直接改动**。
- **migrations/**：表结构变更脚本，按时间/用途命名，执行一次即可。
- 新增表或字段时，先写 migration，再（可选）重新导出更新 schema.sql。

## 重新生成 schema.sql

```bash
mysqldump -h 127.0.0.1 -u root -p --no-data --skip-comments \
  --skip-add-drop-table --default-character-set=utf8mb4 leadnews_reward \
  > src/main/resources/db/schema.sql
```
