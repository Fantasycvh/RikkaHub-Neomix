# RikkaHub TikZ 图表渲染：Tectonic 中文路线探索与 Android 集成失败复盘

> 日期：2026-08-13 ~ 2026-08-14
> 状态：**WebView 路线放弃；2026-08-28 完成重启评估（见文末第八节），倾向方案 B（native Tectonic + dvisvgm）**
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

**待办（第 3/4 步）**：dvisvgm 矢量路径（替换 PdfRenderer 位图）；isolatedProcess 隔离编译进程；UI 层人工验证；xeCJK 进 bundle（正全中文渲染）。

---

*本文档由探索全程记录整理（参考：Tectonic wasm 调研 / 中文宏包注入 / POC 验证 / MuPDF 决策 5 份记录 + 本轮 Android 集成实现与排查 + 2026-08-28 重启评估），结论与事实供未来决策复用。*
