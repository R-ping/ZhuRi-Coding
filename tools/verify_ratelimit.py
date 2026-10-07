"""限流行为实测：清空限流 key 后连续请求，观察第几次开始被拒、拒绝时的响应形态。

复用项目实际的 key 结构（见 RateLimitAspect.generateKey）：
  ratelimit:{SimpleClassName:methodName}:ip:<ip>:value    配额余量（String）
  ratelimit:{SimpleClassName:methodName}:ip:<ip>:permits  令牌记录（ZSET）
"""
import http.client
import json
import socket
import sys

HOST, PORT = '127.0.0.1', 51802
PATH = '/api/v1/article/recommend'
BODY = '{"channel":"__all__","size":10}'
KEY_BASE = 'ratelimit:{ArticleHomeController:recommend}:ip:127.0.0.1'
VALUE_KEY, PERMITS_KEY = KEY_BASE + ':value', KEY_BASE + ':permits'


def redis_cmd(*args):
    s = socket.create_connection(('127.0.0.1', 6379), timeout=5)
    payload = '*%d\r\n' % len(args)
    for a in args:
        b = str(a).encode('utf-8')
        payload += '$%d\r\n%s\r\n' % (len(b), b.decode('utf-8'))
    s.sendall(payload.encode('utf-8'))
    r = s.recv(65536)
    s.close()
    return r.decode('utf-8', 'ignore').strip()


def req():
    c = http.client.HTTPConnection(HOST, PORT, timeout=15)
    c.request('POST', PATH, body=BODY.encode('utf-8'),
              headers={'Content-Type': 'application/json'})
    r = c.getresponse()
    raw = r.read().decode('utf-8', 'ignore')
    c.close()
    try:
        j = json.loads(raw)
        return r.status, j.get('code'), j.get('message')
    except Exception:
        return r.status, None, raw[:60]


if __name__ == '__main__':
    total = int(sys.argv[1]) if len(sys.argv) > 1 else 34
    print('清理限流 key: DEL %s %s -> %s' % (VALUE_KEY, PERMITS_KEY,
                                          redis_cmd('DEL', VALUE_KEY, PERMITS_KEY)))
    print('配额余量(初始): %s' % redis_cmd('GET', VALUE_KEY))
    print()
    print('%-6s %-10s %-10s %s' % ('序号', 'HTTP', '业务码', 'message'))
    first_rejected = None
    for i in range(1, total + 1):
        st, code, msg = req()
        if code != 200 and first_rejected is None:
            first_rejected = i
        print('%-8d %-10s %-10s %s' % (i, st, code, msg))
    print()
    print('首次被拒于第 %s 次请求' % first_rejected)
    print('配额余量(结束): %s' % redis_cmd('GET', VALUE_KEY))
