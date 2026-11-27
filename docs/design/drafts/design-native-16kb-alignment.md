# 原生库 16 KB 内存页对齐与 TikZ 加载收口设计稿

日期：2026-11-27。状态：draft，已复核并修订，待择机实施；本轮仅修订设计文档。关联：[dsh-Go §7.1 16 KB 实证候选](../../references/other%20projects/dsh-go.md)、[TikZ Tectonic 复盘 §8.4](../../tikz-rendering-tectonic-postmortem.md)、[peer-verdicts §六](../../peer-verdicts-2026-11-08.md)。

## 一、问题与全仓实测现状

本仓 `targetSdk = 37`、`compileSdk = 37.2`。2026-11-27 对本机现有 release 合并产物 `app/build/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib` 下的全部原生库（`arm64-v8a` 18 个、`x86_64` 12 个，含 AAR 传递带入的 `libsqlite3x.so`、`libink.so`、`libquickjs-android-wrapper.so` 以及子模块 CMake 编译的 `libtermux.so`、`libworkspace.so`）逐一做 ELF64 程序头核验（本轮未重新构建，最终发布 APK 仍须独立核验）：

- **合格（`PT_LOAD` `p_align ≥ 0x4000` 且 `p_offset ≡ p_vaddr (mod 0x4000)`）**：`x86_64` 全部 12 个；`arm64-v8a` 其余 15 个（含 `libmupdf_java.so`、`libsimple.so`、`libproot_exec.so`、`libproot_loader.so`、`libicuuc78.so`、`libicudata78.so`、`libc++_shared.so`）。
- **不合格（`p_align = 0x1000` 且多段同余不满足 16 KB）**：仅 `app/src/main/jniLibs/arm64-v8a/` 下 3 个手工预编译库：

| 库 | 当前体积 | 不满足 16 KB 同余的 `PT_LOAD` 段数 | 构建来源 | 当前动态依赖（`DT_NEEDED`） |
| --- | --- | --- | --- | --- |
| `libpdf2svg_bridge.so` | 13 KB | 3 / 4 段不满足同余 | `app/src/main/cpp/pdf2svg_bridge.c`（单文件 C，NDK r27c 手工编译） | `libmupdf_java.so`、`liblog.so`、`libdl.so`、`libc.so` |
| `libgraphite2.so` | 1.5 MB | 2 / 3 段不满足同余 | WSL `/root/android-libs` NDK r27c 交叉编译 | `libm.so`、`libdl.so`、`libc.so` |
| `libtex_poc.so` | 13.5 MB | 3 / 5 段不满足同余（第 5 段为 `patchelf` 追加的 `0x10000` 段） | `tools/tex-poc/build-android.sh`（Rust + Tectonic 0.17 + NDK r27c + `patchelf`） | `libc++_shared.so`、`libicuuc78.so`、`libgraphite2.so`、`libz.so`、`libdl.so`、`libm.so`、`libc.so` |

源码核查确认 `app/src/main/cpp/pdf2svg_bridge.c` 与 `tools/tex-poc/src/lib.rs` 均无硬编码 `4096` / `PAGE_SIZE` 的页大小假设。

**16 KB 页 arm64 设备上的风险与验证盲区（以下加载失败路径为代码分析，未在本轮做 16 KB 设备实测）**：

1. **加载失败后的静默降级风险**：若 Bionic 因页对齐拒绝加载并抛出 `UnsatisfiedLinkError`，当前 `TectonicNative` 与 `MupdfSvgBridge` 的探测会返回 `available = false`；聊天可能退化为源码展示，导出在无 SVG 缓存时可能返回 `null`。Android 提供 16 KB 页大小兼容模式，部分 4 KB 对齐应用仍可运行，因此不能声称所有 16 KB 设备必然失败，也不能把兼容模式下的成功算作原生兼容通过。
2. **设备单测假绿盲区**：`TectonicNativeTest` 用 `assumeTrue("libtex_poc.so not available on this ABI", TectonicNative.available)` 过滤非 arm64 架构；在 16 KB arm64 真机上 `dlopen` 失败同样令 `available == false`，导致整套测试被标记为 `ASSUMPTION VIOLATED` 跳过而非失败，掩盖加载故障。

---

## 二、历史提交约束与运行期红线

方案设计必须遵守以下由历史提交确立的运行期边界，不能照搬通用 16 KB 迁移文档的默认建议：

| 编号 | 历史提交 / 来源 | 运行期事实与约束 | 设计红线 |
| --- | --- | --- | --- |
| C1 | PRoot 沙箱演进（`ProotShellRunner.kt` / `WorkspaceTerminalSession.kt`） | `libproot_exec.so` 与 `libproot_loader.so` 是伪装成 `.so` 的 Linux 可执行体；Android 10+ 禁止从 `filesDir` `execve`，全靠 `useLegacyPackaging = true` 在安装期把它们解压到拥有可执行 SELinux 标签（`apk_data_file`）的 `nativeLibraryDir`。 | **`app/build.gradle.kts` 的 `useLegacyPackaging = true` 不能改为 `false`**；解压到磁盘不消除 ELF 对齐要求，原生兼容验收仍须关闭页大小兼容模式。 |
| C2 | `7f3fb912f6`（mupdf 接管 SVG）+ `393e3a7915`（拆出 `ExportTikz.kt`） | `:document` 同时含双 ABI MuPDF，而 TeX 和 SVG 桥仅有 arm64。导出未命中缓存时只判断 `MupdfSvgBridge.available`，其探测当前即会先尝试加载 MuPDF，再加载桥；若误产出 x86_64 桥，还会通过守卫并无谓启动缺少 TeX 库的 `:texcompile`。 | **桥接 target 限定 arm64；导出先检查 TeX 随包存在，再检查转换端，避免无 TeX 的 ABI 因本功能加载 MuPDF 或启动服务**。 |
| C3 | `959f50fb62`（引入 Tectonic + ICU 78） | 仓内 `libc++_shared.so` 为 NDK r27c（Clang 18），而 `libicuuc78.so` 为 Termux Clang 21 编译，二者在 `:texcompile` 进程内共享同一份 `libc++_shared.so`；`libicuuc78.so` / `libicudata78.so` 已是 16 KB 合格件（内部 `DT_SONAME` 仍为 `.so.78`，Bionic 按文件名命中）。 | **`pdf2svg_bridge` 必须声明为纯 `C` 工程（不引入其他 NDK 的 `libc++_shared.so`）；不动仓内 `libicuuc78.so` / `libicudata78.so` / `libc++_shared.so` / `libmupdf_java.so`**。 |
| C4 | `dfbfe222e4`（`:texcompile` 私有进程隔离）+ `6d196c64b6`（首帧同步读缓存） | 编译虽隔离在 `:texcompile` 子进程，但 `TikzJax.kt:78` 在主进程 Compose 组合期调 `TectonicNative.available` → 直接触发主线程 `System.loadLibrary("tex_poc")`，导致主进程无谓加载 TeX/ICU 动态库（文件总体积约 50 MB，不等于实际 RSS）（缓存命中时甚至根本不编译）。 | 将「随包存在探测」（主进程免 `dlopen`）与「实际加载执行」（`:texcompile` 子进程 `dlopen`）拆开；Graphite2 静态化另作独立优化，不作为对齐修复的前置条件。 |
| C5 | 上游 `815e9c20b`（纯 Kotlin PDF 替换 mupdf） | 本仓 `libpdf2svg_bridge.so` 运行期强绑定 `libmupdf_java.so` 导出的 37 个 `fz_*` C 符号（见 `docs/upstream-analysis.md`）。 | 维持不跟上游删 `libmupdf_java.so`；桥接层 `DT_NEEDED` 必须保持裸名 `libmupdf_java.so`（禁带相对路径 `/`）。 |

---

## 三、已找回的 WSL 构建环境

2026-11-27 只读核查：`D:\A-projects-relay\WSL\Ubuntu\ext4.vhdx` 仍在；已进入现有 Ubuntu 发行版核对以下内容。无需从头搭建原生交叉编译环境。

| 项目 | 当前保留的位置与配置 |
| --- | --- |
| Android NDK | `/root/ndk`；`source.properties` 为 r27c，`27.2.12479018` |
| Android 依赖前缀 | `/root/android-libs`；含 Graphite2、FreeType、libpng、ICU、fontconfig stub 与 `lib/pkgconfig/*.pc` |
| Graphite2 源码 | `/root/graphite2-1.3.15`；头文件版本为 1.3.15；生成的 `.pc` 中 `Version: 3.3.1` 来自库接口版本，不能当作源码发布版本 |
| Graphite2 原构建缓存 | `/root/graphite2-1.3.15/build/CMakeCache.txt`；`arm64-v8a`、`android-26`、`Release`、`BUILD_SHARED_LIBS=ON`、`GRAPHITE2_NTRACING=ON`，安装到 `/root/android-libs` |
| Tectonic 工作目录 | `/root/tex-poc`；保留 `Cargo.toml`、`Cargo.lock`、`src/lib.rs` 与 Android release 产物 |
| 全局 Cargo 链接配置 | `/root/.cargo/config.toml`；`aarch64-linux-android` 的 linker 指向 `/root/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang`；仓内构建脚本目前隐式依赖此配置 |
| 当前工具版本 | `rustc 1.98.0 (88d9e12ae 2026-08-18)`、`patchelf 0.18.0`；这是检查时的工具版本，不据此断言原二进制当时的 Rust 编译器版本 |

**产物来源核对**：下列 WSL 文件与仓内对应 `.so` 的 SHA-256 分别完全一致：

- `/root/tex-poc/target/aarch64-linux-android/release/libtex_poc.so`：`326b45bdb938ace1d405b63af0e254f568cdc1d8799fd089bfdb0674a8fe79e1`。
- `/root/android-libs/lib/libgraphite2.so`：`0254afa5241f08e0f87630f7777d5307dc67e694a47dd0efa0d8f9fd01f8e533`。

**源码不能直接沿用旧工作目录**：排除 CRLF/LF 差异后，WSL `src/lib.rs` 仍含旧 JNI 包名 `app.fantasycvh`，仓内已改为 `app.androidrik`，另有 `e.into()` 的类型转换修正；WSL 锁文件的 `indexmap` 为 2.14.0，仓内为 2.14.1。后续复用 WSL 工具链和原生依赖，必须以当前仓内 `tools/tex-poc` 的源码及锁文件为构建输入。文件哈希相同只证明现有产物一致，不证明旧源码目录与产物完全对应。

实施时先记录依赖源码/压缩包校验值、版本、有效构建参数及 `.pc` 配置；将 target linker 等必要配置显式写入仓内脚本或项目配置，允许覆盖路径，不再依赖某个用户的全局 Cargo 配置。不得覆盖旧工作目录来“同步源码”，也不以卸载宿主 `-dev` 包作为常规方案；用独立构建目录、目标专用 `PKG_CONFIG_LIBDIR` 与受控的 `PKG_CONFIG_PATH` 隔离宿主依赖，并检查实际编译/链接日志。

---

## 四、分阶段改进方案

实施顺序为：先编写门禁和修正测试失败条件，随后修复对齐与运行期加载，最后独立评估并实施 Graphite2 静态化。以下阶段均待择机实施，当前仅完成设计修订。

### P1：对齐修复与运行期加载收口（核心范围）

#### P1-1：`libpdf2svg_bridge.so` 接入 Gradle CMake

1. 新增 `app/src/main/cpp/CMakeLists.txt`：
   - 显式声明 `project("pdf2svg_bridge" LANGUAGES C)`，桥接 target 不引入 C++ STL，守住 C3。
   - 仅当 `ANDROID_ABI STREQUAL "arm64-v8a"` 时定义 SHARED target；`x86_64` 不产出桥接库。不要收窄整个 app 的 ABI 过滤，其他原生模块仍保留双 ABI。
   - 链接 `document/src/main/jniLibs/${ANDROID_ABI}/libmupdf_java.so`；若使用 `SHARED IMPORTED`，不设 `IMPORTED_NO_SONAME`，最终核验桥接库 `DT_NEEDED` 为裸名 `libmupdf_java.so`，不能只凭 CMake 声明推定结果。
   - 增加 `-Wl,-z,max-page-size=16384` 与 `-Wl,-z,common-page-size=16384`，覆盖 r27 工具链；其他已合格库不因此更换工具链或重新签入。
2. `app/build.gradle.kts` 增加指向该文件的 `externalNativeBuild`，CMake 版本使用已有的 `3.22.1`。
3. **核对 MuPDF 的打包归属**：现有库由 `:document` 提供，AGP 也可能收集 CMake 的 IMPORTED 库。检查 debug/release 的 native merge 输入与最终 APK，确保每 ABI 只有一份正确的 MuPDF。若重复，调整链接引用或打包归属，不用宽泛 `pickFirst` 掩盖输入冲突，也不直接删除 `:document` 的库。
4. 构建和包内容核验通过后，删除手工签入的 `app/src/main/jniLibs/arm64-v8a/libpdf2svg_bridge.so`。

#### P1-2：保留动态 Graphite2，先完成三库对齐

P1 保留 `libgraphite2.so`，先用同一份 Graphite2 1.3.15 源码、原 Release/ABI/API/tracing 配置，在**新的构建目录和安装前缀**重编动态库，增加上述两个 16 KB 链接参数。不要修改或清理原 `/root/android-libs` 基线；复制或配置受控依赖前缀，并确认 Tectonic 实际链接到新动态库。

修改仓内 `tools/tex-poc/build-android.sh` 并在 WSL 重编当前仓库源码：

1. 显式设置或允许覆盖 `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER`、对应 C/C++ 编译器和归档器；使用仓内锁文件执行 `cargo build --locked --release --target aarch64-linux-android`。
2. 切换依赖前缀/链接形态时，清理**本次构建目录**中的 `tectonic_bridge_graphite2`、`tectonic_bridge_harfbuzz` 缓存，或使用独立 target 目录；不要清理原工作目录的产物。
3. 链接参数加入：
   ```bash
   export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
   ```
   `--gc-sections` 不作为对齐修复的额外优化要求。
4. 在新产物上执行：
   ```bash
   patchelf --page-size 16384 --replace-needed libicuuc.so.78 libicuuc78.so \
     target/aarch64-linux-android/release/libtex_poc.so
   ```
   如使用独立 `CARGO_TARGET_DIR`，相应调整产物路径；**必须核验 patchelf 后、复制进仓及最终 APK 的产物**。
5. 更新仓内 `libtex_poc.so`、`libgraphite2.so`；ICU、`libc++_shared.so`、MuPDF 保持原文件。P1 的 arm64 库数量预计仍为 18，x86_64 仍为 12。

该分阶段方式会在后续静态化时再次重编 Graphite2/Tectonic，换取对齐修复与链接形态变化可独立验证和回退。

#### P1-3：随包探测与实际加载分离，测试独立修复

1. **`TectonicNative` 明确分离两个概念**：
   - 主进程使用 `isPackaged` 一类清楚的名称，通过 `(TectonicNative::class.java.classLoader as? dalvik.system.BaseDexClassLoader)?.findLibrary("tex_poc") != null` 探测。该 API 只查路径；找到文件不代表依赖齐全、符号兼容或能够加载。
   - 不继续用 `available` 同时表达“存在”与“可加载”。迁移所有调用点；真正 `System.loadLibrary("tex_poc")` 留在编译执行路径，生产调用由 `:texcompile` 服务执行。
   - `TikzJax.kt` 避免在 Compose 组合期触发 TeX 加载；命中 SVG 缓存时直接使用缓存。未命中再以随包探测决定是否启动编译。
   - 编译端加载失败时保留实际异常信息并跨进程回传，避免只剩通用 `compile failed`。收益表述为“避免主进程加载 TeX 依赖”；主线程耗时、映射和 RSS 分别实测，不承诺省掉约 50 MB RSS。
2. **`ExportTikz.kt` 保持缓存优先**：未命中时使用 `TectonicNative.isPackaged && MupdfSvgBridge.available` 的短路顺序；无 TeX 库的 ABI 不加载 MuPDF 桥、不拉起 `:texcompile`。这只是编译资格检查，加载/执行成功仍由真实调用判断。
3. **设备测试独立修复假绿**：
   - 只在测试进程运行于不受支持的 ABI 时跳过；不能用 `isPackaged` 或加载结果作为 `assumeTrue` 条件，也不能只按硬件支持的 ABI 列表推断当前进程 ABI。
   - 在受支持的 arm64 进程内，缺库、依赖缺失、JNI 符号不匹配或加载失败均须断言失败。正常输入应确实产出 PDF/SVG；非法 TeX 用例的失败原因不能被任意加载错误冒充。
   - 现有 `MupdfSvgBridgeTest` 已用断言检查桥加载；执行时选择支持的 ABI，保留失败语义，不为 x86_64 引入按加载失败跳过的逻辑。

### P2：Graphite2 PIC 静态化（独立优化）

全仓仅 `libtex_poc.so` 动态依赖 `libgraphite2.so`。静态化可减少一个随包动态依赖和维护点，但不是满足 16 KB 对齐的必要条件；性能和内存收益待测。

1. 沿用 P1 的同版本源码、工具链和相关功能配置，在另一构建目录/前缀中设置 `BUILD_SHARED_LIBS=OFF`、`CMAKE_POSITION_INDEPENDENT_CODE=ON`、`CMAKE_BUILD_TYPE=Release`、`GRAPHITE2_NTRACING=ON`，生成 `libgraphite2.a`。不要删除基线目录中的动态库。
2. 核验当前 `tectonic_dep_support` / `pkg-config` 的静态链接选择方式，显式控制目标依赖前缀及所需的 `GRAPHITE2_STATIC` 定义；不能把“没有 `.so`”直接当作已经正确静态链接的证据。检查 Cargo 构建日志、最终 `DT_NEEDED` 和必要的动态符号。
3. 重编当前仓内 Tectonic，继续应用 P1 的对齐与 patchelf 参数。验证新库不再动态依赖 Graphite2，且所需 JNI 符号仍在。
4. 固定样例渲染与实际设备回归通过后，删除仓内 `libgraphite2.so`；同步修改门禁契约、`README.md` 和 `tools/tex-poc/README.md` 的依赖说明。arm64 数量预计由 18 降为 17，x86_64 保持 12。
5. 单独保存这一阶段的改动与验收结果，使静态化可独立回退；回退应恢复 P1 的**已对齐**动态库，不恢复初始不合格库。

### P3：最终 APK 静态门禁与 CI 防回归（随 P1 落地，随 P2 更新契约）

新增纯 Python 标准库脚本 `scripts/verify_native_16kb.py` 与 `scripts/test_verify_native_16kb.py`，仿照现有 R8 校验模式。这里的“静态闭环”只指二进制结构、包内容和依赖检查，不代替 16 KB 设备验收。

1. **输入模式**：默认源码扫描用于预编译库快速预检；`--apk <apk>` 覆盖最终包内所有目标 ABI 原生库，包含 AAR 和 CMake 产物。源码树不包含完整包，不能套用最终 APK 的完整性契约，也不能用其通过代替 APK 通过。
2. **ELF 结构与对齐**：对 arm64/x86_64 目录里的文件校验 ELF64 类型、机器架构、头表边界和至少一个有效 `PT_LOAD`；损坏、截断、错误架构或无法解析必须失败。每个 `PT_LOAD` 要满足 `p_align >= 0x4000`、合法的幂次对齐与 `(p_offset % 0x4000) == (p_vaddr % 0x4000)`。
3. **依赖名称与闭包**：`DT_NEEDED` 不得含 `/`；非系统依赖必须在同一 APK、同一 ABI 内存在匹配文件。系统库只允许明确的公开 NDK 库白名单。版本化依赖若没有对应打包文件必须失败；不要把仅名称合法当作依赖完整。系统白名单和依赖闭包也不能证明所有符号/API 在最低系统版本可用，仍需运行验证。
4. **TikZ 契约**：
   - 只在 APK 包含 arm64 ABI 时要求其完整 TikZ 库集合；x86_64 独立包不要求 arm64 库。
   - P1 要求 arm64 的 `libtex_poc.so`、`libpdf2svg_bridge.so`、已对齐的 `libgraphite2.so`；P2 更新为禁止独立 `libgraphite2.so` 且 TeX 不再动态依赖它。无需为已经完成的过渡阶段永久维护运行时模式开关。
   - 检查实际导出的动态 JNI 符号：`Java_app_androidrik_rikkahub_tikz_TectonicNative_compile`、`Java_app_androidrik_rikkahub_tikz_TectonicNative_lastError` 与 `Java_app_androidrik_rikkahub_tikz_MupdfSvgBridge_nativePdfToSvg`。不能只在原始字节中搜索名字，也不能把未定义引用当作导出。
   - x86_64 不出现 TeX/桥接库；未来若扩展该 ABI，需单独修改完整支持契约，不以“两个文件同时出现”代替可运行性验证。
5. **脚本测试**：合成可解析 ELF/APK 正负样本，覆盖合法段、低对齐、不同余、非法依赖名、缺失同 ABI 依赖、系统白名单、JNI 导出缺失/仅有未定义符号、缺库、ABI 错配、阶段契约与损坏/截断输入。
6. **CI 接入**：构建前执行脚本单测，构建后在 `.github/workflows/release.yml` 与 `.github/workflows/daily-build.yml` 的发布前检查阶段，紧跟 R8 校验检查**每一份待发布 APK**（arm64、x86_64、universal）。任一失败阻止上传。

---

## 五、验证计划与通过边界

| 阶段 | 验证项 | 验收标准 |
| --- | --- | --- |
| 环境与来源核对 | 保存 WSL 工具链版本、源码/锁文件/原生依赖校验值、有效构建参数和链接日志 | 使用当前仓内源码；target linker 显式配置；隔离宿主 pkg-config 与旧构建缓存 |
| 静态自测 | `python -B -m unittest discover -s scripts -p test_verify_native_16kb.py` | 全部正负样本符合预期，错误输入不能静默跳过 |
| 构建与最终包门禁 | debug/release 构建后对 arm64、x86_64、universal APK 分别执行 `--apk` | P1 arm64 预计 18 个、P2 预计 17 个；x86_64 预计 12 个；数量仅用于核对，以库集合和契约为准；所有库、依赖及 JNI 导出通过；无重复 MuPDF 来源冲突 |
| 4 KB arm64 回归 | `TectonicNativeTest` 当前 4 项、`MupdfSvgBridgeTest` 当前 7 项及修订后的失败条件；PDF 解析、FTS5、PRoot 工作区/终端 | 支持 ABI 的缺库/加载故障必须失败；正常样例真正生成 PDF/SVG，错误信息可诊断；现有功能正常 |
| 主进程加载收口 | 分别冷启测试缓存命中与缓存未命中；清楚记录测试进程和主进程 PID，检查可读取的 `/proc/<pid>/maps` 或等效调试证据 | 主进程两条路径均不加载 `libtex_poc.so` / TeX ICU 依赖；未命中时由 `:texcompile` 加载和执行；x86_64 不因导出尝试启动该服务；时间与内存收益另测 |
| x86_64 16 KB 冒烟（可选） | 当前可用的 16 KB 系统镜像，装 x86_64/universal 包 | `getconf PAGE_SIZE` 为 `16384`，关闭页大小兼容模式；启动、搜索、PDF、PRoot 正常；无缓存 TikZ 降级源码。不能据此证明 arm64 TeX 已兼容 |
| arm64 16 KB 终验 | 支持 16 KB 模式的真机或 arm64 主机 AVD，运行真实 TikZ 与桥接测试 | `getconf PAGE_SIZE` 为 `16384`，**确认关闭页大小兼容模式**，TeX 子进程与 MuPDF 桥均能加载并产出正确结果；记录设备、系统、兼容模式状态和测试报告 |
| 静态化与缓存兼容 | P1/P2 使用同一批中文、字体、pgfplots 等固定输入，分别以空缓存渲染并比较结果；另测历史 SVG 缓存读取 | 原生引擎、bundle、字体与转换参数保持既定版本；图形、文字和尺寸一致；仅比较 PDF 长度/前 32 字节不足以证明渲染一致 |

设备测试安装前核对 application ID 与已有安装，避免覆盖正常用户数据。无可用 arm64 16 KB 环境时，明确记录“静态门禁通过、4 KB 回归通过、arm64 16 KB 终验待完成”，不能标为完整兼容验收通过。切换测试设备的页大小兼容模式前记录原状态，结束后恢复。

## 六、范围边界与待验证判断

- **保留 `useLegacyPackaging = true`**：受 PRoot 可执行文件落盘约束；ELF 对齐仍然必须检查。若未来改变压缩/提取策略，还需补齐 APK ZIP 对齐与安装验证。
- **维持 TikZ 原生引擎仅 arm64**：本次不扩建 x86_64 TeX 工具链。
- **保留已合格的预编译库**：不重编或改写 ICU、`libc++_shared.so`、MuPDF、`libsimple.so`、PRoot 等已有合格件；ELF 对齐合格不代表这些库的所有 16 KB 运行路径已验收。
- **缓存版本暂不递增，需验证支撑**：仅在依赖/资源版本及渲染语义保持一致、固定样例比较通过后保留 `TikzSvgCache.CACHE_VERSION`；若实施中改变了输出语义，则按现有缓存规范递增，不能预先无条件承诺。
- **Graphite2 静态化独立决策**：修复对齐不依赖静态化；减少动态依赖数可以静态确认，性能和 RSS 收益需要测量。

## 七、外部依据

- [Android：支持 16 KB 页大小](https://developer.android.com/guide/practices/page-sizes)：r27 链接参数、运行期页大小假设、16 KB 测试和兼容模式；验收时以实际系统能力为准。
- [BaseDexClassLoader.findLibrary](https://developer.android.com/reference/dalvik/system/BaseDexClassLoader#findLibrary(java.lang.String))：返回原生库路径，不保证实际可加载。
- [Gradle 与原生构建关联及 jniLibs 打包](https://developer.android.com/studio/projects/gradle-external-native-builds#jniLibs)：IMPORTED 库与既有 jniLibs 的重复打包风险。
