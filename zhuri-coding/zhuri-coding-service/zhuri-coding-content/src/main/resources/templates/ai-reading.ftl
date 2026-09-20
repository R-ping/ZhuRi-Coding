<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AI 速读广场 - AI 摘要精选文章</title>
    <meta name="description" content="聚合社区带 AI 摘要的精选技术文章，先看摘要再读原文，快速找到你关心的内容。">
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; background: #f7f8fa; color: #252933; }
        .wrap { max-width: 760px; margin: 0 auto; padding: 32px 20px 60px; }
        .page-title { font-size: 22px; font-weight: 600; margin-bottom: 6px; }
        .page-sub { font-size: 13px; color: #86909c; margin-bottom: 24px; }
        .card { display: block; background: #fff; border: 1px solid #eef0f2; border-radius: 8px; padding: 18px 20px; margin-bottom: 14px; text-decoration: none; color: inherit; transition: box-shadow .2s; }
        .card:hover { box-shadow: 0 4px 16px rgba(0, 0, 0, .06); }
        .card-title { font-size: 17px; font-weight: 600; color: #1d2129; line-height: 1.5; margin-bottom: 8px; }
        .card-ai { font-size: 12px; color: #4f7cff; border: 1px solid #4f7cff; border-radius: 4px; padding: 0 6px; margin-left: 8px; vertical-align: middle; }
        .card-summary { font-size: 14px; color: #4e5969; line-height: 1.7; margin-bottom: 10px; }
        .card-meta { font-size: 12px; color: #86909c; }
        .empty { text-align: center; color: #86909c; padding: 60px 0; }
        .empty a { color: #4f7cff; }
        body.dark { background: #141416; color: #e6e8eb; }
        body.dark .card { background: #1c1e22; border-color: #2d333b; }
        body.dark .card-title { color: #e6e8eb; }
        body.dark .card-summary { color: #b6bcc7; }
    </style>
</head>
<body>
<div class="wrap">
    <h1 class="page-title">AI 速读广场</h1>
    <div class="page-sub">社区优质文章 · AI 摘要生成，仅供参考，点击进入原文阅读</div>

    <#if articles?? && articles?size gt 0>
        <#list articles as a>
            <a class="card" href="/content/article/${a.id!''}">
                <div class="card-title">${a.title!''}<span class="card-ai">AI 摘要</span></div>
                <div class="card-summary">${a.summary!''}</div>
                <div class="card-meta">@${a.authorName!''} · ${a.publishTime?string('yyyy-MM-dd')}</div>
            </a>
        </#list>
    <#else>
        <div class="empty">暂无内容，先去创作中心发布一篇带 AI 摘要的文章吧。</div>
    </#if>
</div>
</body>
</html>
