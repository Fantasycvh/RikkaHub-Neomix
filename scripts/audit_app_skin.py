#!/usr/bin/env python3
"""Inventory native Compose skin entry points; reject unreviewed control/shape bypasses.

This is a bounded source audit, not proof of visual coverage: it does not inspect runtime
plugin code, AndroidView/WebView contents, arbitrary Shape factories or all layout literals.
"""
import argparse
import collections
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/app/androidrik/rikkahub"
POLICY = ROOT / "scripts/app_skin_coverage_exceptions.json"
REPORT = ROOT / "docs/design/drafts/app-skin-component-coverage.md"
CONTROLS = ("Button", "OutlinedButton", "TextButton", "FilledTonalButton", "OutlinedTextField", "TextField")
MATERIAL_HOSTS = ("Card", "OutlinedCard", "ElevatedCard", "DropdownMenu", "ExposedDropdownMenu",
                  "AlertDialog", "BasicAlertDialog", "ModalBottomSheet", "TopAppBar", "CenterAlignedTopAppBar",
                  "LargeTopAppBar", "MediumTopAppBar", "LargeFlexibleTopAppBar", "MediumFlexibleTopAppBar",
                  "IconButton", "FilledIconButton", "FilledTonalIconButton", "OutlinedIconButton",
                  "FilterChip", "AssistChip", "InputChip", "SuggestionChip", "ListItem", "Slider", "Switch")
SHARED_HOSTS = ("CardGroup", "FormItem", "Select", "SelectTextField", "SegmentedPill", "Tag", "ToggleSurface",
                "ExpressivePopup", "ErrorCard", "ChartCard", "CodeHighlightText", "HighlightCodeBlock", "DiffView")
WRAPPERS = "ui/components/ui/AppSkinComponents.kt"


def masked(source):
    # Keep offsets and line numbers while ignoring comments and quoted examples.
    pattern = r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\''
    return re.sub(pattern, lambda m: re.sub(r"[^\n]", " ", m[0]), source)


def calls(code, name):
    return [m for m in re.finditer(r"(?<![\w.])" + re.escape(name) + r"\s*\(", code)
            if not re.search(r"\bfun\s+$", code[max(0, m.start() - 16):m.start()])]


def constructor(code, start):
    opening = code.index("(", start)
    depth = 1
    end = opening + 1
    while end < len(code) and depth:
        depth += (code[end] == "(") - (code[end] == ")")
        end += 1
    return re.sub(r"\s+", " ", code[start:end]).strip()


def scan():
    controls = collections.Counter()
    areas = collections.defaultdict(collections.Counter)
    hosts = collections.Counter()
    findings = []
    for path in sorted(SOURCE.rglob("*.kt")):
        relative = path.relative_to(SOURCE).as_posix()
        code = masked(path.read_text(encoding="utf-8"))
        imports = dict((alias or qualified.rsplit(".", 1)[-1], qualified)
                       for qualified, alias in re.findall(r"^import ([\w.*]+)(?: as (\w+))?", code, re.M))
        area = "/".join(relative.split("/")[:-1][:3]) if relative.startswith("ui/") else "startup/other"
        for name in CONTROLS:
            if relative == WRAPPERS:
                continue
            candidates = {name: imports.get(name, "").endswith(".App" + name), "App" + name: True,
                          "androidx.compose.material3." + name: False,
                          "app.androidrik.rikkahub.ui.components.ui.App" + name: True}
            for alias, qualified in imports.items():
                if qualified == "androidx.compose.material3." + name:
                    candidates[alias] = False
                elif qualified == "app.androidrik.rikkahub.ui.components.ui.App" + name:
                    candidates[alias] = True
            for spelling, is_skin in candidates.items():
                for match in calls(code, spelling):
                    kind = "skin" if is_skin else "raw"
                    controls[(name, kind)] += 1
                    areas[area][kind] += 1
                    if not is_skin:
                        findings.append(dict(path=relative, line=code.count("\n", 0, match.start()) + 1,
                                             kind="control", expression=spelling + "(...)"))
        for name in MATERIAL_HOSTS + SHARED_HOSTS:
            hosts[name] += len(calls(code, name))
        for match in calls(code, "RoundedCornerShape"):
            findings.append(dict(path=relative, line=code.count("\n", 0, match.start()) + 1,
                                 kind="shape", expression=constructor(code, match.start())))
    return controls, areas, hosts, findings


def key(item):
    return item["path"], item["kind"], item["expression"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write-report", action="store_true", help="Refresh the local draft inventory")
    args = parser.parse_args()
    policy = json.loads(POLICY.read_text(encoding="utf-8"))
    exceptions = {key(item): item for item in policy["exceptions"]}
    assert len(exceptions) == len(policy["exceptions"]), "Duplicate exceptions"
    controls, areas, hosts, findings = scan()
    actual = collections.Counter(key(item) for item in findings)
    errors = []
    for item_key, count in actual.items():
        allowed = exceptions.get(item_key)
        if allowed is None or count > allowed["count"]:
            errors.append(f"Unreviewed ({count}): {' | '.join(item_key)}")
    for item_key, allowed in exceptions.items():
        if actual[item_key] < allowed["count"]:
            errors.append(f"Stale exception: {' | '.join(item_key)}")
    skinned = sum(count for (name, kind), count in controls.items() if kind == "skin")
    raw = sum(count for (name, kind), count in controls.items() if kind == "raw")
    print(f"AppSkin controls={skinned}, reviewed raw controls={raw}, shape expressions={sum(actual[k] for k in actual if k[1] == 'shape')}")
    if args.write_report:
        lines = ["# AppSkin 组件覆盖清单", "", "由 `python scripts/audit_app_skin.py --write-report` 生成；策略和逐项理由保存在",
                 "`scripts/app_skin_coverage_exceptions.json`。只更新本地草稿，不执行发布或推送。", "",
                 "## 基础控件入口", "", "| 控件 | AppSkin 调用 | 保留原生调用 |", "| --- | ---: | ---: |"]
        lines += [f"| {name} | {controls[name, 'skin']} | {controls[name, 'raw']} |" for name in CONTROLS]
        lines += ["", "调用名允许通过 import alias 保持原样；审计根据导入目标判定。统一封装内部的 Material 委托不计入页面调用。",
                  "", "| 区域 | AppSkin 调用 | 保留原生调用 |", "| --- | ---: | ---: |"]
        lines += [f"| {area} | {counts['skin']} | {counts['raw']} |" for area, counts in sorted(areas.items())]
        lines += ["", "## 继承 MaterialTheme 的宿主", "", "这些宿主默认读取全局形状、字体、配色；数量不代表其所有内部样式已可配置。",
                  "显式形状优先，固定圆角另见下面的逐项清单。", "", "| 宿主 | 调用数 |", "| --- | ---: |"]
        lines += [f"| {name} | {hosts[name]} |" for name in MATERIAL_HOSTS]
        lines += ["", "## 已接入的共享宿主", "", "共享宿主使用全局或子域 token；宿主行为、显式覆写和最小触控尺寸继续由组件控制。",
                  "", "| 宿主 | 调用数 |", "| --- | ---: |"]
        lines += [f"| {name} | {hosts[name]} |" for name in SHARED_HOSTS]
        lines += ["", "## 保留的形状及原生控件", "", "每项注明来源、表达式和保留理由。新增或增加同类绕过会使审计失败；删除条目后也必须清理过期例外。",
                  "", "| 来源 | 表达式 | 理由 |", "| --- | --- | --- |"]
        for item in findings:
            reason = exceptions.get(key(item), {}).get("reason", "UNREVIEWED")
            source = "../../../app/src/main/java/app/androidrik/rikkahub/" + item["path"]
            expression = item["expression"].replace("|", "\\|")
            lines.append(f"| [{item['path']}:{item['line']}]({source}#L{item['line']}) | `{expression}` | {reason} |")
        lines += ["", "## 审计边界与剩余项目", "",
                  "- 本清单检查应用 Kotlin 源码的六类基础控件入口与直接 RoundedCornerShape 构造；不把调用比例当成整个应用完成度。",
                  "- 页面结构尺寸、触控最小尺寸、语义状态色和内容数据保持宿主控制；不能把全部 dp/颜色机械改成皮肤参数。",
                  "- 自有 HTML、终端默认色/字号与画布工作区/新画纸已接入 embedded 契约，详见 [嵌入式渲染器说明](design-app-skin-embedded.md)。",
                  "- 远程网页、作者 CSS、ANSI/OSC 显式配色、已有画纸/笔画及公式/SVG 图形颜色保留自身内容协议。",
                  "- 自定义插件渲染器可显式覆盖形状/字体；只能约束已采用契约的宿主，不能强制未知插件遵守。",
                  "- 已提供 [本地资源包 v1](design-skin-packages.md)：字体、应用内 Logo、默认聊天背景和槽位纹理的安装/升级/卸载。",
                  "- 全局图标集合、全页面壁纸、资源回收/历史回滚、整皮肤试穿撤销、全页面可访问性/性能验收仍待完成。", ""]
        REPORT.write_text("\n".join(lines), encoding="utf-8")
    for error in errors:
        print(error)
    if errors:
        raise SystemExit(1)
    print("AppSkin audit passed")


if __name__ == "__main__":
    main()
