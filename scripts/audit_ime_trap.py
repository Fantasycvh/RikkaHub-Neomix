#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""IME 输入陷阱回归审计（AGENTS.md「TextField Host Rules」的机器检查）。

分级扫描，对应本仓真实发生过的触发机制：

  A   Lazy item key 用内容哈希（4d2938da6）      —— 命中即失败（exit 1）
  A'  TextField 直接内联在 Lazy item 块内（9bc2d58f4）—— 命中即失败（exit 1）
  B   TextField 出现在 Lazy 容器体内（嵌套 CardGroup 等）—— 告警：字段所在 item
      仅在整体出视口时才销毁（部分可见即保持组合），风险低于 A'，真机复核
  C   AlertDialog 疑似多字段表单且无滚动/ime 处理 —— 告警（启发式噪声大，人工复核）

用法：python scripts/audit_ime_trap.py [repo-root]   # 默认脚本上级目录
"""
import os
import re
import sys

TF = re.compile(r'\b(?:App)?(OutlinedTextField|TextField|BasicTextField)\b')
HASH_KEY = re.compile(r'key\s*=\s*[^\n]*hashCode\(\)')
CARDGROUP_ITEM = re.compile(
    r'headlineContent|supportingContent|overlineContent|trailingContent|'
    r'leadingContent|formContent|subordinateContent')
LAZY_DECL = re.compile(r'\b(LazyColumn|LazyRow|LazyVerticalGrid|LazyVerticalStaggeredGrid)\s*[({]')
LAZY_ITEMS = re.compile(r'\b(items|itemsIndexed)\s*\(|\bitem\s*\(')


def match_brace(c: str, i: int) -> int:
    depth = 0
    for j in range(i, len(c)):
        if c[j] == '{':
            depth += 1
        elif c[j] == '}':
            depth -= 1
            if depth == 0:
                return j
    return -1


def line_of(c: str, i: int) -> int:
    return c.count('\n', 0, i) + 1


def scan(root: str) -> tuple[list, list, list, list]:
    hash_hits, item_hits, nested_warns, dialog_warns = [], [], [], []
    for dp, _, fns in os.walk(os.path.join(root, "app", "src", "main")):
        for fn in fns:
            if not fn.endswith(".kt"):
                continue
            p = os.path.join(dp, fn).replace("\\", "/")
            c = open(p, encoding="utf-8", errors="ignore").read()
            if not TF.search(c) and not HASH_KEY.search(c):
                continue

            # A：items/itemsIndexed 调用参数域里的 key=…hashCode()（限定 item key，
            # 避免 Markdown 内联渲染等非 Lazy 场景误报）
            for m in LAZY_ITEMS.finditer(c):
                if m.group(1) not in ('items', 'itemsIndexed'):
                    continue
                args_end = c.find('{', m.end())
                args = c[m.start():args_end if args_end != -1 else m.end()]
                for h in HASH_KEY.finditer(args):
                    hash_hits.append((p, line_of(c, m.start() + h.start())))

            # A'/B：真 lazy item（排除 CardGroup 的 Column DSL）块内出现 TextField
            for m in LAZY_ITEMS.finditer(c):
                seg = c[m.start():m.start() + 400]
                if m.group(1) in ('items', 'itemsIndexed'):
                    is_lazy = True
                elif CARDGROUP_ITEM.search(seg):
                    is_lazy = False
                elif re.match(r'item\s*\(\s*\)', seg):
                    is_lazy = False  # CardGroup 空参 item
                else:
                    is_lazy = True
                if not is_lazy:
                    continue
                k = c.find('{', m.end())
                if k == -1:
                    continue
                between = c[m.end():k]
                if between.count(')') > 2 and '->' not in between:
                    continue
                end = match_brace(c, k)
                if end == -1:
                    continue
                if TF.search(c[k:end]):
                    item_hits.append((p, line_of(c, k)))

            # B（告警）：TextField 落在 Lazy* 容器体整体内（覆盖 forEach 内联与嵌套
            # CardGroup 形态——字段所在 item 部分可见即保持组合，风险低于 A'）
            for m in LAZY_DECL.finditer(c):
                k = m.end() - 1
                if c[k] != '{':
                    k = c.find('{', m.end())
                    if k == -1:
                        continue
                end = match_brace(c, k)
                if end == -1:
                    continue
                if TF.search(c[k:end]):
                    nested_warns.append((p, line_of(c, k)))

            # C（告警）：AlertDialog + 文件内 >=2 TextField + 窗口内无滚动/ime 处理
            if "AlertDialog" in c and len(TF.findall(c)) >= 2:
                for m in re.finditer(r'AlertDialog\s*\(', c):
                    seg = c[m.start():m.start() + 1500]
                    if not re.search(r'verticalScroll|imePadding|LazyColumn', seg):
                        dialog_warns.append((p, line_of(c, m.start())))
                        break

    return hash_hits, item_hits, nested_warns, dialog_warns


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    hash_hits, item_hits, nested_warns, dialog_warns = scan(root)

    if hash_hits:
        print("[A] Lazy item key 用内容哈希（必须改稳定 id）：")
        for p, ln in hash_hits:
            print(f"  {p}:{ln}")
    if item_hits:
        print("[A'] TextField 直接内联在 Lazy item 内（改 CardGroup / sheet / 整页）：")
        for p, ln in item_hits:
            print(f"  {p}:{ln}")
    if nested_warns:
        print("[B] TextField 在 Lazy 容器体内（嵌套形态，真机复核，不阻断）：")
        for p, ln in nested_warns:
            print(f"  {p}:{ln}")
    if dialog_warns:
        print("[C] AlertDialog 疑似多字段表单且无滚动/ime 处理（人工复核，不阻断）：")
        for p, ln in dialog_warns:
            print(f"  {p}:{ln}")

    print(f"\nA={len(hash_hits)}  A'={len(item_hits)}  B(告警)={len(nested_warns)}  C(告警)={len(dialog_warns)}")
    return 1 if (hash_hits or item_hits) else 0


if __name__ == "__main__":
    sys.exit(main())
