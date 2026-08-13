# RikkaHub TikZ 图表渲染：Tectonic 中文路线探索与 Android 集成失败复盘

> 日期：2026-08-13 ~ 2026-08-14
> 状态：**暂时放弃**（代码已回退到 v2.4.5-androidrik.19 无 TikZ 状态，历史与记录保留）
> 目的：沉淀"为什么走不通"的技术事实，为未来重启提供完整决策依据。

---

## 一、背景与需求

RikkaHub 需要渲染 AI 输出的 TikZ 图表代码，核心痛点是**中文支持**。

触发形式：
- ` ```tikz ` / ` ```tikzpicture ` / ` ```tikzcd ` / ` ```tikztiming ` 代码围栏
- `$$...$$` 内嵌 `\begin{tikzpicture}` / `\begin{tikzcd}` / `\begin{axis}` / `\xymatrix` / `\begin{CD}`

**既有方案 jsTeX**（.20 草稿）：英文可用、**中文空白**、真机报 `btoa`/字体错。jsTeX 基于 tikzjax（WebView 内跑 wasm+worker），中文是硬限制。

---

## 二、技术选型调研

### 2.1 Tectonic wasm（glyphtex-engine）

| 项 | 结论 |
|---|---|
| 引擎 | **XeTeX + xdvipdfmx**，现代 Unicode TeX |
| 中文支持 | ✅ 原生 UTF-8 + 系统字体，理论完美 |
| 浏览器可用 | ✅ glyphtex-engine（TS 封装，`addFile` + `compile` + `pdf()`） |
| 主线程运行 | ✅ `WebAssembly.instantiate`，无需 worker |
| **输出格式** | ❌ **PDF**（不是 SVG）——这是决定后续走向的关键架构约束 |

### 2.2 PDF → 可展示图的转换：三条路线的可行性

| 路线 | 结论 | 原因 |
|---|---|---|
| **MuPDF SVG（JNI）** | ❌ 不可行 | native .so 有 `fz_new_svg_device` C 符号，但**无 SvgDevice JNI 类注册**，且 .so 是预编译的（无源码/编译链）**无法重编补 JNI** |
| **MuPDF 纯 Java SVG Device** | ⚠️ 工程量大 | `Device` 是可继承抽象类（官方注释明确支持），但需实现 fillPath/fillText/fillImage 等 20+ 方法，相当于手写 SVG 渲染器，风险高 |
| **PdfRenderer 位图** | ✅ **零依赖** | Android 内置（API 21+），PDF 页 → Bitmap，简单可靠，代价是非矢量 |

**决策**：先做 **PdfRenderer 位图版**（Tectonic 中文已证明可行，位图能立刻交付闭环）；MuPDF SVG 留作后续矢量优化。

---

## 三、中文突破：`\font"[字体文件]"` 方括号语法

### 3.1 遇到的障碍

- glyphtex bundle（13.4MB）**无 fontspec / xeCJK / 中文字体**（精简版，缺 l3kernel 依赖树）
- 补依赖树需从 CTAN/TeX Live 抓包，**网络环境对 LaTeX 生态访问受限**，逐个试源效率极低
- Tectonic 的 IoProvider 是自研的（`io = { onStdout, onStderr }`），不实现 fontconfig 需要的 `opendir/readdir` → **注入 fonts.conf + 字体目录扫描的方式走不通**

### 3.2 决定性突破

XeTeX 原生支持**方括号字体语法**，直接按文件路径打开字体，**绕过 fontconfig 与 fontspec/xeCJK 依赖树**：

```latex
\font\zhfont="[simhei.ttf]"   % 直接打开注入的字体文件
```

验证结果：

| 验证项 | 结果 |
|---|---|
| XeTeX 原生中文（UTF-8） | ✅ `status=spotless`，PDF 5265 bytes |
| 字体注入 | ✅ `addFile('simhei.ttf')` + `\font"[simhei.ttf]"` |
| 中文在 TikZ 节点 | ✅ `\node ... {\zhfont 开始}` 编译通过 |
| **无 fontspec 依赖** | ✅ 原生 XeTeX 语法，完全绕开缺失的依赖树 |

> **洞察**：中文支持方案比预想简单得多——**不需要 fontspec / xeCJK**。这是 Tectonic 路线的核心价值所在，headless 验证完整可行。

### 3.3 POC 完整验证（headless）

```
B7: status=spotless  outputs=["log","pdf","xdv","other"]
B9: PDF bytes=3523   （英文，~0.6s）
```

| 验证项 | 结果 |
|---|---|
| wasm 加载 | ✅ 主线程，无 worker |
| bundle 注入 | ✅ 1130 文件解 tar → addFile 全部 |
| 英文 TikZ 编译 | ✅ spotless，PDF 3523 bytes |
| 编译耗时 | ~0.6s |
| 内存 | ✅ bundle 解压 49MB + wasm，正常（**修正了"内存是瓶颈"的早期误判**） |
| 中文 | ✅ 方括号字体语法，spotless |

---

## 四、PdfRenderer 位图版实现

### 4.1 资产（约 27MB）

```
app/src/main/assets/html/
├── tectonic_wasm.wasm           3.5MB   Tectonic XeTeX 引擎
├── tectonic-bundle.tikzbundle   13.4MB  TeX 文件包（gzip，解压后 49.5MB / 1130 文件）
├── simhei.ttf                   9.7MB   中文字体（黑体）
├── engine.js                           glyphtex wrapper（加载/注入/编译）
├── imports.js                          wasm 宿主导入（wasi/env）
├── pako.min.js                         gzip 解压
└── tecton.html                         编译页（JS 管线）
```

### 4.2 Kotlin 层

- `utils/PdfRenderer.kt`：`PdfToBitmap.renderFirstPage(pdf, targetWidth)` — Android 内置 PdfRenderer 渲染首页为 Bitmap，临时文件自动清理，分辨率对齐屏幕物理宽
- `TikzJax.kt`：WebView 加载 `https://appassets.androidplatform.net/assets/html/tecton.html#base64url`（真实 https origin，提供 secure context）→ JS 解 bundle + 注入字体 + 编译 → `onPdf(base64)` 回调 → PdfToBitmap → Compose `Image` 展示
- 线程修复：`@JavascriptInterface` 回调在 JavaBridge 线程，Compose state 更新切主线程（`Handler.post`）

### 4.3 编译验证

`BUILD SUCCESSFUL`，三个架构 APK 均生成，Tectonic 资产 STORED 打包正确。发布草稿 v2.4.5-androidrik.21。

---

## 五、Android 真机失败：三轮排查记录

### 现象

真机安装后，TikZ 图表一直显示"渲染中"，最终超时报 **「JS 未执行」（status=null，无任何进度回调）**。

### 排查 1：JS 链路本地复现（排除 JS 层）

用 Edge headless 直接跑 `tecton.html`（同一份资产、同样的中文 TikZ）：

```
id="status">完成 (2225 bytes)
```

**JS 链路完全正常**（解 bundle、注入字体、编译、PDF 输出全通）→ 问题在 **Android 集成层**。

### 排查 2：MIME 假设（appassets 改手动拦截）

- 线索：真机 `favicon.ico` 请求报 `ERR_CONNECTION_CLOSED`（根路径不匹配 `/assets/` 拦截器，属正常噪音），但说明主 frame `tecton.html` **加载成功**了
- 假设：`WebViewAssetLoader.AssetsPathHandler` 用 Android `MimeTypeMap` 推断 MIME，`.js`/`.wasm` 常返回 null/错误类型 → ES module `import` 严格校验 JS MIME → `engine.js` 加载失败 → module 不执行
- 修复：appassets 与 rikkahub.local 统一走**手动拦截 + 自定义 MIME 表**（`js→text/javascript`、`wasm→application/wasm`、`ttf→font/ttf`），移除 WebViewAssetLoader
- **结果**：仍「JS 未执行」

### 排查 3：module 假设（全改普通 script）

- 线索：mermaid（**普通 `<script>`**）在真机正常，而 `tecton.html` 用 `<script type="module">` + `import` 静默失败 → 普通 script 保证执行、module 在拦截响应下不可靠
- 修复：`engine.js`/`imports.js` 去 `import/export`（`#private` 改普通字段），`tecton.html` 改普通 script 标签顺序加载（pako → imports → engine → 主逻辑），并加 **`script-started` 兜底回调** + `document.title` 进度标记 + 去 ES2020 可选链/空值合并
- 本地验证：headless 通过（`完成 (2225 bytes)`）
- **结果**：真机**仍「JS 未执行」，连 `script-started` 兜底都没触发**

### 排查结论

**问题比 JS 层更深**。`script-started` 兜底（普通 script 同步执行即回调）都没触发，说明 WebView 对"`loadUrl` 一个 https 虚拟域名下的 wasm 引擎页面"这个组合存在整体性限制——而非单纯 JS 语法/MIME/加载顺序问题。

与 mermaid 的差异对比：

| | mermaid（正常） | Tectonic（失败） |
|---|---|---|
| 加载方式 | `loadDataWithBaseURL`（baseURL 只做相对路径解析，不真正请求域名） | `loadUrl("https://appassets.androidplatform.net/...")`（真实 https 域名加载） |
| 执行内容 | 纯 JS 库（无 wasm、无大文件 fetch） | wasm 3.5MB + fetch 13.4MB bundle + 9.7MB 字体 |
| JS 形态 | 普通 script | 曾 module，改普通 script 后仍失败 |

根因疑点排序（待验证）：
1. WebView 对**拦截响应下 wasm 引擎 + 大文件 fetch 组合**的内存/执行限制
2. `loadUrl` https 虚拟域名在真机上的安全/证书行为（尽管 shouldInterceptRequest 返回 response）
3. 设备 WebView 版本能力（未确认，但 mermaid 正常说明普通 script + 拦截通路是好的）

---

## 六、沉淀的关键技术事实（未来重启的依据）

### 已证明可行 ✅

1. **Tectonic 中文编译可行**：XeTeX 方括号字体语法 `\font"[simhei.ttf]"` 直接打开注入字体，免 fontspec/xeCJK 依赖树，中文 TikZ `spotless`
2. **内存不是瓶颈**：bundle 解压 49MB + wasm 编译 0.6s，Android 上可行（早期"512MB 满额"判断错误）
3. **英文 TikZ 全链路**：article + tikz → spotless → PDF
4. **glyphtex-engine API**：`addFile`（裸文件名，非 SHA-256 缓存格式）→ `compile({entry})` → `pdf()`，1130 文件 bundle 可整体注入

### 已验证不可行 / 高成本 ❌

1. **MuPDF SVG（JNI）**：.so 预编译无 SvgDevice JNI 注册，不可重编
2. **MuPDF 纯 Java SVG Device**：20+ 方法手写，工程量大、风险高
3. **fontconfig / fonts.conf 注入**：Tectonic IoProvider 不实现 opendir/readdir
4. **从 CTAN/TeX Live 补宏包**：网络对 LaTeX 生态访问受限
5. **Android WebView 跑 wasm 引擎（当前结论）**：三轮排查未解决，普通 script 兜底都不触发

### 未来重启可选方向

| 方向 | 中文 | 输出 | 成本 | 说明 |
|---|---|---|---|---|
| **JVM 渲染**（PdfBox/SVG） | ✅ | 矢量 | 中 | 不用 WebView，纯 Kotlin/Java 渲染 PDF→图，可控性强，**推荐首选** |
| workspace pdflatex + pdftocairo | ✅ | SVG | 高 | 系统级 TeX Live + poppler，完整但重（GB 级） |
| 回归轻量 jsTeX（无中文） | ❌ | SVG | 低 | 若无中文需求可回退 |

---

## 附录：参考实现细节

### tecton.html 编译管线（headless 验证过的核心逻辑）

```js
// 1. 解压 bundle
const gz = new Uint8Array(await (await fetch('./tectonic-bundle.tikzbundle')).arrayBuffer());
const tar = pako.ungzip(gz);
const files = parseTar(tar);            // UStar 平铺 tar → {文件名: bytes}

// 2. 加载 wasm + 注入全部文件
const engine = await TexEngine.load(await fetch('./tectonic_wasm.wasm'));
engine.addFiles(files);

// 3. 注入中文字体
engine.addFile('simhei.ttf', new Uint8Array(await (await fetch('./simhei.ttf')).arrayBuffer()));

// 4. 组装 tex：article + tikz + 用户代码
const tex = `\\documentclass{article}\n\\usepackage{tikz}\n\\begin{document}\n${tikzCode}\n\\end{document}`;
engine.addFile('main.tex', tex);

// 5. 编译 + 取 PDF
const result = engine.compile({ entry: 'main.tex' });   // status: spotless / errors / failed
const pdf = engine.pdf();
```

### 中文字体注入的关键语法

```latex
\font\zhfont="[simhei.ttf]"    % 方括号 = 按文件路径打开，绕过 fontconfig
\begin{tikzpicture}
  \node[draw] {\zhfont 开始测试};
\end{tikzpicture}
```

---

*本文档由探索全程记录整理（参考：Tectonic wasm 调研 / 中文宏包注入 / POC 验证 / MuPDF 决策 5 份记录 + 本轮 Android 集成实现与排查），代码已回退，结论与事实供未来决策复用。*
