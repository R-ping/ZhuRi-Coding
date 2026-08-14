# 详情页留存优化 · 阶段 1：详情页阅读体验 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为文章详情页新增阅读进度条、字号/行距调节、暗色主题、沉浸模式，并持久化阅读偏好，提升长文阅读体验与停留时长。

**Architecture:** 增量式改造。阅读偏好（字号/行距/主题/沉浸）以 `localStorage` 持久化；字号/行距通过 CSS 变量 + `html` 内联变量运行时切换；暗色/沉浸通过 `body` class 切换（叠加选择器覆盖，避免大面积重写既有样式）；新 CSS/JS 一律追加在 `<style>` 末尾与 `article-static.js` 的 IIFE 内部，保证源顺序覆盖既有规则。

**Tech Stack:** Spring Boot + FTL 模板 + 原生 JS + CSS 变量 + localStorage。

> 说明：本项目详情页为 FTL 服务端渲染 + 原生 JS，仓库无 JS 单测框架，故本阶段验证采用「JS 语法校验（node --check）+ 浏览器手动回归」。每个任务含明确验证步骤与提交点。

---

## 文件列表（阶段 1）

| 文件 | 操作 | 责任 |
| --- | --- | --- |
| `heima-leadnews/heima-leadnews-service/heima-leadnews-content/src/main/resources/templates/article.ftl` | modify | 新增进度条/设置弹窗/沉浸退出按钮 HTML 与 CSS |
| `heima-leadnews/heima-leadnews-service/heima-leadnews-content/src/main/resources/static/article-static.js` | modify | 新增进度条更新、阅读偏好加载/应用/持久化、设置弹窗交互、沉浸切换 |
| `docs/CHANGELOG.md` | modify | 记录变更 |

服务端口：网关 `51601`，内容服务 `51802`。详情页地址：`http://localhost:51601/content/article/{文章ID}`（文章 ID 从 `leadnews_article` 表取一条已发布文章的 id）。

---

## 任务 1-1：阅读进度条

**Files:**
- Modify: `heima-leadnews/heima-leadnews-service/heima-leadnews-content/src/main/resources/templates/article.ftl`（CSS 插入到 `</style>` 前，约第 2091 行；HTML 插入到 `<body>` 之后，约第 2093 行）
- Modify: `heima-leadnews/heima-leadnews-service/heima-leadnews-content/src/main/resources/static/article-static.js`（追加到 IIFE 内部末尾）

- [ ] **Step 1: 新增进度条 CSS**

在 `article.ftl` 的 `</style>` 之前追加：

```css
/* ========== 阅读进度条 ========== */
.reading-progress {
    position: fixed;
    top: 60px; /* 顶栏高度 */
    left: 0;
    height: 2px;
    width: 0;
    background: #1e80ff;
    z-index: 101;
    transition: width 0.1s linear;
    pointer-events: none;
}
```

- [ ] **Step 2: 新增进度条 HTML**

在 `<body>` 之后、`<!-- 顶栏 -->` 之前插入：

```html
<!-- 阅读进度条 -->
<div class="reading-progress" id="readingProgress"></div>
```

- [ ] **Step 3: 新增进度条 JS**

在 `article-static.js` 的 IIFE 内部末尾（`})();` 之前）追加：

```javascript
// ========== 阅读进度条 ==========
var readingProgressEl = document.getElementById('readingProgress');
function updateReadingProgress() {
    if (!readingProgressEl) return;
    var scrollTop = window.pageYOffset || document.documentElement.scrollTop;
    var scrollable = document.documentElement.scrollHeight - window.innerHeight;
    var percent = scrollable > 0 ? Math.min(100, scrollTop / scrollable * 100) : 0;
    readingProgressEl.style.width = percent.toFixed(2) + '%';
}
if (readingProgressEl) {
    window.addEventListener('scroll', updateReadingProgress, { passive: true });
    window.addEventListener('resize', updateReadingProgress);
    updateReadingProgress();
}
```

- [ ] **Step 4: 验证 JS 语法**

Run: `node --check heima-leadnews/heima-leadnews-service/heima-leadnews-content/src/main/resources/static/article-static.js`
Expected: 无输出、退出码 0。

- [ ] **Step 5: 浏览器回归**

启动内容服务与网关，访问 `http://localhost:51601/content/article/{文章ID}`，滚动页面：
Expected: 顶栏下方 2px 蓝色细条宽度随滚动比例实时变化，滚动到页底宽度为 100%。

- [ ] **Step 6: Commit**

```bash
git add heima-leadnews/heima-leadnews-service/heima-leadnews-content/src/main/resources/templates/article.ftl heima-leadnews/heima-leadnews-service/heima-leadnews-content/src/main/resources/static/article-static.js
git commit -m "feat(detail): add reading progress bar"
```

---

## 任务 1-2：阅读偏好持久化与暗色主题

**Files:**
- Modify: `.../article.ftl`
- Modify: `.../article-static.js`

- [ ] **Step 1: 新增暗色主题与字号/行距 CSS 变量**

在 `article.ftl` 的 `</style>` 之前追加（源顺序靠后，可覆盖第 10-40 行的既有 body/content-card 颜色）：

```css
/* ========== 阅读设置：字号/行距（CSS 变量，运行时切换） ========== */
:root {
    --read-font-size: 17px;
    --read-line-height: 1.75;
}
.article-body {
    font-size: var(--read-font-size);
    line-height: var(--read-line-height);
}

/* ========== 暗色主题（body.dark 覆盖主阅读区域） ========== */
body.dark { background: #121212; color: #e4e6eb; }
body.dark .article-topbar { background: #1e1e1e; box-shadow: 0 1px 3px rgba(0,0,0,0.5); }
body.dark .content-card { background: #1e1e1e; box-shadow: 0 1px 2px rgba(0,0,0,0.3); }
body.dark .article-title { color: #e4e6eb; }
body.dark .author-name { color: #e4e6eb; }
body.dark .publish-meta,
body.dark .read-count,
body.dark .read-time,
body.dark .column-tag { color: #8a919f; }
body.dark .article-body { color: #c9d1d9; }
body.dark .article-body h1,
body.dark .article-body h2,
body.dark .article-body h3,
body.dark .article-body h4,
body.dark .article-body h5,
body.dark .article-body h6 { color: #e4e6eb; }
body.dark .article-body code,
body.dark .article-body pre { background: #161b22; color: #c9d1d9; border-color: #2d333b; }
body.dark .article-body blockquote { color: #8b949e; border-color: #2d333b; }
body.dark .action-sidebar { background: #1e1e1e; box-shadow: 0 1px 4px rgba(0,0,0,0.5); }
body.dark .action-item .action-count { color: #8a919f; }
body.dark .modal-container { background: #1e1e1e; }
body.dark .modal-title,
body.dark .modal-subtitle { color: #e4e6eb; }
body.dark .modal-header,
body.dark .modal-footer { border-color: #2d333b; }
body.dark .setting-btn { background: #1e1e1e; color: #8a919f; border-color: #2d333b; }
body.dark .setting-btn.active { background: #eaf2ff; color: #1e80ff; border-color: #1e80ff; }
```

- [ ] **Step 2: 新增阅读偏好加载/应用/持久化 JS**

在 `article-static.js` 的 IIFE 内部末尾追加：

```javascript
// ========== 阅读偏好（字号/行距/主题/沉浸）持久化 ==========
var READER_KEY = 'zhuri_reader_settings';
var DEFAULT_SETTINGS = { fontSize: 17, lineHeight: 1.75, theme: 'light', immersive: false };

function loadReaderSettings() {
    try {
        var saved = localStorage.getItem(READER_KEY);
        return saved ? Object.assign({}, DEFAULT_SETTINGS, JSON.parse(saved)) : DEFAULT_SETTINGS;
    } catch (e) {
        return DEFAULT_SETTINGS;
    }
}

function applyReaderSettings(s) {
    // 字号/行距：写入 html 内联变量，.article-body 自动生效
    document.documentElement.style.setProperty('--read-font-size', s.fontSize + 'px');
    document.documentElement.style.setProperty('--read-line-height', String(s.lineHeight));
    // 主题
    document.body.classList.toggle('dark', s.theme === 'dark');
    // 沉浸
    document.body.classList.toggle('immersive-mode', !!s.immersive);
    // 同步设置面板按钮选中态
    syncSettingButtons(s);
}

function saveReaderSettings(s) {
    try { localStorage.setItem(READER_KEY, JSON.stringify(s)); } catch (e) {}
}

function syncSettingButtons(s) {
    document.querySelectorAll('.setting-btn[data-font]').forEach(function (b) {
        b.classList.toggle('active', parseInt(b.getAttribute('data-font'), 10) === s.fontSize);
    });
    document.querySelectorAll('.setting-btn[data-line]').forEach(function (b) {
        b.classList.toggle('active', parseFloat(b.getAttribute('data-line')) === s.lineHeight);
    });
    document.querySelectorAll('.setting-btn[data-theme]').forEach(function (b) {
        b.classList.toggle('active', b.getAttribute('data-theme') === s.theme);
    });
}

var readerSettings = loadReaderSettings();
applyReaderSettings(readerSettings);
```

- [ ] **Step 3: 验证**

Run: `node --check .../article-static.js` → 无输出、退出码 0。

浏览器回归（此时尚无面板，先验证默认值不报错）：
Expected: 页面正常渲染，控制台无 JS 报错，`localStorage['zhuri_reader_settings']` 未写入但 `applyReaderSettings` 用默认值生效。

- [ ] **Step 4: Commit**

```bash
git add .../article.ftl .../article-static.js
git commit -m "feat(detail): add reader preference persistence and dark theme CSS"
```

---

## 任务 1-3：阅读设置弹窗（字号/行距/主题）+ 侧栏设置入口

**Files:**
- Modify: `.../article.ftl`（HTML 追加在 `</body>` 前、`article-static.js` 动态加载脚本之前；CSS 追加到 `</style>` 前）
- Modify: `.../article-static.js`

- [ ] **Step 1: 新增设置弹窗 HTML**

在 `article.ftl` 中 `<!-- 打赏弹窗 -->` 的 `</div>` 之后（约第 2597 行）插入：

```html
<!-- 阅读设置弹窗 -->
<div class="modal-overlay" id="readerSettingsOverlay">
    <div class="modal-container reader-settings-modal" id="readerSettingsModal">
        <div class="modal-header">
            <h3 class="modal-title">阅读设置</h3>
            <p class="modal-subtitle">调整阅读体验，设置自动保存</p>
            <button class="modal-close-btn" id="closeReaderSettings">&times;</button>
        </div>
        <div class="modal-body">
            <div class="setting-group">
                <div class="setting-title">字号</div>
                <div class="setting-buttons">
                    <button class="setting-btn" data-font="15">A− 小</button>
                    <button class="setting-btn" data-font="17">标准</button>
                    <button class="setting-btn" data-font="19">A+ 大</button>
                </div>
            </div>
            <div class="setting-group">
                <div class="setting-title">行间距</div>
                <div class="setting-buttons">
                    <button class="setting-btn" data-line="1.5">紧凑</button>
                    <button class="setting-btn" data-line="1.75">标准</button>
                    <button class="setting-btn" data-line="2.0">宽松</button>
                </div>
            </div>
            <div class="setting-group">
                <div class="setting-title">主题</div>
                <div class="setting-buttons">
                    <button class="setting-btn" data-theme="light">浅色</button>
                    <button class="setting-btn" data-theme="dark">暗色</button>
                </div>
            </div>
            <div class="setting-group">
                <div class="setting-title">模式</div>
                <div class="setting-buttons">
                    <button class="setting-btn" id="toggleImmersiveBtn">切换沉浸阅读</button>
                </div>
            </div>
        </div>
    </div>
</div>
```

- [ ] **Step 2: 新增设置弹窗 CSS**

在 `article.ftl` 的 `</style>` 之前追加：

```css
/* ========== 阅读设置弹窗 ========== */
.reader-settings-modal { width: 420px; }
.setting-group { margin-bottom: 20px; }
.setting-title { font-size: 14px; font-weight: 600; color: #252933; margin-bottom: 10px; }
.setting-buttons { display: flex; gap: 10px; }
.setting-btn {
    flex: 1;
    padding: 8px 12px;
    border: 1px solid #e4e6eb;
    border-radius: 6px;
    background: #fff;
    color: #515767;
    font-size: 14px;
    cursor: pointer;
    transition: all 0.2s;
}
.setting-btn:hover { border-color: #1e80ff; color: #1e80ff; }
.setting-btn.active { background: #eaf2ff; border-color: #1e80ff; color: #1e80ff; }
```

- [ ] **Step 3: 新增设置弹窗 JS**

在 `article-static.js` 的 IIFE 内部末尾追加：

```javascript
// ========== 阅读设置弹窗 ==========
var readerOverlay = document.getElementById('readerSettingsOverlay');
function openReaderSettings() {
    if (!readerOverlay) return;
    readerOverlay.classList.add('open');
    document.body.style.overflow = 'hidden';
}
function closeReaderSettings() {
    if (!readerOverlay) return;
    readerOverlay.classList.remove('open');
    document.body.style.overflow = '';
}

var closeReaderBtn = document.getElementById('closeReaderSettings');
if (closeReaderBtn) closeReaderBtn.addEventListener('click', closeReaderSettings);
if (readerOverlay) {
    readerOverlay.addEventListener('click', function (e) {
        if (e.target === readerOverlay) closeReaderSettings();
    });
}

// 字号
document.querySelectorAll('.setting-btn[data-font]').forEach(function (b) {
    b.addEventListener('click', function () {
        readerSettings.fontSize = parseInt(this.getAttribute('data-font'), 10);
        saveReaderSettings(readerSettings);
        applyReaderSettings(readerSettings);
    });
});
// 行距
document.querySelectorAll('.setting-btn[data-line]').forEach(function (b) {
    b.addEventListener('click', function () {
        readerSettings.lineHeight = parseFloat(this.getAttribute('data-line'));
        saveReaderSettings(readerSettings);
        applyReaderSettings(readerSettings);
    });
});
// 主题
document.querySelectorAll('.setting-btn[data-theme]').forEach(function (b) {
    b.addEventListener('click', function () {
        readerSettings.theme = this.getAttribute('data-theme');
        saveReaderSettings(readerSettings);
        applyReaderSettings(readerSettings);
    });
});
// 沉浸开关
var toggleImmersiveBtn = document.getElementById('toggleImmersiveBtn');
if (toggleImmersiveBtn) {
    toggleImmersiveBtn.addEventListener('click', function () {
        readerSettings.immersive = !readerSettings.immersive;
        saveReaderSettings(readerSettings);
        applyReaderSettings(readerSettings);
        closeReaderSettings();
    });
}
```

- [ ] **Step 4: 新增右侧悬浮栏「设置」入口**

在 `article.ftl` 的 `.action-sidebar`（约第 2407 行）中、`id="sideImmersiveBtn"` 之后追加：

```html
<div class="action-item" id="sideSettingsBtn">
    <div class="action-icon">
        <svg viewBox="0 0 24 24" width="20" height="20"><path d="M19.14 12.94c.04-.31.06-.63.06-.94s-.02-.63-.06-.94l2.03-1.58a.996.996 0 0 0 .25-1.52L19.5 5.64c-.26-.46-.78-.64-1.24-.42l-2.5 1.07c-.33-.26-.73-.44-1.11-.51-.25-.44-.54-.85-.85-1.24l-.6-1.56a.996.996 0 0 0-.99-.72l-1.8.64c-.36.13-.76.3-1.24.51-.38.07-.78.25-1.11.51L5.86 3.28c-.45-.21-.98-.04-1.24.42L2.7 7.02c-.34.5-.21 1.16.25 1.52l2.01 1.56c-.04.31-.06.63-.06.94s.02.63.06.94l-2.03 1.58a.996.996 0 0 0-.25 1.52l1.92 3.32c.26.46.78.64 1.24.42l2.5-1.07c.33.26.73.44 1.11.51.25.44.54.85.85 1.24l.6 1.56c.09.41.32.82.72.99.4.18.83.18 1.27 0l1.8-.64c.36-.13.76-.3 1.24-.51.38-.07.78-.25 1.11-.51l2.5 1.07c.45.21.98.04 1.24-.42l1.92-3.32c.26-.46.12-1.1-.25-1.52zM12 15.5c-1.93 0-3.5-1.57-3.5-3.5s1.57-3.5 3.5-3.5 3.5 1.57 3.5 3.5-1.57 3.5-3.5 3.5z" fill="currentColor"/></svg>
    </div>
    <div class="action-count">设置</div>
</div>
```

在 `article-static.js` 的 IIFE 内部末尾追加：

```javascript
// 右侧悬浮栏「设置」入口
var sideSettingsBtn = document.getElementById('sideSettingsBtn');
if (sideSettingsBtn) sideSettingsBtn.addEventListener('click', openReaderSettings);
```

- [ ] **Step 5: 验证 JS 语法**

Run: `node --check .../article-static.js` → 无输出、退出码 0。

- [ ] **Step 6: 浏览器回归**

访问详情页，逐项验证：
- 点击右侧「设置」→ 弹出阅读设置弹窗（复用 `.modal-overlay.open` 遮罩）✓
- 字号 小/标准/大 三档切换，正文 `.article-body` 字号实时变化 ✓
- 行距 紧凑/标准/宽松 三档切换 ✓
- 主题 浅色/暗色 切换：正文、标题、代码块、侧栏、弹窗配色变化 ✓
- 刷新页面后所有设置保留（`localStorage`）✓
- 暗色下设置弹窗配色可读 ✓

- [ ] **Step 7: Commit**

```bash
git add .../article.ftl .../article-static.js
git commit -m "feat(detail): add reader settings modal and sidebar entry"
```

---

## 任务 1-4：沉浸模式（修复死按钮）

**Files:**
- Modify: `.../article.ftl`（CSS 追加到 `</style>` 前；HTML 追加退出按钮）
- Modify: `.../article-static.js`

- [ ] **Step 1: 新增沉浸模式 CSS**

在 `article.ftl` 的 `</style>` 之前追加：

```css
/* ========== 沉浸模式（body.immersive-mode） ========== */
.article-topbar,
.action-sidebar {
    transition: opacity 0.3s ease;
}
body.immersive-mode .article-topbar,
body.immersive-mode .action-sidebar {
    opacity: 0;
    pointer-events: none;
}
body.immersive-mode .toc-sidebar {
    display: none;
}
body.immersive-mode .main-wrapper {
    max-width: 920px;
}
body.immersive-mode .content-area {
    max-width: 100%;
}
/* 沉浸模式下提供退出按钮 */
.immersive-exit-btn {
    display: none;
    position: fixed;
    top: 72px;
    right: 24px;
    z-index: 99;
    padding: 6px 14px;
    border: 1px solid #e4e6eb;
    border-radius: 6px;
    background: #fff;
    color: #515767;
    font-size: 13px;
    cursor: pointer;
    box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}
.immersive-exit-btn:hover { color: #1e80ff; border-color: #1e80ff; }
body.immersive-mode .immersive-exit-btn { display: block; }
body.dark .immersive-exit-btn { background: #1e1e1e; color: #8a919f; border-color: #2d333b; }
```

- [ ] **Step 2: 新增沉浸退出按钮 HTML**

在 `article.ftl` 中 `.toc-float-btn`（约第 2457 行）之后插入：

```html
<!-- 沉浸模式退出按钮 -->
<button class="immersive-exit-btn" id="immersiveExitBtn">退出沉浸</button>
```

- [ ] **Step 3: 新增沉浸切换 JS**

在 `article-static.js` 的 IIFE 内部末尾追加：

```javascript
// ========== 沉浸模式 ==========
function toggleImmersiveMode() {
    readerSettings.immersive = !readerSettings.immersive;
    saveReaderSettings(readerSettings);
    applyReaderSettings(readerSettings);
}
var sideImmersiveBtn = document.getElementById('sideImmersiveBtn');
if (sideImmersiveBtn) sideImmersiveBtn.addEventListener('click', toggleImmersiveMode);
var immersiveExitBtn = document.getElementById('immersiveExitBtn');
if (immersiveExitBtn) immersiveExitBtn.addEventListener('click', toggleImmersiveMode);
```

- [ ] **Step 4: 验证 JS 语法**

Run: `node --check .../article-static.js` → 无输出、退出码 0。

- [ ] **Step 5: 浏览器回归**

- 点击右侧悬浮栏「沉浸」→ 顶栏、侧栏、悬浮栏淡出，正文居中加宽 ✓
- 右上角出现「退出沉浸」按钮，点击后恢复原布局 ✓
- 沉浸状态刷新后保持（已持久化）✓
- 沉浸模式下可通过「退出沉浸」正常退出（不再有死按钮问题）✓

- [ ] **Step 6: Commit**

```bash
git add .../article.ftl .../article-static.js
git commit -m "feat(detail): enable immersive reading mode with exit button"
```

- [ ] **Step 7: 更新 CHANGELOG**

在 `docs/CHANGELOG.md` 新增小节（阶段 1 完成时）：

```markdown
## 2026-08-14 — 详情页阅读体验升级（阶段1）

### 变更
1. 新增顶部阅读进度条，随滚动实时更新。
2. 新增阅读设置弹窗：字号（15/17/19px）、行距（1.5/1.75/2.0）、浅色/暗色主题，localStorage 持久化。
3. 启用沉浸阅读模式：修复原无效的「沉浸」按钮，沉浸时隐藏顶栏/侧栏/悬浮栏并居中加宽正文，提供「退出沉浸」按钮。
```

```bash
git add docs/CHANGELOG.md
git commit -m "docs(changelog): 记录详情页阅读体验升级（阶段1）"
```

---

## 阶段 1 验收清单

- [ ] 阅读进度条随滚动更新，无性能问题（passive 监听）
- [ ] 字号/行距/主题切换即时生效且刷新保留
- [ ] 暗色模式下主阅读区域（正文/标题/代码/侧栏/弹窗）可读
- [ ] 沉浸模式可进入、可退出，状态持久化
- [ ] 无 console 报错，`node --check` 通过
- [ ] 阶段 1 各任务 commit 就绪

---

## 自检记录

- **Spec 覆盖**：进度条 ✓（1-1）、字号/行距 ✓（1-2/1-3）、暗色 ✓（1-2/1-3）、沉浸 ✓（1-4）、持久化 ✓（1-2）、设置面板整合 ✓（1-3）。
- **无占位符**：所有步骤含完整代码。
- **类型/命名一致性**：阅读偏好对象字段统一为 `fontSize / lineHeight / theme / immersive`；设置按钮 `data-font`(数字) / `data-line`(数字) / `data-theme`(light|dark)；`applyReaderSettings / saveReaderSettings / loadReaderSettings / syncSettingButtons` 在任务间一致；`toggleImmersiveMode` 由侧栏按钮与弹窗开关与退出按钮共用。

> 阶段 2（互动链路：分享/点赞收藏动效/真实收藏与举报）、阶段 3（留存闭环：正文尾部作者卡片/读完推荐/回顶/读完提示）、阶段 4（首页分栏体验）为独立计划，待阶段 1 验收提交后再分别成稿执行（分层交付）。
