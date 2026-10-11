#!/usr/bin/env python3
# 生成 hex 颜色变体的隔离测试文件，逐个编译定位触发点
import subprocess, os, shutil

ADB = "adb"
REMOTE = "/data/local/tmp/texpoc"
SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "hexvars")

os.makedirs(SRC, exist_ok=True)

preamble = r"""\documentclass{article}
\def\pgfsysdriver{pgfsys-dvisvgm.def}
\usepackage{tikz}
\pagestyle{empty}
\begin{document}
\begin{tikzpicture}
"""

variants = {
    "v1_node_fill_hex_only": r"\node[draw, fill=#4A90D9] (a) {x};",
    "v2_node_fill_hex_op": r"\node[draw, fill=#4A90D9!30] (a) {x};",
    "v3_node_fill_name_op": r"\node[draw, fill=blue!30] (a) {x};",
    "v4_draw_hex_op": r"\draw[->, #4A90D9!60] (0,0) -- (1,0);",
    "v5_draw_hex_only": r"\draw[->, #4A90D9] (0,0) -- (1,0);",
}

for name, line in variants.items():
    tex = preamble + line + "\n\\end{tikzpicture}\n\\end{document}\n"
    path = os.path.join(SRC, name + ".tex")
    with open(path, "w", encoding="utf-8") as f:
        f.write(tex)

# push + 逐个编译
for name in variants:
    local = os.path.join(SRC, name + ".tex")
    subprocess.run([ADB, "push", local, REMOTE + "/"], capture_output=True)
    r = subprocess.run(
        [ADB, "shell", "cd " + REMOTE + " && LD_LIBRARY_PATH=" + REMOTE
         + " ./tex-poc tectonic-bundle-v3.tikzbundle droidsans.ttf "
         + name + ".tex " + name + ".xdv xdv 2>&1"],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = r.stdout
    if "OK:" in out:
        print(f"{name}: PASS")
    else:
        err = [l for l in out.splitlines() if l.strip().startswith("!") or "error" in l.lower()]
        print(f"{name}: FAIL -> {err[0] if err else out.strip()[:80]}")
