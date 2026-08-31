<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta http-equiv="X-UA-Compatible" content="IE=edge">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0, viewport-fit=cover">
    <title>${title!''} - 逐日Coding</title>
    <style>
        * { box-sizing: border-box; }
        body {
            margin: 0;
            padding: 0;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans", sans-serif;
            background: #f5f6f7;
            color: #252933;
            line-height: 1.75;
        }
        a { text-decoration: none; color: #1e80ff; }
        img { max-width: 100%; height: auto; }

        /* 主体布局 */
        .main-wrapper {
            display: flex;
            justify-content: center;
            padding: 80px 20px 40px;
            max-width: 1400px;
            margin: 0 auto;
            gap: 24px;
        }
        .content-area {
            flex: 1;
            max-width: 820px;
            min-width: 0;
        }
        .content-card {
            background: #fff;
            border-radius: 4px;
            padding: 32px;
            box-shadow: 0 1px 2px rgba(0,0,0,0.05);
        }

        /* 文章标题 */
        .article-title {
            font-size: 30px;
            font-weight: 700;
            line-height: 1.4;
            margin: 0 0 20px;
            color: #252933;
            word-break: break-word;
        }

        /* 作者信息（掘金风格：水平布局，头像昵称可点击跳转作者主页） */
        .author-header {
            display: flex;
            align-items: center;
            margin-bottom: 32px;
            padding-bottom: 24px;
            border-bottom: 1px solid #e4e6eb;
        }
        .author-avatar {
            width: 48px;
            height: 48px;
            border-radius: 50%;
            background: #e4e6eb;
            margin-right: 12px;
            overflow: hidden;
            flex-shrink: 0;
            display: block;
            transition: box-shadow 0.2s;
        }
        .author-avatar:hover {
            box-shadow: 0 0 0 3px #eaf2ff;
        }
        .author-avatar img {
            width: 100%;
            height: 100%;
            object-fit: cover;
            display: block;
        }
        .author-info {
            flex: 1;
            min-width: 0;
        }
        /* 昵称 + 逐力值等级徽章 同行显示 */
        .author-name-row {
            display: flex;
            align-items: center;
            gap: 8px;
            margin-bottom: 4px;
            line-height: 1.4;
        }
        .author-name {
            font-size: 16px;
            font-weight: 600;
            color: #252933;
            cursor: pointer;
            transition: color 0.2s;
        }
        .author-name:hover {
            color: #1e80ff;
        }
        .author-level-badge {
            display: inline-flex;
            align-items: center;
            gap: 4px;
            font-size: 12px;
            font-weight: 500;
            color: #1e80ff;
            background: #eaf2ff;
            border: 1px solid #d5e6ff;
            border-radius: 4px;
            padding: 1px 8px;
            line-height: 20px;
            white-space: nowrap;
            flex-shrink: 0;
        }
        .author-level-badge svg {
            width: 12px;
            height: 12px;
            fill: #1e80ff;
        }
        .publish-time {
            font-size: 13px;
            color: #8a919f;
        }
        .follow-btn {
            margin-left: 16px;
            padding: 6px 18px;
            border: 1px solid #1e80ff;
            border-radius: 4px;
            background: #fff;
            color: #1e80ff;
            font-size: 14px;
            cursor: pointer;
            flex-shrink: 0;
        }
        .follow-btn.active {
            background: #1e80ff;
            color: #fff;
        }

        /* 文章正文 */
        .article-body {
            font-size: 16px;
            color: #333;
        }
        .article-body h1, .article-body h2, .article-body h3 {
            color: #252933;
            font-weight: 600;
            margin-top: 20px;
            margin-bottom: 20px;
            line-height: 1.4;
        }
        .article-body h1 { font-size: 26px; }
        .article-body h2 { font-size: 22px; }
        .article-body h3 { font-size: 18px; }
        .article-body p {
            margin: 0 0 18px;
            word-break: break-word;
        }
        .article-body img {
            display: block;
            max-width: 100%;
            height: auto;
            box-sizing: border-box;
            margin: 20px auto;
            border-radius: 4px;
            box-shadow: 0 4px 12px rgba(0,0,0,0.08);
        }
        .article-body pre {
            background: #f7f8fa;
            border-radius: 4px;
            padding: 16px;
            overflow-x: auto;
            font-size: 14px;
            line-height: 1.6;
            margin: 0 0 18px;
        }
        .article-body code {
            font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace;
            background: #f2f3f5;
            padding: 2px 6px;
            border-radius: 3px;
            font-size: 14px;
            color: #d63200;
        }
        .article-body pre code {
            background: transparent;
            padding: 0;
            color: inherit;
        }
        .article-body blockquote {
            margin: 0 0 18px;
            padding: 12px 16px;
            border-left: 4px solid #1e80ff;
            background: #f7f8fa;
            color: #666;
        }
        .article-body table {
            width: 100%;
            border-collapse: collapse;
            margin-bottom: 18px;
        }
        .article-body th, .article-body td {
            border: 1px solid #e4e6eb;
            padding: 10px 14px;
            text-align: left;
        }
        .article-body th {
            background: #f7f8fa;
            font-weight: 600;
        }
        .article-body ul, .article-body ol {
            margin: 0 0 18px;
            padding-left: 24px;
        }

        /* 互动按钮 */
        .action-bar {
            display: flex;
            gap: 16px;
            margin-top: 40px;
            padding-top: 24px;
            border-top: 1px solid #e4e6eb;
        }
        .action-btn {
            display: flex;
            align-items: center;
            gap: 6px;
            padding: 8px 18px;
            border: 1px solid #e4e6eb;
            border-radius: 4px;
            background: #fff;
            color: #8a919f;
            font-size: 14px;
            cursor: pointer;
            transition: all 0.2s;
        }
        .action-btn:hover { background: #f7f8fa; }
        .action-btn.active {
            color: #1e80ff;
            border-color: #1e80ff;
            background: #eaf2ff;
        }
        .action-btn svg {
            width: 18px;
            height: 18px;
            fill: currentColor;
        }

        .publish-meta {
            font-size: 13px;
            color: #8a919f;
            display: flex;
            align-items: center;
            gap: 6px;
        }
        .meta-divider {
            color: #c4c9d1;
        }
        .read-count, .read-time {
            font-size: 13px;
            color: #8a919f;
        }
        .meta-icon {
            vertical-align: middle;
            margin-right: 2px;
            display: inline;
        }
        .column-tag {
            font-size: 13px;
            color: #1e80ff;
        }

        .author-info-card {
            background: #fff;
            border-radius: 4px;
            padding: 20px 0 0;
            box-shadow: 0 1px 2px rgba(0,0,0,0.05);
            margin-bottom: 16px;
        }
        /* 掘金风格：头像 + 昵称/等级/职位 水平排列 */
        .author-info-card .author-info-head {
            display: flex;
            align-items: center;
            gap: 12px;
            padding: 0 16px 16px;
        }
        .author-info-card .author-avatar-link {
            width: 48px;
            height: 48px;
            border-radius: 50%;
            overflow: hidden;
            flex-shrink: 0;
            display: block;
            border: 2px solid #1e80ff;
            transition: box-shadow 0.2s;
        }
        .author-info-card .author-avatar-link:hover {
            box-shadow: 0 0 0 3px #eaf2ff;
        }
        .author-info-card .avatar {
            width: 100%;
            height: 100%;
            border-radius: 50%;
            object-fit: cover;
            display: block;
        }
        .author-info-card .author-head-info {
            flex: 1;
            min-width: 0;
        }
        .author-info-card .name-row {
            display: flex;
            align-items: center;
            gap: 6px;
            margin-bottom: 4px;
        }
        .author-info-card .name {
            font-size: 16px;
            font-weight: 600;
            color: #252933;
            cursor: pointer;
            transition: color 0.2s;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }
        .author-info-card .name:hover {
            color: #1e80ff;
        }
        .author-info-card .badge {
            font-size: 12px;
            font-weight: 500;
            color: #1e80ff;
            background: #eaf2ff;
            border: 1px solid #d5e6ff;
            padding: 1px 6px;
            border-radius: 4px;
            white-space: nowrap;
            flex-shrink: 0;
        }
        .author-info-card .author-meta-line {
            font-size: 12px;
            color: #8a919f;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }
        .author-info-card .job-title {
            font-size: 12px;
            color: #515767;
        }
        .author-info-card .company {
            font-size: 12px;
            color: #8a919f;
        }
        .author-info-card .stats {
            display: flex;
            justify-content: space-around;
            padding: 12px 16px;
            border-top: 1px solid #f2f3f5;
            margin-bottom: 12px;
        }
        .author-info-card .stat-item {
            text-align: center;
        }
        .author-info-card .stat-value {
            font-size: 16px;
            font-weight: 600;
            color: #252933;
        }
        .author-info-card .stat-label {
            font-size: 12px;
            color: #8a919f;
        }
        .author-info-card .action-btns {
            display: flex;
            gap: 8px;
            padding: 0 16px;
        }
        .author-info-card .follow-btn {
            flex: 1;
            padding: 8px;
            border: none;
            border-radius: 4px;
            background: #1e80ff;
            color: #fff;
            font-size: 14px;
            font-weight: 500;
            cursor: pointer;
        }
        .author-info-card .message-btn {
            flex: 1;
            padding: 8px;
            border: 1px solid #e4e6eb;
            border-radius: 4px;
            background: #fff;
            color: #515767;
            font-size: 14px;
            cursor: pointer;
        }

        /* 右侧目录（固定高度 + 内部滚动，避免标题过多把下方卡片挤出视口） */
        .toc-sidebar {
            width: 260px;
            flex-shrink: 0;
            position: sticky;
            top: 80px;
            align-self: flex-start;
            max-height: calc(100vh - 100px);
            overflow-y: auto;
            overscroll-behavior: contain;
            scrollbar-width: thin;
            scrollbar-color: #d4d9e0 transparent;
        }
        .toc-sidebar::-webkit-scrollbar { width: 6px; }
        .toc-sidebar::-webkit-scrollbar-thumb { background: #d4d9e0; border-radius: 3px; }
        .toc-sidebar::-webkit-scrollbar-track { background: transparent; }
        .toc-card {
            background: #fff;
            border-radius: 4px;
            padding: 16px 0;
            box-shadow: 0 1px 2px rgba(0,0,0,0.05);
        }
        /* 侧边栏随滚动切换卡片（阅读进度阶段） */
        .toc-sidebar .stage-toc,
        .toc-sidebar .stage-related,
        .toc-sidebar .stage-featured {
            display: none;
            animation: sidebarFadeIn 0.35s ease;
        }
        .toc-sidebar[data-stage="toc"] .stage-toc { display: block; }
        .toc-sidebar[data-stage="related"] .stage-related { display: block; }
        .toc-sidebar[data-stage="featured"] .stage-featured { display: block; }
        .toc-sidebar[data-stage="toc-related"] .stage-toc,
        .toc-sidebar[data-stage="toc-related"] .stage-related { display: block; }
        .toc-sidebar[data-stage="end"] .stage-related,
        .toc-sidebar[data-stage="end"] .stage-featured { display: block; }
        @keyframes sidebarFadeIn {
            from { opacity: 0; transform: translateY(6px); }
            to { opacity: 1; transform: translateY(0); }
        }
        .toc-title {
            font-size: 15px;
            font-weight: 600;
            color: #252933;
            padding: 0 16px 12px;
            border-bottom: 1px solid #e4e6eb;
            margin-bottom: 8px;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .toc-collapse-btn {
            background: none;
            border: none;
            font-size: 12px;
            color: #8a919f;
            cursor: pointer;
            padding: 2px 6px;
            transition: color 0.2s;
        }
        .toc-collapse-btn:hover {
            color: #1e80ff;
        }
        .toc-list.collapsed {
            display: none;
        }
        .toc-list {
            list-style: none;
            margin: 0;
            padding: 0;
            /* 目录固定高度，标题级数再多也在内部滚动，不挤压下方卡片 */
            max-height: 360px;
            overflow-y: auto;
            scrollbar-width: thin;
            scrollbar-color: #d4d9e0 transparent;
        }
        .toc-list::-webkit-scrollbar { width: 6px; }
        .toc-list::-webkit-scrollbar-thumb { background: #d4d9e0; border-radius: 3px; }
        .toc-list::-webkit-scrollbar-track { background: transparent; }
        .toc-list li a {
            display: block;
            padding: 8px 16px;
            font-size: 14px;
            color: #515767;
            border-left: 3px solid transparent;
            transition: all 0.2s;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }
        .toc-list li a:hover {
            color: #1e80ff;
            background: #f7f8fa;
        }
        .toc-list li a.active {
            color: #1e80ff;
            background: #eaf2ff;
            border-left-color: #1e80ff;
        }
        .toc-list li.level-2 a { padding-left: 28px; }
        .toc-list li.level-3 a { padding-left: 40px; font-size: 13px; }

        /* ========== 专栏卡片样式 ========== */
        .column-section {
            display: none;
            margin-top: 32px;
            border-top: 1px solid #e4e6eb;
            padding-top: 24px;
        }
        .column-section.visible {
            display: block;
        }
        .column-section-title {
            font-size: 15px;
            font-weight: 600;
            color: #252933;
            margin-bottom: 12px;
        }
        .column-card {
            display: flex;
            align-items: center;
            background: #f7f8fa;
            border-radius: 4px;
            padding: 16px;
            gap: 16px;
        }
        .column-cover {
            width: 100px;
            height: 70px;
            border-radius: 4px;
            object-fit: cover;
            background: #e4e6eb;
            flex-shrink: 0;
        }
        .column-info {
            flex: 1;
            min-width: 0;
        }
        .column-name {
            font-size: 15px;
            font-weight: 600;
            color: #252933;
            margin-bottom: 4px;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }
        .column-desc {
            font-size: 13px;
            color: #8a919f;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }
        .column-meta {
            display: flex;
            align-items: center;
            gap: 12px;
            flex-shrink: 0;
        }
        .column-meta-item {
            font-size: 12px;
            color: #8a919f;
            text-align: center;
        }
        .column-meta-value {
            font-size: 14px;
            font-weight: 600;
            color: #252933;
        }
        .column-subscribe-btn {
            padding: 6px 16px;
            border: 1px solid #1e80ff;
            border-radius: 4px;
            background: #fff;
            color: #1e80ff;
            font-size: 13px;
            cursor: pointer;
            transition: all 0.2s;
            flex-shrink: 0;
        }
        .column-subscribe-btn.active {
            background: #1e80ff;
            color: #fff;
        }
        .column-nav {
            display: flex;
            justify-content: space-between;
            margin-top: 12px;
            gap: 16px;
        }
        .column-nav a {
            font-size: 13px;
            color: #1e80ff;
            display: flex;
            align-items: center;
            gap: 4px;
        }
        .column-nav a:hover {
            color: #0056d6;
        }
        .column-nav a.disabled {
            color: #c4c9d1;
            pointer-events: none;
        }

        /* ========== 评论区样式 ========== */
        .comment-section {
            background: #fff;
            border-radius: 4px;
            padding: 24px 32px;
            box-shadow: 0 1px 2px rgba(0,0,0,0.05);
            margin-top: 16px;
        }
        .comment-title {
            font-size: 18px;
            font-weight: 600;
            color: #252933;
            margin-bottom: 20px;
            padding-bottom: 16px;
            border-bottom: 1px solid #e4e6eb;
        }
        .comment-input-area {
            display: flex;
            gap: 12px;
            margin-bottom: 24px;
            align-items: flex-start;
        }
        .comment-input-avatar {
            width: 40px;
            height: 40px;
            border-radius: 50%;
            background: #e4e6eb;
            flex-shrink: 0;
            overflow: hidden;
        }
        .comment-input-avatar img {
            width: 100%;
            height: 100%;
            object-fit: cover;
        }
        .comment-input-wrap {
            flex: 1;
            display: flex;
            flex-direction: column;
            gap: 8px;
        }
        .comment-input-wrap textarea {
            width: 100%;
            min-height: 72px;
            padding: 10px 12px;
            border: 1px solid #e4e6eb;
            border-radius: 4px;
            font-size: 14px;
            font-family: inherit;
            color: #252933;
            resize: vertical;
            outline: none;
            transition: border-color 0.2s;
            box-sizing: border-box;
        }
        .comment-input-wrap textarea:focus {
            border-color: #1e80ff;
        }
        .comment-input-wrap .comment-input-footer {
            display: flex;
            justify-content: space-between;
            align-items: center;
            gap: 8px;
        }
        .comment-footer-left {
            display: flex;
            align-items: center;
            gap: 12px;
        }
        .comment-footer-right {
            display: flex;
            align-items: center;
            gap: 12px;
        }
        .comment-input-wrap .login-tip {
            font-size: 13px;
            color: #8a919f;
        }
        .comment-input-wrap .login-tip a {
            color: #1e80ff;
            cursor: pointer;
        }
        .comment-toolbar {
            display: flex;
            align-items: center;
            gap: 4px;
        }
        .comment-tool-btn {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            width: 30px;
            height: 30px;
            padding: 0;
            border: none;
            border-radius: 4px;
            background: transparent;
            font-size: 16px;
            color: #8a919f;
            cursor: pointer;
            transition: background 0.2s, color 0.2s;
        }
        .comment-tool-btn:hover {
            background: #f0f5ff;
            color: #1e80ff;
        }
        .comment-char-count {
            font-size: 12px;
            color: #c4c9d1;
        }
        .comment-image-preview, .reply-image-preview {
            display: flex;
            gap: 8px;
            flex-wrap: wrap;
        }
        .comment-image-preview .preview-item, .reply-image-preview .preview-item {
            position: relative;
            width: 72px;
            height: 72px;
        }
        .preview-item img {
            width: 72px;
            height: 72px;
            object-fit: cover;
            border-radius: 4px;
        }
        .preview-item .preview-remove {
            position: absolute;
            top: -6px;
            right: -6px;
            width: 18px;
            height: 18px;
            background: #ff4d4f;
            color: #fff;
            font-size: 12px;
            line-height: 18px;
            text-align: center;
            border-radius: 50%;
            cursor: pointer;
        }
        .comment-image {
            max-width: 100%;
            max-height: 200px;
            border-radius: 6px;
            margin: 6px 0;
            display: block;
            cursor: pointer;
        }
        .comment-emoji-picker {
            display: none;
            position: fixed;
            background: #fff;
            border: 1px solid #e4e6eb;
            border-radius: 8px;
            padding: 10px;
            box-shadow: 0 4px 16px rgba(0,0,0,0.12);
            width: 320px;
            z-index: 1000;
        }
        .comment-emoji-picker .comment-emoji-grid {
            display: flex;
            flex-wrap: wrap;
            gap: 4px;
            max-height: 200px;
            overflow-y: auto;
        }
        .comment-emoji-picker .comment-emoji-item {
            width: 32px;
            height: 32px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 20px;
            cursor: pointer;
            border-radius: 4px;
        }
        .comment-emoji-picker .comment-emoji-item:hover {
            background: #f0f5ff;
        }
        .comment-submit-btn {
            padding: 6px 20px;
            border: none;
            border-radius: 4px;
            background: #1e80ff;
            color: #fff;
            font-size: 14px;
            cursor: pointer;
            transition: background 0.2s;
        }
        .comment-submit-btn:hover {
            background: #0056d6;
        }
        .comment-submit-btn:disabled {
            background: #a0c4ff;
            cursor: not-allowed;
        }
        .comment-list {
            list-style: none;
            margin: 0;
            padding: 0;
        }
        .comment-item {
            padding: 16px 0;
            border-bottom: 1px solid #f2f3f5;
        }
        .comment-item:last-child {
            border-bottom: none;
        }
        .comment-user {
            display: flex;
            align-items: center;
            gap: 8px;
            margin-bottom: 8px;
        }
        .comment-user-avatar {
            width: 32px;
            height: 32px;
            border-radius: 50%;
            background: #e4e6eb;
            overflow: hidden;
            flex-shrink: 0;
        }
        .comment-user-avatar img {
            width: 100%;
            height: 100%;
            object-fit: cover;
        }
        .comment-user-name {
            font-size: 14px;
            font-weight: 500;
            color: #252933;
        }
        .comment-user-time {
            font-size: 12px;
            color: #8a919f;
            margin-left: auto;
        }
        .comment-content {
            font-size: 14px;
            color: #333;
            line-height: 1.6;
            margin-bottom: 8px;
            word-break: break-word;
        }
        .comment-actions {
            display: flex;
            gap: 16px;
            align-items: center;
        }
        .comment-action-btn {
            display: flex;
            align-items: center;
            gap: 4px;
            font-size: 13px;
            color: #8a919f;
            cursor: pointer;
            background: none;
            border: none;
            padding: 2px 4px;
            transition: color 0.2s;
        }
        .comment-action-btn:hover {
            color: #1e80ff;
        }
        .comment-action-btn.active {
            color: #1e80ff;
        }
        .comment-action-btn svg {
            width: 16px;
            height: 16px;
            fill: currentColor;
        }
        .reply-list {
            margin-top: 8px;
            margin-left: 40px;
            background: #f7f8fa;
            border-radius: 4px;
            padding: 8px 12px;
        }
        .reply-item {
            padding: 6px 0;
            border-bottom: 1px solid #eef0f2;
            font-size: 13px;
            color: #333;
        }
        .reply-item:last-child {
            border-bottom: none;
        }
        .reply-user {
            font-weight: 500;
            color: #1e80ff;
        }
        .reply-more-btn {
            font-size: 13px;
            color: #1e80ff;
            cursor: pointer;
            background: none;
            border: none;
            padding: 6px 0;
            margin-left: 40px;
        }
        .reply-more-btn:hover {
            color: #0056d6;
        }
        .reply-input-area {
            margin-top: 8px;
            margin-left: 40px;
            background: #fff;
            border: 1px solid #e4e6eb;
            border-radius: 4px;
            padding: 8px;
        }
        .reply-input-area textarea {
            width: 100%;
            min-height: 48px;
            border: none;
            resize: none;
            font-size: 13px;
            font-family: inherit;
            color: #252933;
            outline: none;
            box-sizing: border-box;
        }
        .reply-input-area textarea:focus {
            border: none;
        }
        .reply-input-footer {
            display: flex;
            align-items: center;
            gap: 8px;
            margin-top: 6px;
            padding-top: 6px;
            border-top: 1px solid #f2f3f5;
        }
        .reply-input-footer .comment-char-count {
            margin-left: auto;
        }
        .reply-input-area .reply-send-btn {
            padding: 6px 14px;
            border: none;
            border-radius: 4px;
            background: #1e80ff;
            color: #fff;
            font-size: 13px;
            cursor: pointer;
        }
        .reply-input-area .reply-cancel-btn {
            padding: 6px 14px;
            border: 1px solid #e4e6eb;
            border-radius: 4px;
            background: #fff;
            color: #8a919f;
            font-size: 13px;
            cursor: pointer;
        }
        .reply-action-btn {
            margin-left: 8px;
            font-size: 12px;
            color: #1e80ff;
            cursor: pointer;
            background: none;
            border: none;
            padding: 0;
        }
        .reply-action-btn:hover {
            color: #0056d6;
        }
        .comment-empty {
            text-align: center;
            padding: 40px 0;
            color: #8a919f;
            font-size: 14px;
        }
        .comment-empty svg {
            width: 48px;
            height: 48px;
            fill: #c4c9d1;
            margin-bottom: 12px;
        }
        .load-more-btn {
            display: block;
            width: 100%;
            padding: 10px;
            border: 1px solid #e4e6eb;
            border-radius: 4px;
            background: #fff;
            color: #1e80ff;
            font-size: 14px;
            cursor: pointer;
            text-align: center;
            margin-top: 16px;
            transition: all 0.2s;
        }
        .load-more-btn:hover {
            background: #f7f8fa;
        }
        .load-more-btn:disabled {
            color: #c4c9d1;
            cursor: not-allowed;
        }

        /* ========== 为你推荐样式 ========== */
        .recommend-section {
            background: #fff;
            border-radius: 4px;
            padding: 24px 32px;
            box-shadow: 0 1px 2px rgba(0,0,0,0.05);
            margin-top: 16px;
        }
        .recommend-title {
            font-size: 18px;
            font-weight: 600;
            color: #252933;
            margin-bottom: 16px;
            padding-bottom: 16px;
            border-bottom: 1px solid #e4e6eb;
        }
        .recommend-grid {
            display: grid;
            grid-template-columns: repeat(3, 1fr);
            gap: 16px;
            list-style: none;
            margin: 0;
            padding: 0;
        }
        .recommend-card {
            display: flex;
            flex-direction: column;
            text-decoration: none;
            border-radius: 8px;
            overflow: hidden;
            border: 1px solid #f2f3f5;
            transition: transform 0.2s, box-shadow 0.2s;
        }
        .recommend-card:hover {
            transform: translateY(-2px);
            box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
        }
        .recommend-card-cover {
            width: 100%;
            aspect-ratio: 16 / 10;
            overflow: hidden;
            background: #f2f3f5;
        }
        .recommend-card-cover img {
            width: 100%;
            height: 100%;
            object-fit: cover;
            display: block;
        }
        .recommend-card-info {
            padding: 12px 14px 14px;
            display: flex;
            flex-direction: column;
            gap: 8px;
            flex: 1;
        }
        .recommend-card-title {
            font-size: 15px;
            font-weight: 500;
            color: #252933;
            line-height: 1.45;
            display: -webkit-box;
            -webkit-line-clamp: 2;
            -webkit-box-orient: vertical;
            overflow: hidden;
            min-height: 43px;
        }
        .recommend-card:hover .recommend-card-title {
            color: #1e80ff;
        }
        .recommend-card-meta {
            display: flex;
            flex-wrap: wrap;
            gap: 8px;
            align-items: center;
            font-size: 12px;
            color: #8a919f;
            margin-top: auto;
        }
        .recommend-card-meta .category-tag {
            display: inline-block;
            padding: 1px 6px;
            border-radius: 3px;
            background: #eaf2ff;
            color: #1e80ff;
            font-size: 11px;
        }
        .recommend-card-meta .meta-sep {
            color: #c4c9d1;
        }

        /* ========== 读完提示 ========== */
        .read-end-hint {
            text-align: center;
            color: #8a919f;
            font-size: 13px;
            letter-spacing: 2px;
            padding: 8px 0 4px;
            opacity: 0;
            transform: translateY(8px);
            transition: opacity 0.6s, transform 0.6s;
        }
        .read-end-hint.show {
            opacity: 1;
            transform: translateY(0);
        }
        .read-end-hint::before,
        .read-end-hint::after {
            content: "";
            display: inline-block;
            width: 40px;
            height: 1px;
            background: #e4e6eb;
            vertical-align: middle;
            margin: 0 12px;
        }

        /* ========== 正文尾部作者卡片 ========== */
        .end-author-card {
            background: #fff;
            border-radius: 8px;
            border: 1px solid #f2f3f5;
            padding: 24px;
            margin-top: 16px;
            display: flex;
            align-items: center;
            gap: 20px;
        }
        .end-author-card .ea-avatar {
            width: 64px;
            height: 64px;
            border-radius: 50%;
            object-fit: cover;
            flex-shrink: 0;
            background: #f2f3f5;
        }
        .end-author-card .ea-info {
            flex: 1;
            min-width: 0;
        }
        .end-author-card .ea-name {
            font-size: 17px;
            font-weight: 600;
            color: #252933;
            margin-bottom: 6px;
        }
        .end-author-card .ea-desc {
            font-size: 13px;
            color: #8a919f;
            line-height: 1.6;
            display: -webkit-box;
            -webkit-line-clamp: 2;
            -webkit-box-orient: vertical;
            overflow: hidden;
            margin-bottom: 10px;
        }
        .end-author-card .ea-followers {
            font-size: 12px;
            color: #8a919f;
        }
        .end-author-card .ea-followers b {
            color: #4e5969;
            font-weight: 600;
        }
        .end-author-card .ea-actions {
            display: flex;
            flex-direction: column;
            gap: 10px;
            flex-shrink: 0;
        }
        .end-author-card .ea-follow-btn {
            width: 96px;
            height: 34px;
            border: none;
            border-radius: 17px;
            background: #1e80ff;
            color: #fff;
            font-size: 14px;
            cursor: pointer;
            transition: all 0.2s;
        }
        .end-author-card .ea-follow-btn:hover {
            background: #0d6ae0;
        }
        .end-author-card .ea-follow-btn.active {
            background: #eaf2ff;
            color: #1e80ff;
        }
        .end-author-card .ea-links {
            display: flex;
            gap: 14px;
        }
        .end-author-card .ea-link {
            font-size: 13px;
            color: #1e80ff;
            text-decoration: none;
            white-space: nowrap;
        }
        .end-author-card .ea-link:hover {
            text-decoration: underline;
        }

        /* ========== 右侧边栏推荐卡片样式 ========== */
        .sidebar-recommend-card {
            margin-top: 16px;
            background: #fff;
            border-radius: 4px;
            padding: 16px 0;
            box-shadow: 0 1px 2px rgba(0,0,0,0.05);
        }
        .sidebar-recommend-title {
            font-size: 15px;
            font-weight: 600;
            color: #252933;
            padding: 0 16px 12px;
            border-bottom: 1px solid #e4e6eb;
            margin-bottom: 8px;
        }
        .sidebar-recommend-list {
            list-style: none;
            margin: 0;
            padding: 0;
        }
        .sidebar-recommend-item {
            padding: 0;
        }
        .sidebar-recommend-link {
            display: flex;
            flex-direction: column;
            padding: 10px 16px;
            text-decoration: none;
            transition: all 0.2s;
            border-bottom: 1px solid #f7f8fa;
        }
        .sidebar-recommend-link:hover {
            background: #f7f8fa;
        }
        .sidebar-recommend-link-title {
            font-size: 14px;
            color: #252933;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            margin-bottom: 4px;
            line-height: 1.4;
        }
        .sidebar-recommend-link-meta {
            font-size: 12px;
            color: #8a919f;
        }
        .sidebar-recommend-empty {
            padding: 20px 16px;
            text-align: center;
            font-size: 13px;
            color: #c4c9d1;
        }

        /* 移动端目录按钮 */
        .toc-float-btn {
            display: none;
            position: fixed;
            right: 16px;
            bottom: 24px;
            width: 44px;
            height: 44px;
            border-radius: 50%;
            background: #1e80ff;
            color: #fff;
            border: none;
            box-shadow: 0 4px 12px rgba(30,128,255,0.3);
            align-items: center;
            justify-content: center;
            cursor: pointer;
            z-index: 999;
        }
        .toc-float-btn svg {
            width: 22px;
            height: 22px;
            fill: #fff;
        }
        .toc-drawer {
            display: none;
            position: fixed;
            top: 0;
            right: 0;
            bottom: 0;
            width: 280px;
            background: #fff;
            box-shadow: -2px 0 8px rgba(0,0,0,0.1);
            z-index: 1001;
            overflow-y: auto;
            padding: 16px 0;
        }
        .toc-drawer.open { display: block; }
        .toc-drawer .toc-title {
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .toc-drawer .close-btn {
            background: none;
            border: none;
            font-size: 20px;
            color: #8a919f;
            cursor: pointer;
        }
        .drawer-mask {
            display: none;
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            background: rgba(0,0,0,0.4);
            z-index: 1000;
        }
        .drawer-mask.open { display: block; }

        /* ========== 右侧评论抽屉（掘金风：点击左侧评论栏弹出，不打断阅读） ========== */
        .comment-drawer-mask {
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            background: rgba(0,0,0,0.4);
            z-index: 1000;
            opacity: 0;
            visibility: hidden;
            transition: opacity 0.25s ease;
        }
        .comment-drawer-mask.open { opacity: 1; visibility: visible; }
        .comment-drawer {
            position: fixed;
            top: 0;
            right: 0;
            bottom: 0;
            width: 400px;
            max-width: 92vw;
            background: #fff;
            z-index: 1001;
            transform: translateX(100%);
            transition: transform 0.28s ease;
            display: flex;
            flex-direction: column;
            box-shadow: -4px 0 24px rgba(0,0,0,0.08);
        }
        .comment-drawer.open { transform: translateX(0); }
        .comment-drawer-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 16px 20px;
            border-bottom: 1px solid #f0f1f5;
        }
        .comment-drawer-title { font-size: 16px; font-weight: 600; color: #252933; }
        .comment-drawer-title span { color: #8a919f; font-size: 14px; font-weight: 400; margin-left: 4px; }
        .comment-drawer-close {
            background: none;
            border: none;
            font-size: 22px;
            line-height: 1;
            color: #8a919f;
            cursor: pointer;
            padding: 4px;
        }
        .comment-drawer-close:hover { color: #252933; }
        .comment-drawer-body {
            flex: 1;
            overflow-y: auto;
            padding: 16px 20px;
        }

        .action-sidebar {
            position: fixed;
            left: 24px;                     /* 距左屏留白，避免紧贴屏幕边缘 */
            top: 50%;
            transform: translateY(-50%);
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 16px;
            padding: 12px 10px;
            background: rgba(255,255,255,0.96);
            border-radius: 12px;            /* 离开屏幕边缘后四周统一圆角 */
            box-shadow: 0 6px 24px rgba(0,0,0,0.12), 0 1px 4px rgba(0,0,0,0.06);
            z-index: 999;
            color: #6e7681;                 /* 行为图标默认中性灰，取代纯黑提升层次 */
        }
        .action-sidebar .action-item {
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 4px;
            padding: 8px;
            cursor: pointer;
            border-radius: 50%;
            min-width: 48px;
            min-height: 48px;
            justify-content: center;
            transition: all 0.2s;
        }
        .action-sidebar .action-item:hover {
            background: #eaf2ff;
            color: #1e80ff;
        }
        .action-sidebar .action-item.active {
            background: #eaf2ff;
            color: #1e80ff;
        }
        .action-sidebar .action-icon {
            width: 20px;
            height: 20px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 20px;
        }
        .action-sidebar .action-count {
            font-size: 12px;
            color: #98a0ab;
        }
        /* 行为元素语义化配色：弱化“全黑”一致感，让彼此有区分度 */
        .action-sidebar #sideLikeBtn .action-icon { color: #f55d5d; }    /* 点赞=红 */
        .action-sidebar #sideShareBtn .action-icon { color: #00a870; }    /* 分享=绿 */
        .action-sidebar #sideReportBtn .action-icon { color: #f53f3f; }   /* 举报=红(警示) */
        .action-sidebar #sideImmersiveBtn .action-icon,
        .action-sidebar #sideSettingsBtn .action-icon,
        .action-sidebar #sideBackTopBtn .action-icon { color: #6e7681; }  /* 工具类=中性灰 */
        .action-sidebar #sideCommentBtn .action-icon { color: #4e7ff2; }  /* 评论=蓝 */
        .action-sidebar #sideCollectBtn .action-icon { color: #ffae33; }  /* 收藏=金 */
        .action-sidebar .author-mini-avatar {
            width: 36px;
            height: 36px;
            border-radius: 50%;
            object-fit: cover;
            border: 2px solid #1e80ff;
            position: relative;
        }
        .action-sidebar .mini-follow-badge {
            position: absolute;
            bottom: -2px;
            left: 50%;
            transform: translateX(-50%);
            font-size: 10px;
            color: #fff;
            background: #1e80ff;
            padding: 1px 6px;
            border-radius: 10px;
            white-space: nowrap;
        }
        .action-sidebar .hidden-item {
            opacity: 0;
            pointer-events: none;
            height: 0;
            overflow: hidden;
            transition: all 0.3s;
        }
        .action-sidebar .hidden-item.visible {
            opacity: 1;
            pointer-events: auto;
            height: auto;
        }

        /* ========== 点赞/收藏 动效 ========== */
        .action-icon,
        .action-btn svg {
            transition: transform 0.25s cubic-bezier(0.34, 1.56, 0.64, 1), fill 0.2s;
        }
        .action-item.bursting .action-icon,
        .action-btn.bursting svg {
            animation: actionBurst 0.45s cubic-bezier(0.34, 1.56, 0.64, 1);
        }
        @keyframes actionBurst {
            0%   { transform: scale(1); }
            35%  { transform: scale(1.45) rotate(-8deg); }
            70%  { transform: scale(0.92); }
            100% { transform: scale(1); }
        }
        .action-btn svg,
        .action-sidebar .action-icon svg {
            transition: transform 0.25s cubic-bezier(0.34, 1.56, 0.64, 1);
        }
        /* 数字平滑跳动 */
        .action-count {
            transition: color 0.2s, transform 0.2s;
        }
        .action-count.count-bump {
            color: #1e80ff;
            transform: scale(1.25);
        }

        /* ========== 分享面板 ========== */
        .share-panel {
            position: fixed;
            left: 84px;
            top: 50%;
            transform: translateY(-50%) scale(0.92);
            transform-origin: left center;
            width: 264px;
            background: #fff;
            border-radius: 12px;
            box-shadow: 0 8px 28px rgba(0, 0, 0, 0.14);
            border: 1px solid #f2f3f5;
            padding: 16px;
            z-index: 1000;
            opacity: 0;
            visibility: hidden;
            transition: opacity 0.2s, transform 0.2s, visibility 0.2s;
        }
        .share-panel.open {
            opacity: 1;
            visibility: visible;
            transform: translateY(-50%) scale(1);
        }
        .share-panel-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            margin-bottom: 12px;
        }
        .share-panel-title {
            font-size: 14px;
            font-weight: 600;
            color: #252933;
        }
        .share-panel-close {
            border: none;
            background: none;
            font-size: 20px;
            color: #8a919f;
            cursor: pointer;
            line-height: 1;
            padding: 0 4px;
        }
        .share-panel-close:hover {
            color: #252933;
        }
        .share-panel-grid {
            display: grid;
            grid-template-columns: repeat(4, 1fr);
            gap: 8px;
            margin-bottom: 14px;
        }
        .share-option {
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 6px;
            padding: 10px 0;
            border: none;
            border-radius: 8px;
            background: #f7f8fa;
            cursor: pointer;
            transition: background 0.2s, transform 0.15s;
        }
        .share-option:hover {
            background: #eaf2ff;
            transform: translateY(-2px);
        }
        .share-option:active {
            transform: scale(0.95);
        }
        .share-icon {
            width: 26px;
            height: 26px;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        .share-icon svg {
            width: 22px;
            height: 22px;
            fill: #515767;
        }
        .share-option[data-share="wechat"] .share-icon svg { fill: #07c160; }
        .share-option[data-share="weibo"] .share-icon svg { fill: #e6162d; }
        .share-option[data-share="qq"] .share-icon svg { fill: #12b7f5; }
        .share-option[data-share="juejin"] .share-icon svg { fill: #1e80ff; }
        .share-label {
            font-size: 12px;
            color: #515767;
        }
        .share-copy-row {
            display: flex;
            gap: 8px;
        }
        .share-link-input {
            flex: 1;
            min-width: 0;
            height: 32px;
            padding: 0 10px;
            border: 1px solid #e4e6eb;
            border-radius: 6px;
            font-size: 12px;
            color: #8a919f;
            background: #f7f8fa;
            outline: none;
            box-sizing: border-box;
        }
        .share-copy-btn {
            height: 32px;
            padding: 0 14px;
            border: none;
            border-radius: 6px;
            background: #1e80ff;
            color: #fff;
            font-size: 12px;
            cursor: pointer;
            white-space: nowrap;
            transition: background 0.2s;
        }
        .share-copy-btn:hover {
            background: #0056d6;
        }
        body.dark .share-panel { background: #1e1e1e; border-color: #333; }
        body.dark .share-panel-title { color: #e5e6eb; }
        body.dark .share-panel-close { color: #8a919f; }
        body.dark .share-option { background: #2a2a2a; }
        body.dark .share-option:hover { background: #333; }
        body.dark .share-label { color: #c0c4cc; }
        body.dark .share-link-input { background: #2a2a2a; border-color: #444; color: #c0c4cc; }

        .image-lightbox {
            display: none;
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            background: rgba(0,0,0,0.85);
            z-index: 2000;
            align-items: center;
            justify-content: center;
        }
        .image-lightbox.open {
            display: flex;
        }
        .image-lightbox .lightbox-content {
            max-width: 90%;
            max-height: 90%;
            object-fit: contain;
            border-radius: 4px;
        }
        .image-lightbox .close-btn {
            position: absolute;
            top: 20px;
            right: 20px;
            width: 40px;
            height: 40px;
            border-radius: 50%;
            background: rgba(255,255,255,0.2);
            border: none;
            color: #fff;
            font-size: 24px;
            cursor: pointer;
            display: flex;
            align-items: center;
            justify-content: center;
        }

        /* ========== 弹窗通用样式 ========== */
        .modal-overlay {
            display: none;
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            background: rgba(0,0,0,0.5);
            z-index: 3000;
            align-items: center;
            justify-content: center;
        }
        .modal-overlay.open {
            display: flex;
        }
        .modal-container {
            background: #fff;
            border-radius: 8px;
            width: 440px;
            max-height: 80vh;
            overflow-y: auto;
            box-shadow: 0 8px 40px rgba(0,0,0,0.15);
            animation: modalFadeIn 0.25s ease;
        }
        @keyframes modalFadeIn {
            from { opacity: 0; transform: scale(0.95); }
            to { opacity: 1; transform: scale(1); }
        }
        .modal-header {
            display: flex;
            justify-content: space-between;
            align-items: flex-start;
            padding: 20px 24px 16px;
            border-bottom: 1px solid #f2f3f5;
        }
        .modal-title-group {
            flex: 1;
        }
        .modal-title {
            font-size: 18px;
            font-weight: 600;
            color: #252933;
            margin: 0 0 4px;
        }
        .modal-subtitle {
            font-size: 13px;
            color: #8a919f;
            margin: 0;
        }
        .modal-close-btn {
            background: none;
            border: none;
            font-size: 22px;
            color: #8a919f;
            cursor: pointer;
            padding: 0;
            line-height: 1;
        }
        .modal-close-btn:hover {
            color: #252933;
        }
        .modal-body {
            padding: 16px 24px;
        }
        .modal-footer {
            padding: 16px 24px;
            border-top: 1px solid #f2f3f5;
            display: flex;
            justify-content: flex-end;
        }

        /* ========== 举报弹窗 ========== */
        .report-modal {
            width: 520px;
        }
        .report-group {
            margin-bottom: 16px;
        }
        .report-group-title {
            font-size: 14px;
            font-weight: 600;
            color: #252933;
            margin-bottom: 8px;
        }
        .report-options {
            display: flex;
            flex-wrap: wrap;
            gap: 8px;
        }
        .report-option-btn {
            padding: 6px 14px;
            border: 1px solid #e4e6eb;
            border-radius: 4px;
            background: #fff;
            color: #515767;
            font-size: 13px;
            cursor: pointer;
            transition: all 0.2s;
        }
        .report-option-btn:hover {
            border-color: #1e80ff;
            color: #1e80ff;
        }
        .report-option-btn.selected {
            background: #eaf2ff;
            border-color: #1e80ff;
            color: #1e80ff;
        }
        .report-textarea-group {
            margin-bottom: 16px;
            position: relative;
        }
        .report-textarea-label {
            font-size: 14px;
            font-weight: 600;
            color: #252933;
            display: block;
            margin-bottom: 8px;
        }
        .report-textarea {
            width: 100%;
            min-height: 80px;
            padding: 10px 12px;
            border: 1px solid #e4e6eb;
            border-radius: 4px;
            font-size: 14px;
            font-family: inherit;
            color: #252933;
            resize: vertical;
            outline: none;
            box-sizing: border-box;
            transition: border-color 0.2s;
        }
        .report-textarea:focus {
            border-color: #1e80ff;
        }
        .report-textarea-count {
            text-align: right;
            font-size: 12px;
            color: #8a919f;
            margin-top: 4px;
        }
        .report-upload-group {
            margin-bottom: 16px;
        }
        .report-upload-label {
            font-size: 13px;
            color: #8a919f;
            margin-bottom: 8px;
        }
        .report-upload-area {
            display: flex;
            gap: 8px;
        }
        .report-upload-box {
            width: 80px;
            height: 80px;
            border: 1px dashed #c4c9d1;
            border-radius: 4px;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            cursor: pointer;
            transition: border-color 0.2s;
        }
        .report-upload-box:hover {
            border-color: #1e80ff;
        }
        .upload-plus {
            font-size: 24px;
            color: #c4c9d1;
            line-height: 1;
        }
        .upload-text {
            font-size: 11px;
            color: #8a919f;
            margin-top: 4px;
        }
        /* 举报图片预览缩略图 */
        .report-preview-item {
            width: 80px;
            height: 80px;
            border-radius: 4px;
            overflow: hidden;
            position: relative;
            flex-shrink: 0;
        }
        .report-preview-item img {
            width: 100%;
            height: 100%;
            object-fit: cover;
            display: block;
        }
        .report-preview-remove {
            position: absolute;
            top: 2px;
            right: 2px;
            width: 18px;
            height: 18px;
            line-height: 18px;
            text-align: center;
            background: rgba(0, 0, 0, 0.55);
            color: #fff;
            font-size: 14px;
            border-radius: 50%;
            cursor: pointer;
        }
        .report-preview-remove:hover {
            background: rgba(0, 0, 0, 0.75);
        }
        .report-footer {
            gap: 12px;
        }
        .cancel-btn {
            padding: 8px 24px;
            border: 1px solid #e4e6eb;
            border-radius: 4px;
            background: #fff;
            color: #515767;
            font-size: 14px;
            cursor: pointer;
            transition: all 0.2s;
        }
        .cancel-btn:hover {
            background: #f7f8fa;
        }
        .confirm-btn {
            padding: 8px 24px;
            border: none;
            border-radius: 4px;
            background: #1e80ff;
            color: #fff;
            font-size: 14px;
            cursor: pointer;
            transition: background 0.2s;
        }
        .confirm-btn:hover {
            background: #0056d6;
        }

        /* ========== 顶栏（与主页 Web 端顶栏保持一致） ========== */
        .article-topbar {
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            height: 60px;
            background-color: #ffffff;
            box-shadow: 0 1px 3px rgba(0,0,0,0.08);
            z-index: 100;
        }
        .topbar-inner {
            max-width: 1440px;
            margin: 0 auto;
            height: 60px;
            display: flex;
            align-items: center;
            padding: 0 24px;
            box-sizing: border-box;
            justify-content: space-between;
        }
        .topbar-left {
            width: 180px;
            flex-shrink: 0;
            display: flex;
            align-items: center;
        }
        .brand-link {
            display: flex;
            align-items: center;
            gap: 10px;
            cursor: pointer;
            user-select: none;
            transition: opacity 0.2s;
        }
        .brand-link:hover { opacity: 0.85; }
        .brand-logo {
            width: 32px;
            height: 32px;
            flex-shrink: 0;
        }
        .logo-text {
            font-size: 22px;
            font-weight: 700;
            color: #1e80ff;
            white-space: nowrap;
        }
        .main-nav {
            display: flex;
            align-items: center;
            flex-shrink: 0;
            margin: 0 24px;
        }
        .nav-link {
            padding: 0 12px;
            font-size: 14px;
            color: #515767;
            cursor: pointer;
            white-space: nowrap;
            transition: color 0.2s;
            line-height: 60px;
            position: relative;
        }
        .nav-link:hover { color: #1e80ff; }
        .nav-link.active { color: #1e80ff; }
        .nav-link.active::after {
            content: '';
            position: absolute;
            bottom: 0;
            left: 50%;
            transform: translateX(-50%);
            width: 20px;
            height: 2px;
            background-color: #1e80ff;
            border-radius: 1px;
        }
        .topbar-center {
            flex: 0;
            width: 220px;
            margin: 0 16px;
        }
        .web-search-box {
            position: relative;
            display: flex;
            align-items: center;
            height: 40px;
            background-color: #f4f5f5;
            border-radius: 20px;
            padding: 0 16px;
            width: 100%;
            box-sizing: border-box;
            transition: background-color 0.2s;
        }
        .web-search-box:focus-within {
            background-color: #ffffff;
            box-shadow: 0 0 0 2px rgba(49,148,255,0.2);
        }
        .web-search-input {
            flex: 1;
            height: 100%;
            border: none;
            outline: none;
            background-color: transparent;
            font-size: 14px;
            color: #333;
            min-width: 0;
        }
        .web-search-input::placeholder { color: #999; }
        .web-search-btn {
            font-family: "FontAwesome", fontawesome;
            width: 32px;
            height: 32px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 15px;
            color: #999;
            cursor: pointer;
            flex-shrink: 0;
            border-radius: 50%;
            transition: color 0.2s;
        }
        .web-search-btn:hover { color: #1e80ff; }
        .topbar-right {
            width: 180px;
            min-width: 180px;
            flex-shrink: 0;
            display: flex;
            align-items: center;
            justify-content: flex-end;
            gap: 12px;
        }
        .header-btn {
            padding: 6px 16px;
            border-radius: 4px;
            font-size: 14px;
            cursor: pointer;
            white-space: nowrap;
            transition: all 0.2s;
        }
        .write-btn {
            color: #333;
            background-color: #f4f5f5;
        }
        .write-btn:hover { background-color: #e8e8e8; }
        .login-btn {
            color: #ffffff;
            background-color: #1e80ff;
        }
        .login-btn:hover { background-color: #1a7de8; }
        .btn-icon {
            font-family: "FontAwesome", fontawesome;
            margin-right: 4px;
        }
        .header-user {
            display: flex;
            align-items: center;
            gap: 8px;
            cursor: pointer;
            position: relative;
        }
        .header-avatar {
            width: 32px;
            height: 32px;
            border-radius: 50%;
            object-fit: cover;
        }
        .header-username {
            font-size: 14px;
            color: #333;
            max-width: 80px;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }

        /* ========== 登录弹窗（与主页登录弹窗保持一致） ========== */
        .login-overlay {
            position: fixed;
            top: 0; left: 0; right: 0; bottom: 0;
            background-color: rgba(0, 0, 0, 0.55);
            display: none;
            align-items: center;
            justify-content: center;
            z-index: 9999;
            padding: 20px;
            box-sizing: border-box;
        }
        .login-overlay.open { display: flex; }
        .login-modal {
            position: relative;
            width: 100%;
            max-width: 480px;
            background-color: #ffffff;
            border-radius: 12px;
            padding: 48px 48px 24px;
            box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
            box-sizing: border-box;
        }
        .login-close-btn {
            position: absolute;
            top: 20px;
            right: 20px;
            width: 36px;
            height: 36px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 20px;
            color: #999999;
            cursor: pointer;
            border-radius: 50%;
            transition: all 0.2s;
        }
        .login-close-btn:hover { background-color: #f5f5f5; color: #666666; }
        .login-title {
            font-size: 28px;
            color: #333333;
            font-weight: 600;
            text-align: center;
            margin-bottom: 8px;
        }
        .login-subtitle {
            font-size: 16px;
            color: #999999;
            text-align: center;
            margin: 0 0 32px 0;
        }
        .login-form {
            display: flex;
            flex-direction: column;
            gap: 20px;
        }
        .login-input-group {
            display: flex;
            align-items: center;
            background-color: #f7f8fa;
            border-radius: 8px;
            padding: 0 20px;
            border: 1px solid transparent;
            transition: all 0.2s;
            height: 56px;
            box-sizing: border-box;
        }
        .login-input-group:focus-within { border-color: #3194ff; background-color: #ffffff; }
        .login-area-code {
            font-size: 17px;
            color: #333333;
            padding-right: 16px;
            border-right: 1px solid #e0e0e0;
            margin-right: 16px;
            font-weight: 500;
        }
        .login-input {
            flex: 1;
            height: 100%;
            font-size: 16px;
            color: #333333;
            background-color: transparent;
            border: none;
            outline: none;
            min-width: 0;
        }
        .login-input::placeholder { color: #c0c4cc; }
        .login-code-group { padding-right: 12px; }
        .login-code-btn {
            font-size: 15px;
            color: #3194ff;
            cursor: pointer;
            white-space: nowrap;
            padding: 8px 14px;
            border-radius: 4px;
            transition: all 0.2s;
            font-weight: 500;
            background: none;
            border: none;
        }
        .login-code-btn:hover { background-color: #e8f4ff; }
        .login-code-btn.disabled { color: #c0c4cc; cursor: not-allowed; }
        .login-submit-btn {
            height: 52px;
            line-height: 52px;
            background-color: #3194ff;
            color: #ffffff;
            font-size: 18px;
            font-weight: 500;
            text-align: center;
            border-radius: 8px;
            margin-top: 8px;
            cursor: pointer;
            border: none;
            transition: background-color 0.2s;
        }
        .login-submit-btn:hover { background-color: #2684e8; }
        .login-switch-row {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-top: 20px;
        }
        .login-switch-link {
            font-size: 15px;
            color: #3194ff;
            cursor: pointer;
            transition: color 0.2s;
            background: none;
            border: none;
            padding: 0;
        }
        .login-switch-link:hover { color: #1a7de8; text-decoration: underline; }
        .login-forget-link { color: #999999; }
        .login-divider {
            display: flex;
            align-items: center;
            margin: 36px 0 24px;
            gap: 16px;
        }
        .login-divider-line { flex: 1; height: 1px; background-color: #eeeeee; }
        .login-divider-text { font-size: 13px; color: #c0c4cc; }
        .login-social {
            display: flex;
            justify-content: center;
            align-items: center;
            gap: 40px;
        }
        .login-social-item {
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 8px;
            cursor: pointer;
            transition: transform 0.2s;
        }
        .login-social-item:hover { transform: translateY(-2px); }
        .login-social-icon {
            width: 48px;
            height: 48px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 24px;
            border-radius: 50%;
            transition: all 0.2s;
        }
        .login-social-icon svg {
            display: block;
        }
        .login-social-label { font-size: 13px; color: #999999; }
        .login-agreement {
            margin-top: 28px;
            text-align: center;
            font-size: 12px;
            color: #c0c4cc;
            line-height: 1.6;
        }
        .login-toast {
            position: fixed;
            top: 20px;
            left: 50%;
            transform: translateX(-50%);
            background-color: rgba(0,0,0,0.75);
            color: #fff;
            padding: 10px 20px;
            border-radius: 6px;
            font-size: 14px;
            z-index: 20000;
            opacity: 0;
            transition: opacity 0.25s;
            pointer-events: none;
        }
        .login-toast.show { opacity: 1; }

        @media (max-width: 960px) {
            .toc-sidebar { display: none; }
            .action-sidebar { display: none; }
            .main-wrapper { padding-top: 80px; }
            .content-card { padding: 20px; }
            .article-title { font-size: 24px; }
            .toc-float-btn { display: flex; }
            .comment-section { padding: 20px 16px; }
            .recommend-section { padding: 20px 16px; }
            .recommend-grid { grid-template-columns: repeat(2, 1fr); }
        }
        @media (max-width: 640px) {
            .main-wrapper { padding: 56px 12px 24px; }
            .content-card { padding: 16px; }
            .article-title { font-size: 22px; }
            .action-bar { flex-wrap: wrap; }
            .comment-section { padding: 16px 12px; }
            .recommend-section { padding: 16px 12px; }
            .recommend-grid { grid-template-columns: 1fr; }
            .end-author-card { flex-direction: column; align-items: flex-start; text-align: left; }
            .end-author-card .ea-actions { flex-direction: row; align-items: center; }
            .column-card { flex-wrap: wrap; }
            .column-cover { width: 80px; height: 56px; }
            .column-meta { width: 100%; justify-content: flex-start; margin-top: 8px; }
            .tip-modal { width: 92%; }
        }

        /* ========== 打赏（赞赏）卡片与弹窗 ========== */
        .tip-section {
            margin: 24px auto 8px;
            padding: 28px 24px;
            background: linear-gradient(135deg, #fff7f0 0%, #fff 60%);
            border: 1px solid #ffe3c8;
            border-radius: 12px;
            text-align: center;
            max-width: 560px;
        }
        .tip-section-title {
            font-size: 16px;
            font-weight: 600;
            color: #333;
            margin-bottom: 6px;
        }
        .tip-section-sub {
            font-size: 13px;
            color: #999;
            margin-bottom: 16px;
        }
        .tip-reward-btn {
            display: inline-block;
            padding: 10px 40px;
            background: linear-gradient(135deg, #ff8a3d, #ff6b00);
            color: #fff;
            border: none;
            border-radius: 999px;
            font-size: 15px;
            cursor: pointer;
            transition: transform 0.15s, box-shadow 0.15s;
        }
        .tip-reward-btn:hover {
            transform: translateY(-1px);
            box-shadow: 0 6px 18px rgba(255, 107, 0, 0.35);
        }
        .tip-summary-row {
            margin-top: 14px;
            font-size: 13px;
            color: #888;
        }
        .tip-summary-row .tip-summary-count { color: #ff6b00; font-weight: 600; }
        .tip-reward-list {
            margin-top: 18px;
            border-top: 1px dashed #ffe3c8;
            padding-top: 14px;
            text-align: left;
            max-height: 220px;
            overflow-y: auto;
        }
        .tip-reward-list-empty {
            font-size: 13px;
            color: #bbb;
            text-align: center;
            padding: 6px 0;
        }
        .tip-reward-item {
            display: flex;
            align-items: center;
            gap: 10px;
            padding: 8px 4px;
            border-bottom: 1px solid #faf3ec;
        }
        .tip-reward-avatar {
            width: 32px;
            height: 32px;
            border-radius: 50%;
            background: #ffe9d6;
            flex-shrink: 0;
            object-fit: cover;
        }
        .tip-reward-info { flex: 1; min-width: 0; }
        .tip-reward-name { font-size: 13px; color: #333; font-weight: 500; }
        .tip-reward-msg { font-size: 12px; color: #999; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        .tip-reward-amount { font-size: 14px; color: #ff6b00; font-weight: 600; flex-shrink: 0; }

        .tip-modal {
            width: 420px;
            border-radius: 12px;
        }
        .tip-modal .modal-header {
            text-align: center;
            border-bottom: 1px solid #f5f5f5;
        }
        .tip-modal-title { font-size: 17px; font-weight: 600; color: #333; }
        .tip-modal-sub { font-size: 12px; color: #999; margin-top: 4px; }
        .tip-amount-grid {
            display: grid;
            grid-template-columns: repeat(4, 1fr);
            gap: 10px;
            margin: 16px 0;
        }
        .tip-amount-option {
            padding: 12px 0;
            border: 1px solid #e5e5e5;
            border-radius: 8px;
            background: #fff;
            color: #333;
            font-size: 16px;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.15s;
        }
        .tip-amount-option.active {
            border-color: #ff6b00;
            color: #ff6b00;
            background: #fff6ef;
        }
        .tip-amount-custom {
            width: 100%;
            padding: 10px 12px;
            border: 1px solid #e5e5e5;
            border-radius: 8px;
            font-size: 14px;
            box-sizing: border-box;
            margin-bottom: 12px;
        }
        .tip-amount-custom:focus { outline: none; border-color: #ff6b00; }
        .tip-message-input {
            width: 100%;
            height: 72px;
            padding: 10px 12px;
            border: 1px solid #e5e5e5;
            border-radius: 8px;
            font-size: 13px;
            resize: none;
            box-sizing: border-box;
        }
        .tip-message-input:focus { outline: none; border-color: #ff6b00; }
        .tip-pay-btn {
            width: 100%;
            padding: 12px 0;
            background: linear-gradient(135deg, #ff8a3d, #ff6b00);
            color: #fff;
            border: none;
            border-radius: 999px;
            font-size: 15px;
            font-weight: 600;
            cursor: pointer;
            margin-top: 14px;
        }
        .tip-pay-btn:disabled { opacity: 0.6; cursor: not-allowed; }
        .tip-pay-note { font-size: 11px; color: #bbb; text-align: center; margin-top: 10px; }

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
        body.dark .action-sidebar { background: #1e1e1e; box-shadow: 0 1px 4px rgba(0,0,0,0.5); color: #9aa4b2; }
        body.dark .action-sidebar .action-item:hover,
        body.dark .action-sidebar .action-item.active { background: #1c2a44; color: #4d9fff; }
        body.dark .action-item .action-count { color: #8a919f; }
        /* 暗色下行为图标提亮，保持语义区分度 */
        body.dark .action-sidebar #sideLikeBtn .action-icon { color: #ff7b7b; }
        body.dark .action-sidebar #sideCommentBtn .action-icon { color: #7aa2ff; }
        body.dark .action-sidebar #sideCollectBtn .action-icon { color: #ffc04d; }
        body.dark .action-sidebar #sideShareBtn .action-icon { color: #11c97a; }
        body.dark .action-sidebar #sideReportBtn .action-icon { color: #ff6b6b; }
        body.dark .action-sidebar #sideImmersiveBtn .action-icon,
        body.dark .action-sidebar #sideSettingsBtn .action-icon,
        body.dark .action-sidebar #sideBackTopBtn .action-icon { color: #9aa4b2; }
        body.dark .modal-container { background: #1e1e1e; }
        body.dark .modal-title,
        body.dark .modal-subtitle { color: #e4e6eb; }
        body.dark .modal-header,
        body.dark .modal-footer { border-color: #2d333b; }
        body.dark .setting-btn { background: #1e1e1e; color: #8a919f; border-color: #2d333b; }
        body.dark .setting-btn.active { background: #eaf2ff; color: #1e80ff; border-color: #1e80ff; }
        /* 暗色适配：为你推荐横排卡片 / 正文尾部作者卡片 / 读完提示 */
        body.dark .recommend-section,
        body.dark .end-author-card { background: #1e1e1e; border-color: #2d333b; }
        body.dark .recommend-title,
        body.dark .end-author-card .ea-name { color: #e4e6eb; }
        body.dark .recommend-card { background: #1e1e1e; border-color: #2d333b; }
        body.dark .recommend-card:hover { box-shadow: 0 4px 12px rgba(0,0,0,0.4); }
        body.dark .recommend-card-title { color: #e4e6eb; }
        body.dark .recommend-card:hover .recommend-card-title { color: #4d9fff; }
        body.dark .recommend-card-meta { color: #8a919f; }
        body.dark .recommend-card-cover { background: #2a2a2a; }
        body.dark .end-author-card .ea-desc,
        body.dark .end-author-card .ea-followers { color: #8a919f; }
        body.dark .end-author-card .ea-followers b { color: #c0c4cc; }
        body.dark .read-end-hint { color: #8a919f; }
        body.dark .read-end-hint::before,
        body.dark .read-end-hint::after { background: #2d333b; }

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
    </style>
</head>
<body>
    <!-- 阅读进度条 -->
    <div class="reading-progress" id="readingProgress"></div>
    <!-- 顶栏（与主页 Web 端顶栏保持一致） -->
    <header class="article-topbar">
        <div class="topbar-inner">
            <div class="topbar-left">
                <div class="brand-link" id="topBrandLink">
                    <span class="logo-text">逐日<em style="font-style:normal;color:#1e80ff;">Coding</em></span>
                </div>
            </div>
            <nav class="main-nav">
                <span class="nav-link" data-nav="home">首页</span>
                <span class="nav-link" data-nav="pins">沸点</span>
                <span class="nav-link" data-nav="course">课程</span>
                <span class="nav-link">数据标注</span>
                <span class="nav-link">AI Coding</span>
            </nav>
            <div class="topbar-center">
                <div class="web-search-box">
                    <input type="text" class="web-search-input" id="topSearchInput" placeholder="搜索文章" />
                    <span class="web-search-btn" id="topSearchBtn">&#xf002;</span>
                </div>
            </div>
            <div class="topbar-right">
                <span class="header-btn write-btn" id="topWriteBtn" style="display:none;">
                    <span class="btn-icon">&#xf040;</span>写文章
                </span>
                <span class="header-btn login-btn" id="topLoginBtn" style="display:none;">登录</span>
                <div class="header-user" id="topUserInfo" style="display:none;">
                    <img class="header-avatar" id="topAvatar" src="" alt="avatar" />
                    <span class="header-username" id="topUserName"></span>
                </div>
            </div>
        </div>
    </header>

    <!-- 登录弹窗 -->
    <div class="login-overlay" id="loginOverlay">
        <div class="login-modal">
            <span class="login-close-btn" id="loginCloseBtn">&#10005;</span>
            <div class="login-title">登录逐日Coding</div>
            <p class="login-subtitle" id="loginSubtitle">验证码登录</p>
            <div class="login-form" id="loginForm">
                <!-- 验证码登录 -->
                <div id="codeLoginArea">
                    <div class="login-input-group">
                        <span class="login-area-code">+86</span>
                        <input type="tel" placeholder="请输入手机号" class="login-input" id="loginPhone" maxlength="11" />
                    </div>
                    <div class="login-input-group login-code-group">
                        <input type="tel" placeholder="请输入验证码" class="login-input" id="loginCode" maxlength="6" />
                        <button class="login-code-btn" id="loginGetCode">获取验证码</button>
                    </div>
                    <button class="login-submit-btn" id="loginSubmitBtn">登录/注册</button>
                </div>
                <!-- 密码登录 -->
                <div id="passwordLoginArea" style="display:none;">
                    <div class="login-input-group">
                        <input type="text" placeholder="请输入手机号或邮箱" class="login-input" id="loginAccount" />
                    </div>
                    <div class="login-input-group">
                        <input type="password" placeholder="请输入密码" class="login-input" id="loginPassword" />
                    </div>
                    <button class="login-submit-btn" id="pwdLoginSubmitBtn">登录</button>
                </div>
            </div>
            <div class="login-switch-row">
                <button class="login-switch-link" id="loginToggleMode">密码登录</button>
                <button class="login-switch-link login-forget-link" id="loginForgetLink">忘记密码?</button>
            </div>
            <div class="login-divider">
                <span class="login-divider-line"></span>
                <span class="login-divider-text">其他登录方式</span>
                <span class="login-divider-line"></span>
            </div>
            <div class="login-social">
                <div class="login-social-item" data-social="weibo" title="微博登录">
                    <span class="login-social-icon" style="background-color:#fff3f3;color:#e6162d;">
                        <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M10.87 17.07c-2.04-.45-3.86-1.57-5.02-3.24-.16-.22-.1-.53.1-.68.2-.15.51-.1.66.1 1.03 1.47 2.62 2.45 4.4 2.84.25.06.4.31.34.57-.06.25-.3.43-.48.41zm5.12-1.3c-.18-.17-.44-.2-.66-.07-.11.06-.24.11-.38.14-.9.24-1.94.19-2.95-.05-.21-.05-.43.08-.48.29-.05.21.08.43.29.48 1.12.26 2.26.31 3.24.05.2-.06.34-.24.34-.45 0-.13-.06-.27-.19-.39zM12.5 1.5c.23 0 .41.19.41.42 0 .23-.18.41-.41.41-3.11 0-5.94 1.51-7.6 4.03-.1.15-.31.2-.46.1-.16-.1-.21-.31-.11-.46C6.7 2.73 9.48 1.5 12.5 1.5zM12.5 0c-.28 0-.5.22-.5.5s.22.5.5.5c3.41 0 6.5 1.67 8.3 4.47.11.17.34.22.51.11.17-.11.22-.34.11-.51C19.89 2.08 16.44 0 12.5 0zM14.6 4.9c-.21-.12-.48-.05-.6.16-.12.21-.05.48.16.6 1.47.84 2.44 2.3 2.72 3.99.04.23.24.4.47.4.02 0 .05 0 .07-.01.26-.05.43-.3.38-.56C17.51 6.56 16.36 4.85 14.6 4.9zM19.1 12.26c-1.67 3.3-5.15 5.62-8.74 5.85-.55.03-1.1-.01-1.64-.1-.25-.04-.51.06-.68.27-.67.84-2.75 2.72-4.69 2.72H3.27c-.15 0-.27-.12-.27-.27v-.12c.22-1.7 1.38-2.98 2.34-3.78-1.25-.66-2.1-1.84-2.1-3.13 0-2.64 3.17-4.77 7.09-4.77.63 0 1.26.06 1.86.19.8-1.1 2.17-1.82 3.7-1.82.39 0 .78.05 1.14.16.14.04.29.03.41-.05l2.07-1.47c.35-.25.82.06.74.49l-.31 1.55c-.05.24.04.49.23.64 1.27 1.04 2.11 2.64 2.11 4.36 0 .58-.1 1.15-.28 1.69z"/></svg>
                    </span>
                    <span class="login-social-label">微博</span>
                </div>
                <div class="login-social-item" data-social="github" title="GitHub登录">
                    <span class="login-social-icon" style="background-color:#f5f5f5;color:#333333;">
                        <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M12 .5C5.65.5.5 5.65.5 12c0 5.08 3.29 9.39 7.86 10.91.58.11.79-.25.79-.56 0-.28-.01-1.02-.02-2-3.2.7-3.88-1.54-3.88-1.54-.52-1.33-1.28-1.68-1.28-1.68-1.05-.72.08-.71.08-.71 1.16.08 1.77 1.19 1.77 1.19 1.03 1.76 2.7 1.25 3.36.96.1-.75.4-1.25.73-1.54-2.55-.29-5.24-1.28-5.24-5.68 0-1.26.45-2.28 1.19-3.09-.12-.29-.52-1.46.11-3.05 0 0 .97-.31 3.17 1.18a11.05 11.05 0 0 1 5.77 0c2.2-1.49 3.17-1.18 3.17-1.18.63 1.59.23 2.76.11 3.05.74.81 1.19 1.83 1.19 3.09 0 4.41-2.69 5.38-5.26 5.66.41.36.78 1.06.78 2.14 0 1.54-.01 2.79-.01 3.17 0 .31.21.68.8.56A10.52 10.52 0 0 0 23.5 12C23.5 5.65 18.35.5 12 .5z"/></svg>
                    </span>
                    <span class="login-social-label">GitHub</span>
                </div>
                <div class="login-social-item" data-social="wechat" title="微信公众号登录">
                    <span class="login-social-icon" style="background-color:#f0f9eb;color:#07c160;">
                        <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M9.5 4C5.36 4 2 6.69 2 10c0 1.84.99 3.5 2.54 4.59l-.63 2.16 2.2-1.13c.67.19 1.38.3 2.11.32-.16-.5-.25-1.03-.25-1.58 0-3.09 2.91-5.59 6.5-5.59.3 0 .59.02.88.05C14.85 5.86 12.38 4 9.5 4zm-2.2 3.75c-.51 0-.93-.41-.93-.93s.42-.93.93-.93.93.42.93.93-.42.93-.93.93zm4.4 0c-.51 0-.93-.41-.93-.93s.42-.93.93-.93.93.42.93.93-.42.93-.93.93zM21.5 13.5c0-2.69-2.68-4.87-6-4.87s-6 2.18-6 4.87 2.68 4.87 6 4.87c.64 0 1.25-.1 1.83-.29l2.25 1.08-.59-1.96c1.37-.99 2.51-2.3 2.51-3.83zm-4.3-.75c-.34 0-.62-.28-.62-.62s.28-.62.62-.62.62.28.62.62-.28.62-.62.62zm-3.4 0c-.34 0-.62-.28-.62-.62s.28-.62.62-.62.62.28.62.62-.28.62-.62.62z"/></svg>
                    </span>
                    <span class="login-social-label">微信</span>
                </div>
            </div>
            <div class="login-agreement">
                <span>注册登录即表示同意</span>
                <span style="color:#999;cursor:pointer;">《用户协议》</span>
                <span>和</span>
                <span style="color:#999;cursor:pointer;">《隐私政策》</span>
            </div>
        </div>
    </div>
    <div class="login-toast" id="loginToast"></div>

    <div class="main-wrapper">
        <article class="content-area">
            <div class="content-card">
                <h1 class="article-title">${title!''}</h1>

                <div class="author-header">
                    <a class="author-avatar" href="/user/${(authorId!0)?c}" title="查看作者主页">
                        <img src="${authorAvatar!'https://p3.pstatp.com/thumb/1480/7186611868'}" alt="avatar">
                    </a>
                    <div class="author-info">
                        <div class="author-name-row">
                            <a class="author-name" href="/user/${(authorId!0)?c}">${authorName!'黑马头条'}</a>
                            <#if authorLevel?? && authorLevelTitle??>
                            <span class="author-level-badge" title="${authorLevelTitle}">
                                <svg viewBox="0 0 24 24"><path d="M12 2l2.4 2.4L12 6.8 9.6 4.4 12 2zM6.4 8.4L12 14l5.6-5.6L12 2.8 6.4 8.4zm0 5.2L12 19.2l5.6-5.6L12 8 6.4 13.6z"/></svg>
                                Lv.${authorLevel}
                            </span>
                            </#if>
                        </div>
                        <div class="publish-meta">
                            <span class="publish-time">
                                <#if publishTime??>${publishTime?string('yyyy-MM-dd HH:mm')}</#if>
                            </span>
                            <span class="meta-divider">·</span>
                            <span class="read-count">
                                <svg class="meta-icon" viewBox="0 0 24 24" width="14" height="14"><path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z" fill="#8a919f"/></svg>
                                <span id="readCountHeader">${readCount!0}</span>阅读
                            </span>
                            <span class="meta-divider">·</span>
                            <span class="read-time">
                                <svg class="meta-icon" viewBox="0 0 24 24" width="14" height="14"><path d="M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z" fill="#8a919f"/></svg>
                                ${readTime!5}分钟阅读
                            </span>
                            <#if columnName??>
                            <span class="meta-divider">·</span>
                            <span class="column-tag">
                                <svg class="meta-icon" viewBox="0 0 24 24" width="14" height="14"><path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm-1 7V3.5L18.5 9H13z" fill="#8a919f"/></svg>
                                专栏：${columnName!''}
                            </span>
                            </#if>
                        </div>
                    </div>
                    <button class="follow-btn<#if relation?? && relation.isfollow?? && relation.isfollow> active</#if>" id="followBtn">
                        <#if relation?? && relation.isfollow?? && relation.isfollow>已关注<#else>+ 关注</#if>
                    </button>
                </div>

                <div class="article-body" id="articleContent">
                    ${articleContentHtml}
                </div>

                <!-- 读完提示 -->
                <div class="read-end-hint" id="readEndHint">— 已读完，感谢阅读 —</div>

                <!-- 正文尾部作者卡片 -->
                <div class="end-author-card" id="endAuthorCard">
                    <img src="${authorAvatar!'https://p3.pstatp.com/thumb/1480/7186611868'}" class="ea-avatar" id="endAuthorAvatar" alt="avatar">
                    <div class="ea-info">
                        <div class="ea-name" id="endAuthorName">${authorName!'黑马头条'}</div>
                        <div class="ea-desc" id="endAuthorDesc"></div>
                        <div class="ea-followers"><b id="endAuthorFollowers">0</b> 粉丝</div>
                    </div>
                    <div class="ea-actions">
                        <button class="ea-follow-btn<#if relation?? && relation.isfollow?? && relation.isfollow> active</#if>" id="endAuthorFollowBtn">
                            <#if relation?? && relation.isfollow?? && relation.isfollow>已关注<#else>+ 关注</#if>
                        </button>
                        <div class="ea-links">
                            <a href="/user/${(authorId!0)?c}" class="ea-link" target="_blank">查看主页</a>
                            <a href="/user/${(authorId!0)?c}?tab=article" class="ea-link" target="_blank">更多文章</a>
                        </div>
                    </div>
                </div>

                <!-- 打赏（赞赏）卡片 -->
                <div class="tip-section" id="tipSection">
                    <div class="tip-section-title">觉得这篇文章不错？</div>
                    <div class="tip-section-sub">支持作者，让好内容被更多人看到</div>
                    <button class="tip-reward-btn" id="tipRewardBtn">赞赏</button>
                    <div class="tip-summary-row">
                        已获 <span class="tip-summary-count" id="tipCount">0</span> 次赞赏 · 共
                        <span class="tip-summary-count" id="tipAmount">0</span> 元
                    </div>
                    <div class="tip-reward-list" id="tipRewardList">
                        <div class="tip-reward-list-empty">暂无赞赏，期待你的支持～</div>
                    </div>
                </div>

                <!-- 专栏区域 -->
                <div class="column-section" id="columnSection">
                    <div class="column-section-title">本文收录于以下专栏</div>
                    <div class="column-card" id="columnCard">
                        <img class="column-cover" id="columnCover" src="" alt="专栏封面">
                        <div class="column-info">
                            <div class="column-name" id="columnName"></div>
                            <div class="column-desc" id="columnDesc"></div>
                        </div>
                        <div class="column-meta">
                            <div class="column-meta-item">
                                <div class="column-meta-value" id="columnArticleCnt">0</div>
                                <div>文章</div>
                            </div>
                            <div class="column-meta-item">
                                <div class="column-meta-value" id="columnFollowCnt">0</div>
                                <div>订阅</div>
                            </div>
                        </div>
                        <button class="column-subscribe-btn" id="columnSubscribeBtn">订阅</button>
                    </div>
                    <div class="column-nav">
                        <a href="javascript:void(0)" id="prevArticleLink" class="disabled">← 上一篇</a>
                        <a href="javascript:void(0)" id="nextArticleLink" class="disabled">下一篇 →</a>
                    </div>
                </div>

                <div class="action-bar">
                    <button class="action-btn<#if relation?? && relation.islike?? && relation.islike> active</#if>" id="likeBtn">
                        <svg viewBox="0 0 24 24"><path d="M2 20h2v-9H2v9zm20-9c0-1.1-.9-2-2-2h-3.17c-.53-1.4-1.53-2.56-2.83-3.09V4c0-1.66-1.34-3-3-3S8 2.34 8 4v1.91C5.94 6.56 4.5 8.69 4.5 11v6.17l-1.83 1.83L4.17 20h12.5c1.66 0 3.08-1.03 3.65-2.5H22v-6.5z"/></svg>
                        <span id="likeBtnText">点赞</span>
                    </button>
                    <button class="action-btn<#if relation?? && relation.iscollection?? && relation.iscollection> active</#if>" id="collectBtn">
                        <svg viewBox="0 0 24 24"><path d="M17 3H7c-1.1 0-2 .9-2 2v16l7-3 7 3V5c0-1.1-.9-2-2-2z"/></svg>
                        <span id="collectBtnText">收藏</span>
                    </button>
                </div>
            </div>

            <!-- 评论区 -->
            <div class="comment-section" id="commentSection">
                <div class="comment-title">评论 <span id="commentTitleCount">0</span></div>
                <div class="comment-input-area" id="commentInputArea">
                    <div class="comment-input-avatar">
                        <img src="" alt="avatar" id="commentUserAvatar">
                    </div>
                    <div class="comment-input-wrap">
                        <textarea id="commentTextarea" placeholder="写下你的评论..." maxlength="1000"></textarea>
                        <div class="comment-image-preview" id="commentImagePreview"></div>
                        <div class="comment-input-footer">
                            <div class="comment-footer-left">
                                <span class="login-tip" id="loginTip" style="display:none;">
                                    <a id="loginLink">登录</a>后参与评论
                                </span>
                                <div class="comment-toolbar">
                                    <button type="button" class="comment-tool-btn" id="commentEmojiBtn" title="表情">😊</button>
                                    <button type="button" class="comment-tool-btn" id="commentImageBtn" title="插入图片">图片</button>
                                    <input type="file" id="commentImageInput" accept="image/*" style="display:none;">
                                </div>
                            </div>
                            <div class="comment-footer-right">
                                <span class="comment-char-count"><span id="commentCharCount">0</span>/1000</span>
                                <button class="comment-submit-btn" id="commentSubmitBtn">发表评论</button>
                            </div>
                        </div>
                    </div>
                </div>
                <ul class="comment-list" id="commentList"></ul>
                <div class="comment-empty" id="commentEmpty" style="display:none;">
                    <svg viewBox="0 0 24 24"><path d="M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H5.17L4 17.17V4h16v12z"/></svg>
                    <div>暂无评论，快来抢沙发吧~</div>
                </div>
                <button class="load-more-btn" id="commentLoadMore" style="display:none;">加载更多评论</button>
            </div>

            <!-- 为你推荐 -->
            <div class="recommend-section" id="recommendSection">
                <div class="recommend-title">为你推荐</div>
                <div class="recommend-grid" id="recommendList"></div>
                <button class="load-more-btn" id="recommendLoadMore" style="display:none;">加载更多</button>
            </div>
        </article>

        <aside class="toc-sidebar" id="tocSidebar" data-stage="toc">
            <div class="author-info-card">
                <div class="author-info-head">
                    <a class="author-avatar-link" href="/user/${(authorId!0)?c}" title="查看作者主页">
                        <img src="${authorAvatar!'https://p3.pstatp.com/thumb/1480/7186611868'}" class="avatar" alt="avatar">
                    </a>
                    <div class="author-head-info">
                        <div class="name-row">
                            <a class="name" href="/user/${(authorId!0)?c}">${authorName!'黑马头条'}</a>
                            <#if authorLevel?? && authorLevelTitle??>
                            <span class="badge" title="${authorLevelTitle}">Lv.${authorLevel}</span>
                            </#if>
                        </div>
                        <div class="author-meta-line">
                            <#if authorJobTitle?? && authorJobTitle?has_content><span class="job-title">${authorJobTitle}</span></#if>
                            <#if authorCompany?? && authorCompany?has_content><span class="company"> · ${authorCompany}</span></#if>
                        </div>
                    </div>
                </div>
                <div class="stats">
                    <div class="stat-item">
                        <div class="stat-value">${articleCount!0}</div>
                        <div class="stat-label">文章</div>
                    </div>
                    <div class="stat-item">
                        <div class="stat-value" id="readCountSidebar">${readCount!0}</div>
                        <div class="stat-label">阅读</div>
                    </div>
                    <div class="stat-item">
                        <div class="stat-value">${fansCount!0}</div>
                        <div class="stat-label">粉丝</div>
                    </div>
                </div>
                <div class="action-btns">
                    <button class="follow-btn<#if relation?? && relation.isfollow?? && relation.isfollow> active</#if>" id="authorFollowBtn">
                        <#if relation?? && relation.isfollow?? && relation.isfollow>已关注<#else>+ 关注</#if>
                    </button>
                    <button class="message-btn">私信</button>
                </div>
            </div>
            <div class="toc-card stage-toc">
                <div class="toc-title">
                目录
                <button class="toc-collapse-btn" id="tocCollapseBtn" title="收起">收起</button>
            </div>
            <ul class="toc-list" id="tocList">
                    <#if tocList??>
                        <#list tocList as item>
                            <li class="level-${item.level!1}"><a href="#${item.id!''}" data-target="${item.id!''}">${item.text!''}</a></li>
                        </#list>
                    </#if>
                </ul>
            </div>
            <!-- 相关推荐（取代原"作者作品"区域，优先该作者的其他文章） -->
            <div class="sidebar-recommend-card stage-related" id="relatedCard">
                <div class="sidebar-recommend-title">相关推荐</div>
                <ul class="sidebar-recommend-list" id="relatedList">
                    <li class="sidebar-recommend-empty">加载中...</li>
                </ul>
            </div>
            <!-- 精选内容 -->
            <div class="sidebar-recommend-card stage-featured" id="featuredCard">
                <div class="sidebar-recommend-title">精选内容</div>
                <ul class="sidebar-recommend-list" id="featuredList">
                    <li class="sidebar-recommend-empty">加载中...</li>
                </ul>
            </div>
        </aside>
    </div>

    <div class="action-sidebar" id="actionSidebar">
        <div class="action-item hidden-item" id="miniAuthorAvatar">
            <div style="position: relative;">
                <img src="${authorAvatar!'https://p3.pstatp.com/thumb/1480/7186611868'}" class="author-mini-avatar" alt="avatar">
                <span class="mini-follow-badge">关注</span>
            </div>
        </div>
        <div class="action-item<#if relation?? && relation.islike?? && relation.islike> active</#if>" id="sideLikeBtn">
            <div class="action-icon">
                <svg viewBox="0 0 24 24" width="20" height="20"><path d="M2 20h2v-9H2v9zm20-9c0-1.1-.9-2-2-2h-3.17c-.53-1.4-1.53-2.56-2.83-3.09V4c0-1.66-1.34-3-3-3S8 2.34 8 4v1.91C5.94 6.56 4.5 8.69 4.5 11v6.17l-1.83 1.83L4.17 20h12.5c1.66 0 3.08-1.03 3.65-2.5H22v-6.5z"/></svg>
            </div>
            <div class="action-count">${likeCount!0}</div>
        </div>
        <div class="action-item" id="sideCommentBtn">
            <div class="action-icon">
                <svg viewBox="0 0 24 24" width="20" height="20"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>
            </div>
            <div class="action-count">${commentCount!0}</div>
        </div>
        <div class="action-item<#if relation?? && relation.iscollection?? && relation.iscollection> active</#if>" id="sideCollectBtn">
            <div class="action-icon">
                <svg viewBox="0 0 24 24" width="20" height="20"><path d="M17 3H7c-1.1 0-2 .9-2 2v16l7-3 7 3V5c0-1.1-.9-2-2-2z"/></svg>
            </div>
            <div class="action-count">${collectCount!0}</div>
        </div>
        <div class="action-item" id="sideShareBtn">
            <div class="action-icon">
                <svg viewBox="0 0 24 24" width="20" height="20"><path d="M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z"/></svg>
            </div>
            <div class="action-count">分享</div>
        </div>
        <div class="action-item" id="sideReportBtn">
            <div class="action-icon">
                <svg viewBox="0 0 24 24" width="20" height="20"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/></svg>
            </div>
            <div class="action-count">举报</div>
        </div>
        <div class="action-item" id="sideImmersiveBtn">
            <div class="action-icon">
                <svg viewBox="0 0 24 24" width="20" height="20"><path d="M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z"/></svg>
            </div>
            <div class="action-count">沉浸</div>
        </div>
        <div class="action-item" id="sideSettingsBtn">
            <div class="action-icon">
                <svg viewBox="0 0 24 24" width="20" height="20"><path d="M19.14 12.94c.04-.31.06-.63.06-.94s-.02-.63-.06-.94l2.03-1.58a.996.996 0 0 0 .25-1.52L19.5 5.64c-.26-.46-.78-.64-1.24-.42l-2.5 1.07c-.33-.26-.73-.44-1.11-.51-.25-.44-.54-.85-.85-1.24l-.6-1.56a.996.996 0 0 0-.99-.72l-1.8.64c-.36.13-.76.3-1.24.51-.38.07-.78.25-1.11.51L5.86 3.28c-.45-.21-.98-.04-1.24.42L2.7 7.02c-.34.5-.21 1.16.25 1.52l2.01 1.56c-.04.31-.06.63-.06.94s.02.63.06.94l-2.03 1.58a.996.996 0 0 0-.25 1.52l1.92 3.32c.26.46.78.64 1.24.42l2.5-1.07c.33.26.73.44 1.11.51.25.44.54.85.85 1.24l.6 1.56c.09.41.32.82.72.99.4.18.83.18 1.27 0l1.8-.64c.36-.13.76-.3 1.24-.51.38-.07.78-.25 1.11-.51l2.5 1.07c.45.21.98.04 1.24-.42l1.92-3.32c.26-.46.12-1.1-.25-1.52zM12 15.5c-1.93 0-3.5-1.57-3.5-3.5s1.57-3.5 3.5-3.5 3.5 1.57 3.5 3.5-1.57 3.5-3.5 3.5z" fill="currentColor"/></svg>
            </div>
            <div class="action-count">设置</div>
        </div>
        <div class="action-item" id="sideBackTopBtn" title="回到顶部">
            <div class="action-icon">
                <svg viewBox="0 0 24 24" width="20" height="20"><path d="M12 5.83l6.59 6.59L17 14l-5-5-5 5-1.59-1.58L12 5.83zm0 6l6.59 6.59L17 20l-5-5-5 5-1.59-1.58L12 11.83z"/></svg>
            </div>
            <div class="action-count">回顶</div>
        </div>
    </div>

    <!-- 分享面板 -->
    <div class="share-panel" id="sharePanel">
        <div class="share-panel-header">
            <span class="share-panel-title">分享文章</span>
            <button class="share-panel-close" id="closeSharePanel" aria-label="关闭">&times;</button>
        </div>
        <div class="share-panel-grid">
            <button class="share-option" data-share="wechat" title="复制微信分享文案">
                <span class="share-icon"><svg viewBox="0 0 24 24"><path d="M9.5 4C5.36 4 2 6.69 2 10c0 1.84.99 3.5 2.54 4.59l-.63 2.16 2.2-1.13c.67.19 1.38.3 2.11.32-.16-.5-.25-1.03-.25-1.58 0-3.09 2.91-5.59 6.5-5.59.3 0 .59.02.88.05C14.85 5.86 12.38 4 9.5 4zm-2.2 3.75c-.51 0-.93-.41-.93-.93s.42-.93.93-.93.93.42.93.93-.42.93-.93.93zm4.4 0c-.51 0-.93-.41-.93-.93s.42-.93.93-.93.93.42.93.93-.42.93-.93.93zM21.5 13.5c0-2.69-2.68-4.87-6-4.87s-6 2.18-6 4.87 2.68 4.87 6 4.87c.64 0 1.25-.1 1.83-.29l2.25 1.08-.59-1.96c1.37-.99 2.51-2.3 2.51-3.83zm-4.3-.75c-.34 0-.62-.28-.62-.62s.28-.62.62-.62.62.28.62.62-.28.62-.62.62zm-3.4 0c-.34 0-.62-.28-.62-.62s.28-.62.62-.62.62.28.62.62-.28.62-.62.62z"/></svg></span>
                <span class="share-label">微信</span>
            </button>
            <button class="share-option" data-share="weibo" title="分享到微博">
                <span class="share-icon"><svg viewBox="0 0 24 24"><path d="M10.87 17.07c-2.04-.45-3.86-1.57-5.02-3.24-.16-.22-.1-.53.1-.68.2-.15.51-.1.66.1 1.03 1.47 2.62 2.45 4.4 2.84.25.06.4.31.34.57-.06.25-.3.43-.48.41zm5.12-1.3c-.18-.17-.44-.2-.66-.07-.11.06-.24.11-.38.14-.9.24-1.94.19-2.95-.05-.21-.05-.43.08-.48.29-.05.21.08.43.29.48 1.12.26 2.26.31 3.24.05.2-.06.34-.24.34-.45 0-.13-.06-.27-.19-.39zM12.5 1.5c.23 0 .41.19.41.42 0 .23-.18.41-.41.41-3.11 0-5.94 1.51-7.6 4.03-.1.15-.31.2-.46.1-.16-.1-.21-.31-.11-.46C6.7 2.73 9.48 1.5 12.5 1.5zM12.5 0c-.28 0-.5.22-.5.5s.22.5.5.5c3.41 0 6.5 1.67 8.3 4.47.11.17.34.22.51.11.17-.11.22-.34.11-.51C19.89 2.08 16.44 0 12.5 0zM14.6 4.9c-.21-.12-.48-.05-.6.16-.12.21-.05.48.16.6 1.47.84 2.44 2.3 2.72 3.99.04.23.24.4.47.4.02 0 .05 0 .07-.01.26-.05.43-.3.38-.56C17.51 6.56 16.36 4.85 14.6 4.9zM19.1 12.26c-1.67 3.3-5.15 5.62-8.74 5.85-.55.03-1.1-.01-1.64-.1-.25-.04-.51.06-.68.27-.67.84-2.75 2.72-4.69 2.72H3.27c-.15 0-.27-.12-.27-.27v-.12c.22-1.7 1.38-2.98 2.34-3.78-1.25-.66-2.1-1.84-2.1-3.13 0-2.64 3.17-4.77 7.09-4.77.63 0 1.26.06 1.86.19.8-1.1 2.17-1.82 3.7-1.82.39 0 .78.05 1.14.16.14.04.29.03.41-.05l2.07-1.47c.35-.25.82.06.74.49l-.31 1.55c-.05.24.04.49.23.64 1.27 1.04 2.11 2.64 2.11 4.36 0 .58-.1 1.15-.28 1.69z"/></svg></span>
                <span class="share-label">微博</span>
            </button>
            <button class="share-option" data-share="qq" title="分享到 QQ">
                <span class="share-icon"><svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm5.5 13.28c-.18.5-.42.97-.74 1.4-.13.17-.06.43.14.52.3.15.59.32.86.53.21.16.15.48-.08.6-2.1 1.12-4.8 1.67-6.68 1.67-1.87 0-4.57-.55-6.67-1.67-.23-.12-.3-.44-.09-.6.28-.2.57-.38.87-.53.2-.09.27-.35.14-.52-.32-.43-.56-.9-.74-1.4-.1-.28-.37-.44-.65-.39-1.32.23-2.2-.61-1.48-1.73.48-.75 1.4-.95 2.17-.9-.06-.9-.45-1.7-.58-2.57-.18-1.27.4-2.18 1.55-2.74C9.9 6.42 10.9 6.5 12 6.5s2.1-.08 3.17.42c1.15.56 1.73 1.47 1.55 2.74-.13.87-.52 1.67-.58 2.57.77-.05 1.69.15 2.17.9.72 1.12-.16 1.96-1.48 1.73-.27-.05-.54.11-.64.39z"/></svg></span>
                <span class="share-label">QQ</span>
            </button>
            <button class="share-option" data-share="juejin" title="复制掘金分享文案">
                <span class="share-icon"><svg viewBox="0 0 24 24"><path d="M12 2l2.4 2.4L12 6.8 9.6 4.4 12 2zM6.4 8.4L12 14l5.6-5.6L12 2.8 6.4 8.4zm0 5.2L12 19.2l5.6-5.6L12 8 6.4 13.6z"/></svg></span>
                <span class="share-label">掘金</span>
            </button>
        </div>
        <div class="share-copy-row">
            <input type="text" class="share-link-input" id="shareLinkInput" readonly>
            <button class="share-copy-btn" id="shareCopyBtn">复制链接</button>
        </div>
    </div>

    <div class="image-lightbox" id="imageLightbox">
        <button class="close-btn" id="closeLightbox">&times;</button>
        <img src="" class="lightbox-content" id="lightboxImage">
    </div>

    <button class="toc-float-btn" id="tocFloatBtn" aria-label="目录">
        <svg viewBox="0 0 24 24"><path d="M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z"/></svg>
    </button>

    <!-- 沉浸模式退出按钮 -->
    <button class="immersive-exit-btn" id="immersiveExitBtn">退出沉浸</button>

    <div class="drawer-mask" id="drawerMask"></div>
    <div class="toc-drawer" id="tocDrawer">
        <div class="toc-title">
            目录
            <button class="close-btn" id="closeDrawer">&times;</button>
        </div>
        <ul class="toc-list">
            <#if tocList??>
                <#list tocList as item>
                    <li class="level-${item.level!1}"><a href="#${item.id!''}" data-target="${item.id!''}">${item.text!''}</a></li>
                </#list>
            </#if>
        </ul>
    </div>

    <!-- 右侧评论抽屉（点击左侧评论栏打开，不打断阅读位置） -->
    <div class="comment-drawer-mask" id="commentDrawerMask"></div>
    <aside class="comment-drawer" id="commentDrawer" aria-hidden="true">
        <div class="comment-drawer-header">
            <span class="comment-drawer-title">评论 <span id="drawerCommentTitleCount">0</span></span>
            <button class="comment-drawer-close" id="commentDrawerClose" aria-label="关闭">&times;</button>
        </div>
        <div class="comment-drawer-body" id="commentDrawerBody">
            <div class="comment-input-area">
                <div class="comment-input-avatar">
                    <img src="" alt="avatar" id="drawerCommentUserAvatar">
                </div>
                <div class="comment-input-wrap">
                    <textarea id="drawerCommentTextarea" placeholder="写下你的评论..." maxlength="1000"></textarea>
                    <div class="comment-image-preview" id="drawerCommentImagePreview"></div>
                    <div class="comment-input-footer">
                        <div class="comment-footer-left">
                            <span class="login-tip" id="drawerLoginTip"><a id="drawerLoginLink">登录</a>后参与评论</span>
                            <div class="comment-toolbar">
                                <button type="button" class="comment-tool-btn" id="drawerCommentEmojiBtn" title="表情">😊</button>
                                <button type="button" class="comment-tool-btn" id="drawerCommentImageBtn" title="插入图片">图片</button>
                                <input type="file" id="drawerCommentImageInput" accept="image/*" style="display:none;">
                            </div>
                        </div>
                        <div class="comment-footer-right">
                            <span class="comment-char-count"><span id="drawerCommentCharCount">0</span>/1000</span>
                            <button class="comment-submit-btn" id="drawerCommentSubmitBtn">发表评论</button>
                        </div>
                    </div>
                </div>
            </div>
            <ul class="comment-list" id="drawerCommentList"></ul>
            <div class="comment-empty" id="drawerCommentEmpty" style="display:none;">
                <svg viewBox="0 0 24 24" width="48" height="48"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z" fill="#c9cdd4"/></svg>
                <div>暂无评论，快来抢沙发吧~</div>
            </div>
            <button class="load-more-btn" id="drawerCommentLoadMore" style="display:none;">加载更多评论</button>
        </div>
    </aside>

    <!-- 举报反馈弹窗 -->
    <div class="modal-overlay" id="reportModalOverlay">
        <div class="modal-container report-modal" id="reportModal">
            <div class="modal-header">
                <h3 class="modal-title">举报反馈</h3>
                <button class="modal-close-btn" id="closeReportModal">&times;</button>
            </div>
            <div class="modal-body">
                <div class="report-group">
                    <div class="report-group-title">内容违规</div>
                    <div class="report-options">
                        <button class="report-option-btn" data-reason="低俗色情">低俗色情</button>
                        <button class="report-option-btn" data-reason="内容抄袭">内容抄袭</button>
                        <button class="report-option-btn" data-reason="涉嫌违法">涉嫌违法</button>
                        <button class="report-option-btn" data-reason="恶意营销">恶意营销</button>
                    </div>
                </div>
                <div class="report-group">
                    <div class="report-group-title">内容低质</div>
                    <div class="report-options">
                        <button class="report-option-btn" data-reason="内容质量太差">内容质量太差</button>
                    </div>
                </div>
                <div class="report-group">
                    <div class="report-group-title">侵犯权益</div>
                    <div class="report-options">
                        <button class="report-option-btn" data-reason="侵犯名誉/隐私/著作/肖像权">侵犯名誉/隐私/著作/肖像权</button>
                    </div>
                </div>
                <div class="report-group">
                    <div class="report-group-title">其他原因</div>
                    <div class="report-options">
                        <button class="report-option-btn" data-reason="其他原因">其他原因</button>
                    </div>
                </div>
                <div class="report-textarea-group">
                    <label class="report-textarea-label">补充说明</label>
                    <textarea class="report-textarea" id="reportTextarea" placeholder="请输入举报相关的补充说明" maxlength="100"></textarea>
                    <div class="report-textarea-count"><span id="reportCharCount">0</span>/100</div>
                </div>
                <div class="report-upload-group">
                    <div class="report-upload-label">上传图片（选填，最多4张）</div>
                    <div class="report-upload-area" id="reportUploadArea">
                        <input type="file" id="reportImageInput" accept="image/*" multiple style="display:none;">
                        <div class="report-upload-box" id="reportUploadBox">
                            <span class="upload-plus">+</span>
                            <span class="upload-text" id="reportUploadText">上传 0/4</span>
                        </div>
                    </div>
                </div>
            </div>
            <div class="modal-footer report-footer">
                <button class="cancel-btn" id="cancelReportBtn">取消</button>
                <button class="confirm-btn" id="confirmReportBtn">确定举报</button>
            </div>
        </div>
    </div>

    <!-- 打赏弹窗 -->
    <div class="modal-overlay" id="tipModalOverlay">
        <div class="modal-container tip-modal" id="tipModal">
            <div class="modal-header">
                <div class="tip-modal-title">赞赏作者</div>
                <div class="tip-modal-sub">你的支持是作者持续创作的最大动力</div>
                <button class="modal-close-btn" id="closeTipModal">&times;</button>
            </div>
            <div class="modal-body">
                <div class="tip-amount-grid" id="tipAmountGrid">
                    <button type="button" class="tip-amount-option active" data-amount="1">1</button>
                    <button type="button" class="tip-amount-option" data-amount="5">5</button>
                    <button type="button" class="tip-amount-option" data-amount="10">10</button>
                    <button type="button" class="tip-amount-option" data-amount="50">50</button>
                </div>
                <input type="number" class="tip-amount-custom" id="tipAmountCustom" placeholder="自定义金额（元）" min="1" max="10000">
                <textarea class="tip-message-input" id="tipMessage" placeholder="说点什么鼓励一下作者（选填，最多200字）" maxlength="200"></textarea>
                <button class="tip-pay-btn" id="tipPayBtn">立即赞赏</button>
                <div class="tip-pay-note">赞赏金额将进入平台账户，用于支持作者创作</div>
            </div>
        </div>
    </div>

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

    <script>
        // 雪花ID超过 JS Number 安全范围(2^53)，必须作为字符串注入，避免被 JS 解析成 Number 丢精度，
        // 否则上报阅读量/评论等按 articleId 查询会匹配不到记录（浏览量不累加、评论拉不到）
        window.ARTICLE_ID = "${(articleId!0)?c}";
        window.AUTHOR_ID = "${(authorId!0)?c}";
    </script>
    <script>
        // 加载共用交互脚本（方案②：作为内容服务静态资源由网关 /content/article-static.js 统一提供）
        (function() {
            var s = document.createElement('script');
            s.src = '/content/article-static.js';
            s.defer = true;
            document.body.appendChild(s);
        })();
    </script>
</body>
</html>