# zhuri-coding-user 数据库脚本

本目录集中管理 `zhuri-coding-user` 服务连接的数据库脚本。

## 数据库

- 库名：`leadnews_user`（用户库）

## 目录结构

```
db/
├── schema.sql        # 全量建表 DDL（由 mysqldump --no-data 导出）
└── README.md
```

## 约定

- **schema.sql**：唯一结构基线。表结构变更直接以数据库为准，随后重新导出；不再维护 `migrations/` 增量 DDL 脚本。

## 重新生成 schema.sql

```bash
mysqldump -h 127.0.0.1 -u root -p --no-data --skip-comments \
  --skip-add-drop-table --default-character-set=utf8mb4 leadnews_user \
  > src/main/resources/db/schema.sql
```
