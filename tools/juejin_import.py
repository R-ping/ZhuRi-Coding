#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
掘金文章导入工具（逐日 Coding 内容社区）

从稀土掘金抓取文章（分类列表 + 详情正文），把「作者头像 / 文章封面 / 正文内图片」
转存到自有阿里云 OSS 并替换链接，然后写入本地库（作者用户 + 文章 + 正文 + 文章配置）。

用法:
    python tools/juejin_import.py fetch     # 抓取文章 -> data/juejin_articles.json
    python tools/juejin_import.py images    # 图片转存 OSS 并替换链接
    python tools/juejin_import.py db        # 入库（作者用户 + 文章）
    python tools/juejin_import.py all       # 依次执行 fetch -> images -> db

可选参数:
    --per-category N   每个分类抓取篇数（默认 5）
    --sort-type N      200=热门（默认）/ 300=最新

幂等说明:
    data/juejin_import_map.json 记录「掘金 id -> 本地 id」映射，
    重复运行不会重复建用户 / 重复插文章。
"""
import argparse
import json
import os
import random
import re
import subprocess
import sys
import time
import uuid
from datetime import datetime
from pathlib import Path

# 本机系统代理（Clash 等）会拦截并破坏较大的上传请求（表现为 OSS 502 / 读超时），
# 本脚本仅访问掘金与自有 OSS，直连即可，因此对程序内所有请求禁用代理。
os.environ.setdefault("NO_PROXY", "*")
os.environ.setdefault("no_proxy", "*")

import pymysql
import requests

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

# ---------------------------------------------------------------------------
# 路径与外部依赖
# ---------------------------------------------------------------------------
ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT / "data"
ARTICLES_FILE = DATA_DIR / "juejin_articles.json"
MAP_FILE = DATA_DIR / "juejin_import_map.json"
IMG_CACHE_FILE = DATA_DIR / "juejin_image_cache.json"
TMP_DIR = DATA_DIR / "juejin_tmp"

NODE_BIN = r"C:\Users\hudong\.workbuddy\binaries\node\versions\22.22.2-2\node.exe"
PARSE_NUXT = ROOT / "tools" / "parse_nuxt.cjs"

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

# 掘金分类 cate_id -> (ap_channel.id, ap_channel.name)
CATEGORIES = [
    ("6809637769959178254", 1, "后端"),
    ("6809637767543259144", 2, "前端"),
    ("6809635626879549454", 3, "Android"),
    ("6809635626661445640", 4, "iOS"),
    ("6809637773935378440", 5, "人工智能"),
    ("6809637771511070734", 6, "开发工具"),
    ("6809637776263217160", 7, "代码人生"),
    ("6809637772874219534", 8, "阅读"),
]
CATE_TO_CHANNEL = {c[0]: (c[1], c[2]) for c in CATEGORIES}

# OSS 配置（与 content 服务 application.yml 保持一致）
OSS_ENDPOINT = "oss-cn-beijing.aliyuncs.com"
OSS_BUCKET = "zhuri-leadnews"
OSS_HOST = "https://zhuri-leadnews.oss-cn-beijing.aliyuncs.com"
OSS_PREFIX = "material/juejin"

# 数据库配置（与 .env 一致）
DB_HOST, DB_PORT, DB_USER, DB_PASS = "127.0.0.1", 3306, "root", "123456"
DB_USER_NAME, DB_ARTICLE_NAME = "leadnews_user", "leadnews_article"

# 掘金作者 user_id 超出 ap_user.id（INT UNSIGNED）范围，改用固定基数 + 序号
USER_ID_BASE = 900000000

# 正文中的图片存在两种形态：markdown 语法 ![]() 与 HTML <img src>
RE_MD_IMG = re.compile(r"!\[([^\]]*)\]\((https?://[^)\s]+)\)")
RE_HTML_IMG = re.compile(r"""<img[^>]*src=["'](https?://[^"'\s]+)["']""")

REQ_TIMEOUT = 30

# 全局会话：复用连接与游客 cookie，可显著降低掘金风控概率
SESSION = requests.Session()
SESSION.headers.update({
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
})


# ---------------------------------------------------------------------------
# 通用工具
# ---------------------------------------------------------------------------
def log(msg):
    print(msg, flush=True)


def warmup_session():
    """访问掘金首页建立游客会话，降低后续风控概率。"""
    try:
        SESSION.get("https://juejin.cn/", timeout=REQ_TIMEOUT)
        log("[会话] 已建立掘金游客会话")
    except Exception as e:  # noqa: BLE001
        log(f"[会话] 预热失败（忽略）: {e}")


def load_json(path, default):
    if path.exists():
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    return default


def save_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)


def http_headers(referer=None):
    h = {"User-Agent": UA, "Accept": "*/*"}
    if referer:
        h["Referer"] = referer
    return h


def post_json(url, payload, referer=None, retries=3):
    """POST JSON，带简单重试。"""
    last = None
    for i in range(retries):
        try:
            r = SESSION.post(url, json=payload, headers=http_headers(referer), timeout=REQ_TIMEOUT)
            if r.status_code == 200:
                return r.json()
            last = f"HTTP {r.status_code}"
        except Exception as e:  # noqa: BLE001
            last = str(e)
        time.sleep(1.5 * (i + 1))
    log(f"  [!] 请求失败 {url}: {last}")
    return None


def get_text(url, referer=None, retries=4, encoding=None):
    """
    抓取页面文本。

    掘金对高频访问会返回阿里云盾风控页（约 2.5KB、不含 __NUXT__），
    检测到后指数退避重试。
    """
    last = None
    for i in range(retries):
        try:
            r = SESSION.get(url, headers=http_headers(referer), timeout=REQ_TIMEOUT)
            if r.status_code == 200:
                if encoding:
                    r.encoding = encoding
                html = r.text
                if "window.__NUXT__" in html:
                    return html
                last = f"风控页({len(html)}B)"
                wait = 20 * (i + 1)
                log(f"  [!] 触发风控，等待 {wait}s 后重试")
                time.sleep(wait)
                continue
            last = f"HTTP {r.status_code}"
        except Exception as e:  # noqa: BLE001
            last = str(e)
        time.sleep(2.0 * (i + 1))
    log(f"  [!] 拉取失败 {url}: {last}")
    return None


def get_bytes(url, referer=None, retries=3):
    last = None
    for i in range(retries):
        try:
            r = SESSION.get(url, headers=http_headers(referer), timeout=REQ_TIMEOUT)
            if r.status_code == 200 and r.content:
                return r.content, r.headers.get("Content-Type", "")
            last = f"HTTP {r.status_code}"
        except Exception as e:  # noqa: BLE001
            last = str(e)
        time.sleep(1.0 * (i + 1))
    log(f"  [!] 图片下载失败 {url[:80]}: {last}")
    return None, ""


def truncate(s, n):
    """按字符截断（varchar 长度按字符计）。"""
    if s is None:
        return None
    s = str(s).strip()
    return s if len(s) <= n else s[:n]


def strip_markdown(md, limit=None):
    """提取 markdown 纯文本摘要。"""
    if not md:
        return ""
    t = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", md)          # 图片
    t = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", t)        # 链接
    t = re.sub(r"```[\s\S]*?```", " ", t)                 # 代码块
    t = re.sub(r"[#>*`_~\-]+", " ", t)                    # 标记
    t = re.sub(r"\s+", " ", t).strip()
    return t[:limit] if limit else t


# ---------------------------------------------------------------------------
# 阶段 1：抓取
# ---------------------------------------------------------------------------
def find_article_entry(obj):
    """
    在 __NUXT__ 树中递归定位文章详情节点。

    掘金文章页结构随文章类型变化（普通文章在 state.view.entryPublic，
    专栏文章在 state.view.column），统一以「含 article_info.mark_content」为准。
    """
    stack = [obj]
    while stack:
        cur = stack.pop()
        if isinstance(cur, dict):
            ai = cur.get("article_info")
            if isinstance(ai, dict) and "mark_content" in ai:
                return cur
            stack.extend(cur.values())
        elif isinstance(cur, list):
            stack.extend(cur)
    return None


def parse_nuxt_html(html_path, out_json):
    """调用 node 解析器把 window.__NUXT__ 落地为 JSON。"""
    try:
        p = subprocess.run([NODE_BIN, str(PARSE_NUXT), str(html_path), str(out_json)],
                           capture_output=True, text=True, timeout=60)
        if p.returncode != 0:
            log(f"  [!] __NUXT__ 解析失败: {p.stderr.strip()[:120]}")
            return None
        with open(out_json, encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:  # noqa: BLE001
        log(f"  [!] __NUXT__ 解析异常: {e}")
        return None


def fetch_category(cate_id, limit, sort_type):
    """拉取某分类下的文章列表（仅元数据，正文需另抓详情页）。"""
    url = "https://api.juejin.cn/recommend_api/v1/article/recommend_cate_feed"
    payload = {"cate_id": cate_id, "cursor": "0", "id_type": 2,
               "sort_type": sort_type, "limit": limit}
    d = post_json(url, payload, referer="https://juejin.cn/")
    if not d or d.get("err_no") != 0:
        log(f"  [!] 分类 {cate_id} 列表拉取失败: {d.get('err_msg') if d else 'no response'}")
        return []
    items = []
    for it in d.get("data") or []:
        ai = it.get("article_info") or {}
        au = it.get("author_user_info") or {}
        if not ai.get("article_id"):
            continue
        items.append({
            "article_id": str(ai.get("article_id")),
            "title": ai.get("title") or "",
            "brief_content": ai.get("brief_content") or "",
            "cover_image": ai.get("cover_image") or "",
            "category_id": str(ai.get("category_id") or cate_id),
            "tag_names": [t.get("tag_name") for t in (it.get("tags") or []) if t.get("tag_name")],
            "view_count": ai.get("view_count") or 0,
            "digg_count": ai.get("digg_count") or 0,
            "collect_count": ai.get("collect_count") or 0,
            "comment_count": ai.get("comment_count") or 0,
            "ctime": ai.get("ctime") or "",
            "author": {
                "user_id": str(au.get("user_id") or ""),
                "user_name": au.get("user_name") or "",
                "avatar_large": au.get("avatar_large") or "",
                "job_title": au.get("job_title") or "",
                "description": au.get("description") or "",
                "level": au.get("level") or 0,
                "power": au.get("power") or 0,
            },
        })
    return items


def fetch_article_detail(item):
    """抓取文章详情页并解析出完整正文。"""
    aid = item["article_id"]
    url = f"https://juejin.cn/post/{aid}"
    html = get_text(url, referer="https://juejin.cn/")
    if not html:
        return None
    TMP_DIR.mkdir(parents=True, exist_ok=True)
    html_path = TMP_DIR / f"{aid}.html"
    html_path.write_text(html, encoding="utf-8")
    nuxt = parse_nuxt_html(html_path, TMP_DIR / f"{aid}.json")
    if not nuxt:
        return None
    entry = find_article_entry(nuxt)
    if not entry:
        log(f"  [!] 未定位文章节点: {aid}")
        return None
    ai = entry.get("article_info") or {}
    au = entry.get("author_user_info") or {}
    mark = ai.get("mark_content") or ""
    if not mark.strip():
        log(f"  [!] 正文为空: {aid}")
        return None

    # 以详情页为准补全元数据
    item["title"] = ai.get("title") or item["title"]
    item["brief_content"] = ai.get("brief_content") or item["brief_content"]
    item["cover_image"] = ai.get("cover_image") or item["cover_image"]
    item["mark_content"] = mark
    item["category_id"] = str(ai.get("category_id") or item["category_id"])
    item["view_count"] = ai.get("view_count") or item["view_count"]
    item["digg_count"] = ai.get("digg_count") or item["digg_count"]
    item["collect_count"] = ai.get("collect_count") or item["collect_count"]
    item["comment_count"] = ai.get("comment_count") or item["comment_count"]
    item["ctime"] = ai.get("ctime") or item["ctime"]
    tags = [t.get("tag_name") for t in (entry.get("tags") or []) if t.get("tag_name")]
    if tags:
        item["tag_names"] = tags
    if au:
        item["author"] = {
            "user_id": str(au.get("user_id") or item["author"].get("user_id") or ""),
            "user_name": au.get("user_name") or item["author"].get("user_name") or "",
            "avatar_large": au.get("avatar_large") or item["author"].get("avatar_large") or "",
            "job_title": au.get("job_title") or "",
            "description": au.get("description") or "",
            "level": au.get("level") or 0,
            "power": au.get("power") or 0,
        }
    return item


def stage_fetch(per_category, sort_type):
    log("=" * 70)
    log(f"阶段 1/3 抓取：8 个分类 × 目标 {per_category} 篇（sort_type={sort_type}）")
    log("=" * 70)
    seen, ok = set(), []
    for cate_id, ch_id, ch_name in CATEGORIES:
        log(f"\n[{ch_name}] cate_id={cate_id}")
        # 多取候选：部分文章未登录时掘金不返回正文，需按「成功数」补足
        candidates = fetch_category(cate_id, per_category * 3, sort_type)
        log(f"  候选 {len(candidates)} 篇，目标成功 {per_category} 篇")
        got_cnt = 0
        for it in candidates:
            if got_cnt >= per_category:
                break
            aid = it["article_id"]
            if aid in seen:
                continue
            seen.add(aid)
            log(f"  ({got_cnt + 1}/{per_category}) {aid} {truncate(it['title'], 30)}")
            got = fetch_article_detail(it)
            if got:
                ok.append(got)
                got_cnt += 1
            time.sleep(random.uniform(3.0, 6.0))  # 放慢节奏，规避掘金风控
        if got_cnt < per_category:
            log(f"  [!] 仅成功 {got_cnt}/{per_category} 篇（候选不足）")
        time.sleep(random.uniform(2.0, 4.0))  # 分类之间留间隔

    save_json(ARTICLES_FILE, ok)
    log(f"\n[完成] 成功抓取 {len(ok)} 篇 -> {ARTICLES_FILE}")


# ---------------------------------------------------------------------------
# 阶段 2：图片转存 OSS
# ---------------------------------------------------------------------------
def get_oss_credentials():
    """优先读系统环境变量，其次读 Windows 注册表（本机凭证在 Machine 级）。"""
    import os
    key, secret = os.environ.get("ALIBABA_RAM_ACCESS_KEY"), os.environ.get("ALIBABA_RAM_ACCESS_SECRET")
    if key and secret:
        return key, secret
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,
                            r"SYSTEM\CurrentControlSet\Control\Session Manager\Environment") as k:
            return (winreg.QueryValueEx(k, "ALIBABA_RAM_ACCESS_KEY")[0],
                    winreg.QueryValueEx(k, "ALIBABA_RAM_ACCESS_SECRET")[0])
    except Exception as e:  # noqa: BLE001
        raise RuntimeError(f"未找到 OSS 凭证: {e}")


EXT_BY_TYPE = {"image/jpeg": ".jpg", "image/jpg": ".jpg", "image/png": ".png",
               "image/webp": ".webp", "image/gif": ".gif", "image/svg+xml": ".svg"}


def guess_ext(url, content_type):
    m = re.search(r"\.(jpg|jpeg|png|webp|gif|svg)(?:\?|$)", url, re.I)
    if m:
        return "." + m.group(1).lower().replace("jpeg", "jpg")
    return EXT_BY_TYPE.get((content_type or "").split(";")[0].strip().lower(), ".jpg")


def make_object_key(kind, ext):
    day = datetime.now().strftime("%Y/%m/%d")
    return f"{OSS_PREFIX}/{kind}/{day}/{uuid.uuid4().hex}{ext}"


def stage_images():
    """
    并发下载掘金图片并转存自有 OSS，随后把文章中的链接替换为 OSS 地址。

    OSS 上传偶发 502（网络抖动），单张失败会重试；串行太慢，故用线程池并发处理。
    """
    import oss2
    from concurrent.futures import ThreadPoolExecutor, as_completed

    log("=" * 70)
    log("阶段 2/3 图片转存：下载掘金图片 -> 上传自有 OSS -> 替换链接")
    log("=" * 70)
    articles = load_json(ARTICLES_FILE, [])
    if not articles:
        log("[!] 无文章数据，请先执行 fetch")
        return
    cache = load_json(IMG_CACHE_FILE, {})
    key, secret = get_oss_credentials()
    bucket = oss2.Bucket(oss2.Auth(key, secret), OSS_ENDPOINT, OSS_BUCKET)

    # 1) 收集所有待转存图片 URL（同一 URL 只处理一次）
    jobs = {}
    for art in articles:
        au = art.get("author") or {}
        if au.get("avatar_large"):
            jobs.setdefault(au["avatar_large"], "avatar")
        if art.get("cover_image"):
            jobs.setdefault(art["cover_image"], "cover")
        for m in RE_MD_IMG.finditer(art.get("mark_content") or ""):
            jobs.setdefault(m.group(2), "content")
        for m in RE_HTML_IMG.finditer(art.get("mark_content") or ""):
            jobs.setdefault(m.group(1), "content")
    pending = {u: k for u, k in jobs.items()
               if u not in cache and not u.startswith(OSS_HOST)}
    log(f"  图片总计 {len(jobs)} 张，待转存 {len(pending)} 张，缓存命中 {len(jobs) - len(pending)} 张")

    def work(item):
        """下载单张图片并上传 OSS（最多重试 3 次），返回 (原url, OSS地址或None)。"""
        url, kind = item
        data, ctype = get_bytes(url, referer="https://juejin.cn/")
        if not data:
            return url, None
        obj_key = make_object_key(kind, guess_ext(url, ctype))
        for attempt in range(3):
            try:
                bucket.put_object(obj_key, data)
                return url, f"{OSS_HOST}/{obj_key}"
            except Exception as e:  # noqa: BLE001
                if attempt == 2:
                    log(f"  [!] 上传失败(3次) {url[:60]}: {str(e)[:70]}")
                    return url, None
                time.sleep(1.5 * (attempt + 1))
        return url, None

    ok_cnt = 0
    with ThreadPoolExecutor(max_workers=6) as ex:
        futures = [ex.submit(work, it) for it in pending.items()]
        for idx, f in enumerate(as_completed(futures), 1):
            url, oss_url = f.result()
            if oss_url:
                cache[url] = oss_url
                ok_cnt += 1
            if idx % 20 == 0:
                save_json(IMG_CACHE_FILE, cache)
                log(f"  进度 {idx}/{len(pending)}（成功 {ok_cnt}）")
    save_json(IMG_CACHE_FILE, cache)
    log(f"  转存完成：成功 {ok_cnt}/{len(pending)}")

    # 2) 替换文章中的图片链接
    def repl(url):
        return cache.get(url, url) if url else url

    for art in articles:
        au = art.get("author") or {}
        au["avatar_large"] = repl(au.get("avatar_large"))
        mark = art.get("mark_content") or ""
        content_urls = []
        for m in RE_MD_IMG.finditer(mark):
            u = m.group(2)
            oss_url = cache.get(u, u)
            content_urls.append(oss_url)
            if oss_url != u:
                mark = mark.replace(f"]({u})", f"]({oss_url})")
        # HTML <img src> 形态（部分掘金文章用富文本标签插图）
        for m in RE_HTML_IMG.finditer(mark):
            u = m.group(1)
            oss_url = cache.get(u, u)
            if oss_url not in content_urls:
                content_urls.append(oss_url)
            if oss_url != u:
                mark = mark.replace(u, oss_url)
        art["mark_content"] = mark
        cover = repl(art.get("cover_image"))
        if not cover and content_urls:
            cover = content_urls[0]
        art["cover_image"] = cover
        art["content_images"] = content_urls

    save_json(ARTICLES_FILE, articles)
    log(f"\n[完成] 转存 {ok_cnt} 张，缓存 {len(cache)} 条 -> {ARTICLES_FILE}")


# ---------------------------------------------------------------------------
# 阶段 3：入库
# ---------------------------------------------------------------------------
def db_conn(database):
    return pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASS,
                           database=database, charset="utf8mb4", autocommit=False)


def ensure_user(user_cache, au, bucket_avatar, conn_user):
    """
    作者用户不存在则入库（ap_user + user_profile），返回本地 user_id。

    user_cache: {juejin_user_id: local_user_id}
    """
    jid = au.get("user_id")
    if not jid:
        return None
    if jid in user_cache:
        return user_cache[jid]

    with conn_user.cursor() as cur:
        # 分配一个未被占用的本地 id（固定基数 + 递增）
        cur.execute("SELECT MAX(id) FROM ap_user WHERE id >= %s", (USER_ID_BASE,))
        row = cur.fetchone()
        local_id = (row[0] or (USER_ID_BASE - 1)) + 1
        nickname = truncate(au.get("user_name") or f"掘金用户{jid[-6:]}", 20)
        now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        cur.execute(
            """INSERT INTO ap_user (id, nickname, password, phone, email, image, sex,
                                    is_certification, is_identity_authentication, status, flag, created_time)
               VALUES (%s,%s,NULL,NULL,NULL,%s,2,0,0,1,1,%s)""",
            (local_id, nickname, bucket_avatar, now))
        cur.execute(
            """INSERT INTO user_profile (user_id, username, avatar_url, position, bio, level, update_time)
               VALUES (%s,%s,%s,%s,%s,%s,%s)
               ON DUPLICATE KEY UPDATE avatar_url=VALUES(avatar_url), update_time=VALUES(update_time)""",
            (local_id, nickname, bucket_avatar,
             truncate(au.get("job_title"), 50), truncate(au.get("description"), 100),
             truncate(str(au.get("level") or ""), 20), now))
    user_cache[jid] = local_id
    return local_id


def stage_db():
    log("=" * 70)
    log("阶段 3/3 入库：作者用户 + 文章 + 正文 + 配置")
    log("=" * 70)
    articles = load_json(ARTICLES_FILE, [])
    if not articles:
        log("[!] 无文章数据，请先执行 fetch / images")
        return
    mapping = load_json(MAP_FILE, {"users": {}, "articles": {}})
    user_cache, article_map = mapping.get("users", {}), mapping.get("articles", {})

    conn_user, conn_art = db_conn(DB_USER_NAME), db_conn(DB_ARTICLE_NAME)
    n_user, n_art, n_skip = 0, 0, 0
    try:
        for i, art in enumerate(articles, 1):
            aid = art["article_id"]
            au = art.get("author", {})
            log(f"\n({i}/{len(articles)}) {truncate(art['title'], 36)}")
            if aid in article_map:
                # 已入库：正文/封面可能因图片重新转存而变化，做一次幂等更新
                local_id = article_map[aid]
                with conn_art.cursor() as cur:
                    cur.execute("UPDATE ap_article_content SET content=%s WHERE article_id=%s",
                                (art.get("mark_content") or "", local_id))
                    cur.execute(
                        "UPDATE ap_article SET cover_image=%s, cont_pics=%s, author_image=%s WHERE id=%s",
                        (art.get("cover_image") or "",
                         json.dumps(art.get("content_images") or [], ensure_ascii=False),
                         au.get("avatar_large"), local_id))
                n_skip += 1
                log(f"  已入库，更新正文/封面（本地 article_id={local_id}）")
                continue

            before = len(user_cache)
            local_uid = ensure_user(user_cache, au, au.get("avatar_large"), conn_user)
            if len(user_cache) > before:
                n_user += 1
                log(f"  新建作者用户: {au.get('user_name')} -> id={local_uid}")
            if not local_uid:
                log("  [!] 缺少作者信息，跳过")
                continue

            ch_id, ch_name = CATE_TO_CHANNEL.get(art.get("category_id"), (None, None))
            ts = art.get("ctime")
            dt = datetime.fromtimestamp(int(ts)).strftime("%Y-%m-%d %H:%M:%S") if ts else \
                datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            summary = truncate(art.get("brief_content") or strip_markdown(art.get("mark_content"), 200), 500)
            cover = art.get("cover_image") or ""
            cont_pics = art.get("content_images") or []
            tags = art.get("tag_names") or []

            with conn_art.cursor() as cur:
                cur.execute(
                    """INSERT INTO ap_article
                       (title, summary, author_id, author_name, channel_id, channel_name, layout, flag,
                        cover_image, cont_pics, likes, tags, collection, comment, comment_open,
                        tip_count, tip_amount, views, score, status, sync_status, origin,
                        created_time, publish_time, is_deleted, author_image)
                       VALUES (%s,%s,%s,%s,%s,%s,%s,0,%s,%s,%s,%s,%s,%s,1,0,0,%s,NULL,9,0,0,%s,%s,0,%s)""",
                    (truncate(art.get("title"), 50), summary, local_uid,
                     truncate(au.get("user_name"), 20), ch_id, ch_name,
                     2 if cover else 1, cover,
                     json.dumps(cont_pics, ensure_ascii=False), art.get("digg_count") or 0,
                     json.dumps(tags, ensure_ascii=False), art.get("collect_count") or 0,
                     art.get("comment_count") or 0, art.get("view_count") or 0,
                     dt, dt, au.get("avatar_large")))
                new_article_id = cur.lastrowid
                cur.execute("INSERT INTO ap_article_content (article_id, content) VALUES (%s,%s)",
                            (new_article_id, art.get("mark_content") or ""))
                cur.execute(
                    """INSERT INTO ap_article_config (article_id, is_comment, is_forward, is_down, is_delete, is_recommend)
                       VALUES (%s,1,1,0,0,1)""", (new_article_id,))
            article_map[aid] = new_article_id
            n_art += 1
            log(f"  入库成功: 本地 article_id={new_article_id} 频道={ch_name} 标签={tags}")

        conn_user.commit()
        conn_art.commit()
        mapping["users"], mapping["articles"] = user_cache, article_map
        save_json(MAP_FILE, mapping)
    except Exception as e:  # noqa: BLE001
        conn_user.rollback()
        conn_art.rollback()
        log(f"\n[!] 入库失败已回滚: {e}")
        raise
    finally:
        conn_user.close()
        conn_art.close()
    log(f"\n[完成] 新增用户 {n_user} 个，新增文章 {n_art} 篇，跳过 {n_skip} 篇")


# ---------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description="掘金文章导入工具")
    ap.add_argument("stage", choices=["fetch", "images", "db", "all"])
    ap.add_argument("--per-category", type=int, default=5)
    ap.add_argument("--sort-type", type=int, default=200)
    args = ap.parse_args()

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    if args.stage in ("fetch", "all"):
        warmup_session()
        stage_fetch(args.per_category, args.sort_type)
    if args.stage in ("images", "all"):
        stage_images()
    if args.stage in ("db", "all"):
        stage_db()


if __name__ == "__main__":
    main()
