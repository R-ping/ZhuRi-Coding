<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta http-equiv="X-UA-Compatible" content="IE=edge">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0, viewport-fit=cover">
    <title>页面不存在 - 逐日Coding</title>
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
        a { text-decoration: none; }

        /* 顶栏（与文章详情页保持一致） */
        .topbar {
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
        .logo-text {
            font-size: 22px;
            font-weight: 700;
            color: #1e80ff;
            white-space: nowrap;
        }
        .logo-text em { font-style: normal; }
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

        /* 错误主体 */
        .error-wrapper {
            display: flex;
            justify-content: center;
            align-items: center;
            min-height: 100vh;
            padding: 100px 20px 40px;
        }
        .error-card {
            background: #ffffff;
            border-radius: 4px;
            padding: 64px 48px;
            box-shadow: 0 1px 2px rgba(0,0,0,0.05);
            text-align: center;
            max-width: 520px;
            width: 100%;
        }
        .error-code {
            font-size: 72px;
            font-weight: 700;
            color: #1e80ff;
            line-height: 1;
            margin-bottom: 16px;
        }
        .error-title {
            font-size: 20px;
            font-weight: 600;
            color: #252933;
            margin-bottom: 8px;
        }
        .error-desc {
            font-size: 14px;
            color: #8a919f;
            margin-bottom: 32px;
        }
        .error-actions {
            display: flex;
            justify-content: center;
            gap: 16px;
        }
        .btn {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            height: 38px;
            padding: 0 24px;
            border-radius: 4px;
            font-size: 14px;
            cursor: pointer;
            transition: all 0.2s;
            user-select: none;
        }
        .btn-primary {
            background-color: #1e80ff;
            color: #ffffff;
            border: 1px solid #1e80ff;
        }
        .btn-primary:hover { background-color: #1171ee; }
        .btn-plain {
            background-color: #ffffff;
            color: #515767;
            border: 1px solid #e4e6eb;
        }
        .btn-plain:hover { color: #1e80ff; border-color: #1e80ff; }
    </style>
</head>
<body>
    <!-- 顶栏 -->
    <header class="topbar">
        <div class="topbar-inner">
            <div class="topbar-left">
                <a class="brand-link" href="/home">
                    <span class="logo-text">逐日<em style="color:#1e80ff;">Coding</em></span>
                </a>
            </div>
            <nav class="main-nav">
                <a class="nav-link" href="/home">首页</a>
                <a class="nav-link" href="/pins">沸点</a>
                <a class="nav-link" href="/course">课程</a>
            </nav>
        </div>
    </header>

    <!-- 404 提示 -->
    <div class="error-wrapper">
        <div class="error-card">
            <div class="error-code">404</div>
            <div class="error-title">文章不存在或已被删除</div>
            <div class="error-desc">你访问的内容可能已被删除、下架，或链接有误</div>
            <div class="error-actions">
                <a class="btn btn-primary" href="/home">返回首页</a>
                <a class="btn btn-plain" href="javascript:history.back()">返回上一页</a>
            </div>
        </div>
    </div>
</body>
</html>
