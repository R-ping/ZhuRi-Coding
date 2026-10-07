"""轻量 HTTP 压测：统计 QPS、延迟分位、HTTP 状态码与业务码分布（仅标准库，无第三方依赖）。"""
import argparse
import http.client
import json
import threading
import time
from collections import Counter


def one_request(host, port, path, body_bytes, headers, timeout=15):
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    t0 = time.perf_counter()
    try:
        conn.request('POST', path, body=body_bytes, headers=headers)
        resp = conn.getresponse()
        data = resp.read()
        return resp.status, time.perf_counter() - t0, data
    except Exception as e:
        return -1, time.perf_counter() - t0, str(e).encode('utf-8', 'ignore')
    finally:
        conn.close()


def run(host, port, path, body, threads, total):
    body_bytes = body.encode('utf-8')
    headers = {'Content-Type': 'application/json', 'Connection': 'close'}
    per_thread = max(1, total // threads)
    lock = threading.Lock()
    records = []

    def worker():
        local = []
        for _ in range(per_thread):
            local.append(one_request(host, port, path, body_bytes, headers))
        with lock:
            records.extend(local)

    pool = [threading.Thread(target=worker) for _ in range(threads)]
    t0 = time.perf_counter()
    for t in pool:
        t.start()
    for t in pool:
        t.join()
    wall = time.perf_counter() - t0

    lat = sorted(r[1] for r in records)
    codes = Counter(r[0] for r in records)
    biz = Counter()
    samples = {}
    for st, el, data in records:
        try:
            j = json.loads(data.decode('utf-8'))
            biz[j.get('code')] += 1
            samples.setdefault(str(j.get('code')), data.decode('utf-8')[:160])
        except Exception:
            biz['non-json'] += 1

    def pct(p):
        if not lat:
            return 0.0
        return lat[min(len(lat) - 1, int(len(lat) * p))] * 1000

    n = len(records)
    print('请求数=%d  并发=%d  墙钟=%.2fs  QPS=%.1f' % (n, threads, wall, (n / wall) if wall else 0))
    print('HTTP 状态码分布: %s' % dict(codes))
    print('业务码分布: %s' % dict(biz))
    print('延迟 ms: P50=%.1f  P90=%.1f  P95=%.1f  P99=%.1f  max=%.1f'
          % (pct(0.50), pct(0.90), pct(0.95), pct(0.99), (lat[-1] * 1000) if lat else 0))
    for k, v in samples.items():
        print('  样例[code=%s]: %s' % (k, v))
    return {'qps': (n / wall) if wall else 0, 'lat': lat, 'codes': dict(codes), 'biz': dict(biz)}


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--host', default='127.0.0.1')
    ap.add_argument('--port', type=int, required=True)
    ap.add_argument('--path', required=True)
    ap.add_argument('--body', default='{}')
    ap.add_argument('--threads', type=int, default=20)
    ap.add_argument('--total', type=int, default=200)
    a = ap.parse_args()
    run(a.host, a.port, a.path, a.body, a.threads, a.total)
