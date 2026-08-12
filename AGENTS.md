<!-- code-review-graph MCP tools -->
## MCP Tools: code-review-graph

**IMPORTANT: This project has a knowledge graph. ALWAYS use the
code-review-graph MCP tools BEFORE using Grep/Glob/Read to explore
the codebase.** The graph is faster, cheaper (fewer tokens), and gives
you structural context (callers, dependents, test coverage) that file
scanning cannot.

### When to use graph tools FIRST

- **Exploring code**: `semantic_search_nodes_tool` or `query_graph_tool` instead of Grep
- **Understanding impact**: `get_impact_radius_tool` instead of manually tracing imports
- **Code review**: `detect_changes_tool` + `get_review_context_tool` instead of reading entire files
- **Finding relationships**: `query_graph_tool` with callers_of/callees_of/imports_of/tests_for
- **Architecture questions**: `get_architecture_overview_tool` + `list_communities_tool`

Fall back to Grep/Glob/Read **only** when the graph doesn't cover what you need.

### Key Tools

| Tool | Use when |
| ------ | ---------- |
| `detect_changes_tool` | Reviewing code changes — gives risk-scored analysis |
| `get_review_context_tool` | Need source snippets for review — token-efficient |
| `get_impact_radius_tool` | Understanding blast radius of a change |
| `get_affected_flows_tool` | Finding which execution paths are impacted |
| `query_graph_tool` | Tracing callers, callees, imports, tests, dependencies |
| `semantic_search_nodes_tool` | Finding functions/classes by name or keyword |
| `get_architecture_overview_tool` | Understanding high-level codebase structure |
| `refactor_tool` | Planning renames, finding dead code |

### Workflow

1. The graph auto-updates on file changes (via hooks).
2. Use `detect_changes_tool` for code review.
3. Use `get_affected_flows_tool` to understand impact.
4. Use `query_graph_tool` pattern="tests_for" to check coverage.

---

# 全局固定规则（必须严格遵守）

本项目中，等级体系包含两种等级，由于命名不规范，逐日等级就是逐友等级，另一是逐力值等级

给你的参考数据，若你需要用到头像、icon图标资源，就将其上传到oss上，你可以用已有的web直传方案——/post_signature，prefix由你决定，格式通常: {prefix}/{yyyy/MM/dd}/{uuid}。

登录时，账号是任意11为数字，如11111111111、22222222222，验证码自动填入。

在浏览器测试过程中，涉及到文章上传时，除了自己编写内容，你还可以用“参考资料”里的文档，通过import接口文档导入形式完成，尽量让每个文章内容信息有图片、有封面（路径是，C:\Users\hudong\OneDrive\图片\本机照片\素材图片，随机一张即可），文章的必填项不能遗漏。

------

### 1. Git 版本管理（强制）

#### 1.1 新功能/修复/重构必须开新分支

- 从最新的主分支（`main` 或 `master`）切出新分支，**禁止**在主分支上直接修改。
- 分支命名规范（仅小写字母、数字、短横线）：
  - `feat/<描述>` —— 新功能
  - `fix/<描述>` —— 缺陷修复
  - `refactor/<描述>` —— 重构
  - `docs/<描述>` —— 文档
- 示例：`feat/user-login`, `fix/cart-total-error`

#### 1.2 任务完成后的提交流程

当一个完整功能点（接口、页面、逻辑闭环）开发完毕后，我叫你提交才提交，要不然我在编辑器里看不到新增或改动点，提交时按以下步骤操作：

1. 执行 `git status` 检查变更文件。
2. 执行项目规定的**编译/校验/测试命令**（如 `npm run build && npm run lint`），**未通过校验禁止提交**。
3. 暂存所有变更：`git add .`（自动排除 `.gitignore` 中的文件）。
4. 生成符合 **约定式提交** 的 Commit Message 并提交：
   - 格式：`<type>(<scope>): <subject>`
   - 常用 type：`feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `chore`
   - 示例：`feat(auth): add login API with JWT`
     `fix(cart): correct total price calculation`

#### 1.3 推送与发起合并请求

- 将当前分支推送到远程：`git push origin <当前分支名>`。
- 在代码托管平台（GitHub / GitLab 等）上向主分支发起 **Pull Request / Merge Request**。
- PR 标题和描述需简要概括本次变更，可复用 Commit Message。
- **严格禁止** 在审核通过前自行合并分支或删除远程分支。合并权由项目负责人掌握。

------

### 2. 开发行为与流程

#### 2.1 理解上下文（先读后写）

- 开始编码前，必须浏览项目目录结构、现有技术栈、配置文件（`package.json`, `pom.xml` 等）。
- **禁止** 在未理解项目架构时引入冲突依赖或覆盖关键配置。

#### 2.2 增量式开发（小步快跑）

- 优先搭建最小可行骨架（如路由、空组件、接口桩），确认方向后再逐步填充细节。
- 单次生成代码量控制在可审查范围，**避免一次输出数百行复杂逻辑**。

#### 2.4 依赖管理

- **引入新依赖前**，必须检查：是否与项目已有依赖冲突？维护状态（最近半年有更新）？License 兼容性？
- 优先复用项目已安装的依赖，避免重复引入功能相似包。
- 前端依赖通过 `npm/yarn/pnpm install` 安装，**严禁**手动下载 JS/CSS 文件放入 `public` 或 `assets`。
- 后端依赖在 `pom.xml` / `build.gradle` 中声明，从官方源导入，**严禁**拷贝第三方 `.jar` 到 `lib` 目录。

------

### 3. 代码质量标准

#### 3.1 异常与边界处理

- 所有对外接口（API、公共函数）必须包含输入参数校验。
- 必须捕获常见异常：空指针、网络超时、数组越界、类型转换失败等，并提供有意义的错误反馈。

#### 3.2 清理与注释

- 任务完成后，**必须删除**：调试用 `console.log`/`print`、临时硬编码数据、被注释掉的旧代码块。
- 复杂公共函数必须编写 JSDoc / DocString，说明参数、返回值和用途。
- 类型定义优先：**禁止使用裸 `Object`** 或 `any`（除非确有必要），所有数据结构必须有明确 interface / type。
- 开发过程中要有精确的注释信息，在设计实体类的时候属性上要有注释说明，在业务流程中尤其关键代码开始结束位置，以中文注释为主可以包含英文词。

#### 3.3 自测要求

- 交付前，应在本地启动项目并验证核心流程无运行时错误。
- 鼓励对关键业务逻辑编写单元测试，但不强制。

------

### 4. 数据来源与后端服务规则

#### 4.1 前端数据获取

- **严禁** 将示例数据、Mock 数据直接硬编码在组件或状态管理内。
- 所有列表、详情、下拉选项等数据，必须通过调用后端 API 实时获取。
- 前端 Mock 机制仅允许在接口未就绪时使用，且必须在接口对接完成后**完全移除**。

#### 4.2 本地数据库环境（仅限开发）

- 连接信息通过环境变量 `.env` 提供，**密码绝不硬编码**。

- 示例配置（仅结构，值从 `.env` 读取）：

  text

  ```
  DB_HOST=localhost
  DB_PORT=3306
  DB_USER=root
  DB_PASSWORD=123456（仅限开发环境）
  DB_NAME=leadnews_article(内容库,包括文章、沸点帖、话题、圈子、评论、专栏、课程、逐友等级和逐日值等级等)、leadnews_reward、leadnews_notification（系统通知数据库）、leadnews_user（用户数据库）
  ```

  

- 若你（项目负责人）提供了本地调试用的临时密码，AI 也应将其写入 `.env` 并确认 `.gitignore` 已包含该文件。

#### 4.3 动态维护数据库表结构

- 编码过程中若发现缺少必要的表、字段或类型不匹配，AI 需：
  1. **生成正确的 DDL 语句**（`CREATE TABLE` / `ALTER TABLE`）。
  2. 同步修改实体类、Mapper/DAO 和 SQL 映射文件，确保代码与数据库结构一致。
  3. 告诉你了数据库连接信息：*mysql -h localhost -u root -p* 123456，当需要插入表，或发生表结构变更时，由你直接操作完成，相应的.sql文件保留下来；若对某个表、表字段、字段类型不确定，可以提前查看，使你的输出更精准。
- **禁止** 因表结构缺失而使用临时数组或假数据阻塞开发。

------

### 5. 服务交互与微服务约束（分布式场景）

- 不同服务/模块间的通信必须通过远程调用（如 OpenFeign、Dubbo、gRPC、RESTful API），**禁止** 本地方法直调或共享内存。
- 调用方需处理服务不可用时的降级/熔断逻辑，避免级联故障。

------

### 6. API 返回值序列化规范（强制）

- 接口返回的 JSON 中，**所有字段值严禁为 `null`**。按以下规则替代：
  - 字符串 → 空字符串 `""`
  - 数值（int/float 等） → `0`
  - 数组/集合 → `[]`
  - 对象 → 空对象 `{}`（若业务上该字段可缺省，可通过 `@JsonInclude(NON_EMPTY)` 整体省略此字段，但一旦返回该字段，值绝不能是 `null`）
- 后端实施：
  - 配置全局 Jackson 空值序列化器，将 `null` 自动转换为对应空值。
  - DTO/VO 中，集合字段初始化 `= new ArrayList<>()`，字符串初始化 `= ""`。
- 收益：前端取值时无需 `?.` 链或冗余判空，直接使用即可。

------

### 7. 交互与记录规则

#### 7.1 需求模糊必须先确认

- 遇到模糊、矛盾或缺失的业务描述，**禁止自行猜测**。
- 必须列出具体疑问点，向用户确认后再动手。

#### 7.2 变更日志（CHANGELOG）

- 每完成一个可用的功能节点（可被感知的增量），在 `/docs` 目录下创建或更新 `CHANGELOG.md`（若目录不存在则自动创建）。
- 记录版本、日期、变更类型、简述。

#### 7.3 任务结束汇报格式

每次任务完成后，回复末尾必须包含：

- ✅ **完成内容**：一句话总结。
- 📁 **变更文件**：列出修改/新增文件列表。
- 🔍 **下一步建议**：潜在风险、重点测试项或遗留工作。
