import { defaultSchema } from "rehype-sanitize";

/**
 * LLM 输出原始 HTML 的清洗白名单（`rehypeSanitize` 用）。
 *
 * [SECURITY] 为什么必须清洗：`markdown.tsx` 用 `rehypeRaw` 把回复里的任意 HTML 解析成真实
 * 元素；而该组件**自定义了 `rehypePlugins`**，这会让 Streamdown 自带的
 * `[raw, sanitize, harden]` 默认三件套**整体失效**（默认值只在未传 prop 时生效）。若不显式
 * 补 `rehypeSanitize`，一个 `<img src=x onerror=...>` 就能在 Web UI 里执行脚本，而
 * `app/services/api.ts` 把 JWT 存在 `localStorage` ⇒ 可直接读走令牌、拿到内嵌服务器
 * （`web` 模块）的完整 API 权限，点击型 `<a href="javascript:...">` 亦可执行。
 *
 * 基线是 `defaultSchema`（禁 script/iframe/object/embed/form/meta/link/base，且高危协议被禁：
 * `href` 仅 http/https/irc/ircs/mailto/xmpp，`src` 仅 http/https）。在此之上的定制都对应
 * **本页既有的渲染需求**，逐条说明：
 *
 * - `className`（全元素）：KaTeX 公式体系（`.katex` / `.strut` 等）、`citation-badge`、
 *   以及代码块 `language-*`（`CodeBlock` 靠它识别语言并交给 Shiki 高亮）——**不加则公式与
 *   代码高亮全废**。
 * - `style`（**仅 `span`/`td`/`th`**）：KaTeX 的 strut 行高与间距是内联样式，且只出现在
 *   这三类元素上。刻意**不**用「`*` 全放开」——那会留下整页遮罩式钓鱼（`position:fixed;
 *   inset:0`）的注入面。
 * - `a` 的 `target`/`rel`：外链新窗口（组件内已固定 `noopener noreferrer`）。
 * - `img` 的 `loading`：懒加载。
 * - **MathML 标签**：KaTeX 默认 `htmlAndMathml` 输出含 `<math>` 子树。默认 schema 不含这些
 *   标签，它们会被**展开成裸文本**（无障碍信息变成可见的原始 TeX），故显式加入。
 *   注意**不加 SVG**（`<svg>` / `<animate>` 是经典 XSS 面，KaTeX 也不需要）。
 */
export const MARKDOWN_SANITIZE_SCHEMA = {
  ...defaultSchema,
  tagNames: [
    ...(defaultSchema.tagNames ?? []),
    // MathML（KaTeX 输出）
    "math", "semantics", "annotation", "annotation-xml", "mrow", "mi", "mn", "mo",
    "ms", "mtext", "msup", "msub", "msubsup", "mfrac", "msqrt", "mroot", "mtable",
    "mtr", "mtd", "mover", "munder", "munderover", "mspace", "mpadded", "mphantom",
    "menclose", "mstyle", "merror", "mlabeledtr", "mmultiscripts", "mprescripts",
    "none", "maction",
  ],
  attributes: {
    ...defaultSchema.attributes,
    "*": ["className"],
    span: ["style"],
    td: ["style"],
    th: ["style"],
    a: [...(defaultSchema.attributes?.a ?? ["href", "title"]), "target", "rel"],
    img: [...(defaultSchema.attributes?.img ?? ["src", "alt", "title"]), "loading"],
  },
};
