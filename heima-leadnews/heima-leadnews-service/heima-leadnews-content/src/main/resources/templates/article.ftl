<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta http-equiv="X-UA-Compatible" content="IE=edge">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0, viewport-fit=cover">
    <title>${title!''} - 黑马头条</title>
    <style>
        * { box-sizing: border-box; }
        body {
            margin: 0;
            padding: 0;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans", sans-serif;
            background: #f4f5f5;
            color: #252933;
            line-height: 1.75;
        }
        a { text-decoration: none; color: #1e80ff; }
        img { max-width: 100%; height: auto; }

        /* 主体布局 */
        .main-wrapper {
            display: flex;
            justify-content: center;
            padding: 56px 20px 40px;
            max-width: 1200px;
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
            font-size: 32px;
            font-weight: 700;
            line-height: 1.4;
            margin: 0 0 20px;
            color: #252933;
            word-break: break-word;
        }

        /* 作者信息 */
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
        }
        .author-avatar img {
            width: 100%;
            height: 100%;
            object-fit: cover;
        }
        .author-info {
            flex: 1;
            min-width: 0;
        }
        .author-name {
            font-size: 16px;
            font-weight: 600;
            color: #252933;
            margin-bottom: 4px;
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
            margin-top: 32px;
            margin-bottom: 16px;
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

        .author-info-card {
            background: #fff;
            border-radius: 4px;
            padding: 20px 0;
            box-shadow: 0 1px 2px rgba(0,0,0,0.05);
            margin-bottom: 16px;
        }
        .author-info-card .author-avatar-wrap {
            display: flex;
            flex-direction: column;
            align-items: center;
            padding: 0 20px;
            margin-bottom: 16px;
        }
        .author-info-card .avatar {
            width: 64px;
            height: 64px;
            border-radius: 50%;
            object-fit: cover;
            margin-bottom: 10px;
            border: 2px solid #1e80ff;
        }
        .author-info-card .name {
            font-size: 16px;
            font-weight: 600;
            color: #252933;
            margin-bottom: 4px;
        }
        .author-info-card .badge {
            font-size: 12px;
            color: #1e80ff;
            background: #eaf2ff;
            padding: 2px 8px;
            border-radius: 4px;
            margin-bottom: 8px;
        }
        .author-info-card .job-title {
            font-size: 13px;
            color: #515767;
        }
        .author-info-card .company {
            font-size: 13px;
            color: #515767;
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

        /* 右侧目录 */
        .toc-sidebar {
            width: 260px;
            flex-shrink: 0;
        }
        .toc-card {
            position: sticky;
            top: 56px;
            background: #fff;
            border-radius: 4px;
            padding: 16px 0;
            box-shadow: 0 1px 2px rgba(0,0,0,0.05);
            max-height: calc(100vh - 100px);
            overflow-y: auto;
        }
        .toc-title {
            font-size: 15px;
            font-weight: 600;
            color: #252933;
            padding: 0 16px 12px;
            border-bottom: 1px solid #e4e6eb;
            margin-bottom: 8px;
        }
        .toc-list {
            list-style: none;
            margin: 0;
            padding: 0;
        }
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

        /* 作者作品 */
        .author-works-card {
            margin-top: 16px;
            background: #fff;
            border-radius: 4px;
            padding: 16px 0;
            box-shadow: 0 1px 2px rgba(0,0,0,0.05);
        }
        .works-title {
            font-size: 15px;
            font-weight: 600;
            color: #252933;
            padding: 0 16px 12px;
            border-bottom: 1px solid #e4e6eb;
            margin-bottom: 8px;
        }
        .works-list {
            list-style: none;
            margin: 0;
            padding: 0;
        }
        .work-item {
            padding: 0;
        }
        .work-link {
            display: flex;
            flex-direction: column;
            padding: 10px 16px;
            text-decoration: none;
            transition: all 0.2s;
            border-bottom: 1px solid #f7f8fa;
        }
        .work-link:hover {
            background: #f7f8fa;
        }
        .work-article-title {
            font-size: 14px;
            color: #252933;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            margin-bottom: 4px;
            line-height: 1.4;
        }
        .work-publish-time {
            font-size: 12px;
            color: #8a919f;
        }

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
            justify-content: flex-end;
            align-items: center;
            gap: 8px;
        }
        .comment-input-wrap .login-tip {
            font-size: 13px;
            color: #8a919f;
        }
        .comment-input-wrap .login-tip a {
            color: #1e80ff;
            cursor: pointer;
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
            display: flex;
            gap: 8px;
            margin-top: 8px;
            margin-left: 40px;
        }
        .reply-input-area input {
            flex: 1;
            padding: 6px 10px;
            border: 1px solid #e4e6eb;
            border-radius: 4px;
            font-size: 13px;
            outline: none;
        }
        .reply-input-area input:focus {
            border-color: #1e80ff;
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
        .recommend-list {
            list-style: none;
            margin: 0;
            padding: 0;
        }
        .recommend-item {
            padding: 14px 0;
            border-bottom: 1px solid #f2f3f5;
        }
        .recommend-item:last-child {
            border-bottom: none;
        }
        .recommend-item-title {
            font-size: 15px;
            font-weight: 500;
            color: #252933;
            margin-bottom: 6px;
            line-height: 1.4;
            display: -webkit-box;
            -webkit-line-clamp: 2;
            -webkit-box-orient: vertical;
            overflow: hidden;
        }
        .recommend-item-title a {
            color: #252933;
        }
        .recommend-item-title a:hover {
            color: #1e80ff;
        }
        .recommend-item-meta {
            display: flex;
            flex-wrap: wrap;
            gap: 8px;
            align-items: center;
            font-size: 12px;
            color: #8a919f;
        }
        .recommend-item-meta .category-tag {
            display: inline-block;
            padding: 1px 6px;
            border-radius: 3px;
            background: #eaf2ff;
            color: #1e80ff;
            font-size: 11px;
        }
        .recommend-item-meta .meta-sep {
            color: #c4c9d1;
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

        .action-sidebar {
            position: fixed;
            left: 0;
            top: 50%;
            transform: translateY(-50%);
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 8px;
            padding: 12px;
            background: rgba(255,255,255,0.95);
            border-radius: 0 8px 8px 0;
            box-shadow: 2px 0 8px rgba(0,0,0,0.08);
            z-index: 999;
        }
        .action-sidebar .action-item {
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 4px;
            padding: 8px;
            cursor: pointer;
            border-radius: 6px;
            transition: all 0.2s;
        }
        .action-sidebar .action-item:hover {
            background: #f7f8fa;
        }
        .action-sidebar .action-item.active {
            color: #1e80ff;
        }
        .action-sidebar .action-icon {
            width: 28px;
            height: 28px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 20px;
        }
        .action-sidebar .action-count {
            font-size: 12px;
            color: #8a919f;
        }
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

        @media (max-width: 960px) {
            .toc-sidebar { display: none; }
            .action-sidebar { display: none; }
            .main-wrapper { padding-top: 56px; }
            .content-card { padding: 20px; }
            .article-title { font-size: 24px; }
            .toc-float-btn { display: flex; }
            .comment-section { padding: 20px 16px; }
            .recommend-section { padding: 20px 16px; }
        }
        @media (max-width: 640px) {
            .main-wrapper { padding: 56px 12px 24px; }
            .content-card { padding: 16px; }
            .article-title { font-size: 22px; }
            .action-bar { flex-wrap: wrap; }
            .comment-section { padding: 16px 12px; }
            .recommend-section { padding: 16px 12px; }
            .column-card { flex-wrap: wrap; }
            .column-cover { width: 80px; height: 56px; }
            .column-meta { width: 100%; justify-content: flex-start; margin-top: 8px; }
        }
    </style>
</head>
<body>
    <div class="main-wrapper">
        <article class="content-area">
            <div class="content-card">
                <h1 class="article-title">${title!''}</h1>

                <div class="author-header">
                    <div class="author-avatar">
                        <img src="${authorAvatar!'https://p3.pstatp.com/thumb/1480/7186611868'}" alt="avatar">
                    </div>
                    <div class="author-info">
                        <div class="author-name">${authorName!'黑马头条'}</div>
                        <div class="publish-meta">
                            <span class="publish-time">
                                <#if publishTime??>${publishTime?string('yyyy-MM-dd HH:mm')}</#if>
                            </span>
                            <span class="meta-divider">·</span>
                            <span class="read-count">${readCount!0}阅读</span>
                            <span class="meta-divider">·</span>
                            <span class="read-time">${readTime!5}分钟阅读</span>
                        </div>
                    </div>
                    <button class="follow-btn<#if relation?? && relation.isfollow?? && relation.isfollow> active</#if>" id="followBtn">
                        <#if relation?? && relation.isfollow?? && relation.isfollow>已关注<#else>+ 关注</#if>
                    </button>
                </div>

                <div class="article-body">
                    <#noautoesc>${(htmlContent! '')}</#noautoesc>
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
                <div class="comment-title">评论</div>
                <div class="comment-input-area" id="commentInputArea">
                    <div class="comment-input-avatar">
                        <img src="" alt="avatar" id="commentUserAvatar">
                    </div>
                    <div class="comment-input-wrap">
                        <textarea id="commentTextarea" placeholder="写下你的评论..."></textarea>
                        <div class="comment-input-footer">
                            <span class="login-tip" id="loginTip" style="display:none;">
                                <a id="loginLink">登录</a>后参与评论
                            </span>
                            <button class="comment-submit-btn" id="commentSubmitBtn">发表评论</button>
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
                <ul class="recommend-list" id="recommendList"></ul>
                <button class="load-more-btn" id="recommendLoadMore" style="display:none;">加载更多</button>
            </div>
        </article>

        <aside class="toc-sidebar">
            <div class="author-info-card">
                <div class="author-avatar-wrap">
                    <img src="${authorAvatar!'https://p3.pstatp.com/thumb/1480/7186611868'}" class="avatar" alt="avatar">
                    <div class="name">${authorName!'黑马头条'}</div>
                    <div class="badge">AI + 全栈开发工程师</div>
                    <div class="job-title">${authorJobTitle!'全栈开发工程师'}</div>
                    <div class="company">${authorCompany!'某科技公司'}</div>
                </div>
                <div class="stats">
                    <div class="stat-item">
                        <div class="stat-value">${articleCount!0}</div>
                        <div class="stat-label">文章</div>
                    </div>
                    <div class="stat-item">
                        <div class="stat-value">${readCount!0}</div>
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
            <div class="toc-card">
                <div class="toc-title">目录</div>
                <ul class="toc-list">
                    <#if tocList??>
                        <#list tocList as item>
                            <li class="level-${item.level!1}"><a href="#${item.id!''}" data-target="${item.id!''}">${item.text!''}</a></li>
                        </#list>
                    </#if>
                </ul>
            </div>
            <div class="author-works-card">
                <div class="works-title">作者作品</div>
                <ul class="works-list">
                    <#if authorWorks??>
                        <#list authorWorks as work>
                            <li class="work-item">
                                <a href="${work.staticUrl!'#'}" class="work-link" target="_blank">
                                    <span class="work-article-title">${work.title!''}</span>
                                    <span class="work-publish-time">
                                        <#if work.publishTime??>${work.publishTime?string('MM-dd')}</#if>
                                    </span>
                                </a>
                            </li>
                        </#list>
                    </#if>
                </ul>
            </div>
            <!-- 相关推荐 -->
            <div class="sidebar-recommend-card" id="relatedCard">
                <div class="sidebar-recommend-title">相关推荐</div>
                <ul class="sidebar-recommend-list" id="relatedList">
                    <li class="sidebar-recommend-empty">加载中...</li>
                </ul>
            </div>
            <!-- 精选内容 -->
            <div class="sidebar-recommend-card" id="featuredCard">
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
    </div>

    <div class="image-lightbox" id="imageLightbox">
        <button class="close-btn" id="closeLightbox">&times;</button>
        <img src="" class="lightbox-content" id="lightboxImage">
    </div>

    <button class="toc-float-btn" id="tocFloatBtn" aria-label="目录">
        <svg viewBox="0 0 24 24"><path d="M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z"/></svg>
    </button>

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

    <script>
        (function() {
            var articleId = '${articleId!0}';
            var tocLinks = document.querySelectorAll('.toc-list a');
            var headings = Array.from(document.querySelectorAll('.article-body h1, .article-body h2, .article-body h3'));

            // ========== 工具函数 ==========
            function getToken() {
                return localStorage.getItem('token') || localStorage.getItem('user_token') || '';
            }

            function getHeaders() {
                var headers = { 'Content-Type': 'application/json' };
                var token = getToken();
                if (token) {
                    headers['X-Token'] = token;
                    headers['Authorization'] = 'Bearer ' + token;
                }
                return headers;
            }

            function isLoggedIn() {
                return !!getToken();
            }

            function formatTime(ts) {
                if (!ts) return '';
                var d = new Date(ts);
                var now = new Date();
                var diff = Math.floor((now - d) / 1000);
                if (diff < 60) return '刚刚';
                if (diff < 3600) return Math.floor(diff / 60) + '分钟前';
                if (diff < 86400) return Math.floor(diff / 3600) + '小时前';
                if (diff < 172800) return '昨天';
                var m = (d.getMonth() + 1);
                var day = d.getDate();
                if (d.getFullYear() === now.getFullYear()) {
                    return m + '-' + day;
                }
                return d.getFullYear() + '-' + m + '-' + day;
            }

            function escapeHtml(text) {
                if (!text) return '';
                var div = document.createElement('div');
                div.appendChild(document.createTextNode(text));
                return div.innerHTML;
            }

            function apiGet(url) {
                return fetch(url, { headers: getHeaders() }).then(function(r) { return r.json(); });
            }

            function apiPost(url, body) {
                return fetch(url, {
                    method: 'POST',
                    headers: getHeaders(),
                    body: body ? JSON.stringify(body) : undefined
                }).then(function(r) { return r.json(); });
            }

            // ========== 平滑滚动 ==========
            function bindTocClick(links) {
                links.forEach(function(link) {
                    link.addEventListener('click', function(e) {
                        var targetId = this.getAttribute('data-target');
                        var target = document.getElementById(targetId);
                        if (target) {
                            e.preventDefault();
                            var top = target.getBoundingClientRect().top + window.pageYOffset - 72;
                            window.scrollTo({ top: top, behavior: 'smooth' });
                        }
                        closeDrawer();
                    });
                });
            }
            bindTocClick(tocLinks);

            // 高亮当前目录
            function highlightToc() {
                var scrollPos = window.pageYOffset + 80;
                var current = null;
                headings.forEach(function(h) {
                    if (h.offsetTop <= scrollPos) {
                        current = h;
                    }
                });
                tocLinks.forEach(function(link) {
                    link.classList.remove('active');
                });
                if (current) {
                    var activeLink = document.querySelector('.toc-list a[data-target="' + current.id + '"]');
                    if (activeLink) activeLink.classList.add('active');
                }
            }
            window.addEventListener('scroll', highlightToc);
            highlightToc();

            // 移动端抽屉
            var tocFloatBtn = document.getElementById('tocFloatBtn');
            var tocDrawer = document.getElementById('tocDrawer');
            var drawerMask = document.getElementById('drawerMask');
            var closeDrawerBtn = document.getElementById('closeDrawer');

            function openDrawer() {
                tocDrawer.classList.add('open');
                drawerMask.classList.add('open');
            }
            function closeDrawer() {
                tocDrawer.classList.remove('open');
                drawerMask.classList.remove('open');
            }
            if (tocFloatBtn) tocFloatBtn.addEventListener('click', openDrawer);
            if (closeDrawerBtn) closeDrawerBtn.addEventListener('click', closeDrawer);
            if (drawerMask) drawerMask.addEventListener('click', closeDrawer);

            // 头像滚动显示
            var actionSidebar = document.getElementById('actionSidebar');
            var miniAuthorAvatar = document.getElementById('miniAuthorAvatar');
            function handleScrollForAvatar() {
                var scrollTop = window.pageYOffset;
                if (scrollTop > 300) {
                    miniAuthorAvatar.classList.add('visible');
                } else {
                    miniAuthorAvatar.classList.remove('visible');
                }
            }
            window.addEventListener('scroll', handleScrollForAvatar);
            handleScrollForAvatar();

            // 图片灯箱
            var articleImages = document.querySelectorAll('.article-body img');
            var imageLightbox = document.getElementById('imageLightbox');
            var lightboxImage = document.getElementById('lightboxImage');
            var closeLightboxBtn = document.getElementById('closeLightbox');

            articleImages.forEach(function(img) {
                img.style.cursor = 'pointer';
                img.addEventListener('click', function() {
                    lightboxImage.src = this.src;
                    imageLightbox.classList.add('open');
                    document.body.style.overflow = 'hidden';
                });
            });

            function closeLightbox() {
                imageLightbox.classList.remove('open');
                document.body.style.overflow = '';
            }
            if (closeLightboxBtn) closeLightboxBtn.addEventListener('click', closeLightbox);
            imageLightbox.addEventListener('click', function(e) {
                if (e.target === imageLightbox) closeLightbox();
            });
            document.addEventListener('keydown', function(e) {
                if (e.key === 'Escape') closeLightbox();
            });

            // ========== 文章详情加载 ==========
            var detailData = null;
            var diggCount = 0;
            var collectCount = 0;
            var commentCount = 0;

            function loadArticleDetail() {
                apiGet('/content/api/v1/article/detail/' + articleId).then(function(res) {
                    if (res && res.code === 200 && res.data) {
                        detailData = res.data;
                        diggCount = res.data.diggCount || 0;
                        collectCount = res.data.collectCount || 0;
                        commentCount = res.data.commentCount || 0;
                        updateSidebarCounts();
                        updateActionButtons();
                        updateFollowButton();
                    }
                }).catch(function(err) {
                    console.error('加载文章详情失败:', err);
                });
            }

            function updateSidebarCounts() {
                var sideLikeCount = document.querySelector('#sideLikeBtn .action-count');
                var sideCollectCount = document.querySelector('#sideCollectBtn .action-count');
                var sideCommentCount = document.querySelector('#sideCommentBtn .action-count');
                if (sideLikeCount) sideLikeCount.textContent = diggCount;
                if (sideCollectCount) sideCollectCount.textContent = collectCount;
                if (sideCommentCount) sideCommentCount.textContent = commentCount;
            }

            function updateActionButtons() {
                if (!detailData) return;
                var likeBtn = document.getElementById('likeBtn');
                var collectBtn = document.getElementById('collectBtn');
                var sideLikeBtn = document.getElementById('sideLikeBtn');
                var sideCollectBtn = document.getElementById('sideCollectBtn');

                if (detailData.isDigg) {
                    likeBtn.classList.add('active');
                    sideLikeBtn.classList.add('active');
                    document.getElementById('likeBtnText').textContent = '已赞';
                }
                if (detailData.isCollect) {
                    collectBtn.classList.add('active');
                    sideCollectBtn.classList.add('active');
                    document.getElementById('collectBtnText').textContent = '已收藏';
                }
            }

            function updateFollowButton() {
                if (!detailData) return;
                var followBtn = document.getElementById('followBtn');
                var authorFollowBtn = document.getElementById('authorFollowBtn');
                if (detailData.isFollow) {
                    if (followBtn) { followBtn.classList.add('active'); followBtn.textContent = '已关注'; }
                    if (authorFollowBtn) { authorFollowBtn.classList.add('active'); authorFollowBtn.textContent = '已关注'; }
                }
            }

            // ========== 点赞/收藏/关注 API ==========
            document.getElementById('likeBtn').addEventListener('click', function() {
                var btn = this;
                apiPost('/content/api/v1/article/' + articleId + '/like').then(function(res) {
                    if (res && res.code === 200 && res.data) {
                        btn.classList.toggle('active', res.data.liked);
                        document.getElementById('likeBtnText').textContent = res.data.liked ? '已赞' : '点赞';
                        diggCount = res.data.diggCount || 0;
                        updateSidebarCounts();
                        var sideLikeBtn = document.getElementById('sideLikeBtn');
                        sideLikeBtn.classList.toggle('active', res.data.liked);
                    }
                }).catch(function(err) { console.error('点赞失败:', err); });
            });

            document.getElementById('collectBtn').addEventListener('click', function() {
                var btn = this;
                apiPost('/content/api/v1/article/' + articleId + '/collect').then(function(res) {
                    if (res && res.code === 200 && res.data) {
                        btn.classList.toggle('active', res.data.collected);
                        document.getElementById('collectBtnText').textContent = res.data.collected ? '已收藏' : '收藏';
                        collectCount = res.data.collectCount || 0;
                        updateSidebarCounts();
                        var sideCollectBtn = document.getElementById('sideCollectBtn');
                        sideCollectBtn.classList.toggle('active', res.data.collected);
                    }
                }).catch(function(err) { console.error('收藏失败:', err); });
            });

            function handleFollow() {
                apiPost('/content/api/v1/article/' + articleId + '/follow').then(function(res) {
                    if (res && res.code === 200 && res.data) {
                        var followed = res.data.followed;
                        var btns = [document.getElementById('followBtn'), document.getElementById('authorFollowBtn')];
                        btns.forEach(function(btn) {
                            if (btn) {
                                btn.classList.toggle('active', followed);
                                btn.textContent = followed ? '已关注' : '+ 关注';
                            }
                        });
                    }
                }).catch(function(err) { console.error('关注失败:', err); });
            }
            document.getElementById('followBtn').addEventListener('click', handleFollow);
            document.getElementById('authorFollowBtn').addEventListener('click', handleFollow);

            // 侧边栏点赞/收藏
            document.getElementById('sideLikeBtn').addEventListener('click', function() {
                document.getElementById('likeBtn').click();
            });
            document.getElementById('sideCollectBtn').addEventListener('click', function() {
                document.getElementById('collectBtn').click();
            });
            document.getElementById('sideCommentBtn').addEventListener('click', function() {
                var commentSection = document.getElementById('commentSection');
                if (commentSection) {
                    var top = commentSection.getBoundingClientRect().top + window.pageYOffset - 72;
                    window.scrollTo({ top: top, behavior: 'smooth' });
                }
            });

            // ========== 专栏加载 ==========
            function loadColumn() {
                apiGet('/content/api/v1/article/' + articleId + '/column').then(function(res) {
                    if (res && res.code === 200 && res.data && res.data.columnId) {
                        var data = res.data;
                        document.getElementById('columnCover').src = data.columnCover || '';
                        document.getElementById('columnName').textContent = data.columnTitle || '';
                        document.getElementById('columnDesc').textContent = data.columnDescription || '';
                        document.getElementById('columnArticleCnt').textContent = data.articleCnt || 0;
                        document.getElementById('columnFollowCnt').textContent = data.followCnt || 0;
                        var subscribeBtn = document.getElementById('columnSubscribeBtn');
                        if (data.isFollow) {
                            subscribeBtn.classList.add('active');
                            subscribeBtn.textContent = '已订阅';
                        }
                        // 上下篇导航
                        var prevLink = document.getElementById('prevArticleLink');
                        var nextLink = document.getElementById('nextArticleLink');
                        if (data.prevArticleId) {
                            prevLink.href = '/article/' + data.prevArticleId;
                            prevLink.classList.remove('disabled');
                            prevLink.textContent = '← ' + (data.prevArticleTitle || '上一篇');
                        }
                        if (data.nextArticleId) {
                            nextLink.href = '/article/' + data.nextArticleId;
                            nextLink.classList.remove('disabled');
                            nextLink.textContent = (data.nextArticleTitle || '下一篇') + ' →';
                        }
                        document.getElementById('columnSection').classList.add('visible');
                    }
                }).catch(function(err) { console.error('加载专栏信息失败:', err); });
            }

            // ========== 评论功能 ==========
            var commentCursor = '';
            var commentHasMore = false;
            var commentLoading = false;
            var replyToCommentId = null;
            var replyToRootId = null;

            // 检查登录状态
            function checkCommentLogin() {
                var textarea = document.getElementById('commentTextarea');
                var submitBtn = document.getElementById('commentSubmitBtn');
                var loginTip = document.getElementById('loginTip');
                var avatar = document.getElementById('commentUserAvatar');
                if (isLoggedIn()) {
                    textarea.disabled = false;
                    textarea.placeholder = '写下你的评论...';
                    submitBtn.disabled = false;
                    loginTip.style.display = 'none';
                    avatar.src = ''; // 可以在文章详情中获取用户头像
                } else {
                    textarea.disabled = true;
                    textarea.placeholder = '登录后参与评论';
                    submitBtn.disabled = true;
                    loginTip.style.display = 'block';
                    avatar.src = '';
                }
            }
            checkCommentLogin();

            function renderComment(comment) {
                var li = document.createElement('li');
                li.className = 'comment-item';
                li.setAttribute('data-comment-id', comment.commentId);
                var userInfo = comment.userInfo || {};
                var userName = userInfo.userName || '匿名用户';
                var avatarUrl = userInfo.avatarLarge || '';

                var html = '<div class="comment-user">';
                html += '<div class="comment-user-avatar"><img src="' + avatarUrl + '" alt="avatar"></div>';
                html += '<span class="comment-user-name">' + escapeHtml(userName) + '</span>';
                html += '<span class="comment-user-time">' + formatTime(comment.ctime) + '</span>';
                html += '</div>';
                html += '<div class="comment-content">' + escapeHtml(comment.content) + '</div>';
                html += '<div class="comment-actions">';
                html += '<button class="comment-action-btn comment-like-btn' + (comment.isDigg ? ' active' : '') + '" data-comment-id="' + comment.commentId + '">';
                html += '<svg viewBox="0 0 24 24"><path d="M2 20h2v-9H2v9zm20-9c0-1.1-.9-2-2-2h-3.17c-.53-1.4-1.53-2.56-2.83-3.09V4c0-1.66-1.34-3-3-3S8 2.34 8 4v1.91C5.94 6.56 4.5 8.69 4.5 11v6.17l-1.83 1.83L4.17 20h12.5c1.66 0 3.08-1.03 3.65-2.5H22v-6.5z"/></svg>';
                html += '<span>' + (comment.diggCount || 0) + '</span>';
                html += '</button>';
                html += '<button class="comment-action-btn comment-reply-btn" data-comment-id="' + comment.commentId + '">';
                html += '<svg viewBox="0 0 24 24"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>';
                html += '<span>回复</span>';
                html += '</button>';
                html += '</div>';

                // 子回复
                var replies = comment.replyInfos || [];
                if (replies.length > 0) {
                    var showReplies = replies.slice(0, 2);
                    var hasMoreReplies = replies.length > 2;
                    html += '<div class="reply-list">';
                    showReplies.forEach(function(reply) {
                        var replyUser = reply.userInfo || {};
                        html += '<div class="reply-item"><span class="reply-user">' + escapeHtml(replyUser.userName || '匿名') + '：</span>' + escapeHtml(reply.content) + '</div>';
                    });
                    html += '</div>';
                    if (hasMoreReplies) {
                        html += '<button class="reply-more-btn" data-comment-id="' + comment.commentId + '">查看全部 ' + replies.length + ' 条回复</button>';
                    }
                }

                li.innerHTML = html;
                return li;
            }

            function loadComments(append) {
                if (commentLoading) return;
                commentLoading = true;
                var url = '/content/api/v1/comment/article/' + articleId + '/comments?cursor=' + encodeURIComponent(commentCursor) + '&size=10';
                apiGet(url).then(function(res) {
                    commentLoading = false;
                    if (res && res.code === 200 && res.data) {
                        var list = res.data.list || [];
                        commentCursor = res.data.cursor || '';
                        commentHasMore = res.data.has_more || false;
                        var container = document.getElementById('commentList');
                        var emptyEl = document.getElementById('commentEmpty');
                        var loadMoreBtn = document.getElementById('commentLoadMore');

                        if (!append) {
                            container.innerHTML = '';
                        }

                        if (list.length === 0 && !append) {
                            emptyEl.style.display = 'block';
                            loadMoreBtn.style.display = 'none';
                        } else {
                            emptyEl.style.display = 'none';
                            list.forEach(function(comment) {
                                container.appendChild(renderComment(comment));
                            });
                            // 绑定评论按钮事件
                            bindCommentEvents();
                            loadMoreBtn.style.display = commentHasMore ? 'block' : 'none';
                        }
                    }
                }).catch(function(err) {
                    commentLoading = false;
                    console.error('加载评论失败:', err);
                });
            }

            function bindCommentEvents() {
                // 点赞评论
                document.querySelectorAll('.comment-like-btn').forEach(function(btn) {
                    btn.removeEventListener('click', handleCommentLike);
                    btn.addEventListener('click', handleCommentLike);
                });
                // 回复按钮
                document.querySelectorAll('.comment-reply-btn').forEach(function(btn) {
                    btn.removeEventListener('click', handleCommentReply);
                    btn.addEventListener('click', handleCommentReply);
                });
                // 查看更多回复
                document.querySelectorAll('.reply-more-btn').forEach(function(btn) {
                    btn.removeEventListener('click', handleShowMoreReplies);
                    btn.addEventListener('click', handleShowMoreReplies);
                });
            }

            function handleCommentLike(e) {
                e.stopPropagation();
                var btn = e.currentTarget;
                var commentId = btn.getAttribute('data-comment-id');
                if (!isLoggedIn()) {
                    alert('请先登录');
                    return;
                }
                apiPost('/content/api/v1/comment/comment/' + commentId + '/like').then(function(res) {
                    if (res && res.code === 200) {
                        btn.classList.toggle('active');
                        var countSpan = btn.querySelector('span');
                        var current = parseInt(countSpan.textContent) || 0;
                        countSpan.textContent = btn.classList.contains('active') ? (current + 1) : Math.max(0, current - 1);
                    }
                }).catch(function(err) { console.error('点赞评论失败:', err); });
            }

            function handleCommentReply(e) {
                e.stopPropagation();
                var btn = e.currentTarget;
                var commentId = btn.getAttribute('data-comment-id');
                if (!isLoggedIn()) {
                    alert('请先登录');
                    return;
                }
                // 移除已有的回复输入框
                var existing = document.querySelector('.reply-input-area');
                if (existing) existing.remove();
                replyToCommentId = commentId;
                replyToRootId = commentId;
                var area = document.createElement('div');
                area.className = 'reply-input-area';
                area.innerHTML = '<input type="text" placeholder="写下你的回复..." id="replyInput">' +
                    '<button class="reply-send-btn" id="replySendBtn">发送</button>' +
                    '<button class="reply-cancel-btn" id="replyCancelBtn">取消</button>';
                btn.parentNode.parentNode.appendChild(area);
                document.getElementById('replyInput').focus();
                document.getElementById('replySendBtn').addEventListener('click', function() {
                    sendReply();
                });
                document.getElementById('replyCancelBtn').addEventListener('click', function() {
                    area.remove();
                    replyToCommentId = null;
                    replyToRootId = null;
                });
                document.getElementById('replyInput').addEventListener('keydown', function(ev) {
                    if (ev.key === 'Enter') {
                        ev.preventDefault();
                        sendReply();
                    }
                });
            }

            function handleShowMoreReplies(e) {
                e.stopPropagation();
                // 简单实现：重新加载评论列表并展开全部
                // 这里可以展开显示所有回复，简化处理为重新加载
                var commentId = e.currentTarget.getAttribute('data-comment-id');
                // 找到对应的评论项，展开所有回复
                var parent = e.currentTarget.parentNode;
                var replyList = parent.querySelector('.reply-list');
                // 在实际场景中需要调用API获取更多回复，这里简化处理
                alert('查看更多回复功能开发中');
            }

            function sendReply() {
                var input = document.getElementById('replyInput');
                var content = input.value.trim();
                if (!content) return;
                if (!replyToCommentId) return;
                var body = { content: content };
                if (replyToRootId) {
                    body.rootId = parseInt(replyToRootId);
                }
                var url = '/content/api/v1/comment/comment/' + replyToCommentId + '/reply';
                apiPost(url, body).then(function(res) {
                    if (res && res.code === 200) {
                        input.value = '';
                        var area = document.querySelector('.reply-input-area');
                        if (area) area.remove();
                        replyToCommentId = null;
                        replyToRootId = null;
                        // 重新加载评论
                        commentCursor = '';
                        loadComments(false);
                    } else {
                        alert('回复失败: ' + (res.message || '未知错误'));
                    }
                }).catch(function(err) { console.error('回复失败:', err); });
            }

            // 发表评论
            document.getElementById('commentSubmitBtn').addEventListener('click', function() {
                if (!isLoggedIn()) {
                    alert('请先登录');
                    return;
                }
                var textarea = document.getElementById('commentTextarea');
                var content = textarea.value.trim();
                if (!content) {
                    alert('请输入评论内容');
                    return;
                }
                var btn = this;
                btn.disabled = true;
                btn.textContent = '提交中...';
                apiPost('/content/api/v1/comment/article/' + articleId + '/comment', { content: content }).then(function(res) {
                    btn.disabled = false;
                    btn.textContent = '发表评论';
                    if (res && res.code === 200) {
                        textarea.value = '';
                        commentCursor = '';
                        loadComments(false);
                        commentCount++;
                        updateSidebarCounts();
                    } else {
                        alert('评论失败: ' + (res.message || '未知错误'));
                    }
                }).catch(function(err) {
                    btn.disabled = false;
                    btn.textContent = '发表评论';
                    console.error('评论失败:', err);
                    alert('评论失败，请稍后重试');
                });
            });

            // 登录链接点击
            document.getElementById('loginLink').addEventListener('click', function() {
                alert('请先登录后操作');
            });

            // 加载更多评论
            document.getElementById('commentLoadMore').addEventListener('click', function() {
                loadComments(true);
            });

            // ========== 为你推荐 ==========
            var recommendCursor = '';
            var recommendHasMore = false;

            function loadRecommend(append) {
                var url = '/content/api/v1/article/' + articleId + '/recommend?cursor=' + encodeURIComponent(recommendCursor) + '&size=5';
                apiGet(url).then(function(res) {
                    if (res && res.code === 200 && res.data) {
                        var list = res.data.list || [];
                        recommendCursor = res.data.cursor || '';
                        recommendHasMore = res.data.has_more || false;
                        var container = document.getElementById('recommendList');
                        var loadMoreBtn = document.getElementById('recommendLoadMore');
                        if (!append) {
                            container.innerHTML = '';
                        }
                        list.forEach(function(item) {
                            var li = document.createElement('li');
                            li.className = 'recommend-item';
                            var tags = '';
                            if (item.categoryName) {
                                tags += '<span class="category-tag">' + escapeHtml(item.categoryName) + '</span>';
                            }
                            li.innerHTML = '<div class="recommend-item-title"><a href="/article/' + item.articleId + '" target="_blank">' + escapeHtml(item.title) + '</a></div>' +
                                '<div class="recommend-item-meta">' +
                                '<span>' + escapeHtml(item.authorName || '') + '</span>' +
                                '<span class="meta-sep">·</span>' +
                                '<span>' + formatTime(item.publishTime) + '</span>' +
                                '<span class="meta-sep">·</span>' +
                                '<span>' + (item.viewCount || 0) + '阅读</span>' +
                                '<span class="meta-sep">·</span>' +
                                '<span>' + (item.diggCount || 0) + '赞</span>' +
                                '<span class="meta-sep">·</span>' +
                                '<span>' + (item.commentCount || 0) + '评论</span>' +
                                tags +
                                '</div>';
                            container.appendChild(li);
                        });
                        loadMoreBtn.style.display = recommendHasMore ? 'block' : 'none';
                    }
                }).catch(function(err) { console.error('加载为你推荐失败:', err); });
            }

            document.getElementById('recommendLoadMore').addEventListener('click', function() {
                loadRecommend(true);
            });

            // ========== 相关推荐（右侧边栏） ==========
            function loadRelated() {
                var url = '/content/api/v1/article/' + articleId + '/related?cursor=&size=5';
                apiGet(url).then(function(res) {
                    var container = document.getElementById('relatedList');
                    if (res && res.code === 200 && res.data) {
                        var list = res.data.list || [];
                        if (list.length === 0) {
                            container.innerHTML = '<li class="sidebar-recommend-empty">暂无相关推荐</li>';
                            return;
                        }
                        container.innerHTML = '';
                        list.forEach(function(item) {
                            var li = document.createElement('li');
                            li.className = 'sidebar-recommend-item';
                            li.innerHTML = '<a href="/article/' + item.articleId + '" class="sidebar-recommend-link" target="_blank">' +
                                '<span class="sidebar-recommend-link-title">' + escapeHtml(item.title) + '</span>' +
                                '<span class="sidebar-recommend-link-meta">' + escapeHtml(item.authorName || '') + ' · ' + formatTime(item.publishTime) + '</span>' +
                                '</a>';
                            container.appendChild(li);
                        });
                    } else {
                        container.innerHTML = '<li class="sidebar-recommend-empty">暂无相关推荐</li>';
                    }
                }).catch(function(err) {
                    console.error('加载相关推荐失败:', err);
                    document.getElementById('relatedList').innerHTML = '<li class="sidebar-recommend-empty">加载失败</li>';
                });
            }

            // ========== 精选内容（右侧边栏） ==========
            function loadFeatured() {
                var url = '/content/api/v1/article/' + articleId + '/featured?cursor=&size=5';
                apiGet(url).then(function(res) {
                    var container = document.getElementById('featuredList');
                    if (res && res.code === 200 && res.data) {
                        var list = res.data.list || [];
                        if (list.length === 0) {
                            container.innerHTML = '<li class="sidebar-recommend-empty">暂无精选内容</li>';
                            return;
                        }
                        container.innerHTML = '';
                        list.forEach(function(item) {
                            var li = document.createElement('li');
                            li.className = 'sidebar-recommend-item';
                            li.innerHTML = '<a href="/article/' + item.articleId + '" class="sidebar-recommend-link" target="_blank">' +
                                '<span class="sidebar-recommend-link-title">' + escapeHtml(item.title) + '</span>' +
                                '<span class="sidebar-recommend-link-meta">' + escapeHtml(item.authorName || '') + ' · ' + formatTime(item.publishTime) + '</span>' +
                                '</a>';
                            container.appendChild(li);
                        });
                    } else {
                        container.innerHTML = '<li class="sidebar-recommend-empty">暂无精选内容</li>';
                    }
                }).catch(function(err) {
                    console.error('加载精选内容失败:', err);
                    document.getElementById('featuredList').innerHTML = '<li class="sidebar-recommend-empty">加载失败</li>';
                });
            }

            // ========== 初始化加载 ==========
            loadArticleDetail();
            loadColumn();
            loadComments(false);
            loadRecommend(false);
            loadRelated();
            loadFeatured();
        })();
    </script>
</body>
</html>