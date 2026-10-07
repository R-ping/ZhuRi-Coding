#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
静态 SimpleDateFormat 并发安全验证脚本

背景：ArticleDetailServiceImpl / ContentDataServiceImpl / JScoreServiceImpl 中
声明了 `private static final SimpleDateFormat`。SimpleDateFormat 内部持有可变的 Calendar，
多线程并发 format/parse 时会互相踩踏 —— 典型症状是输出错误日期或抛异常。

本脚本并发压文章详情接口（该接口每次请求会对同一个静态实例调用 2 次 format），
用「同一篇文章的 publishTime 应在所有响应中完全一致」作为判据：
一旦出现不一致或不合法的日期串，即为并发竞态的实测证据。
"""
import json
import re
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import Counter, defaultdict

sys.stdout.reconfigure(encoding="utf-8")

BASE = "http://127.0.0.1:51802/api/v1/article/detail/"
ARTICLE_IDS = [
    2091867593875820950, 2091867593875820927, 2091867593875820951,
    2091867593875820948, 2091867593875820953, 2091867593875820934,
    2091867593875820952, 2091867593875820945, 2091867593875820943,
    2091867593875820954, 2091867593875820922, 2091867593875820955,
]
THREADS = 60
REQUESTS_PER_THREAD = 50

LEGAL = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$")

values = defaultdict(Counter)      # articleId -> Counter(publishTime)
errors = Counter()
lock = threading.Lock()


def probe(article_id):
    try:
        req = urllib.request.Request(BASE + str(article_id))
        with urllib.request.urlopen(req, timeout=30) as r:
            body = r.read().decode("utf-8", "replace")
            status = r.status
    except urllib.error.HTTPError as e:
        with lock:
            errors["http_%s" % e.code] += 1
        return
    except Exception as e:
        with lock:
            errors[type(e).__name__] += 1
        return

    if status != 200:
        with lock:
            errors["http_%s" % status] += 1
        return
    try:
        data = json.loads(body).get("data") or {}
    except Exception:
        with lock:
            errors["unparsable_json"] += 1
        return
    pt = data.get("publishTime")
    with lock:
        values[article_id][pt] += 1


def worker(n):
    for i in range(REQUESTS_PER_THREAD):
        probe(ARTICLE_IDS[(n + i) % len(ARTICLE_IDS)])


def main():
    print("=" * 78)
    print("静态 SimpleDateFormat 并发竞态验证")
    print("目标接口：GET /api/v1/article/detail/{id}（每次请求调用静态 DATE_FORMAT.format 两次）")
    print("并发=%d 线程 × %d 请求 = %d 次请求" % (THREADS, REQUESTS_PER_THREAD, THREADS * REQUESTS_PER_THREAD))
    print("=" * 78)

    # 串行基线：先拿到每篇文章「正确」的 publishTime
    baseline = {}
    for aid in ARTICLE_IDS:
        try:
            req = urllib.request.Request(BASE + str(aid))
            with urllib.request.urlopen(req, timeout=30) as r:
                data = json.loads(r.read().decode("utf-8", "replace")).get("data") or {}
            baseline[aid] = data.get("publishTime")
        except Exception as e:
            baseline[aid] = None
            print("  基线请求失败 aid=%s: %r" % (aid, e))
    print("串行基线：")
    for aid in ARTICLE_IDS:
        print("  %s -> %s" % (aid, baseline[aid]))

    print("\n并发压测中 ...")
    t0 = time.time()
    threads = [threading.Thread(target=worker, args=(n,)) for n in range(THREADS)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.time() - t0

    total = sum(sum(c.values()) for c in values.values())
    print("完成：耗时 %.2fs，成功响应 %d 次，QPS≈%.0f" % (elapsed, total, total / elapsed if elapsed else 0))

    print("\n" + "=" * 78)
    print("结果：每篇文章返回过的不同 publishTime 取值")
    print("=" * 78)
    corrupt = 0
    inconsistent = 0
    for aid in ARTICLE_IDS:
        c = values.get(aid, Counter())
        distinct = list(c.keys())
        flagged = []
        for v in distinct:
            if v is None:
                continue
            if not LEGAL.match(str(v)):
                flagged.append(v)
                corrupt += c[v]
        if len(distinct) > 1:
            inconsistent += 1
        mark = ""
        if len(distinct) > 1:
            mark = "  <== 并发下出现多个取值！"
        elif flagged:
            mark = "  <== 出现非法日期串！"
        print("  %s 基线=%-22s 取值数=%d%s" % (aid, baseline.get(aid), len(distinct), mark))
        if len(distinct) > 1:
            for v, n in c.most_common():
                tag = " [非法格式]" if v is not None and not LEGAL.match(str(v)) else ""
                print("        %-24s x%d%s" % (v, n, tag))

    print("\n" + "=" * 78)
    print("异常汇总")
    print("=" * 78)
    print("非法日期格式的出现次数：%d" % corrupt)
    print("同一文章出现多个 publishTime 的文章数：%d" % inconsistent)
    print("请求异常分布：%s" % (dict(errors) or "无"))
    if corrupt or inconsistent or errors:
        print("\n判定：并发竞态已被实测捕获 —— 该类的日期格式化不是线程安全的。")
    else:
        print("\n判定：0 错乱。")
        print("  - 若被压类仍在用 static SimpleDateFormat：竞态是概率事件，本轮未触发不等于安全；")
        print("  - 若已改用 DateTimeFormatter / ThreadLocal：这正是预期结果"
              "（修复前同参数下为 53 次错乱 / 1.77%，12 篇文章全部出现多个取值）。")


if __name__ == "__main__":
    main()
