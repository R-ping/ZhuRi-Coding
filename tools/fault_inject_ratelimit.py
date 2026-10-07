"""故障演练：让限流依赖的 Redis 数据结构异常，观察系统的失败方式。

手法：把 permits_key（限流 Lua 脚本按 ZSET 使用）改成 String 类型，
      使脚本内的 zrangebyscore 抛 WRONGTYPE，模拟「限流依赖不可用」。
      只影响这一个 key，演练后删除即恢复；不触碰 Redis 进程、不停服、不改配置。
"""
import http.client
import json
import socket

HOST, PORT = '127.0.0.1', 51802
KEY_BASE = 'ratelimit:{ArticleHomeController:recommend}:ip:127.0.0.1'
VALUE_KEY, PERMITS_KEY = KEY_BASE + ':value', KEY_BASE + ':permits'
LIMITED = ('/api/v1/article/recommend', '{"channel":"__all__","size":10}')
UNLIMITED = ('/api/v1/article/load/', '{"size":10,"tag":"__all__"}')


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


def req(spec):
    path, body = spec
    c = http.client.HTTPConnection(HOST, PORT, timeout=20)
    c.request('POST', path, body=body.encode('utf-8'),
              headers={'Content-Type': 'application/json'})
    r = c.getresponse()
    raw = r.read().decode('utf-8', 'ignore')
    c.close()
    try:
        j = json.loads(raw)
        return r.status, j.get('code'), j.get('message')
    except Exception:
        return r.status, None, raw[:80]


def show(tag, spec):
    st, code, msg = req(spec)
    print('  %-24s HTTP=%-5s 业务码=%-6s %s' % (tag, st, code, msg))
    return st, code


if __name__ == '__main__':
    redis_cmd('DEL', VALUE_KEY, PERMITS_KEY)   # 先清空该接口配额，保证基线处于"未限流"状态
    print('【1】基线：Redis 正常（已重置该接口配额）')
    show('限流接口 /recommend', LIMITED)
    show('无限流接口 /load/', UNLIMITED)

    print()
    print('【2】注入故障：把 permits_key 的类型改成 String（Redis 进程仍存活）')
    print('     DEL permits_key            -> %s' % redis_cmd('DEL', PERMITS_KEY))
    print('     SET permits_key chaos      -> %s' % redis_cmd('SET', PERMITS_KEY, 'chaos-injection'))
    print('     TYPE permits_key           -> %s' % redis_cmd('TYPE', PERMITS_KEY))
    print('  注入后：')
    show('限流接口 /recommend', LIMITED)
    show('无限流接口 /load/', UNLIMITED)

    print()
    print('【3】恢复：删除异常 key（无需重启服务）')
    print('     DEL permits_key            -> %s' % redis_cmd('DEL', PERMITS_KEY))
    show('限流接口 /recommend', LIMITED)
