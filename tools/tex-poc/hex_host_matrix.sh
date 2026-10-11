#!/usr/bin/env bash
# Host TeX (Ubuntu TeX Live, upstream pgf) 对照矩阵：
# 复现 native 引擎的 v1-v5 隔离用例，确认 #hex 在 pgf options 中是通用限制。
cd "$(dirname "$0")"

cat > /tmp/hex_v1_fill_hex.tex <<'EOF'
\documentclass{article}
\usepackage{tikz}
\pagestyle{empty}
\begin{document}
\begin{tikzpicture}
\node[draw, fill=#4A90D9] (a) {A};
\end{tikzpicture}
\end{document}
EOF

cat > /tmp/hex_v2_fill_hex_op.tex <<'EOF'
\documentclass{article}
\usepackage{tikz}
\pagestyle{empty}
\begin{document}
\begin{tikzpicture}
\node[draw, fill=#4A90D9!30] (a) {A};
\end{tikzpicture}
\end{document}
EOF

cat > /tmp/hex_v3_fill_name_op.tex <<'EOF'
\documentclass{article}
\usepackage{tikz}
\pagestyle{empty}
\begin{document}
\begin{tikzpicture}
\node[draw, fill=blue!30] (a) {A};
\end{tikzpicture}
\end{document}
EOF

cat > /tmp/hex_v4_draw_hex_op.tex <<'EOF'
\documentclass{article}
\usepackage{tikz}
\pagestyle{empty}
\begin{document}
\begin{tikzpicture}
\draw[->, #4A90D9!60] (0,0) -- (1,0);
\end{tikzpicture}
\end{document}
EOF

cat > /tmp/hex_v5_draw_hex.tex <<'EOF'
\documentclass{article}
\usepackage{tikz}
\pagestyle{empty}
\begin{document}
\begin{tikzpicture}
\draw[->, #4A90D9] (0,0) -- (1,0);
\end{tikzpicture}
\end{document}
EOF

for name in v1_fill_hex v2_fill_hex_op v3_fill_name_op v4_draw_hex_op v5_draw_hex; do
  pdflatex -interaction=nonstopmode -halt-on-error -output-directory=/tmp /tmp/hex_${name}.tex > /tmp/hex_${name}.log 2>&1
  rc=$?
  err=$(grep -aE '^!' /tmp/hex_${name}.log | head -1)
  if [ $rc -eq 0 ] && [ -f /tmp/hex_${name}.pdf ]; then
    echo "$name : PASS"
  else
    echo "$name : FAIL  <-  $err"
  fi
done
