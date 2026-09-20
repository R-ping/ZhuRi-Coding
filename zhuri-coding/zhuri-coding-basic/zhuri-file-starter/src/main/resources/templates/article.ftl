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
        }
        @media (max-width: 640px) {
            .main-wrapper { padding: 56px 12px 24px; }
            .content-card { padding: 16px; }
            .article-title { font-size: 22px; }
            .action-bar { flex-wrap: wrap; }
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

                <div class="action-bar">
                    <button class="action-btn<#if relation?? && relation.islike?? && relation.islike> active</#if>" id="likeBtn">
                        <svg viewBox="0 0 24 24"><path d="M2 20h2v-9H2v9zm20-9c0-1.1-.9-2-2-2h-3.17c-.53-1.4-1.53-2.56-2.83-3.09V4c0-1.66-1.34-3-3-3S8 2.34 8 4v1.91C5.94 6.56 4.5 8.69 4.5 11v6.17l-1.83 1.83L4.17 20h12.5c1.66 0 3.08-1.03 3.65-2.5H22v-6.5z"/></svg>
                        <span>点赞</span>
                    </button>
                    <button class="action-btn<#if relation?? && relation.iscollection?? && relation.iscollection> active</#if>" id="collectBtn">
                        <svg viewBox="0 0 24 24"><path d="M17 3H7c-1.1 0-2 .9-2 2v16l7-3 7 3V5c0-1.1-.9-2-2-2z"/></svg>
                        <span>收藏</span>
                    </button>
                </div>
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

            // 平滑滚动
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

            // 互动按钮占位：可在这里接入后端接口
            function toggleBtn(id, activeText, inactiveText) {
                var btn = document.getElementById(id);
                if (!btn) return;
                btn.addEventListener('click', function() {
                    var isActive = btn.classList.toggle('active');
                    var span = btn.querySelector('span');
                    if (span) span.textContent = isActive ? activeText : inactiveText;
                });
            }
            toggleBtn('likeBtn', '已赞', '点赞');
            toggleBtn('collectBtn', '已收藏', '收藏');

            var followBtn = document.getElementById('followBtn');
            if (followBtn) {
                followBtn.addEventListener('click', function() {
                    var isActive = followBtn.classList.toggle('active');
                    followBtn.textContent = isActive ? '已关注' : '+ 关注';
                });
            }

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

            var authorFollowBtn = document.getElementById('authorFollowBtn');
            if (authorFollowBtn) {
                authorFollowBtn.addEventListener('click', function() {
                    var isActive = authorFollowBtn.classList.toggle('active');
                    authorFollowBtn.textContent = isActive ? '已关注' : '+ 关注';
                });
            }

            function toggleSideBtn(id) {
                var btn = document.getElementById(id);
                if (!btn) return;
                btn.addEventListener('click', function() {
                    btn.classList.toggle('active');
                });
            }
            toggleSideBtn('sideLikeBtn');
            toggleSideBtn('sideCollectBtn');
        })();
    </script>
</body>
</html>