#!/usr/bin/env python3
"""Check every production route has a skin acceptance case; summarize pulled device evidence."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/app/androidrik/rikkahub"
TEST = ROOT / "app/src/androidTest/java/app/androidrik/rikkahub/ui/theme/SkinPageAcceptanceTest.kt"
MODES = ("light-100", "dark-100", "light-200", "dark-200")


def inventory():
    routes = set(re.findall(r"entry<Screen\.(\w+)>", (SOURCE / "AppNavHost.kt").read_text(encoding="utf-8")))
    cases = re.findall(r'"(\w+)" to Screen\.(\w+)', TEST.read_text(encoding="utf-8"))
    errors = []
    if len(cases) != len(set(name for name, _ in cases)):
        errors.append("Duplicate acceptance cases")
    errors += [f"Mismatched case: {name}/{route}" for name, route in cases if name != route]
    names = {name for name, _ in cases}
    errors += [f"Missing case: {name}" for name in sorted(routes - names)]
    errors += [f"Stale case: {name}" for name in sorted(names - routes)]
    return sorted(routes), errors


def evidence(directory, routes):
    matrix = {}
    errors = []
    metadata = directory.parent / "run.json"
    if metadata.is_file() and not json.loads(metadata.read_text(encoding="utf-8")).get("passed"):
        errors.append("Instrumentation run failed")
    for mode in MODES:
        path = directory / mode / "results.json"
        if not path.is_file():
            errors.append(f"Missing device matrix: {mode}")
            continue
        rows = json.loads(path.read_text(encoding="utf-8"))
        names = [row["route"] for row in rows]
        if len(names) != len(set(names)):
            errors.append(f"Duplicate device rows: {mode}")
        if set(names) != set(routes):
            errors.append(f"Incomplete device matrix: {mode}, missing={sorted(set(routes) - set(names))}")
        matrix[mode] = {row["route"]: row for row in rows}
        for row in rows:
            if row["clipped"]:
                errors.append(f"Clipped text: {mode}/{row['route']} ({len(row['clipped'])})")
            if not (directory / mode / (row["route"] + ".png")).is_file():
                errors.append(f"Missing screenshot: {mode}/{row['route']}")
    return matrix, errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", type=Path, help="Pulled files/skin-acceptance directory")
    parser.add_argument("--report", type=Path, help="Write a local Markdown report")
    args = parser.parse_args()
    routes, errors = inventory()
    matrix = {}
    if args.results:
        matrix, failures = evidence(args.results, routes)
        errors += failures
    lines = ["# 全页面皮肤验收矩阵", "", f"生产导航入口：{len(routes)} 个；测试采用真实 appEntryProvider 与独立测试数据。",
             "", "四种配置：页面360×720dp，明/暗，系统字号100%/200%。Activity局部Configuration覆盖独立窗口字号，Compose覆写页面尺寸，保留平台非线性字体缩放。",
             "每格记录实际挂载页面与滚动视口数；截图和完整诊断保存在忽略的 app/build/reports/appearance 中。",
             "语义文字检查忽略 1px 内的测量取整，编辑器滚动视口单独保留；省略号与裁切分开记录。",
             "这些自动检查不能替代所有弹窗、数据状态、插件、自定义材质或远程内容的人工验收。", "",
             "| 路由 | 明100 | 暗100 | 明200 | 暗200 |", "| --- | --- | --- | --- | --- |"]
    for route in routes:
        cells = []
        for mode in MODES:
            row = matrix.get(mode, {}).get(route)
            if row is None:
                cells.append("未执行")
            else:
                cells.append(f"{row.get('viewports', 1)}屏 / 裁切{len(row['clipped'])} / 省略{len(row['ellipsized'])}" +
                             (" / 未到底" if not row.get("reachedEnd", False) else ""))
        lines.append("| " + route + " | " + " | ".join(cells) + " |")
    if errors:
        lines += ["", "## 未通过的证据检查", ""] + ["- " + error for error in errors]
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Skin acceptance routes={len(routes)}, modes={len(matrix)}, errors={len(errors)}")
    for error in errors:
        print(error)
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())
