#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""IME 输入陷阱回归审计（AGENTS.md「TextField Host Rules」的机器检查）。

分级扫描，对应本仓真实发生过的触发机制：

  A   Lazy item key 用内容哈希（4d2938da6）      —— 命中即失败（exit 1）
  A'  TextField 直接内联在 Lazy item 块内（9bc2d58f4）—— 命中即失败（exit 1）
  B   TextField 出现在 Lazy 容器体内（嵌套 CardGroup 等）—— 告警：字段所在 item
      仅在整体出视口时才销毁（部分可见即保持组合），风险低于 A'，真机复核
  C   AlertDialog 疑似多字段表单且无滚动/ime 处理 —— 告警（启发式噪声大，人工复核）
  D   imePadding 落在 verticalScroll 之内（内容侧而非视口侧）—— 告警：
      键盘弹起时视口不收缩、内容高度随 IME 动画逐帧变化，「滚动到光标」落点
      每帧都在动 ⇒ 整页抖动（真机定案：ScheduledTask* / 2026-11-12 assistant 详情页）
  E   TextField 的 value 直接绑异步持久态（Settings/assistant/provider… 投影）
      且 onValueChange 每字符写回 —— 告警（规则 4）：落盘后旧值回灌活跃编辑框
      ⇒ 逐字符抖动/丢字/光标跳（2026-11-12 真机 A/B：120ms/字符注入 abcdefghij
      得到 bcdefghija）。修法：ui/hooks/PersistedTextFieldState.kt

用法：python scripts/audit_ime_trap.py [repo-root]   # 默认脚本上级目录

存量豁免：`scripts/ime_trap_exceptions.json`（与 `app_skin_coverage_exceptions.json` 同风格）。
仅 A' 类支持豁免（每条带 count，超量部分仍阻断），D/E 类本来就是告警。豁免必须写明已复核
理由与待办，禁止用它掩盖新增违规。
"""
import json
import os
import re
import sys

TF = re.compile(r'\b(?:App)?(OutlinedTextField|TextField|BasicTextField)\b')
HASH_KEY = re.compile(r'key\s*=\s*[^\n]*hashCode\(\)')
CARDGROUP_ITEM = re.compile(
    r'headlineContent|supportingContent|overlineContent|trailingContent|'
    r'leadingContent|formContent|subordinateContent')
LAZY_DECL = re.compile(r'\b(LazyColumn|LazyRow|LazyVerticalGrid|LazyVerticalStaggeredGrid)\s*[({]')
# `items(...)` / `itemsIndexed(...)` / `item(...)` / `item {`（无括号写法曾是 A' 盲区）
LAZY_ITEMS = re.compile(r'\b(items|itemsIndexed)\s*\(|\bitem\s*(?=[({])')

# D 类：同一条 Modifier 链上 `verticalScroll` 先于 `imePadding`
MOD_CHAIN_LINE = re.compile(r'^\s*\.')

# E 类：value 直连这些「异步持久态根」即视为可回灌的投影
ASYNC_ROOTS = {
    'assistant', 'assistants', 'settings', 'provider', 'providers', 'preset', 'presets',
    'lorebook', 'book', 'entry', 'regex', 'kb', 'knowledgeBase', 'theme', 'config',
    'server', 'task', 'memory', 'file', 'subagent', 'model', 'workspace', 'current',
}
VALUE_ARG = re.compile(r'\bvalue\s*=\s*([A-Za-z_][A-Za-z0-9_]*)')
VALUE_EXPR = re.compile(r'\bvalue\s*=\s*([^,\n]+)')
WRITE_BACK = re.compile(r'\b(?:onUpdate|updateSettings|vm\.update|update)\s*\(|\bcopy\s*\(')
# F 类：value = 直接放格式化/转串/拼接结果且带 onValueChange（排除 TextFieldState 桥接 .text.toString()）
FORMAT_ECHO_EXPR = re.compile(r'\.toString\(\)|format[A-Z]\w*\(|joinToString\(')
STATE_BRIDGE_EXPR = re.compile(r'\b\w*State\.text\.toString\(\)')
# G 类：var <text> by remember(<value>) { mutableStateOf(...) } 伪本地缓冲
PSEUDO_BUFFER_HEAD_RE = re.compile(
    r'\bvar\s+([A-Za-z_][A-Za-z0-9_]*)\s+by\s+remember\s*\(\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\)\s*'
    r'\{\s*mutableStateOf\s*\('
)
SAFE_RESET_KEY_RE = re.compile(r'(?:^|\.)(?:id|init|key|items|slot|index)$|^\s*Unit\s*$')


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


def match_paren(c: str, i: int) -> int:
    """从 '(' 起配对，跳过字符串字面量（注释不处理，Kotlin 注释里的括号会带来噪声，仅告警用）。"""
    depth = 0
    j = i
    n = len(c)
    while j < n:
        ch = c[j]
        if ch in ('"', "'"):
            quote = ch
            j += 1
            while j < n:
                if c[j] == '\\':
                    j += 2
                    continue
                if c[j] == quote:
                    break
                j += 1
        elif ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
            if depth == 0:
                return j
        j += 1
    return -1


def line_of(c: str, i: int) -> int:
    return c.count('\n', 0, i) + 1


def scan(root: str) -> tuple[list, list, list, list, list, list]:
    hash_hits, item_hits, nested_warns, dialog_warns, inset_warns, echo_warns = [], [], [], [], [], []
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
                open_at = c.find('(', m.start())
                if open_at == -1:
                    continue
                close_at = match_paren(c, open_at)
                if close_at == -1:
                    continue
                args = c[open_at:close_at]
                for h in HASH_KEY.finditer(args):
                    hash_hits.append((p, line_of(c, open_at + h.start())))

            # 预计算当前文件内所有 Lazy* 容器区间，确保 A' 只命中真正位于 Lazy* 内的 item，
            # 不会把非 Lazy 页面（如纯 Column + CardGroup { advancedGroup { item { ... } } }）误报为 Lazy item。
            lazy_spans = []
            for m in LAZY_DECL.finditer(c):
                k = m.end() - 1
                if c[k] != '{':
                    k = c.find('{', m.end())
                    if k == -1:
                        continue
                end = match_brace(c, k)
                if end != -1:
                    lazy_spans.append((k, end))

            # A'/B：真 lazy item（排除 CardGroup 的 Column DSL）块内出现 TextField
            for m in LAZY_ITEMS.finditer(c):
                if not any(s < m.start() < e for s, e in lazy_spans):
                    continue
                seg = c[m.start():m.start() + 300]
                if m.group(1) in ('items', 'itemsIndexed'):
                    is_lazy = True
                elif re.match(r'item\s*\{', seg):
                    is_lazy = True  # LazyListScope.item { }（CardGroup 的 item 必须带 headlineContent）
                elif re.match(r'item\s*\(\s*\)', seg):
                    is_lazy = False  # CardGroup 空参 item
                elif CARDGROUP_ITEM.search(seg):
                    is_lazy = False  # CardGroup item(headlineContent = …) 是 Column DSL，非 Lazy
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

            # D（告警）：同一条 Modifier 链上 verticalScroll 先于 imePadding（§2026-11-12）
            lines = c.splitlines()
            i = 0
            while i < len(lines):
                if 'Modifier' in lines[i] and not lines[i].lstrip().startswith('//'):
                    chain = [lines[i]]
                    j = i + 1
                    while j < len(lines) and MOD_CHAIN_LINE.match(lines[j]):
                        chain.append(lines[j])
                        j += 1
                    chain_text = "\n".join(chain)
                    scroll_at = chain_text.find('.verticalScroll(')
                    ime_at = chain_text.find('.imePadding(')
                    if scroll_at != -1 and ime_at > scroll_at:
                        inset_warns.append((p, i + 1))
                    i = j
                else:
                    i += 1

            # E（告警）：value = <异步持久态投影>，且 onValueChange 每字符写回
            for m in TF.finditer(c):
                open_at = c.find('(', m.end())
                if open_at == -1:
                    continue
                close_at = match_paren(c, open_at)
                if close_at == -1:
                    continue
                args = c[open_at:close_at]
                if 'onValueChange' not in args:
                    continue
                value = VALUE_ARG.search(args)
                if not value:
                    continue
                if value.group(1) not in ASYNC_ROOTS:
                    continue
                if 'state' in args[:value.start()] and 'state =' in args[:value.start()]:
                    continue
                if WRITE_BACK.search(args):
                    echo_warns.append((p, line_of(c, m.start())))

    return hash_hits, item_hits, nested_warns, dialog_warns, inset_warns, echo_warns


def scan_value_echo_traps(root: str) -> tuple[list, list]:
    """补充扫描 F/G 类写穿/格式化回灌陷阱（避免 audit_ime_trap 盲区回归）：
    - F：TextField 的 value= 直接绑定 .toString()/formatXxx()/joinToString()（非 TextFieldState 桥接）
    - G：var text by remember(value) { mutableStateOf(...) } 伪本地缓冲（以值而非实体 id 为 key）
    """
    format_warns, pseudo_buf_warns = [], []
    for dp, _, fns in os.walk(os.path.join(root, "app", "src", "main")):
        for fn in fns:
            if not fn.endswith(".kt"):
                continue
            p = os.path.join(dp, fn).replace("\\", "/")
            c = open(p, encoding="utf-8", errors="ignore").read()
            if not TF.search(c):
                continue

            for m in TF.finditer(c):
                open_at = c.find('(', m.end())
                if open_at == -1:
                    continue
                close_at = match_paren(c, open_at)
                if close_at == -1:
                    continue
                args = c[open_at:close_at]
                if 'onValueChange' not in args:
                    continue
                vm = VALUE_EXPR.search(args)
                if not vm:
                    continue
                expr = vm.group(1).strip()
                if STATE_BRIDGE_EXPR.search(expr):
                    continue
                if FORMAT_ECHO_EXPR.search(expr):
                    format_warns.append((p, line_of(c, m.start())))

            for m in PSEUDO_BUFFER_HEAD_RE.finditer(c):
                var_name, key_expr = m.group(1), m.group(2).strip()
                if SAFE_RESET_KEY_RE.search(key_expr):
                    continue
                open_at = m.end() - 1
                close_at = match_paren(c, open_at)
                if close_at == -1:
                    continue
                init_expr = c[open_at + 1:close_at].strip()
                # 仅关心被作为 TextField value 的文本缓冲（init 与 key 同源）
                if key_expr not in init_expr:
                    continue
                if re.search(rf'\bvalue\s*=\s*{re.escape(var_name)}\b', c[close_at:close_at + 2000]):
                    pseudo_buf_warns.append((p, line_of(c, m.start())))

    return format_warns, pseudo_buf_warns


def load_a_prime_allowances(root: str) -> dict:
    """读取 A' 类存量豁免（path → count）。文件不存在则无豁免。"""
    path = os.path.join(root, "scripts", "ime_trap_exceptions.json")
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    allowances = {}
    for e in data.get("exceptions", []):
        if e.get("class", "A'") != "A'":
            continue
        allowances[e["path"]] = allowances.get(e["path"], 0) + int(e.get("count", 1))
    return allowances


def split_a_prime(root: str, item_hits: list) -> tuple[list, list]:
    """把 A' 命中拆成 (仍需阻断, 存量豁免)。同一文件超出豁免额度的命中也算阻断。"""
    allowances = load_a_prime_allowances(root)
    blocking, exempted = [], []
    used = {}
    for p, ln in item_hits:
        rel = os.path.relpath(p, root).replace("\\", "/")
        used[rel] = used.get(rel, 0) + 1
        if used[rel] <= allowances.get(rel, 0):
            exempted.append((rel, ln))
        else:
            blocking.append((rel, ln))
    return blocking, exempted


def main() -> int:
    root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    hash_hits, item_hits, nested_warns, dialog_warns, inset_warns, echo_warns = scan(root)
    format_warns, pseudo_buf_warns = scan_value_echo_traps(root)
    item_blocking, item_exempted = split_a_prime(root, item_hits)
    rel = lambda hits: [(os.path.relpath(p, root).replace("\\", "/"), ln) for p, ln in hits]

    if hash_hits:
        print("[A] Lazy item key 用内容哈希（必须改稳定 id）：")
        for p, ln in rel(hash_hits):
            print(f"  {p}:{ln}")
    if item_blocking:
        print("[A'] TextField 直接内联在 Lazy item 内（改 CardGroup / sheet / 整页）：")
        for p, ln in item_blocking:
            print(f"  {p}:{ln}")
    if item_exempted:
        print("[A'/豁免] 已复核的 Lazy item 存量（见 scripts/ime_trap_exceptions.json，不阻断）：")
        for p, ln in item_exempted:
            print(f"  {p}:{ln}")
    if nested_warns:
        print("[B] TextField 在 Lazy 容器体内（嵌套形态，真机复核，不阻断）：")
        for p, ln in rel(nested_warns):
            print(f"  {p}:{ln}")
    if dialog_warns:
        print("[C] AlertDialog 疑似多字段表单且无滚动/ime 处理（人工复核，不阻断）：")
        for p, ln in rel(dialog_warns):
            print(f"  {p}:{ln}")
    if inset_warns:
        print("[D] imePadding 在 verticalScroll 之内（应移到之前/视口侧，人工复核，不阻断）：")
        for p, ln in rel(inset_warns):
            print(f"  {p}:{ln}")
    if echo_warns:
        print("[E] TextField value 直连异步持久态且每字符写回（改 rememberPersistedTextFieldState，不阻断）：")
        for p, ln in rel(echo_warns):
            print(f"  {p}:{ln}")
    if format_warns:
        print("[F] TextField value 直连格式化/toString/joinToString（输入中易改写或清空，改本地权威缓冲，不阻断）：")
        for p, ln in rel(format_warns):
            print(f"  {p}:{ln}")
    if pseudo_buf_warns:
        print("[G] remember(value) 伪本地缓冲（回声会重置活跃编辑态，改 rememberSyncedPersistedTextFieldState，不阻断）：")
        for p, ln in rel(pseudo_buf_warns):
            print(f"  {p}:{ln}")

    print(
        f"\nA={len(hash_hits)}  A'={len(item_blocking)}（豁免 {len(item_exempted)}）  "
        f"B(告警)={len(nested_warns)}  C(告警)={len(dialog_warns)}  "
        f"D(告警)={len(inset_warns)}  E(告警)={len(echo_warns)}  "
        f"F(告警)={len(format_warns)}  G(告警)={len(pseudo_buf_warns)}"
    )
    return 1 if (hash_hits or item_blocking) else 0


if __name__ == "__main__":
    sys.exit(main())
