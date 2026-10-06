import { normalizeLatexEnvironments } from "./latex-environments";

let failed = 0;
function check(label: string, actual: unknown, expected: unknown) {
  const ok = actual === expected;
  if (!ok) failed++;
  console.log(`${ok ? "PASS" : "FAIL"}  ${label}`);
  if (!ok) {
    console.log("   expected:", JSON.stringify(expected));
    console.log("   actual  :", JSON.stringify(actual));
  }
}
function checkTrue(label: string, condition: boolean, detail = "") {
  if (!condition) failed++;
  console.log(`${condition ? "PASS" : "FAIL"}  ${label}${condition ? "" : "  → " + detail}`);
}

// 1. 真实用例：会话 Tikz中文支持 的多重公式对齐（列表项内、\\ 保留）
const real =
  "- **多重公式对齐**  \n" +
  "  \\begin{align}\n" +
  "      f(x) &= x^2 + 2x + 1 \\\\\n" +
  "      g(x) &= \\int f(x) \\, dx\n" +
  "  \\end{align}\n";
const realExpected =
  "- **多重公式对齐**  \n" +
  "  $$\n" +
  "  \\begin{aligned}\n" +
  "      f(x) &= x^2 + 2x + 1 \\\\\n" +
  "      g(x) &= \\int f(x) \\, dx\n" +
  "  \\end{aligned}\n" +
  "  $$\n";
check("真实用例：裸 align → $$\n + aligned", normalizeLatexEnvironments(real), realExpected);
checkTrue("真实用例：\\ 换行分隔符保留", normalizeLatexEnvironments(real).includes("\\\\"));

// 2. 定界符必须独占一行
const cases = normalizeLatexEnvironments("x\n\\begin{cases}a & b\\end{cases}\n");
checkTrue("$$ 不与 \\begin 同行", !cases.includes("$$\\begin{"), JSON.stringify(cases));
checkTrue("$$ 独占行（含缩进）", cases.includes("$$\n\\begin{cases}"));
checkTrue("结尾 $$ 独占行", cases.includes("\\end{cases}\n$$"));

// 3. 已带定界符 → 不二次包裹
const dollar = "$$\n\\begin{cases}\n x^2 & x \\ge 0 \\\\\n -x^2 & x < 0\n\\end{cases}\n$$";
check("$$ 内环境不二次包裹", normalizeLatexEnvironments(dollar), dollar);
checkTrue("没有 $$$$", !normalizeLatexEnvironments(dollar).includes("$$$$"));

// 4. \[...\] 内只改名不包裹
const bracket = "\\[\n\\begin{align} a &= b \\\\ c &= d \\end{align}\n\\]";
const bracketOut = normalizeLatexEnvironments(bracket);
checkTrue("\\[...\\] 内不外包 $$", !bracketOut.includes("$$"), JSON.stringify(bracketOut));
checkTrue("\\[...\\] 内改名 begin", bracketOut.includes("\\begin{aligned}"));
checkTrue("\\[...\\] 内改名 end", bracketOut.includes("\\end{aligned}"));

// 5. 行内 $...$ 只改名
check(
  "行内 $...$ 只改名",
  normalizeLatexEnvironments("文本 $\\begin{align} a &= b \\end{align}$ 结尾"),
  "文本 $\\begin{aligned} a &= b \\end{aligned}$ 结尾",
);

// 6. 代码块不动
const fence = "```latex\n\\begin{align} a &= b \\\\ c &= d \\end{align}\n```";
check("围栏代码块不动", normalizeLatexEnvironments(fence), fence);
const inlineCode = "用 `\\begin{align}` 起头";
check("行内代码不动", normalizeLatexEnvironments(inlineCode), inlineCode);

// 7. gather / multline / alignat 映射
check(
  "gather → gathered",
  normalizeLatexEnvironments("\\begin{gather}\n a \\\\ b\n\\end{gather}"),
  "$$\n\\begin{gathered}\n a \\\\ b\n\\end{gathered}\n$$",
);
checkTrue("multline → gathered", normalizeLatexEnvironments("\\begin{multline} a \\\\ b \\end{multline}").includes("\\begin{gathered}"));
checkTrue(
  "alignat* → alignedat",
  normalizeLatexEnvironments("\\begin{alignat*}{2} a &= b \\end{alignat*}").includes("\\begin{alignedat}{2}"),
);

// 8. tikz 只包裹不改名
const tikz = normalizeLatexEnvironments("\\begin{tikzpicture}\n\\draw (0,0) -- (1,1);\n\\end{tikzpicture}");
checkTrue("tikzpicture 被包裹", tikz.includes("$$\n\\begin{tikzpicture}"));
checkTrue("tikzpicture 结尾", tikz.includes("\\end{tikzpicture}\n$$"));
checkTrue("tikzpicture 不改名", !tikz.includes("tikzpictured"));

// 9. 未知名 / 行中 / 未闭合
check("未知环境不动", normalizeLatexEnvironments("\\begin{itemize}\n\\item a\n\\end{itemize}"), "\\begin{itemize}\n\\item a\n\\end{itemize}");
const midLine = "前面有字 \\begin{cases}a & b\\end{cases} 后面有字";
check("行中环境不包裹", normalizeLatexEnvironments(midLine), midLine);
const unclosed = "- 对齐\n  \\begin{align}\n      f(x) &= x^2";
checkTrue("未闭合不补定界符", !normalizeLatexEnvironments(unclosed).includes("$$"));
checkTrue("未闭合仍改名", normalizeLatexEnvironments(unclosed).includes("\\begin{aligned}"));

// 10. 嵌套同名环境配对
const nested = normalizeLatexEnvironments("\\begin{align}\n a \\\\ \\begin{align} b \\end{align}\n\\end{align}");
check("嵌套 begin 计数", (nested.match(/\\begin\{aligned\}/g) || []).length, 2);
check("嵌套 end 计数", (nested.match(/\\end\{aligned\}/g) || []).length, 2);

// 11. 幂等
const once = normalizeLatexEnvironments(real);
check("幂等", normalizeLatexEnvironments(once), once);

// 12. 无环境内容完全不变（回归保护）
const plain = "- 普通文本 `$ a $`\n- 行内公式 \\(x^2\\)\n- 块公式 \\[\n y = kx\n\\]\n";
check("无环境内容不变", normalizeLatexEnvironments(plain), plain);

// 13. 已内联安全的环境不变
const safe = "$$\n\\begin{aligned}\n a &= b \\\\\n c &= d\n\\end{aligned}\n$$";
check("已安全环境不变", normalizeLatexEnvironments(safe), safe);

console.log(failed === 0 ? "\nALL PASS" : `\n${failed} FAILED`);
process.exit(failed === 0 ? 0 : 1);
