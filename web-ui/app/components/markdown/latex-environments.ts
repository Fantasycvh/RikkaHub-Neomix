/**
 * 裸 LaTeX 环境归一化（Kotlin 侧 `LatexEnvironments.kt` 的前端镜像，逻辑需保持一致）。
 *
 * 背景：模型有时输出**不带任何数学定界符**的块级环境：
 *
 * ```
 * - **多重公式对齐**
 *   \begin{align}
 *       f(x) &= x^2 + 2x + 1 \\
 *       g(x) &= \int f(x) \, dx
 *   \end{align}
 * ```
 *
 * 没有定界符就不会进入 KaTeX，被当普通段落文本处理 → `\\` 被 markdown 当转义退化成 `\`、
 * 软换行被折叠成空格、`&` 变 `&amp;`。同时 `align` 在 KaTeX 里只能用于 display 模式，
 * `multline` / `flalign` 更是完全不支持。
 *
 * 两步处理：
 * 1. 裸的块级环境两侧补上**独占一行**的 `$$`（实测 `$$\begin{align}` 同行会被 remark-math 误判）；
 * 2. 环境名改写成两端渲染器都认的内联安全等价物（`align`→`aligned`、`gather`→`gathered` 等）。
 */

/** 需要自动补 `$$` 定界符的环境：AMS 块级公式 + 矩阵/分段家族。 */
export const DISPLAY_MATH_ENVIRONMENTS: ReadonlySet<string> = new Set([
  // AMS 多行/对齐
  "align", "align*", "aligned",
  "alignat", "alignat*", "alignedat",
  "gather", "gather*", "gathered",
  "multline", "multline*",
  "equation", "equation*",
  "flalign", "flalign*",
  "split", "CD",
  // 分段/矩阵家族
  "cases", "dcases", "rcases",
  "array", "darray", "subarray",
  "matrix", "pmatrix", "bmatrix", "Bmatrix", "vmatrix", "Vmatrix", "smallmatrix",
]);

/** 环境名重写表：只保留 KaTeX 与 jlatexmath 都能吃下的等价环境。 */
export const LATEX_ENVIRONMENT_ALIASES: Readonly<Record<string, string>> = {
  align: "aligned",
  "align*": "aligned",
  flalign: "aligned",
  "flalign*": "aligned",
  equation: "aligned",
  "equation*": "aligned",
  alignat: "alignedat",
  "alignat*": "alignedat",
  gather: "gathered",
  "gather*": "gathered",
  multline: "gathered",
  "multline*": "gathered",
};

const BEGIN_REGEX_SOURCE = "\\\\begin\\{([A-Za-z*]+)\\}";
const NAME_REGEX_SOURCE = "\\\\(begin|end)\\{([A-Za-z*]+)\\}";
const CODE_BLOCK_REGEX_SOURCE = "```[\\s\\S]*?```|`[^`\\n]*`";
const MATH_SPAN_REGEX_SOURCE = "\\$\\$[\\s\\S]*?\\$\\$|\\\\\\[[\\s\\S]*?\\\\\\]|\\\\\\([\\s\\S]*?\\\\\\)|\\$(?!\\$)[^\\n$]*?\\$";

type Range = { start: number; end: number };

function collectRanges(content: string, source: string): Range[] {
  const ranges: Range[] = [];
  const regex = new RegExp(source, "g");
  let match: RegExpExecArray | null;
  while ((match = regex.exec(content)) !== null) {
    ranges.push({ start: match.index, end: match.index + match[0].length - 1 });
    if (match[0].length === 0) regex.lastIndex += 1;
  }
  return ranges;
}

const inRanges = (ranges: Range[], position: number): boolean =>
  ranges.some((range) => position >= range.start && position <= range.end);

/** 重写环境名；`\begin{align}` 与其配对的 `\end{align}` 必须成对改写。 */
export function renameLatexEnvironments(
  content: string,
  isInCodeBlock: (position: number) => boolean = () => false,
): string {
  if (!Object.keys(LATEX_ENVIRONMENT_ALIASES).some((env) => content.includes(`{${env}}`))) {
    return content;
  }
  return content.replace(new RegExp(NAME_REGEX_SOURCE, "g"), (whole, kind: string, env: string, offset: number) => {
    const alias = LATEX_ENVIRONMENT_ALIASES[env];
    if (alias === undefined) return whole;
    if (isInCodeBlock(offset)) return whole;
    return `\\${kind}{${alias}}`;
  });
}

/**
 * 从 `searchStart` 起找与已开启环境配对的 `\end{environment}`，支持同名环境嵌套。
 * 找不到闭合时返回 null —— 流式生成中途常见，此时不补定界符。
 */
function findEnvironmentEnd(content: string, environment: string, searchStart: number): Range | null {
  const open = `\\begin{${environment}}`;
  const close = `\\end{${environment}}`;
  let depth = 0;
  let index = searchStart;
  while (index < content.length) {
    const nextOpen = content.indexOf(open, index);
    const nextClose = content.indexOf(close, index);
    if (nextClose < 0) return null;
    if (nextOpen >= 0 && nextOpen < nextClose) {
      depth += 1;
      index = nextOpen + open.length;
      continue;
    }
    if (depth === 0) return { start: nextClose, end: nextClose + close.length - 1 };
    depth -= 1;
    index = nextClose + close.length;
  }
  return null;
}

/**
 * 归一化入口：先补裸块级环境的 `$$` 定界符，再统一重写环境名。
 *
 * 幂等：补过 `$$` 的环境再次进入时已被数学定界符区间保护，不会重复包裹。
 */
export function normalizeLatexEnvironments(content: string): string {
  if (!content.includes("\\begin{")) return content;

  const codeBlocks = collectRanges(content, CODE_BLOCK_REGEX_SOURCE);
  const mathSpans = collectRanges(content, MATH_SPAN_REGEX_SOURCE);
  const protectedRanges = [...codeBlocks, ...mathSpans];
  const isProtected = (position: number) => inRanges(protectedRanges, position);

  // 重命名是定长替换，位置在重命名后的文本上一一对应，故先重命名再补定界符。
  const renamed = renameLatexEnvironments(content, (position) => inRanges(codeBlocks, position));

  const edits: Array<{ start: number; end: number; replacement: string }> = [];
  const beginRegex = new RegExp(BEGIN_REGEX_SOURCE, "g");
  let match: RegExpExecArray | null;
  while ((match = beginRegex.exec(renamed)) !== null) {
    const beginPos = match.index;
    const environment = match[1];
    if (!DISPLAY_MATH_ENVIRONMENTS.has(environment)) continue;
    if (isProtected(beginPos)) continue;

    // 只处理「独占行首」的环境：保证插入的 $$ 落在行首，remark-math 才认。
    const lineStart = renamed.lastIndexOf("\n", beginPos - 1) + 1;
    const indent = renamed.slice(lineStart, beginPos);
    if (/[^ \t]/.test(indent)) continue;

    const endRange = findEnvironmentEnd(renamed, environment, beginPos + match[0].length);
    if (endRange === null) continue;
    if (isProtected(endRange.start)) continue;

    // 列表项内缩进必须保留：$$ 掉到列 0 会让数学块脱离列表项。
    const body = renamed.slice(beginPos, endRange.end + 1);
    edits.push({ start: lineStart, end: endRange.end, replacement: `${indent}$$\n${indent}${body}\n${indent}$$` });
    // 跳过整个环境体，避免同一段被重复当作裸环境处理。
    beginRegex.lastIndex = endRange.end + 1;
  }

  if (edits.length === 0) return renamed;

  let result = "";
  let cursor = 0;
  for (const edit of edits) {
    result += renamed.slice(cursor, edit.start) + edit.replacement;
    cursor = edit.end + 1;
  }
  return result + renamed.slice(cursor);
}
