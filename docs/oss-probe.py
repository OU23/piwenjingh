#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OSS 接入独立探针（零依赖，仅 Python 标准库）
============================================

用途：**不经过我们的应用**，直接验证阿里云侧配置对不对，把问题一刀切开：

    [长期 AK]  ──⓪  GetCallerIdentity ──▶  这把 AK 到底是谁？（账号 ID / 用户 ARN / **用户 UID**）
               ──①  STS AssumeRole ──▶  能换到临时凭证吗？（AK 有效性 + 角色信任策略 + sts:AssumeRole）
               ──②  OSS Put/Get/Delete ──▶  这把 AK 对桶有没有权限？

    [临时凭证] ──③  OSS Put/Get/Delete ──▶  角色（浏览器直传用的那个身份）对桶有没有权限？

⓪ 的用途：桶策略（Bucket Policy）的 Principal 就填这个 **UserId**，
   所以拿到它 = 拿到"该给谁授权"的答案（不再需要问管理员"这把 AK 属于谁"）。

配置全部从**环境变量**读取（就是启动后端时设的那几个），脚本本身不存任何密钥：

    OSS_ACCESS_KEY_ID        必填
    OSS_ACCESS_KEY_SECRET    必填
    OSS_BUCKET               必填
    OSS_ENDPOINT             必填（如 oss-cn-beijing.aliyuncs.com，不要带 https://）
    OSS_STS_ROLE_ARN         选填；不填则跳过 ① ③
    OSS_STS_ENDPOINT         选填，默认 sts.aliyuncs.com（也可用 sts.cn-beijing.aliyuncs.com）

用法：
    python oss-probe.py                                 # 只做检查（只读 + 一次探针写删）
    python oss-probe.py --prefix tools/_diagnose        # 换探针对象前缀
    python oss-probe.py --no-cleanup                    # 保留探针对象（默认会删掉）

自助修权限（**不需要控制台登录，也不需要 RAM 管理员**，用 AK 直接走 API）：
    python oss-probe.py --set-policy-for <RAM用户UID>     # 演练：只打印将要写入的桶策略
    python oss-probe.py --set-policy-for <UID> --yes     # 真正写入桶策略（PUT /?policy）
    # 用的是当前环境变量里的那把 AK —— 它必须自己有 oss:PutBucketPolicy 才写得进去，
    # 通常就是"当初建这个桶"的那把 AK：把它设成 OSS_ACCESS_KEY_ID/SECRET 再跑即可。

⚠️ 脚本会往桶里写一个几十字节的探针对象，**测完立即删除**（除非加了 --no-cleanup）。
   退出码：0 = 全部通过；1 = 有失败；2 = 配置缺失。
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import uuid
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

TIMEOUT_SECONDS = 20
DEFAULT_STS_ENDPOINT = "sts.aliyuncs.com"
STS_API_VERSION = "2015-04-01"


# ---------------------------------------------------------------- 小工具

def env(name: str) -> str:
    return (os.environ.get(name) or "").strip()


def pct(value: str) -> str:
    """RFC3986 percent-encode（保留 A-Za-z0-9 与 -_.~）—— 阿里云 V1 签名要求"""
    return quote(str(value), safe="")


def mask(value: str) -> str:
    if not value:
        return "(空)"
    if len(value) <= 8:
        return value[0] + "*" * (len(value) - 1)
    return f"{value[:4]}{'*' * 6}{value[-4:]}"


def ok(text: str) -> str:
    return f"\033[32m✅ {text}\033[0m"


def bad(text: str) -> str:
    return f"\033[31m❌ {text}\033[0m"


def skip(text: str) -> str:
    return f"\033[33m⛔ {text}\033[0m"


# ---------------------------------------------------------------- STS（RPC 风格 V1 签名）

def sts_rpc(ak: str, sk: str, endpoint: str, action: str, extra: dict = None) -> dict:
    """STS 的 RPC 风格 V1 签名调用（AssumeRole / GetCallerIdentity 共用）"""
    params = {
        "Action": action,
        "Format": "JSON",
        "Version": STS_API_VERSION,
        "AccessKeyId": ak,
        "SignatureMethod": "HMAC-SHA1",
        "SignatureVersion": "1.0",
        "SignatureNonce": uuid.uuid4().hex,
        "Timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    if extra:
        params.update({k: str(v) for k, v in extra.items()})
    cqs = "&".join(f"{pct(k)}={pct(params[k])}" for k in sorted(params))
    string_to_sign = "GET&%2F&" + pct(cqs)
    digest = hmac.new((sk + "&").encode("utf-8"), string_to_sign.encode("utf-8"), hashlib.sha1).digest()
    signature = base64.b64encode(digest).decode("utf-8")

    url = f"https://{endpoint}/?{cqs}&Signature={pct(signature)}"
    status, body = _http("GET", url)
    if status != 200:
        raise RuntimeError(_sts_error_message(body, status))
    try:
        return json.loads(body)
    except ValueError as exc:  # pragma: no cover - 防御
        raise RuntimeError(f"STS 响应解析失败：{body[:300]}") from exc


def sts_assume_role(ak: str, sk: str, role_arn: str, endpoint: str,
                    session_name: str, duration: int = 3600) -> dict:
    """调 STS AssumeRole，返回 Credentials 字典；失败抛 RuntimeError（含错误码）"""
    data = sts_rpc(ak, sk, endpoint, "AssumeRole", {
        "RoleArn": role_arn,
        "RoleSessionName": session_name,
        "DurationSeconds": duration,
    })
    try:
        return data["Credentials"]
    except KeyError as exc:  # pragma: no cover - 防御
        raise RuntimeError(f"STS 响应缺少 Credentials：{str(data)[:300]}") from exc


def sts_get_caller_identity(ak: str, sk: str, endpoint: str) -> dict:
    """⓪ 查"这把 AK 是谁"：返回 AccountId / UserId / Arn / IdentityType。

    该 API 无额外授权要求（官方即用它做凭证自检）；返回的 **UserId** 就是
    Bucket Policy 里 Principal 要填的 RAM 用户 UID。
    """
    return sts_rpc(ak, sk, endpoint, "GetCallerIdentity")


def _sts_error_message(body: str, status: int) -> str:
    try:
        data = json.loads(body)
        return f"HTTP {status} · {data.get('Code', '?')} · {data.get('Message', '')}"
    except ValueError:
        return f"HTTP {status} · {body[:300]}"


# ---------------------------------------------------------------- OSS（V1 签名）

def oss_call(method: str, endpoint: str, bucket: str, key: str, ak: str, sk: str,
             token: str = None, body: bytes = None, content_type: str = None):
    """最小 OSS 请求（Put/Get/Delete 够用）。返回 (status, body_text)"""
    date = format_datetime(datetime.now(timezone.utc), usegmt=True)
    resource = f"/{bucket}/{key}"

    headers = {"Date": date}
    oss_headers = ""
    if token:
        # STS 临时凭证必须带该头，且它属于 x-oss-* → 参与签名
        headers["x-oss-security-token"] = token
        oss_headers = f"x-oss-security-token:{token}\n"

    ctype = content_type or ""
    if ctype:
        headers["Content-Type"] = ctype

    string_to_sign = f"{method}\n\n{ctype}\n{date}\n{oss_headers}{resource}"
    digest = hmac.new(sk.encode("utf-8"), string_to_sign.encode("utf-8"), hashlib.sha1).digest()
    headers["Authorization"] = f"OSS {ak}:{base64.b64encode(digest).decode('utf-8')}"

    url = f"https://{bucket}.{endpoint}/{quote(key, safe='/')}"
    return _http(method, url, headers=headers, body=body)


def oss_put_bucket_policy(endpoint: str, bucket: str, ak: str, sk: str, policy_text: str):
    """写桶策略：PUT /?policy（桶级子资源，CanonicalizedResource = /<bucket>/?policy）"""
    body = policy_text.encode("utf-8")
    content_md5 = base64.b64encode(hashlib.md5(body).digest()).decode("utf-8")
    ctype = "application/json"
    date = format_datetime(datetime.now(timezone.utc), usegmt=True)
    resource = f"/{bucket}/?policy"

    string_to_sign = f"PUT\n{content_md5}\n{ctype}\n{date}\n{resource}"
    digest = hmac.new(sk.encode("utf-8"), string_to_sign.encode("utf-8"), hashlib.sha1).digest()
    headers = {
        "Date": date,
        "Content-Type": ctype,
        "Content-MD5": content_md5,
        "Authorization": f"OSS {ak}:{base64.b64encode(digest).decode('utf-8')}",
    }
    return _http("PUT", f"https://{bucket}.{endpoint}/?policy", headers=headers, body=body)


# ---------------------------------------------------------------- HTTP

def _http(method: str, url: str, headers: dict = None, body: bytes = None):
    request = Request(url, data=body, headers=headers or {}, method=method)
    try:
        with urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", errors="replace")
    except URLError as exc:
        return 0, f"网络不可达：{exc.reason}"


def brief(body: str, limit: int = 160) -> str:
    text = " ".join(body.split())
    return text if len(text) <= limit else text[:limit] + "…"


# ---------------------------------------------------------------- 主流程

def report(title: str, result: str) -> None:
    print(f"  {title:<34} {result}")


def probe_oss(tag: str, endpoint: str, bucket: str, key: str,
              ak: str, sk: str, token: str = None, cleanup: bool = True) -> bool:
    """Put → Get → Delete；返回是否全通过。失败时打印 OSS 错误码 + RequestId"""
    payload = f"toolbox oss probe @ {datetime.now(timezone.utc).isoformat()}\n".encode("utf-8")

    failures = []

    status, body = oss_call("PUT", endpoint, bucket, key, ak, sk, token,
                            body=payload, content_type="text/plain; charset=utf-8")
    if status == 200:
        report(f"[{tag}] PutObject", ok("200"))
    else:
        report(f"[{tag}] PutObject", bad(f"{status} · {oss_code(body)}"))
        failures.append("PutObject")

    if not failures:
        status, body = oss_call("GET", endpoint, bucket, key, ak, sk, token)
        if status == 200 and body.encode("utf-8").startswith(b"toolbox oss probe"):
            report(f"[{tag}] GetObject", ok("200（内容校验通过）"))
        else:
            report(f"[{tag}] GetObject", bad(f"{status} · {oss_code(body)}"))
            failures.append("GetObject")

    if not failures and cleanup:
        status, body = oss_call("DELETE", endpoint, bucket, key, ak, sk, token)
        if status in (200, 204):
            report(f"[{tag}] DeleteObject", ok(str(status)))
        else:
            report(f"[{tag}] DeleteObject", bad(f"{status} · {oss_code(body)}"))
            failures.append("DeleteObject")
    elif not cleanup:
        report(f"[{tag}] DeleteObject", skip("--no-cleanup：保留探针对象"))

    return not failures


def oss_code(body: str) -> str:
    """从 OSS 错误 XML 里取 Code/RequestId，便于对号入座"""
    code = _xml_value(body, "Code")
    request_id = _xml_value(body, "RequestId")
    message = _xml_value(body, "Message")
    parts = [p for p in (code, message) if p]
    text = " · ".join(parts) if parts else brief(body, 80)
    return f"{text}（RequestId {request_id}）" if request_id else text


def _xml_value(body: str, tag: str) -> str:
    start_tag, end_tag = f"<{tag}>", f"</{tag}>"
    start = body.find(start_tag)
    if start < 0:
        return ""
    end = body.find(end_tag, start)
    return body[start + len(start_tag):end].strip() if end > start else ""


def bucket_policy_json(principal: str, bucket: str) -> str:
    """生成"给桶配 Bucket 策略"用的 JSON（Principal = RAM 用户 UID，无需 RAM 管理员）"""
    return json.dumps({
        "Version": "1",
        "Statement": [{
            "Effect": "Allow",
            "Action": [
                "oss:PutObject", "oss:GetObject", "oss:DeleteObject",
                "oss:InitiateMultipartUpload", "oss:UploadPart",
                "oss:CompleteMultipartUpload", "oss:AbortMultipartUpload", "oss:ListParts",
            ],
            "Principal": [principal],
            "Resource": [f"acs:oss:*:*:{bucket}/*"],
        }],
    }, ensure_ascii=False, indent=2)


def apply_bucket_policy(principal: str, bucket: str, endpoint: str,
                        ak: str, sk: str, confirm: bool) -> int:
    """把「授权给 principal」的 Bucket 策略写入桶（默认只演练，加 --yes 才真写）"""
    policy_text = bucket_policy_json(principal, bucket)
    print(f"待写入的 Bucket 策略（桶 {bucket}，Principal = {principal}）")
    for line in policy_text.splitlines():
        print("  " + line)
    print()

    if not confirm:
        print(skip("演练模式：未写入任何内容。确认无误后加 --yes 真正保存。"))
        return 0

    status, body = oss_put_bucket_policy(endpoint, bucket, ak, sk, policy_text)
    if status in (200, 204):
        print(ok(f"桶策略已写入 {bucket}"))
        print("  → 换回**应用那把 AK** 再跑一次 `python oss-probe.py`，第 2 行应从 ❌ 变 ✅")
        print("  → **无需重启后端**（权限由 OSS 每次请求实时判定）")
        return 0

    print(bad(f"写入失败：{status} · {oss_code(body)}"))
    print("  → 官方口径：这两句报错都是**权限不足**，不是「桶在别的账号下」（很容易误读）：")
    print("     ·「You have no right to access this object because of bucket acl」= 缺**对象级**权限")
    print("     ·「The bucket you access does not belong to you」= 缺**桶级管理**权限")
    print("  → ⚠️ 换 AK 未必有用：若新 AK 仍属于**同一个 RAM 用户**，权限一模一样。")
    print("     真正要做的是「给这个用户补权限」（管理员挂 RAM 策略）或「换一把确有桶管理权限的 AK」。")
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description="OSS 接入独立探针（零依赖）")
    parser.add_argument("--prefix", default="tools/_diagnose", help="探针对象前缀（默认 tools/_diagnose）")
    parser.add_argument("--no-cleanup", action="store_true", help="失败时不删除探针对象（便于排查）")
    parser.add_argument("--set-policy-for", metavar="RAM_USER_UID",
                        help="把桶策略授予该 RAM 用户 UID（默认只演练；配 --yes 才写入）")
    parser.add_argument("--yes", action="store_true", help="与 --set-policy-for 连用：确认实际写入桶策略")
    args = parser.parse_args()

    ak, sk = env("OSS_ACCESS_KEY_ID"), env("OSS_ACCESS_KEY_SECRET")
    bucket, endpoint = env("OSS_BUCKET"), env("OSS_ENDPOINT")
    role_arn = env("OSS_STS_ROLE_ARN")
    sts_endpoint = env("OSS_STS_ENDPOINT") or DEFAULT_STS_ENDPOINT

    print("=" * 72)
    print("OSS 接入独立探针（不经过应用，直接打阿里云）")
    print("=" * 72)
    print("配置（来自环境变量）")
    print(f"  OSS_ENDPOINT      {endpoint or '(缺失)'}")
    print(f"  OSS_BUCKET        {bucket or '(缺失)'}")
    print(f"  OSS_ACCESS_KEY_ID {mask(ak)}")
    print(f"  OSS_STS_ROLE_ARN  {role_arn or '(未设置 → 跳过 ① ③)'}")
    print(f"  OSS_STS_ENDPOINT  {sts_endpoint}")
    print()

    missing = [n for n, v in (("OSS_ACCESS_KEY_ID", ak), ("OSS_ACCESS_KEY_SECRET", sk),
                              ("OSS_BUCKET", bucket), ("OSS_ENDPOINT", endpoint)) if not v]
    if missing:
        print(bad("配置缺失：" + "、".join(missing)))
        print("  → 请先在同一个终端里 set 好环境变量（见 OSS配置说明.md §4.5），再运行本脚本。")
        return 2

    endpoint = endpoint.replace("https://", "").replace("http://", "").strip("/")
    key = f"{args.prefix.strip('/')}/probe-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')}.txt"
    credentials = None

    # ⓪ 这把 AK 到底是谁 —— 决定"该给谁授权"，免去猜 AK 归属
    identity = None
    print("当前 AccessKey 身份（STS GetCallerIdentity）")
    try:
        identity = sts_get_caller_identity(ak, sk, sts_endpoint)
        report("IdentityType", identity.get("IdentityType", "?"))
        report("AccountId", identity.get("AccountId", "?"))
        report("UserId", identity.get("UserId", "?"))
        report("Arn", identity.get("Arn", "?"))
    except RuntimeError as exc:
        report("GetCallerIdentity", bad(str(exc)))
        print("       → 该 AK 无效或网络不通；下面几项仍会尝试")
    print()

    # 自助写桶策略模式：只做这一件事就退出（不需要控制台登录）
    if args.set_policy_for:
        return apply_bucket_policy(args.set_policy_for, bucket, endpoint, ak, sk, args.yes)

    print("检查项")
    # ① 长期 AK 能否扮演角色
    if role_arn:
        try:
            credentials = sts_assume_role(ak, sk, role_arn, sts_endpoint,
                                          f"toolbox-probe-{uuid.uuid4().hex[:8]}")
            report("[AK] STS AssumeRole", ok(f"成功（{credentials.get('Expiration', '?')} 过期）"))
        except RuntimeError as exc:
            report("[AK] STS AssumeRole", bad(str(exc)))
            print("       → 该 AK 无效 / 未授 sts:AssumeRole / 角色信任策略不含它")
    else:
        report("[AK] STS AssumeRole", skip("未设置 OSS_STS_ROLE_ARN"))

    print()
    # ② 长期 AK 对桶的权限
    ak_ok = probe_oss("AK", endpoint, bucket, key, ak, sk, cleanup=not args.no_cleanup)

    print()
    # ③ 临时凭证（= 浏览器直传用的身份）对桶的权限
    if credentials:
        probe_oss("STS", endpoint, bucket, key, credentials["AccessKeyId"],
                  credentials["AccessKeySecret"], credentials.get("SecurityToken"),
                  cleanup=not args.no_cleanup)
    else:
        report("[STS] Put/Get/Delete", skip("无临时凭证（见 ①）"))

    print()
    print("=" * 72)
    print("结论")
    if not role_arn:
        print("  · 未配置 OSS_STS_ROLE_ARN，只验证了长期 AK。")
    if not ak_ok:
        print(bad("  长期 AK 对桶无权限"))
        print("     入口①（需 RAM 管理员）：给该 AK 所属 RAM 用户挂 RAM 策略（OSS配置说明.md §2.2 策略 A）")
        print("     入口②（自助，无需管理员）：给桶配 Bucket 策略，Principal 用上面的 UserId")
        principal = (identity or {}).get("UserId") or (identity or {}).get("Arn")
        if principal:
            print()
            print("  ——「Bucket 授权策略 → 语法策略」可直接粘贴（Principal 已按实测值填好）——")
            for line in bucket_policy_json(str(principal), bucket).splitlines():
                print("  " + line)
        else:
            print("     （未能拿到 UserId，先解决 ⓪ 才能生成策略 JSON）")
    elif credentials:
        print(ok("  长期 AK 与角色对该桶均正常"))
    return 0 if ak_ok else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n已中断")
        sys.exit(130)
