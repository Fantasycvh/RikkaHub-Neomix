#!/usr/bin/env python3
"""把 androidx.benchmark 宏基准输出的 JSON 汇总成一张表（只用 Python 3 标准库）。

用法：
    python3 app/baselineprofile/perf/summarize.py <文件或目录> [更多路径...]
    python3 app/baselineprofile/perf/summarize.py                       # 默认扫当前目录

列的来源：
    ttid       StartupTimingMetric 的 timeToInitialDisplayMs 中位数（ms）
    p50/p90/p99 FrameTimingMetric 的 frameDurationCpuMs 分位（ms）；没有 sample 时退回 metrics 里的分位
    jank%      所有迭代所有帧里 frameOverrunMs > 0 的比例（丢帧率）
    frames     所有迭代的帧总数
    n          该用例的重复迭代次数

注意：
    - 模拟器是软件渲染、与宿主机抢 CPU，数值没有参考价值，只能证明管线跑通；
    - 本仓 App 未接 reportFullyDrawn，所以不存在 ttfd 列；
    - 结果用于同设备、同编译模式下的前后对比，不要跨设备比。
"""

import glob
import json
import os
import sys


def iter_json(paths):
    for path in paths or ["."]:
        if os.path.isdir(path):
            yield from sorted(glob.glob(os.path.join(path, "**", "*.json"), recursive=True))
        elif os.path.isfile(path):
            yield path


def median(metrics, *names):
    for name in names:
        value = metrics.get(name)
        if isinstance(value, dict) and value.get("median") is not None:
            return float(value["median"])
    return None


def percentile_from_runs(runs, percent):
    values = sorted(v for run in runs or [] for v in run if v is not None)
    if not values:
        return None
    if len(values) == 1:
        return values[0]
    # 与 androidx.benchmark 一致：线性插值分位
    pos = (len(values) - 1) * percent / 100.0
    low = int(pos)
    high = min(low + 1, len(values) - 1)
    return values[low] + (values[high] - values[low]) * (pos - low)


def fmt(value, digits=1):
    return "-" if value is None else f"{value:.{digits}f}"


def shorten(name):
    """`app.androidrik.baselineprofile.JourneyBenchmarks.sendAndMeasure` → `JourneyBenchmarks.sendAndMeasure`。"""
    parts = name.split(".")
    return ".".join(parts[-2:]) if len(parts) >= 2 else name


def row(benchmark):
    metrics = benchmark.get("metrics") or {}
    sampled = benchmark.get("sampledMetrics") or {}

    cpu = sampled.get("frameDurationCpuMs") or {}
    runs = cpu.get("runs") or []
    p50 = cpu.get("P50") if cpu.get("P50") is not None else percentile_from_runs(runs, 50)
    p90 = cpu.get("P90") if cpu.get("P90") is not None else percentile_from_runs(runs, 90)
    p99 = cpu.get("P99") if cpu.get("P99") is not None else percentile_from_runs(runs, 99)
    frames = sum(len(run) for run in runs) or None

    overrun = (sampled.get("frameOverrunMs") or {}).get("runs") or []
    overrun_values = [v for run in overrun for v in run if v is not None]
    jank = (
        100.0 * sum(1 for v in overrun_values if v > 0) / len(overrun_values)
        if overrun_values
        else None
    )

    iterations = benchmark.get("repeatIterations")
    if not iterations:
        iterations = max((len(run) for run in runs), default=0) or 0

    return [
        shorten(benchmark.get("name", "?")),
        str(iterations),
        fmt(median(metrics, "timeToInitialDisplayMs")),
        fmt(p50),
        fmt(p90),
        fmt(p99),
        fmt(jank),
        "-" if frames is None else str(frames),
    ]


def context_line(context):
    if not context:
        return None
    build = context.get("build") or {}
    version = build.get("version") or {}
    device = build.get("device") or ""
    model = build.get("model") or ""
    emulator = "emulator" in str(device).lower() or str(device).startswith("emu") or "sdk_" in str(model).lower()
    line = (
        f"device: {build.get('brand', '?')} {model or '?'} "
        f"(API {version.get('sdk', '?')})"
    )
    if emulator:
        line += "  [emulator: frame numbers are not meaningful]"
    return line


def main(argv):
    header = ["test", "n", "ttid", "p50", "p90", "p99", "jank%", "frames"]
    rows = []
    context = None
    for path in iter_json(argv):
        try:
            data = json.load(open(path, encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if not isinstance(data, dict) or "benchmarks" not in data:
            continue
        context = context or data.get("context")
        for benchmark in data.get("benchmarks") or []:
            rows.append(row(benchmark))

    if not rows:
        print("没有找到宏基准结果：参数应为 connected 任务产出的 *-benchmarkData.json 所在目录", file=sys.stderr)
        return 1

    rows.sort(key=lambda r: r[0])
    widths = [max(len(r[i]) for r in [header] + rows) for i in range(len(header))]

    device = context_line(context)
    if device:
        print(device)

    def line(cols):
        out = []
        for i, col in enumerate(cols):
            out.append(col.ljust(widths[i]) if i == 0 else col.rjust(widths[i]))
        return "  ".join(out)

    print(line(header))
    print("  ".join("-" * w for w in widths))
    for r in rows:
        print(line(r))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
