"""把 pgfplots 用例注入 pgfsys-dvisvgm.def 的一次性实验脚本。

deprecated：dvisvgm 链已废弃，仅存档（见 ../README.md 与 README.md）。
用法：TEX_POC_DIR=<tools/tex-poc> python3 make_pgp_test.py
输出：$TEX_POC_OUT（默认 /tmp/test_pgp.tex）
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
POC = os.environ.get("TEX_POC_DIR", os.path.join(HERE, ".."))
OUT = os.environ.get("TEX_POC_OUT", "/tmp/test_pgp.tex")

src = open(os.path.join(POC, "test_pgfplots.tex"), encoding="utf-8").read()
bs = chr(92)
old = bs + "usepackage{pgfplots}"
new = (bs + "def" + bs + "pgfsysdriver{pgfsys-dvisvgm.def}" + chr(10)
       + bs + "usepackage{pgfplots}")
src = src.replace(old, new, 1)
open(OUT, "w", encoding="utf-8").write(src)
print("written:", OUT)
