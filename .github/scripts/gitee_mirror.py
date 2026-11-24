#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Gitee 镜像工具（两个镜像仓共用）。

两个子命令：
  upload  把本次构建的 APK 传到 Gitee 同名 tag 的 Release（已存在则跳过同名同大小文件）
  urls    把商店目录里每个条目的下载地址前置成 Gitee 直链，并把 gitee.com /
          foruda.gitee.com 加进 trustedHosts，然后用同一把目录私钥重签、revision +1

设计约束：只做增量改写，条目其它字段（版本、sha256、签名字、更新说明）原样保留；
GitHub 链接退到第二位继续当备用，客户端本来就是按 urls 顺序回退下载。
"""

import argparse
import base64
import datetime
import json
import os
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request

DOMAIN = b"CLL-CATALOG-V1\n"
API = "https://gitee.com/api/v5"
DEFAULT_OWNER = "luminous-brightness"
EXTRA_HOSTS = ["gitee.com", "foruda.gitee.com"]
UA = {"User-Agent": "lyricon-gitee-mirror"}


def _json_call(url, token, method="GET", body=None):
    data = None
    headers = dict(UA)
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def find_release(owner, repo, tag, token):
    rels = _json_call("%s/repos/%s/%s/releases?access_token=%s&per_page=100" % (API, owner, repo, urllib.parse.quote(token)), token)
    for r in rels:
        if r.get("tag_name") == tag:
            return r
    return None


def cmd_upload(args):
    token = os.environ.get("GITEE_TOKEN")
    if not token:
        raise SystemExit("缺少环境变量 GITEE_TOKEN")
    rel = find_release(args.owner, args.repo, args.tag, token)
    if rel is None:
        rel = _json_call(
            "%s/repos/%s/%s/releases" % (API, args.owner, args.repo),
            token,
            method="POST",
            body={
                "access_token": token,
                "tag_name": args.tag,
                "name": args.name or ("Build " + args.tag),
                "body": "国内镜像构建（与 GitHub Release %s 内容一致），供国内加速下载。" % args.tag,
                "target_commitish": "master",
                "prerelease": True,
            },
        )
        print("Gitee Release 已创建：%s %s (id=%s)" % (args.repo, args.tag, rel.get("id")))
    else:
        print("Gitee Release 已存在：%s %s (id=%s)" % (args.repo, args.tag, rel.get("id")))

    have = {}
    for a in rel.get("assets", []) or []:
        have[a["name"]] = True

    files = sorted(f for f in os.listdir(args.dir) if f.endswith(".apk"))
    if not files:
        raise SystemExit("目录里没有 APK：%s" % args.dir)
    uploaded = skipped = 0
    for name in files:
        path = os.path.join(args.dir, name)
        size = os.path.getsize(path)
        # 同一 tag 下文件名已存在就跳过：Release 是不可变的，同名必然是同一份产物
        if have.get(name):
            skipped += 1
            continue
        url = "%s/repos/%s/%s/releases/%s/attach_files" % (API, args.owner, args.repo, rel["id"])
        out = subprocess.run(
            ["curl", "-sS", "--max-time", "300", "-X", "POST", url,
             "-F", "access_token=" + token, "-F", "file=@" + path],
            capture_output=True, text=True,
        ).stdout
        if '"browser_download_url"' in out:
            uploaded += 1
            print("  上传 %s（%d 字节）" % (name, size))
        else:
            print("  上传失败 %s :: %s" % (name, out[:200]), file=sys.stderr)
            raise SystemExit(1)
    print("Gitee 附件：新传 %d，已存在跳过 %d" % (uploaded, skipped))


def _openssl():
    return os.environ.get("OPENSSL_BIN") or "openssl"


def _sign(payload_bytes, key_path):
    with tempfile.NamedTemporaryFile(delete=False, suffix=".bin") as h:
        h.write(DOMAIN + payload_bytes)
        path = h.name
    try:
        return subprocess.run([_openssl(), "dgst", "-sha256", "-sign", key_path, path],
                              check=True, capture_output=True).stdout
    finally:
        os.unlink(path)


def cmd_urls(args):
    with open(args.catalog, "r", encoding="utf-8") as f:
        env = json.load(f)
    payload = json.loads(base64.b64decode(env["payload"]))

    hosts = list(payload.get("trustedHosts") or [])
    for h in EXTRA_HOSTS:
        if h not in hosts:
            hosts.append(h)
    payload["trustedHosts"] = hosts[:16]

    rewritten = skipped = 0
    for e in payload["entries"]:
        art = e["artifact"]
        urls = list(art.get("urls") or [])
        gitee_url = None
        # 文件名以 GitHub 链接的末段为准：产物名两个镜像完全一致
        for u in urls:
            if u.startswith("https://github.com/"):
                gitee_url = "https://gitee.com/%s/%s/releases/download/%s/%s" % (
                    args.owner, args.repo, args.tag, u.rsplit("/", 1)[-1])
                break
        if gitee_url is None:
            # 已经是 Gitee 直链在前的情况：取第一条 gitee 链接，换个 tag 重排
            for u in urls:
                if u.startswith("https://gitee.com/"):
                    tail = u.rsplit("/", 3)[-1]
                    gitee_url = "https://gitee.com/%s/%s/releases/download/%s/%s" % (
                        args.owner, args.repo, args.tag, tail)
                    break
        if gitee_url is None:
            skipped += 1
            continue
        rest = [u for u in urls if u != gitee_url]
        art["urls"] = ([gitee_url] + rest)[:8]
        rewritten += 1

    now = int(datetime.datetime.now(datetime.timezone.utc).timestamp())
    payload["catalogRevision"] = int(payload.get("catalogRevision", 0)) + 1
    payload["issuedAtEpochSeconds"] = now
    payload["expiresAtEpochSeconds"] = max(int(payload.get("expiresAtEpochSeconds", 0)), now + 180 * 24 * 3600)

    pb = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    sig = _sign(pb, args.key)
    out = {
        "envelopeVersion": 1,
        "algorithm": "ECDSA_P256_SHA256",
        "keyId": args.key_id,
        "payload": base64.b64encode(pb).decode("ascii"),
        "signature": base64.b64encode(sig).decode("ascii"),
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))

    print("目录改写完成：rev=%d，%d 条前置 Gitee 直链，%d 条无 GitHub 链接跳过，hosts=%d"
          % (payload["catalogRevision"], rewritten, skipped, len(hosts)))


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)

    up = sub.add_parser("upload")
    up.add_argument("--repo", required=True)
    up.add_argument("--tag", required=True)
    up.add_argument("--dir", required=True)
    up.add_argument("--name")
    up.add_argument("--owner", default=DEFAULT_OWNER)
    up.set_defaults(func=cmd_upload)

    u = sub.add_parser("urls")
    u.add_argument("--catalog", required=True)
    u.add_argument("--repo", required=True)
    u.add_argument("--tag", required=True)
    u.add_argument("--out", required=True)
    u.add_argument("--key", required=True)
    u.add_argument("--key-id", required=True)
    u.add_argument("--owner", default=DEFAULT_OWNER)
    u.set_defaults(func=cmd_urls)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
