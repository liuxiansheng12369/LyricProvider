#!/usr/bin/env python3
"""
生成自建商店目录（catalog/stable.json），由 CI 在构建完成后调用。

规则与词岛客户端 StoreCatalogVerifier 对齐：
  * payload 是 UTF-8 JSON，字段、类型、取值范围全部按客户端校验器要求；
  * 信封签名 = ECDSA P-256 / SHA-256，签名原文是 b"CLL-CATALOG-V1\\n" + payload 字节；
  * revision 只升不降，从分支上已有目录读上一次的 revision + 1。
"""

import argparse
import base64
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile

DOMAIN = b"CLL-CATALOG-V1\n"
PROVIDER_ID = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
APPLICATION_ID = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$")
HEX40 = re.compile(r"^[0-9a-f]{40}$")

TRUSTED_HOSTS = ["github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"]

# 模块目录名 -> providerId / 展示名（与客户端 DISPLAY_NAMES 对应，这里只写 providerId）
MODULE_PROVIDER_ID = {
    "163-music": "163-music",
    "apple-music": "apple-music",
    "bodian": "bodian",
    "car-provider": "car-provider",
    "cloud-provider": "cloud-provider",
    "feiniu": "feiniu",
    "gramophone": "gramophone",
    "kugou": "kugou",
    "kuwo": "kuwo",
    "lx": "lx",
    "meizu-provider": "meizu-provider",
    "music-free": "music-free",
    "poweramp": "poweramp",
    "qishui": "qishui",
    "qq-music": "qq-music",
    "qq-music-free": "qq-music-free",
    "qq-music-hd": "qq-music-hd",
    "retro": "retro",
    "splayer": "splayer",
    "spotify": "spotify",
    "symfonium": "symfonium",
}


def find_aapt():
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home:
        return None
    build_tools = os.path.join(home, "build-tools")
    if not os.path.isdir(build_tools):
        return None
    for name in sorted(os.listdir(build_tools), reverse=True):
        for binary in ("aapt2", "aapt"):
            path = os.path.join(build_tools, name, binary)
            if os.path.isfile(path) and os.access(path, os.X_OK):
                return path
    return None


def pick_label(out):
    """从 aapt badging 里挑展示名：优先中文标签，其次默认标签。"""
    candidates = []
    for key in ("zh-CN", "zh-Hans-CN", "zh", ""):
        suffix = ("-" + key) if key else ""
        pattern = "application-label" + re.escape(suffix) + r":'((?:\\.|[^'])*)'"
        match = re.search(pattern, out)
        if match:
            raw = match.group(1).replace("\\'", "'").replace("\\\\", "\\")
            candidates.append(raw)
    for raw in candidates:
        text = re.sub(r"\s+", " ", raw).strip()
        if text:
            return text[:40]
    return None


def dump_badging(aapt, apk):
    out = subprocess.run(
        [aapt, "dump", "badging", apk],
        check=True, capture_output=True, text=True,
    ).stdout
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out)
    if not package:
        raise SystemExit("无法从 %s 解析包信息" % apk)
    min_sdk = re.search(r"sdkVersion:'(\d+)'", out)
    return {
        "applicationId": package.group(1),
        "versionCode": int(package.group(2)),
        "versionName": package.group(3),
        "minSdk": int(min_sdk.group(1)) if min_sdk else 21,
        "displayName": pick_label(out),
    }


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def apk_signer_sha256(apksigner, apk):
    out = subprocess.run(
        [apksigner, "verify", "--print-certs", apk],
        check=True, capture_output=True, text=True,
    ).stdout
    match = re.search(r"certificate SHA-256 digest:\s*([0-9a-f]{64})", out)
    if not match:
        raise SystemExit("无法读取 %s 的签名证书" % apk)
    return match.group(1)


def read_notes(notes_dir, module, version_name, provider_id):
    """按约定路径取模块的更新说明；没有就返回 None。

    查找顺序：<模块>/<版本名>.md -> <模块>.md -> <providerId>.md，
    版本名带点的目录名（如 1.0.2）在 Windows 上也合法，不需要转义。
    """
    if not notes_dir:
        return None
    candidates = [
        os.path.join(notes_dir, module, version_name + ".md"),
        os.path.join(notes_dir, module + ".md"),
        os.path.join(notes_dir, provider_id + ".md"),
    ]
    for candidate in candidates:
        if os.path.isfile(candidate):
            with open(candidate, "r", encoding="utf-8") as handle:
                text = handle.read().replace("\r\n", "\n").strip()
            if text:
                return text[:4000]
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk-dir")
    parser.add_argument("--repo")
    parser.add_argument("--tag")
    parser.add_argument("--commit")
    parser.add_argument("--resign-from", help="直接给已有的信封文件：只更新 revision 与有效期，条目原样保留")
    parser.add_argument("--notes-dir", help="更新说明目录：按 <模块>/<版本>.md 或 <模块>.md 读取，内容写进条目的 releaseNotes")
    parser.add_argument("--key", required=True, help="目录签名私钥（PKCS#8 PEM）")
    parser.add_argument("--key-id", required=True)
    parser.add_argument("--revision", required=True, type=int)
    parser.add_argument("--validity-days", type=int, default=180)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    now = int(datetime.datetime.now(datetime.timezone.utc).timestamp())

    if args.resign_from:
        # 续签模式：不重新扫描 APK，只把 revision +1、有效期往后推，条目不动
        with open(args.resign_from, "r", encoding="utf-8") as handle:
            envelope = json.load(handle)
        payload = json.loads(base64.b64decode(envelope["payload"]))
        entries = payload["entries"]
        if not entries:
            raise SystemExit("已有目录里没有条目")
        if args.notes_dir:
            # 续签时把缺说明的条目补上，只在本地缺字段时才加，已经有说明的不覆盖
            for entry in entries:
                if entry.get("releaseNotes"):
                    continue
                notes = read_notes(
                    args.notes_dir,
                    entry["providerId"],
                    entry.get("versionName", ""),
                    entry["providerId"],
                )
                if notes:
                    entry["releaseNotes"] = notes
    else:
        for name in ("apk_dir", "repo", "tag", "commit"):
            if not getattr(args, name):
                raise SystemExit("构建模式下 --%s 必填" % name.replace("_", "-"))
        aapt = find_aapt()
        if not aapt:
            raise SystemExit("找不到 aapt2/aapt，无法读取 APK 元信息")

        apksigner = os.path.join(os.path.dirname(aapt), "apksigner")
        if not os.path.isfile(apksigner):
            raise SystemExit("找不到 apksigner")

        if not HEX40.match(args.commit):
            raise SystemExit("commit 必须是 40 位十六进制")

        entries = []

        for name in sorted(os.listdir(args.apk_dir)):
            if not name.endswith("-release.apk"):
                continue
            module = name[: -len("-release.apk")]
            provider_id = MODULE_PROVIDER_ID.get(module, module)
            if not PROVIDER_ID.match(provider_id):
                print("跳过 providerId 不合规的模块：%s" % module, file=sys.stderr)
                continue
            apk_path = os.path.join(args.apk_dir, name)
            info = dump_badging(aapt, apk_path)
            if not APPLICATION_ID.match(info["applicationId"]):
                print("跳过 applicationId 不合规的模块：%s" % module, file=sys.stderr)
                continue
            if info["versionCode"] < 1:
                print("跳过 versionCode 不合规的模块：%s" % module, file=sys.stderr)
                continue
            entry = {
                "providerId": provider_id,
                "kind": "dedicated-lyrics",
                "applicationId": info["applicationId"],
                "versionName": info["versionName"] or "unknown",
                "versionCode": info["versionCode"],
                "minSdk": info["minSdk"],
                "minStoreProtocol": 1,
                "sourceCommit": args.commit,
                "artifact": {
                    "urls": [
                        "https://github.com/%s/releases/download/%s/%s" % (args.repo, args.tag, name)
                    ],
                    "bytes": os.path.getsize(apk_path),
                    "sha256": sha256_file(apk_path),
                    "signerSha256": [apk_signer_sha256(apksigner, apk_path)],
                },
            }
            if info.get("displayName"):
                entry["displayName"] = info["displayName"]
            notes = read_notes(args.notes_dir, module, entry["versionName"], provider_id)
            if notes:
                entry["releaseNotes"] = notes
            entries.append(entry)

    if not entries:
        raise SystemExit("没有可发布的 APK 条目")

    payload = {
        "schemaVersion": 1,
        "catalogRevision": args.revision,
        "issuedAtEpochSeconds": now,
        "expiresAtEpochSeconds": now + args.validity_days * 24 * 3600,
        "channel": "stable",
        "trustedHosts": TRUSTED_HOSTS,
        "entries": entries,
    }
    payload_bytes = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    with tempfile.NamedTemporaryFile(delete=False, suffix=".bin") as handle:
        handle.write(DOMAIN + payload_bytes)
        signed_path = handle.name
    try:
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", args.key, signed_path],
            check=True, capture_output=True,
        ).stdout
    finally:
        os.unlink(signed_path)

    envelope = {
        "envelopeVersion": 1,
        "algorithm": "ECDSA_P256_SHA256",
        "keyId": args.key_id,
        "payload": base64.b64encode(payload_bytes).decode("ascii"),
        "signature": base64.b64encode(signature).decode("ascii"),
    }

    out_dir = os.path.dirname(os.path.abspath(args.out))
    os.makedirs(out_dir, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(envelope, handle, ensure_ascii=False, separators=(",", ":"))

    with_notes = sum(1 for entry in entries if entry.get("releaseNotes"))
    print("目录已生成：rev %d，%d 条（%d 条带更新说明），%d 字节"
          % (args.revision, len(entries), with_notes, os.path.getsize(args.out)))


if __name__ == "__main__":
    main()
