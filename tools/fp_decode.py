#!/usr/bin/env python3
"""fp_decode.py — system_fingerprint 还原 / 校验工具。

背景
----
DeepSeek（以及兼容 OpenAI 形状的端点）在 /chat/completions 响应里返回
`system_fingerprint`，它不是一个随机串，而是「内部构建标签」的 MD5：

    system_fingerprint = md5(plaintext)
    plaintext = fp_<model>_<build>_prod<deploy>_<quant>_kvcache_<kv>_fp

已用逐条 md5 复算验证的样本（4/4 命中）：

    fp_v4pro_20260812_prod0820_fp8_kvcache_20260402_fp
        -> a307abda487cd1b463329ccb945ce396    v4-pro
    fp_v41f_0908_prod0820_fp8_kvcache_20260402_fp
        -> 6d641ab04c479a91a8f79fb96f1d69c0    v4f 0908（内测）
    fp_dsv41f_0910_prod0820_fp8_kvcache_20260402_fp
        -> aeb56401ca74e127821c4f9126dcb669    flash 0910（统一版）
    fp_dsv41f_1017_prod0820_fp8_kvcache_20260402_fp
        -> e72d3c7f663d3e1383b00b3884cb4501    flash 1017

各字段含义（从指纹里能读出来的信息）：

    model   模型代号：v4pro / v41f / dsv41f ...
    build   构建日期：0908 / 1017 / 20260812 ...
    deploy  生产部署波次：prod0820（现役构建共用）
    quant   推理量化精度：fp8 / fp4 ...
    kv      KV cache / 推理栈构建日期：20260402

语义边界：指纹只标识「后端这一版构建」，不随请求参数变化（温度 / seed /
tools / 图像 / effort / 流式 / service_tier 全部无关）。指纹只出现在
OpenAI 形状的响应里（/chat/completions、/beta/chat/completions、FIM
/beta/completions、流式 chunk）；Anthropic Messages 与 Responses 形状不带。

用法
----
    python tools/fp_decode.py                          # 校验内置检查点表
    python tools/fp_decode.py -f bc19ba97cbe0...       # 指纹反查（未收录会明说）
    python tools/fp_decode.py -p 'fp_dsv41f_1017_prod0820_fp8_kvcache_20260402_fp'
    python tools/fp_decode.py -p '<候选明文>' -f '<实际指纹>'   # 候选命中校验
    python tools/fp_decode.py -f <指纹> --sweep        # 按语法结构化穷举（快扫）
    python tools/fp_decode.py -f <指纹> --sweep --wide # 构建日 × 部署波全交叉（慢）
    python tools/fp_decode.py --probe                  # 实际请求端点取当前指纹
    python tools/fp_decode.py -t table.json            # 换成外部检查点表

外部表格式（JSON，两种都接受；项只给 plaintext 时自动算 md5）：
    [{"name": "flash 1017", "plaintext": "fp_...", "fingerprint": "e72d..."}]
    {"checkpointTable": [ ... ]}

仅依赖标准库。退出码：0 正常/命中，1 未命中或未知指纹，2 参数/网络错误。
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import os
import sys
import time
import unicodedata
import urllib.error
import urllib.request
from datetime import date, timedelta

PROBE_URL = "https://api.deepseek.com/chat/completions"
PROBE_MODELS = ("deepseek-flash", "deepseek-v4-pro")

# 内置检查点表：plaintext <=> fingerprint 均已复算一致。
KNOWN_CHECKPOINTS = [
    {
        "name": "v4-pro",
        "plaintext": "fp_v4pro_20260812_prod0820_fp8_kvcache_20260402_fp",
        "note": "2026-08-12 构建 / prod0820 波 / fp8 / KV 栈 2026-04-02",
    },
    {
        "name": "v4f 0908（内测）",
        "plaintext": "fp_v41f_0908_prod0820_fp8_kvcache_20260402_fp",
        "note": "0908 构建；代号 v41f，尚为内测线",
    },
    {
        "name": "flash 0910（统一版）",
        "plaintext": "fp_dsv41f_0910_prod0820_fp8_kvcache_20260402_fp",
        "note": "0910 构建；代号改为 dsv41f（flash 统一版）",
    },
    {
        "name": "flash 1017",
        "plaintext": "fp_dsv41f_1017_prod0820_fp8_kvcache_20260402_fp",
        "note": "1017 构建；2026-10-17 灰度上线的那一版",
    },
]

# 已观测、但明文尚未还原的指纹（只做提示，不冒充检查点名）。
SEEN_UNRESOLVED = {
    "bc19ba97cbe016479903222bb1578db6": {
        "alias": "deepseek-flash",
        "since": "2026-10-27",
        "note": "10/27 换上的 flash 新构建；明文未收录，猜不出（见 --sweep）",
    },
}

# ---- 结构化穷举用的词表（只影响 --sweep） ---------------------------------

MODEL_TAGS = [
    "dsv41f", "v41f", "v4f", "dsv4f", "dsv41", "dsv42f", "v42f", "dsv4.1f",
    "dsv41flash", "dsv4flash", "dsv41f_flash", "flash", "v4pro", "v4.1f",
]
QUANTS = ["fp8", "fp4", "bf16", "fp16", "fp8e4m3", "fp8e5m2", "mxfp4", "int8"]
KV_DATES = [
    "20260402", "20261017", "20261020", "20261021", "20261027",
    "20261028", "20260820", "20260401",
]
PRODS_COMMON = [
    "prod0820", "prod0908", "prod0910", "prod1017", "prod1020",
    "prod1021", "prod1026", "prod1027", "prod1028",
]
EXTRA_TOKENS = ["_dspark", "_mtp", "_dspark_mtp", "_v2"]
SUFFIXES = ["_fp", "_fp2", "_v2", ""]



def _disp_width(text: str) -> int:
    """终端显示宽度：CJK 全角算 2 列，保证表格对齐。"""
    return sum(2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1 for ch in str(text))


def _pad(text, width: int) -> str:
    text = str(text)
    return text + " " * max(0, width - _disp_width(text))


def md5_hex(text: str) -> str:
    return hashlib.md5(text.encode("utf-8")).hexdigest()


def norm_fp(value: str) -> str:
    return (value or "").strip().lower()


def load_table(path: str) -> list[dict]:
    """读取外部检查点表（JSON 列表，或 {"checkpointTable": [...]}）。"""
    with open(path, "r", encoding="utf-8") as fh:
        data = json.load(fh)
    if isinstance(data, dict):
        for key in ("checkpointTable", "table", "checkpoints", "data"):
            if isinstance(data.get(key), list):
                data = data[key]
                break
        else:
            raise SystemExit(f"表格式不支持：需要在顶层给出 checkpointTable 列表（{path}）")
    if not isinstance(data, list):
        raise SystemExit(f"表格式不支持：{path}")
    out = []
    for item in data:
        if not isinstance(item, dict):
            continue
        out.append(
            {
                "name": str(item.get("name") or item.get("plaintext") or item.get("fingerprint") or "?"),
                "plaintext": item.get("plaintext"),
                "fingerprint": norm_fp(item.get("fingerprint") or ""),
                "note": str(item.get("note") or ""),
            }
        )
    return out


def effective_table(args) -> list[dict]:
    table = load_table(args.table) if args.table else [dict(row) for row in KNOWN_CHECKPOINTS]
    for row in table:
        if row.get("plaintext") and not row.get("fingerprint"):
            row["fingerprint"] = md5_hex(row["plaintext"])
    return table


def print_json(payload) -> None:
    print(json.dumps(payload, ensure_ascii=False, indent=2))


def fmt_short(fp: str) -> str:
    return f"{fp[:8]}…{fp[-4:]}" if len(fp) == 32 else fp


# ---- 子命令 ---------------------------------------------------------------


def cmd_verify(table: list[dict], as_json: bool) -> int:
    rows = []
    ok = 0
    bad = 0
    for row in table:
        pt, fp = row.get("plaintext"), row.get("fingerprint")
        got = md5_hex(pt) if pt else None
        if got and fp:
            status = "OK" if got == fp else "MISMATCH"
        elif got:
            status = "PLAINTEXT-ONLY"
        else:
            status = "FINGERPRINT-ONLY"
        if status == "OK":
            ok += 1
        elif status == "MISMATCH":
            bad += 1
        rows.append(
            {
                "name": row["name"],
                "fingerprint": fp or (got or ""),
                "computed": got,
                "status": status,
                "note": row.get("note", ""),
            }
        )
    if as_json:
        print_json({"checked": len(rows), "ok": ok, "mismatch": bad, "rows": rows})
    else:
        print(f"checkpoint table: {len(rows)} entries, {ok} md5-verified, {bad} mismatch\n")
        print(f"{_pad('name', 22)} {_pad('fingerprint', 34)} {_pad('md5(plaintext)', 34)} status")
        print("-" * 104)
        for r in rows:
            print(f"{_pad(r['name'], 22)} {_pad(r['fingerprint'], 34)} {_pad(r['computed'] or '-', 34)} {r['status']}")
        print("\n说明：指纹 = md5(plaintext)；status=OK 表示复算一致。")
    return 0 if rows and bad == 0 else 1


def cmd_resolve(table: list[dict], fp: str, as_json: bool) -> int:
    fp = norm_fp(fp)
    hit = next((r for r in table if r.get("fingerprint") == fp), None)
    if hit:
        payload = {
            "resolved": True,
            "fingerprint": fp,
            "name": hit["name"],
            "plaintext": hit.get("plaintext"),
            "note": hit.get("note", ""),
        }
        if as_json:
            print_json(payload)
        else:
            print(f"fingerprint : {fp}  ({fmt_short(fp)})")
            print(f"checkpoint  : {hit['name']}")
            if hit.get("plaintext"):
                print(f"plaintext   : {hit['plaintext']}")
                print(f"verify      : md5 -> {md5_hex(hit['plaintext'])}  {'OK' if md5_hex(hit['plaintext']) == fp else 'MISMATCH'}")
            if hit.get("note"):
                print(f"note        : {hit['note']}")
        return 0

    known = SEEN_UNRESOLVED.get(fp)
    payload = {"resolved": False, "fingerprint": fp, "known_observation": known}
    if as_json:
        print_json(payload)
    else:
        print(f"fingerprint : {fp}  ({fmt_short(fp)})")
        print("checkpoint  : 未收录（不猜测、不冒充检查点名）")
        if known:
            print(f"observed    : {known['alias']} since {known['since']}")
            print(f"note        : {known['note']}")
        print("提示        : 拿到候选明文后用 `-p <明文> -f <指纹>` 秒验证；")
        print("              没有候选明文时试 `--sweep`（结构化穷举，只覆盖已知语法）。")
    return 1


def cmd_plaintext(plaintext: str, expect: str | None, as_json: bool) -> int:
    got = md5_hex(plaintext)
    matched = None if expect is None else (got == norm_fp(expect))
    if as_json:
        print_json({"plaintext": plaintext, "md5": got, "expect": expect, "match": matched})
    else:
        print(f"plaintext   : {plaintext}")
        print(f"md5         : {got}")
        if expect is None:
            print("usage       : 把 md5 当 system_fingerprint 对比即可（32 位小写十六进制）")
        else:
            print(f"expect      : {norm_fp(expect)}")
            print(f"match       : {'YES' if matched else 'NO'}")
        # 顺带拆字段，便于人工核对语法
        parts = plaintext.split("_")
        if len(parts) >= 7 and parts[0] == "fp" and parts[-1] == "fp":
            print("\n字段拆分（语法 fp_<model>_<build>_prod<deploy>_<quant>_kvcache_<kv>_fp）：")
            print(f"  model  = {parts[1]}")
            print(f"  build  = {parts[2]}")
            print(f"  deploy = {parts[3]}")
            print(f"  quant  = {parts[4]}")
            print(f"  kv     = {parts[6]}")
    return 0 if matched in (None, True) else 1


def _date_tokens(years: tuple[int, ...], with_long: bool) -> list[str]:
    tokens: list[str] = []
    for year in years:
        day = date(year, 1, 1)
        while day.year == year:
            tokens.append(day.strftime("%m%d"))
            if with_long:
                tokens.append(day.strftime("%Y%m%d"))
            day += timedelta(days=1)
    return tokens


def _candidates(years: tuple[int, ...], wide: bool):
    """生成候选明文；指纹只是 md5，所以「语法 + 词表」就是全部可猜空间。"""
    short_dates = _date_tokens(years, with_long=False)
    long_dates = _date_tokens(years, with_long=True)

    def emit(model, build, deploy, quant, kv, suffix="_fp"):
        yield f"fp_{model}_{build}_{deploy}_{quant}_kvcache_{kv}{suffix}"

    # 1) 单槽位替换：其它槽固定为已知 1017 模板，只换一个字段
    for model in MODEL_TAGS:
        yield from emit(model, "1017", "prod0820", "fp8", "20260402")
    for build in long_dates:
        yield from emit("dsv41f", build, "prod0820", "fp8", "20260402")
    for deploy in PRODS_COMMON:
        yield from emit("dsv41f", "1017", deploy, "fp8", "20260402")
    for quant in QUANTS:
        yield from emit("dsv41f", "1017", "prod0820", quant, "20260402")
    for kv in KV_DATES:
        yield from emit("dsv41f", "1017", "prod0820", "fp8", kv)

    # 2) 小交叉：模型 × 构建日（月日） × 常见部署波 × 量化 × KV 日期
    for model, build, deploy, quant, kv in itertools.product(
        MODEL_TAGS[:4], short_dates, PRODS_COMMON[:5], QUANTS[:3], ("20260402", "20261027")
    ):
        yield from emit(model, build, deploy, quant, kv)

    if not wide:
        return

    # 3) 全交叉：构建日 × 部署波（同一年内的所有月日）——慢，分钟级
    for model, build, deploy, quant, kv in itertools.product(
        ("dsv41f", "v41f", "v4f", "dsv42f"), short_dates, [f"prod{d}" for d in short_dates],
        ("fp8", "fp4", "bf16", "fp16"), ("20260402", "20261017", "20261020", "20261027", "20260820", "20260401"),
    ):
        yield from emit(model, build, deploy, quant, kv)

    # 4) 语法扰动：额外 token / 后缀 / 字段顺序
    for model, build, deploy, quant, kv, extra, suffix in itertools.product(
        ("dsv41f", "v41f", "v4f"), ("1017", "1027", "20261027"), PRODS_COMMON[:5],
        ("fp8", "fp4"), ("20260402", "20261027"), EXTRA_TOKENS, SUFFIXES,
    ):
        yield from emit(model, build, deploy, quant, kv, suffix)  # type: ignore[arg-type]
        yield f"fp_{model}_{build}_{deploy}_{quant}_kvcache_{kv}{extra}{suffix}"
        yield f"fp_{model}_{build}_{deploy}_kvcache_{kv}_{quant}{extra}{suffix}"
        yield f"fp_{model}_{build}_{quant}_kvcache_{kv}_{deploy}{extra}{suffix}"
        yield f"fp_{build}_{model}_{deploy}_{quant}_kvcache_{kv}{extra}{suffix}"



def cmd_sweep(table: list[dict], fp: str, years: tuple[int, ...], wide: bool, as_json: bool) -> int:
    target = norm_fp(fp)
    if target in {r.get("fingerprint") for r in table}:
        name = next(r["name"] for r in table if r.get("fingerprint") == target)
        print(f"{target} 已在表中（{name}），无需穷举。")
        return 0

    started = time.time()
    tried = 0
    hit: str | None = None
    for candidate in _candidates(years, wide):
        tried += 1
        if md5_hex(candidate) == target:
            hit = candidate
            break
    elapsed = time.time() - started

    payload = {
        "fingerprint": target,
        "hit": hit,
        "candidates": tried,
        "seconds": round(elapsed, 2),
        "rate_per_sec": int(tried / elapsed) if elapsed > 0 else None,
        "years": list(years),
        "wide": wide,
    }
    if as_json:
        print_json(payload)
    else:
        print(f"target      : {target}  ({fmt_short(target)})")
        print(f"candidates  : {tried:,}   ({elapsed:.1f}s, {payload['rate_per_sec'] or 0:,}/s)")
        if hit:
            print(f"HIT         : {hit}")
            print(f"verify      : md5 -> {md5_hex(hit)}  OK")
        else:
            print("result      : 未命中。说明该构建的标签字段落在已知语法/词表之外")
            print("              （新增字段、改命名，或不再用可猜明文）；可扩 --years / --wide / 改词表。")
    return 0 if hit else 1


def cmd_probe(table: list[dict], api_key: str | None, models: tuple[str, ...], as_json: bool) -> int:
    key = api_key or os.environ.get("DEEPSEEK_API_KEY") or os.environ.get("DEEPSEEK_KEY")
    if not key:
        print("缺少凭据：用 --api-key 或环境变量 DEEPSEEK_API_KEY。", file=sys.stderr)
        return 2
    results = []
    for model in models:
        body = json.dumps(
            {"model": model, "messages": [{"role": "user", "content": "hi"}], "max_tokens": 1}
        ).encode("utf-8")
        req = urllib.request.Request(
            PROBE_URL,
            data=body,
            headers={
                "Authorization": "Bearer " + key,
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:200]
            results.append({"model": model, "error": f"HTTP {exc.code}: {detail}"})
            continue
        except Exception as exc:  # noqa: BLE001 - 网络错误原样上报
            results.append({"model": model, "error": f"{type(exc).__name__}: {exc}"})
            continue
        fp = norm_fp(payload.get("system_fingerprint") or "")
        hit = next((r for r in table if r.get("fingerprint") == fp), None)
        usage = payload.get("usage") or {}
        results.append(
            {
                "model": model,
                "echo_model": payload.get("model"),
                "system_fingerprint": fp or None,
                "checkpoint": hit["name"] if hit else None,
                "plaintext": hit.get("plaintext") if hit else None,
                "tokens": usage.get("total_tokens"),
            }
        )

    if as_json:
        print_json({"probe_url": PROBE_URL, "results": results})
        return 0
    print(f"probe       : {PROBE_URL}  (每次约 30~90 tokens)")
    for row in results:
        if row.get("error"):
            print(f"  {row['model']:16s} ERROR {row['error']}")
            continue
        fp = row["system_fingerprint"] or "(无此字段)"
        tag = f"{row['checkpoint']}  <- {row['plaintext']}" if row["checkpoint"] else "未收录（明文未知）"
        print(f"  {row['model']:16s} {fp}")
        print(f"  {'':16s} -> {tag}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="system_fingerprint 还原/校验：验证「指纹 = md5(内部构建标签)」，反查检查点，"
        "按语法穷举找回明文，或直接探测端点取当前指纹。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("-f", "--fingerprint", help="32 位 system_fingerprint（反查 / 穷举目标）")
    parser.add_argument("-p", "--plaintext", help="候选明文；给了 -f 时做命中校验")
    parser.add_argument("-t", "--table", help="外部检查点表 JSON（列表或 {checkpointTable:[...]}）")
    parser.add_argument("--sweep", action="store_true", help="对 -f 指定的指纹做结构化穷举")
    parser.add_argument("--wide", action="store_true", help="穷举扩大到「构建日 × 部署波」全交叉（慢）")
    parser.add_argument("--years", default="2026", help="穷举覆盖的构建年份，逗号分隔（默认 2026）")
    parser.add_argument("--probe", nargs="?", const="all", help="实际请求端点取当前指纹（需 key）")
    parser.add_argument("--models", default=",".join(PROBE_MODELS), help="--probe 的模型列表，逗号分隔")
    parser.add_argument("--api-key", help="探测用 key（或用环境变量 DEEPSEEK_API_KEY）")
    parser.add_argument("--json", action="store_true", help="机器可读输出")
    args = parser.parse_args(argv)

    table = effective_table(args)

    if args.probe:
        models = tuple(m.strip() for m in args.models.split(",") if m.strip())
        return cmd_probe(table, args.api_key, models, args.json)
    if args.sweep:
        if not args.fingerprint:
            parser.error("--sweep 需要 -f/--fingerprint 指定目标指纹")
        years = tuple(int(y) for y in args.years.split(",") if y.strip())
        return cmd_sweep(table, args.fingerprint, years, args.wide, args.json)
    if args.plaintext:
        return cmd_plaintext(args.plaintext, args.fingerprint, args.json)
    if args.fingerprint:
        return cmd_resolve(table, args.fingerprint, args.json)
    return cmd_verify(table, args.json)


if __name__ == "__main__":
    # Windows 控制台默认 GBK：强制 UTF-8 + 替换非法字符，避免中文/符号打印崩溃。
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
        except Exception:  # noqa: BLE001 - 老版本/非 TTY 环境忽略
            pass
    sys.exit(main())
