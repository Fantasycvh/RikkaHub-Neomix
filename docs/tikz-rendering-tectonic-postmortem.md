# RikkaHub TikZ 图表渲染：Tectonic 中文路线探索与 Android 集成失败复盘

> 日期：2026-08-13 ~ 2026-08-14（初稿）
> 状态：**WebView 路线放弃；已重启并完成落地（2026-08-28 起，最终链「native Tectonic + mupdf SVG 桥」）**——`dvisvgm` 矢量链已废弃，SVG 由自写 JNI 桥调 mupdf `fz_new_svg_device`（`TEXT_AS_PATH`）生成；含 preview 裁剪（`CACHE_VERSION v5`）+ `#hex` 运行时归一化 + 字体 bundle 补全。落地清单见「八」8.4.9/8.4.10；**改渲染输出必递增 CACHE_VERSION**。
> 目的：沉淀"为什么走不通"的技术事实 + 重启后的完整落地记录。

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

## 八、重启评估（2026-08-28）

### 8.1 现状：纯 Kotlin 子集渲染器已成为第三条路线（文档前七节未覆盖）

复盘后 fork 内长出了当前主线（未回退，持续演进）：

- `app/src/main/java/app/androidrik/rikkahub/tikz/TikzRenderer.kt`（~1200 行）：**纯 Kotlin TikZ 子集解析器**，TikZ 源码 → SVG 字符串。覆盖 MVP 子集：`node/coordinate/draw/circle/rectangle/grid/arc/plot/cycle`、`to[bend]`、`.. controls ..`、极坐标/命名坐标/锚点、三种箭头、`fill/thick/dashed/rounded corners`、`tikzcd` 交换图，中文文本原样输出。
- `ui/components/richtext/TikzJax.kt`（文件名为历史遗留）：TikzRenderer → SVG → AndroidSVG → Picture → Compose Canvas **纯矢量**绘制；无 WebView/wasm/JS；SVG 字符串同时作为导出产物。
- 防线内建：`MAX_SVG_PIXELS = 20_000`、`MAX_PLOT_ABS = 5_000`（对应第五节崩溃问题的修复，commit `e5f0fcba`/`9e8e89fd`）。

**四个原始痛点的满足情况**：中文 ✅、矢量 ✅、离线 ✅、同步零依赖 ✅。短板是**覆盖面**——子集之外的语法直接渲染失败，而 AI 输出常踩：positioning 库（`right=of A`）、`\tikzset`/`foreach`/`scope`/`fit`/矩阵、pgfplots（`\begin{axis}`，轴刻度计算必须真 TeX 引擎）、node 文本内 LaTeX 数学（当前 `<text>` escapeXml 直出，观感最差）。

旧 WebView/tikzjax 路线（无中文）已彻底移除，不回头。

### 8.2 路线对比

| 路线 | 完整性 | 中文 | 输出 | 成本 | 结论 |
|---|---|---|---|---|---|
| **A. 继续扩展纯 Kotlin 渲染器** | 子集，pgfplots 类永久不可达 | ✅ | 矢量 SVG | 低（渐进） | 主线维持，但需数据驱动 |
| **B. native Tectonic + dvisvgm（JNI，绕开 WebView）** | 任意 TikZ/pgfplots | ✅（已验证） | **真矢量 SVG** | 中高 | **倾向选择，见 8.4** |
| C. workspace 沙箱内 TeX（rootfs 装 tectonic/dvisvgm） | 完整 | ✅ | SVG | 高（依赖 workspace 初始化 + 几百 MB） | 不进渲染管线；定位为 agent 能力 |
| D. 修复 WebView 路线（tectonic wasm + localhost Ktor HTTP） | 完整 | ✅ | PDF→位图（非 SVG） | 中 | B 的严格下位替代，放弃 |

补充事实（相对第七节"未来方向"表的更新）：

- **B 的原生编译有先例**：Tectonic（Rust）在 Termux 有 aarch64 打包，cargo-ndk 交叉编译走得通；dvisvgm（C++）依赖 freetype/potrace（可选），交叉编译可行。
- **TikZ 自带 dvisvgm driver**（SVG specials 写入 DVI），**不需要 ghostscript**；`dvisvgm --no-fonts` 把文本转轮廓 path，中文完美显示。
- **复盘资产直接复用**：离线 bundle（13.4MB / 1130 文件，headless 验证过）、中文字体注入语法 `\font"[simhei.ttf]"`、glyphtex 引擎侧验证结论。
- **隔离先例**：SoLab 的 `BlutterRunnerService` 模式（`isolatedProcess="true"` + AIDL + ParcelFileDescriptor，每作业一进程用完即弃）是 native 编译崩溃隔离的现成方案。
- WebView 失败根因（`script-started` 都不触发）仍未诊断；fork 虽新增了内嵌 Ktor web server 可消除拦截变量，但 WebView+大资产行为仍是未知数，且 D 输出非矢量——不值得再投入。

### 8.3 立即可做的低成本项（与路线选择解耦）

1. **失败埋点**：TikzRenderer 失败路径按原因分类记录（positioning/foreach/axis/语法其他），用真实失败分布决定 A 路线的扩展优先级。
2. **node 文本接 jlatexmath**：数学公式是当前观感最差的短板（LaTeX 源码直出）。
3. **内置系统提示引导**：引导模型输出受支持子集（如 positioning 改显式坐标）——同类先例 `d0ec2e2a`（LaTeX 定界符提示）。

### 8.4 决策与方案 B 实施草案

**决策：倾向方案 B**（native Tectonic + dvisvgm 矢量管线），A 路线维持现状不再主动扩展（埋点观察）。

#### 8.4.1 第 1 步 POC 结果（2026-08-28，已完成 ✅）

POC crate：`<work>\projects\tectonic-poc\`（WSL 内构建于 `~/tex-poc`）。

**已验证结论：**

| 验证项 | 结果 |
|---|---|
| 离线 bundle（tar.gz 1130 文件）内存注入 + 自定义 IoProvider | ✅ |
| 英文 TikZ → PDF | ✅ 4247 bytes，6.0s（含 format 重建） |
| 中文 TikZ（`\font\zhfont="[simhei.ttf]"` + 节点中文） | ✅ 5886 bytes，554ms（format 缓存命中后） |
| format 版本兼容 | ✅ 移除历史 `latex.fmt`，由当前引擎从 `latex.ltx` 自动重建（合成 `tectonic-format-latex.tex` 入口文件） |
| 字体文件经 IoStack 加载 | ✅ tectonic 0.17 的 `createFontFromFile` 走 `engine.input_open()`，内存 bundle 可命中，无需 fontconfig 查找 |
| aarch64-linux-android 交叉编译 | ✅ `libtex_poc.so`（13.4MB，C ABI：`poc_compile`/`poc_free`）+ CLI 均链接成功 |
| 真机运行 | ⏳ 待设备接入（`adb devices` 当前为空） |

**关键实现事实（第 2 步集成依据）：**

1. **API 版本**：tectonic 0.17 的 `ProcessingSession` 只有 `run()` / `into_file_data()`；`input_open_name` 无 FileFormat 参数；`Bundle` trait 在独立的 `tectonic_bundles` crate（0.4.x），需实现 `get_digest`（POC 用全零摘要）+ `all_files`。
2. **format 自动重建**：`ProcessingSession::run` 在 `.fmt` 缺失时自动从 `latex.ltx` 构建（6s 一次性成本），之后复用缓存 ~0.55s/次。Android 侧应设置 `format_cache_path` 持久化首次构建结果。
3. **Android 交叉编译的依赖清单**（全部解决，脚本 `build-android.sh`）：
   - `libpng16.a` / `libfreetype.a` / `libgraphite2.so`：NDK cmake 工具链自行交叉编译 → `/root/android-libs` + 手写/生成的 `.pc`
   - `zlib`：NDK sysroot 自带头文件 + Android 系统 libz（手写 `zlib.pc`）
   - `ICU`：直接复用 **Termux 预编译包**（libicu 78.3 aarch64 deb，含 unicode 头），手写 `icu-uc.pc`/`icu-i18n.pc`——绕开 ICU 交叉构建的大坑
   - `fontconfig`：**stub**（真头文件 + Fc* 空实现静态库）——运行时字体不查 fontconfig，仅满足链接
4. **宿主 dev 包污染**：交叉编译前必须卸载宿主的 libgraphite2/libharfbuzz/libfontconfig/libfreetype/libpng `-dev` 包，否则 pkg-config 把 `-I/usr/include`（glibc 头）注入 Android 编译（fatal: bits/wordsize.h not found）。
5. **build script 缓存坑**：修改 pkg-config 目标后需 `cargo clean -p <对应桥crate>`，否则旧的 DEP_*_INCLUDE 缓存值继续生效。
6. **cargo-ndk v4.1.2 在本机 panic**（已知问题），改用 `~/.cargo/config.toml` 手动配 target linker，效果等价。

**POC 后的体积预算**：引擎 so 13.4MB + bundle 13.4MB + 字体 9.7MB ≈ 36MB（可按需裁剪：bundle 仅保留 tikz/article/amsmath 子集可显著缩小；ICU so 运行期另需 ~30MB 或改用数据裁剪）。

#### 8.4.2 第 2 步 app 集成结果（2026-08-29，已完成 ✅）

commit `83b23ca7`。**instrumented test 3 项全部通过（<device> 真机）**：

| 验证项 | 结果 |
|---|---|
| `compileChineseTikzOnDevice`：assets bundle+字体 → JNI → PDF | ✅ 4982 bytes |
| `compileIsDeterministicAcrossRuns`：两次编译输出一致 | ✅ |
| `invalidTexReturnsNullWithLastError`：坏 LaTeX → null + lastError | ✅ |

实现（均入 app 模块）：

- `tikz/TectonicNative.kt`：JNI 桥；bundle+字体从 assets 读入常驻内存（~23MB）；format 缓存落 `cacheDir/texfmt`（已验证生效：`<digest>-latex-33.fmt`，22MB）。
- `tikz/PdfToBitmap.kt`：PdfRenderer 首页位图（衬白底）。
- `TikzJax.kt` 三级回退链：TikzRenderer 矢量 → TectonicNative（LaunchedEffect 异步，不阻塞组合）→ 源码展示。
- jniLibs（arm64-v8a）：`libtex_poc.so` + `libc++_shared.so` + `libgraphite2.so` + ICU（Termux 78.3，patchelf 改名 `libicuuc78.so`/`libicudata78.so` 适配 AGP 的 .so 命名约束）+ fontconfig stub。

真机耗时（<device> / 骁龙 845）：format 首建后每次编译 **~3.6s**（bundle 加载 0.37s + 编译 ~3s）；下一次优化方向是 bundle 进程常驻与 isolatedProcess 隔离。

#### 8.4.3 扩展 bundle + 字体替换（2026-08-29，已完成 ✅，commit `6299c328`）

真机（<device>）暴露关键缺口后补全：

| 项 | 结果 |
|---|---|
| 精简 bundle 缺 tikz 库文件（`tikzlibrarypositioning.code.tex` 等）→ positioning 语法编译失败 | ✅ 从 pgf 3.1.12 TDS 补全全部库（tikzlibrary 77 + pgflibrary 47 + core 同版本对齐） |
| pgfplots 1.18.3 axis + 中文标签编译 | ✅ 4600 B / 3.8s |
| 字体 SimHei（9.7MB，**不可再分发**） | ✅ 换 Droid Sans Fallback（3.4MB，Apache 2.0），真机中文渲染通过 |
| bundle 体积 | 13.4MB → 14.5MB（1598 文件） |
| instrumented test | ✅ 4 项通过（positioning 片段 / pgfplots / 确定性 / 坏输入 null+lastError） |

新增机制：

- **wrapDocument**：TikZ 片段自动包装为完整文档（article + tikz + 常用库 positioning/arrows.meta/calc/shapes + 中文字体）；`
ormalfont` 重定义为 Droid Sans Fallback 使全文默认可渲染中文（POC 限制：无粗/斜体变体，xeCJK 待后续进 bundle）。
- **编译失败语义**：TeX 非交互恢复模式对含错误文档仍可能产出 PDF，故 keep_logs 保留 main.log，含 `! ` 错误行即判失败（修复"坏输入返回位图"问题）。
- 打包注意：重打 bundle 时 tar 成员不能带 `./` 前缀（Rust tar loader 按名字精确查找）。

**下载路线的决策反转**：本节期间曾实现按需下载（公开资源仓库 [rikkahub-resources](https://github.com/androidrik/rikkahub-resources) Release v1 + jsDelivr + 2 镜像 4 候选源 + TikzAssets 下载器，WSL 匿名下载校验通过），**按决策回退，改为全部内置 APK**——资源仓库保留作未来 bundle 升级路径（manifest 结构已验证可用）。当时遗留的坑（复用价值）：宿主 dev 包污染 pkg-config、build script 缓存、cargo-ndk v4 panic、AGP 拒绝版本化 .so 文件名（patchelf 改 NEEDED）、adb MSYS 路径转换。

#### 8.4.4 第 3 步 dvisvgm 矢量路径（2026-08-29，POC 完成 ✅，commit `e3a16196`）

**已验证**（真机 <device>，app 内全链路）：

| 项 | 结果 |
|---|---|
| XDV 输出（tectonic OutputFormat::Xdv，JNI format 参数） | ✅ 2628B |
| dvisvgm 3.2.1 aarch64 交叉编译（`--enable-bundled-libs --disable-woff`） | ✅ |
| kpathsea stub（真头文件 + 空实现；kpse_find_file 支持当前目录直查） | ✅ |
| **exec 路线被 SELinux 封死**（`execute_no_trans` 拒绝，MIUI 老内核；`/data` 无 noexec 但 app 域无 exec 转换权限） | ⛔ 改 JNI 化 |
| **libdvisvgmexec.so**（共享库 + `Java_..._dvisvgmMain` JNI 入口，chdir 后转 dvisvgm_main_impl） | ✅ SVG 真机产出 |
| version-script 符号隔离（1831 → 1 导出，防 kpse_/Fc 介入污染 tectonic） | ✅ |
| TikzJax 矢量优先回退链（子集 SVG → native XDV+dvisvgm SVG → PDF 位图 → 源码） | ✅ 代码入库 |
| instrumented test | ✅ 4 PDF 用例全绿；矢量用例单独验证通过 |

**关键技术点**：

1. **JNI argv 悬垂指针**：`vector<string>` 逐个 `emplace_back` + `push_back(c_str())` 交替，扩容后指针悬垂 → dvisvgm 解析垃圾参数 exit=-4。修复：先收集全部 string 再统一转指针。
2. **pgf 图形进 SVG 需 driver 声明**：`\def\pgfsysdriver{pgfsys-dvisvgm.def}`（wrapDocument 已内置），否则 dvisvgm 只输出文字（dvipdfm specials 不识别）。
3. **CM 字体**：texlive-fonts-recommended 的 `.pfb`（cmr10 等 7 个）+ 无扩展名副本（kpse stub 按原名 fopen）。
4. **编译失败语义**：keep_logs 后检查 main.log 的 `! ` 错误行（TeX 非交互恢复模式对坏输入仍产出 PDF）。

**已知限制（产品化阻断项）**：dvisvgm 与 tectonic 同进程共存，C++ 静态单例/流状态互污染——dvisvgm 运行后 tectonic 的 xdvipdfmx 后续编译失败（`ttbc_output_close` SIGABRT 或 unrecoverable error；version-script 符号隔离不解决）。临时缓解：`TectonicNative.dvisvgmUsed` 进程内单次限制。**根治方案 = isolatedProcess 隔离编译进程（第 4 步）**。

#### 8.4.5 第 4 步 双私有进程隔离（2026-08-29，完成 ✅，commit `9dee4f4c`）

**根治互污染**：TexCompileService（tectonic，`:texcompile` 进程）+ SvgConvertService（dvisvgm，`:svgconvert` 进程），Messenger 协议（MSG_RUN/MSG_RESULT），`IsoServiceClient` bindService+CountDownLatch 同步封装。native 崩溃只杀对应子进程。

**真机 5/5 全绿**（此前同进程 dvisvgm 后 tectonic 必坏）：

| 用例 | 结果 |
|---|---|
| positioning 片段（XDV→SVG 矢量链） | ✅ SVG 728B |
| positioning 片段（PDF 位图链） | ✅ 2245B |
| pgfplots axis | ✅ 4063B |
| 坏 LaTeX → null+lastError | ✅ |
| 确定性（两次编译一致） | ✅ |
| **SVG 转换后 5 次编译无互污染** | ✅（架构级验证） |

**三个坑（反复崩溃的排障记录）**：

1. **isolatedProcess 跑完整 Application**：RikkaHubApp 初始化（WorkManager 的 JobScheduler cast）在隔离环境直接崩 → `isIsolatedProcess()`（uid ≥ 99000）短路全部初始化。
2. **isolatedProcess 的 makeApplication 链路仍有问题**（handleCreateService 阶段 `getApplicationContext` on null）→ 放弃 isolatedProcess，改普通私有进程 `android:process=":texcompile"`（同 UID，Application 正常，隔离目标——防 native 崩溃拖垮主进程——不受影响；安全沙箱非本步目标）。
3. **Service 构造阶段 base context 未 attach**：`Messenger(IncomingHandler(this))` 写在字段初始化 → IncomingHandler 构造里 `applicationContext` NPE（反复崩溃的直接根因）→ messenger 懒初始化到 `onCreate`，Handler 提为文件级类。

#### 8.4.6 移除第①级子集渲染（2026-08-29，commit `0db5e6f7`）

**决策**：TikzJax 统一走 native 引擎（XDV→dvisvgm SVG 矢量 → PDF 位图 → 源码），TikzRenderer 从渲染链移除（文件与单测保留，可随时接回）。

**动机**：第①级的边界不是白名单而是"尽力解析"，存在"部分解析画错图"的静默错误类（positioning 重叠 bug 即此例——语法被当未知样式忽略，节点落原点，渲染"成功"但错误）。子集实现的正确性验证成本随语法数线性增长，native 引擎一个实现路径天然全语法正确。代价是简单图也需 3-6s 编译（异步渲染，体验可接受）。

**顺带修复**：
1. 删除 `dvisvgmUsed` 单次限制（双进程隔离前的互污染旧补丁，隔离后无必要；残留导致 svgconvert 进程第二次调用起全部走位图）。
2. `wrapDocument` 补 `\def\pgfsysdriver{pgfsys-dvisvgm.def}`：缺失时 pgf 写 dvipdfm PDF specials，dvisvgm 不识别 → 图形全丢只剩文字（T1 SVG 398B→1292B）。

**验证**：NativeOnlyTikzTest 真机 3/3（T1/T2/T3 positioning 全场景），SVG 浏览器渲染确认布局与图形完整。

**遗留**（非阻断）：进程驻留；xeCJK；LM 字体映射补全。

---

#### 8.4.7 收尾：第①级删除 + 缓存 + 渲染修复 + 发布（2026-08-29）

**后续完成的收尾工作**（均在 8.4.6 之后）：

| 提交 | 内容 |
|---|---|
| `d5118bbf` | 删除第①级遗留代码（TikzRenderer/TikzTelemetry/MathExpr + 4 单测；恢复从历史 `a10aaed5` 取回）；Export.kt（HTML 导出）TikZ 改走 native 同链路（suspend + 占位收集统一编译，失败还原为围栏源码） |
| `3b75520a` | **SVG 落盘缓存**（TikzSvgCache）：sha256(trim(code)) 内容寻址，原子写；TeX 确定性输出已验证，同源码必同产物。TikzJax/Export 共用。历史消息秒开 |
| `5a17ab40` | tikzcd 支持：bundle v3（+tikz-cd.sty，CTAN）+ wrapDocument 检测自动加载。**经验：宏包缺失症状 = terminal input forbidden** |
| `3b966c10` | 超长消息/思考部分渲染修复：HeightClamp 语义反转（测量约束原样传递，仅结果异常巨大时 clamp）——旧固定 10000dp 会把 >20 屏的正常内容静默裁剪 |
| `d2de1429` | 短思考后整屏空白：思考卡片 MarkdownBlock 误用 fillMaxSize（LazyColumn item 内=填满视口）→ fillMaxWidth |
| `ec99c43a` | 系统提示词：告知全语法支持（避免模型自我降级）+ axis 示例 |
| `fe580f09`+`00ee99fd` | versionCode 239 / versionName 2.4.10.15 草稿 + release notes；GitHub Release `v2.4.10.15-build239` |

**最终架构**（全仓库唯一 TikZ 路径）：

```
TikZ 输入（任意语法）
  ├─ TikzSvgCache 命中 → SVG（毫秒级）
  ├─ XDV 编译（:texcompile）→ dvisvgm（:svgconvert）→ SVG 矢量
  ├─ PDF → PdfRenderer 位图降级
  └─ 源码展示
聊天显示与 HTML 导出同链路同缓存。
```

**遗留**（非阻断，未排期）：进程驻留策略；xeCJK（粗体/斜体中文）；LM 字体映射补全（SVG 728B 量级偏小）；tikz_cache 无 LRU（可挂设置存储清理）。

#### 8.4.8 排雷：`#hex!opacity` 颜色语法边界（2026-08-29）

用户报告原版图 `fill[#4A90D9!30]` 导致整图失败、降级为源码。真机 native 引擎 + 宿主 Ubuntu TeX Live 2023/Debian（上游 pgf，非 fork bundle）双端对照，v1-v5 隔离用例结论完全一致：

| 语法 | 用例 | 真机 native | 宿主系统 TeX | 结果 |
|---|---|---|---|---|
| 颜色名 `blue` | - | ✅ | ✅ | 支持 |
| 颜色名+不透明度 `blue!30` | v3 | ✅ | ✅ PASS | 支持 |
| `#hex` 纯十六进制 `#4A90D9`（node fill） | v1 | ❌ 原始 | ❌ FAIL | **已由运行时归一化支持**（见下） |
| `#hex` 纯十六进制（draw） | v5 | ❌ 原始 | ❌ FAIL | 同上 |
| `#hex!opacity` `#4A90D9!30`（node） | v2 | ❌ 原始 | ❌ FAIL | 同上 |
| `#hex!opacity`（draw） | v4 | ❌ 原始 | ❌ FAIL | 同上 |

> **2026-09-08 更新**：上表是"原生进 TeX"的结果。现已落地**运行时归一化** `normalizeHexColors`（`TectonicNative.kt`）——渲染前把 options 内的 `#hex` 替换为 `\definecolor{hexN}{HTML}{RRGGBB}` + 引用名，真机验证 `#4A90D9`/`!30`/3 位 `#f80`/8 位 alpha 均可渲染（D 图通过）。下方"不支持/禁用"结论仅适用于"未经归一化直接进 TeX"的场景，保留作根因记录。

> **修正**：初判以为"`#hex` 纯十六进制可用、仅 `!NN` 组合失败"——真机隔离测试（v1 也 FAIL）与宿主对照（v1/v5 FAIL）均否定。**pgf options 里出现裸 `#` 一律失败**，`#hex` 与 `#hex!NN` 同等待遇。早期"`#hex` 可用"的印象来自已删除的第①级纯 Kotlin 渲染器自带的颜色解析模块（`#hex` 由其自行解析），native 路径没有这个模块——代码原样进 TeX。

**错误信息**（真机与宿主完全一致）：
- node 选项（`fill=#4A90D9!30`）→ `! Illegal parameter number in definition of \tikz@temp.`
- draw 选项（`\draw[->, #4A90D9!60]`）→ `! Illegal parameter number in definition of \pgfkeyscurrentkey.`

**根因**：`#` 在 TeX 中是参数 token，pgf 解析 options 时把键值对展开进 `\tikz@temp`/`\pgfkeyscurrentkey` 这类内部宏定义，`#hex` 被当作非法参数号 → 解析崩 → 整图失败、TikzJax 降级源码。**这是 pgf/TeX 层通用限制，不是引擎 bug，也不是 fork bundle 缺宏包问题**——宿主上游 pgf 同文件同报错即铁证。

**替代方案**（推荐给模型/用户）：
1. `颜色名!NN`（`blue!30`、`red!20`）——效果最接近，已实测渲染正常
2. 预算混合后的纯 `颜色名` 或 `#hex` 仅在**非 options 位置**（如颜色字面量直接出现）考虑；options 内一律用颜色名

**排雷经验**：pgf options（`[...]`）里出现裸 `#` 一律视为雷区；`Illegal parameter number in definition of \tikz@temp` 报错第一反应检查 options 内是否含 `#hex`。已同步写入项目记忆 [[tikz-hex-color-opacity]]。

**顺带改进：native 错误详情透出**（lib.rs + TectonicNative.kt）。此前 JNI 失败只回传 `"unknown error"`/`"TeX errors detected in main.log"`，主进程拿到的是隔离进程丢失的错误——诊断要靠 CLI 复现。本次改动：
- `lib.rs tex_compile`：main.log 里取首条 `! ` 行拼 `TeX error: <detail>`（XeTeX 致命错时 session.run 先返回 "unrecoverable error"，但 main.log 仍有 `! ` 详细行，优先取它）；
- `TectonicNative.lastErrorText()`：隔离进程的错误文本经 `lastIsolatedError` 缓存回主进程（此前只读主进程 native 槽，隔离编译失败拿到的是空值）。现在编译失败 `lastErrorText()` 直接给出 `TeX error: Illegal parameter number in definition of \tikz@temp.`，提示词同步加了 `#hex` 禁用说明（GenerationHandler.kt）。

复现脚本（`<work>\projects\tectonic-poc\`）：`hex_host_matrix.sh`（宿主 TeX v1-v5 矩阵）、`hex_variants.py`（真机 v1-v5 隔离）、`test_hexop.tex`/`test_hexvariants.tex`（设备 CLI 复现）。


#### 8.4.9 dvisvgm 文字悬空真相 + 路线切换 mupdf SVG 桥（2026-09-07/08）

**会话排查结论（用户 A/B/C 三例截图 + 三轮真机取证实锤）**：

1. **文字悬空是 8.4.5 验收缺陷**：当时"矢量链 ✅"只验证了图形进 SVG，文字从未验证——日志 `SVG 728B` 量级偏小即为证（单个字形 path 就几百字节）。遗留清单里的「LM 字体映射补全」正是病名。
2. **三层障碍依次定位**：
   - dvisvgm 默认 glyph cache 写 `XDG_CACHE_HOME`/`~/.cache`——Android HOME 未设、路径不可写 → 轮廓转换静默失败但 rc=0（`--cache=<work>` 可缓解，但该 so 参数集窄，无 `--no-cache`/`--font-dir`）；**`--cache` 走 glyph cache 反而让字形错乱（实测只有拉丁字符、连英文都丢），是错误方向**。
   - 补 CM pfb 无济于事：tectonic bundle v3 的默认文本字体是 **Latin Modern**，XDV 记录 OT 名 `lmroman10-regular`（不是 cmr10）——assets 只随包 7 个 CM pfb 时 dvisvgm 按名找不到。
   - 补 LM **Type1 pfb** 也错：pfb 是 OT1 编码（TeX 字形索引），与 XDV 的 **OpenType glyph id** 错配 → 轮廓"转出但内容全错"。
3. **正确路线 = mupdf SVG 桥（已实现，取代 dvisvgm）**：tectonic 编译 PDF（必内嵌字体）→ `MupdfSvgBridge.pdfToSvg` 用 mupdf `FZ_SVG_TEXT_AS_PATH(0)`（非默认 TEXT_AS_TEXT）→ mupdf 从 PDF 内嵌字形直接提取轮廓输出 `<path>`——**中英文都渲染、与字体文件查找完全无关**。旧文本态（TEXT_AS_TEXT 输出 `<text>`，AndroidSVG 渲不了中文 glyph）是根因之一。
   - 相关提交：`1c09d5cd`（TEXT_AS_PATH + MathInline 补 TikZ 分发）、`92961b83`（cache v3）、`42ed36ef`（fence/complete/弧度归一化）、`5b063217`（selectfont 追 zhfont）、`6385a138`（cache v4 自动清空）、fork 会话补提交 `07a5661e`（MarkdownMathParseTest 取证）。
4. **现状**：SVG 矢量路径 = mupdf 桥（arm64-v8a；x86_64 自动落 PDF 位图链）；PDF 位图保留为降级。`wrapDocument` 已按 format 条件注入 dvisvgm driver 的版本一并废弃（PDF 用默认 dvipdfm 驱动）。

**排障工具链资产**（8.4.9 起可复用）：真机 root + run-as/`su` 拉缓存与 XDV、adb logcat TAG 过滤、SoLab MCP（`zhou.solab` :8800，so 字符串逆向——此轮靠它确认 dvisvgm 选项集）、CTAN 资产下载（amsfonts.zip / lm.zip）。

**决策**：dvisvgm 矢量链的"字形轮廓"目标被 mupdf 方案零成本超越，不再回去补字体映射。

#### 8.4.10 文字链收官 + SVG 裁剪 + #hex 运行时归一化（2026-09-08）

**A. 文字链三连修复（全部真机验证）**
1. **`\selectfont` 包装 `\zhfont`（`5b063217`）**：`\small`/`\textbf`/`\footnotesize` 等字号/字重切换都经 `\selectfont` 按默认族（cmr/lmroman）重设字体族 → 中文缺字形整行丢（转生图 `\textbf{前世}` 粗体标题空实锤）。修复：`\let\zholdselectfont\selectfont` + `\renewcommand{\selectfont}{\zholdselectfont\zhfont}`（末尾强制回 droidsans）；**勿改单个 `\small` 等命令**（破坏 tikz `font=` 选项展开）。
2. **bundle 缺数学字号 pfb → xdvipdfmx `ttbc_output_close` SIGABRT（`b9a7f620`）**：`\footnotesize $x$`（8pt 数学）需 cmmi8/lmmi8/lmsy8 等 pfb，bundle 只有 tfm 无 pfb → **崩溃无 TeX 报错文本、非 Missing、非语法**，直接退化源码。修复：从 WSL TeX 提取 **CM**（cmmi8/9/12/5/6、cmsy8/9/5/6、cmr6/8/9/12/17）与 **LM**（lmmi8/9/12/5/6、lmsy8/9/5/6、lmex10、lmmib5/7）pfb 入 bundle **tar 根路径**（kpse 直查）。**今后“编译崩溃无报错”第一反应查 bundle 字体 pfb 齐全性**（脚本比对 tfm 全集 vs pfb 全集）。
3. **完整 `\documentclass` #hex 盲区（`5e2c3858`）**：wrapDocument 先判 documentclass → 完整文档只做弧度归一化、**不碰 #hex**（用户自管 preamble，避免替换后无定义的 Undefined color）；裸片段才做 #hex 替换+注入。

**B. #hex 运行时归一化（`42ed36ef` 起，`72d5ee90` 扩展）**
- 方案：`normalizeHexColors` 把 options 内 `#hex` 替换为 `\definecolor{hexN}{HTML}{RRGGBB}`（xcolor HTML 命名）+ 引用名，保留 `!NN` 后缀（`hex0!30`）。
- 范围：6 位 `#RRGGBB`、3 位 `#RGB` 按位展开（`#f80`→`ff8800`）、8 位 alpha 取前 6、**仅 `[...]` 选项上下文替换**（逐字符扫描，避免 node 文本内 #hex 误伤）；完整文档不碰（见 A3）。
- 真机：D 图（`#4A90D9`/`#E85D75`/`#7ED321`/`#F5A623`）通过。
- **残留盲区**（低频，未处理）：多层 `!30!50`（第二段应为颜色名）、`hexN` 命名冲突、8 位 alpha 的 alpha 语义（pgf 本身不支持）。

**C. SVG 裁剪到图 bbox（`89233a77`，v2.4.10.22）**
- 问题：wrapDocument 用 `article` 整页（612×792pt），PDF→SVG 后四周大空白、图居中占小块。
- **方案取舍**：math-plus 用 `standalone[border]`，但其 bbox 裁剪依赖外部 `pdfcrop`（设备 tectonic 无此工具）→ **方案 B 在设备上伪可行**，否决。改用 **preview.sty + tightpage**（纯 TeX 层改 `\pdfpagewidth/height`，WSL 验证裁出 58×58pt / 171×185pt）。
- **实现**：bundle 根路径补 `preview.sty`/`prtightpage.def`/`prauctex.cfg`（**kpse stub 只按文件名直查根目录——首次放 `tex/latex/preview/` 子目录导致 `preview.sty not found`，是上次“全渲染失效”的根因，已修**）；wrapDocument 注入 `\usepackage[active,tightpage]{preview}` + `\PreviewEnvironment{tikzpicture}`/`{tikzcd}`。
- **缓存联动**：裁剪改变渲染输出 → `CACHE_VERSION` 递增 v4→v5（**改渲染输出必递增，否则历史消息命中旧 A4 SVG**——`5b063217` 漏递增的教训）。

**教训沉淀**：①“编译崩溃无报错”→ 查 bundle 字体 pfb 齐全性；②bundle 补文件必须放根路径；③改渲染输出必须递增 CACHE_VERSION；④二分崩溃用 instrumented `BisectTest`（一次装 APK 跑完全部组合）高效。
