# 原生库 16 KB 内存页对齐与 TikZ 加载收口设计稿

日期：2026-11-27。状态：draft，待实施。关联：[dsh-Go §7.1 16 KB 实证候选](../../references/other%20projects/dsh-go.md)、[TikZ Tectonic 复盘 §8.4](../../tikz-rendering-tectonic-postmortem.md)、[peer-verdicts §六](../../peer-verdicts-2026-11-08.md)。

## 一、问题与全仓实测现状

本仓 `targetSdk = 37`、`compileSdk = 37.2`。对 `merged_native_libs` 下打入最终 APK 的全部原生库（`arm64-v8a` 18 个、`x86_64` 12 个，含 AAR 传递带入的 `libsqlite3x.so`、`libink.so`、`libquickjs-android-wrapper.so` 以及子模块 CMake 编译的 `libtermux.so`、`libworkspace.so`）逐一做 ELF64 程序头核验：

- **合格（`PT_LOAD` `p_align ≥ 0x4000` 且 `p_offset ≡ p_vaddr (mod 0x4000)`）**：`x86_64` 全部 12 个；`arm64-v8a` 其余 15 个（含 `libmupdf_java.so`、`libsimple.so`、`libproot_exec.so`、`libproot_loader.so`、`libicuuc78.so`、`libicudata78.so`、`libc++_shared.so`）。
- **不合格（`p_align = 0x1000` 且多段同余不满足 16 KB）**：仅 `app/src/main/jniLibs/arm64-v8a/` 下 3 个手工预编译库：

| 库 | 当前体积 | 不合格 `PT_LOAD` 段数 | 构建来源 | 当前动态依赖（`DT_NEEDED`） |
| --- | --- | --- | --- | --- |
| `libpdf2svg_bridge.so` | 13 KB | 3 / 4 段不满足同余 | `app/src/main/cpp/pdf2svg_bridge.c`（单文件 C，NDK r27c 手工编译） | `libmupdf_java.so`、`liblog.so`、`libdl.so`、`libc.so` |
| `libgraphite2.so` | 1.5 MB | 2 / 3 段不满足同余 | WSL `/root/android-libs` NDK r27c 交叉编译 | `libm.so`、`libdl.so`、`libc.so` |
| `libtex_poc.so` | 13.5 MB | 3 / 5 段不满足同余（第 5 段为 `patchelf` 追加的 `0x10000` 段） | `tools/tex-poc/build-android.sh`（Rust + Tectonic 0.17 + NDK r27c + `patchelf`） | `libc++_shared.so`、`libicuuc78.so`、`libgraphite2.so`、`libz.so`、`libdl.so`、`libm.so`、`libc.so` |

源码核查确认 `app/src/main/cpp/pdf2svg_bridge.c` 与 `tools/tex-poc/src/lib.rs` 均无硬编码 `4096` / `PAGE_SIZE` 的页大小假设。

**当前在 Android 15+ 16 KB 页 arm64 设备上的表现与盲区**：

1. **功能静默降级**：Bionic `linker64` 拒绝加载上述三库抛 `UnsatisfiedLinkError`，被 `TectonicNative` 与 `MupdfSvgBridge` 的 `runCatching` 捕获并置 `available = false`——应用不崩溃，但聊天 TikZ 退化为纯源码展示，`ExportTikz.compileTikzToSvg` 返回 `null`（HTML/PNG 导出丢失 TikZ 图）。
2. **设备单测假绿盲区**：`TectonicNativeTest` 用 `assumeTrue("libtex_poc.so not available on this ABI", TectonicNative.available)` 过滤非 arm64 架构；在 16 KB arm64 真机上 `dlopen` 失败同样令 `available == false`，导致整套测试被标记为 `ASSUMPTION VIOLATED` 跳过而非失败，掩盖加载故障。

---

## 二、历史提交约束与运行期红线

方案设计必须遵守以下由历史提交确立的运行期边界，不能照搬通用 16 KB 迁移文档的默认建议：

| 编号 | 历史提交 / 来源 | 运行期事实与约束 | 设计红线 |
| --- | --- | --- | --- |
| C1 | PRoot 沙箱演进（`ProotShellRunner.kt` / `WorkspaceTerminalSession.kt`） | `libproot_exec.so` 与 `libproot_loader.so` 是伪装成 `.so` 的 Linux 可执行体；Android 10+ 禁止从 `filesDir` `execve`，全靠 `useLegacyPackaging = true` 在安装期把它们解压到拥有可执行 SELinux 标签（`apk_data_file`）的 `nativeLibraryDir`。 | **`app/build.gradle.kts` 的 `useLegacyPackaging = true` 绝对不能改为 `false`**（解压态 `.so` 在 16 KB 内核上同样强制要求 ELF `PT_LOAD` 16 KB 对齐）。 |
| C2 | `7f3fb912f6`（mupdf 接管 SVG）+ `393e3a7915`（拆出 `ExportTikz.kt`） | `:document` 同时含 `arm64-v8a` 与 `x86_64` 的 `libmupdf_java.so`，而 `libtex_poc.so` 仅有 `arm64-v8a`。`ExportTikz.kt:35` 当前只判 `if (MupdfSvgBridge.available)` 未判 `TectonicNative.available`——若 `app` CMake 不锁 ABI 多产出 `x86_64/libpdf2svg_bridge.so`，`x86_64` 上主进程会无谓加载未经实跑的 9.8 MB `libmupdf_java.so`，且每次导出都会白白拉起 `:texcompile` 子进程再报错回传。 | **`pdf2svg_bridge` 接入 CMake 时必须限定仅 `arm64-v8a` 构建，并同步补齐 `ExportTikz.kt` 的双侧守卫**。 |
| C3 | `959f50fb62`（引入 Tectonic + ICU 78） | 仓内 `libc++_shared.so` 为 NDK r27c（Clang 18），而 `libicuuc78.so` 为 Termux Clang 21 编译，二者在 `:texcompile` 进程内共享同一份 `libc++_shared.so`；`libicuuc78.so` / `libicudata78.so` 已是 16 KB 合格件（内部 `DT_SONAME` 仍为 `.so.78`，Bionic 按文件名命中）。 | **`pdf2svg_bridge` 必须声明为纯 `C` 工程（不引入其他 NDK 的 `libc++_shared.so`）；不动仓内 `libicuuc78.so` / `libicudata78.so` / `libc++_shared.so` / `libmupdf_java.so`**。 |
| C4 | `dfbfe222e4`（`:texcompile` 私有进程隔离）+ `6d196c64b6`（首帧同步读缓存） | 编译虽隔离在 `:texcompile` 子进程，但 `TikzJax.kt:78` 在主进程 Compose 组合期调 `TectonicNative.available` → 直接触发主线程 `System.loadLibrary("tex_poc")`，导致主进程无谓 `dlopen` 整套约 50 MB 的 TeX/ICU 动态库（缓存命中时甚至根本不编译）。 | 将「随包存在探测」（主进程免 `dlopen`）与「实际加载执行」（`:texcompile` 子进程 `dlopen`）拆开；把仅被 `libtex_poc.so` 依赖的 `libgraphite2` 改为静态链接，双侧同时减负。 |
| C5 | 上游 `815e9c20b`（纯 Kotlin PDF 替换 mupdf） | 本仓 `libpdf2svg_bridge.so` 运行期强绑定 `libmupdf_java.so` 导出的 37 个 `fz_*` C 符号（见 `docs/upstream-analysis.md`）。 | 维持不跟上游删 `libmupdf_java.so`；桥接层 `DT_NEEDED` 必须保持裸名 `libmupdf_java.so`（禁带相对路径 `/`）。 |

---

## 三、分阶段改进方案

### P1：三库 16 KB 页对齐根治（核心必做，3 → 2 库）

#### P1-1：`libpdf2svg_bridge.so` 接入 `app` Gradle CMake（消除手工预编译）

1. 新增 `app/src/main/cpp/CMakeLists.txt`：
   - 显式声明 `project("pdf2svg_bridge" LANGUAGES C)`，杜绝 CMake 自动引入 C++ STL（避免与 `jniLibs` 内的 `libc++_shared.so` 冲突，守住 C3）。
   - 架构门控：仅当 `ANDROID_ABI STREQUAL "arm64-v8a"` 时定义 `pdf2svg_bridge` SHARED target；`x86_64` 直接 `return()` 不产出 `.so`（守住 C2，与 `libtex_poc.so` ABI 严格一致）。
   - 以 `SHARED IMPORTED` 方式引用 `${CMAKE_CURRENT_SOURCE_DIR}/../../../../document/src/main/jniLibs/${ANDROID_ABI}/libmupdf_java.so`（不设 `IMPORTED_NO_SONAME`，依赖 `libmupdf_java.so` 自带的 `SONAME: libmupdf_java.so` 生成干净的裸名 `DT_NEEDED`，守住 C5）。
   - 显式追加 `target_link_options(pdf2svg_bridge PRIVATE "-Wl,-z,max-page-size=16384")`，无论本机或 CI 选用 NDK r27 还是 r28+ 均强制输出 `0x4000` 对齐。
2. `app/build.gradle.kts` 增加 `externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }`（与 `:terminal` / `:workspace` 同版本 CMake，CI 无需新增工具链依赖）。
3. 删除仓库内手工签入的 `app/src/main/jniLibs/arm64-v8a/libpdf2svg_bridge.so`。

#### P1-2：`libgraphite2` 改编 PIC 静态库并入 `libtex_poc.so`（裁掉独立动态库）

全仓仅 `libtex_poc.so` 依赖 `libgraphite2.so`，且 `libgraphite2.so` 仅依赖系统 `libm/libdl/libc`。改为静态并入后直接消除一个预编译 `.so` 维护点，同时减少动态链接器加载项：

1. 在 WSL 交叉环境中用 NDK CMake 重编 Graphite2 到 `$ANDROID_LIBS`（默认 `/root/android-libs`）：
   ```bash
   cmake -S . -B build-android-static \
     -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
     -DANDROID_ABI=arm64-v8a \
     -DANDROID_PLATFORM=android-26 \
     -DBUILD_SHARED_LIBS=OFF \
     -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
     -DGRAPHITE2_NTRACING=ON \
     -DCMAKE_INSTALL_PREFIX="$ANDROID_LIBS"
   cmake --build build-android-static -j
   cmake --install build-android-static
   ```
   - 关键约束：必须开启 `-DCMAKE_POSITION_INDEPENDENT_CODE=ON`（静态库最终链入 `cdylib` `libtex_poc.so`）。
   - 清理 `$ANDROID_LIBS/lib/libgraphite2.so*` 动态库残留，确保 `graphite2.pc` 配合 `tectonic_dep_support`（`pkg-config`）自动解析为 `static=graphite2`，并在 `Cflags` 中保留 `-DGRAPHITE2_STATIC`。
2. 从仓库删除 `app/src/main/jniLibs/arm64-v8a/libgraphite2.so`，同步修正 `README.md` 与 `tools/tex-poc/README.md` 的依赖表文档（顺手修正原 `README.md:216` 误写其依赖 `libc++_shared` 的笔误）。

#### P1-3：`libtex_poc.so` 16 KB 对齐重编 + `patchelf` 页大小锁定

修改 `tools/tex-poc/build-android.sh` 并在 WSL 执行重编：

1. **清理桥接缓存**：重编前执行 `cargo clean -p tectonic_bridge_graphite2 -p tectonic_bridge_harfbuzz`（防旧 `DEP_*` 缓存残留，见复盘文档 §8.4.1 坑 5），并确认宿主机无系统 `-dev` 包污染 `pkg-config`。
2. **注入 16 KB 链接参数与死代码裁剪**：
   ```bash
   export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,--gc-sections"
   ```
3. **锁定 `patchelf` 页对齐粒度**：
   ```bash
   patchelf --page-size 16384 --replace-needed libicuuc.so.78 libicuuc78.so \
     target/aarch64-linux-android/release/libtex_poc.so
   ```
   更新 `app/src/main/jniLibs/arm64-v8a/libtex_poc.so`（仓内 `libicuuc78.so`、`libicudata78.so`、`libc++_shared.so` 原封不动）。

---

### P2：运行期加载收口与测试假绿修复（推荐顺手落地，改动 < 25 行）

解决 C2 与 C4 暴露的两个运行期遗留问题：

1. **`TectonicNative` 拆分「ABI 随包存在」与「进程内 `dlopen`」**（`app/src/main/java/app/androidrik/rikkahub/tikz/TectonicNative.kt`）：
   - `val available: Boolean` 改为基于公开 API `(TectonicNative::class.java.classLoader as? dalvik.system.BaseDexClassLoader)?.findLibrary("tex_poc") != null` 惰性求值（纯路径查找，不调 `System.loadLibrary("tex_poc")`、无需传 `Context`）。
   - 真正的 `System.loadLibrary("tex_poc")` 仅保留在 `TectonicNative.compile()` 内部（只会在 `:texcompile` 子进程执行编译时触发）。
   - **收益**：
     - 主进程首次渲染 TikZ（尤以命中磁盘 SVG 缓存的历史会话秒开场景）**省掉 UI 线程同步 `dlopen` 约 50 MB 动态库（`libtex_poc` + `libicuuc78` + `libicudata78` + `libc++_shared`）的开销与内存占用**；
     - `TectonicNativeTest` 在 `arm64-v8a` 设备上 `assumeTrue(TectonicNative.available)` 必过，若子进程 `dlopen` 因 16 KB 对齐或缺符号失败，`compileIsolated` 返回 `null` 会直接触发 `assertNotNull` **真实红灯报错**，消除假绿盲区；在 `x86_64` 模拟器上 `findLibrary("tex_poc")` 为 `null`，仍按预期跳过。
2. **`ExportTikz.kt` 补齐对称可用性守卫**（`app/src/main/java/app/androidrik/rikkahub/ui/pages/chat/ExportTikz.kt`）：
   - 第 35 行 `else if (MupdfSvgBridge.available)` 改为 `else if (TectonicNative.available && MupdfSvgBridge.available)`，确保编译端与转换端任一不可用时直接短路，不无谓拉起 `:texcompile`。

---

### P3：静态 ELF 16 KB 门禁与 CI 防回归（无需设备即可闭环）

仿照 `scripts/verify_r8_output.py` + `scripts/test_verify_r8_output.py` 的双层守卫模式，新增纯 Python（仅标准库 `struct` / `zipfile`，不依赖宿主机 NDK）校验脚本：

1. **`scripts/verify_native_16kb.py`**（支持默认扫源码树 `*/src/main/jniLibs/**/*.so`，以及 `--apk <apk>` 直接流式扫最终 APK 内全部 `lib/{arm64-v8a,x86_64}/*.so`）：
   - **门禁 1（16 KB 段对齐）**：所有 64 位 `.so` 的每一个 `PT_LOAD` 段必须满足 `p_align >= 0x4000` 且 `(p_offset % 0x4000) == (p_vaddr % 0x4000)`（拦截未来手工重编漏传参数，以及升级第三方 AAR 带入 4 KB `.so`）。
   - **门禁 2（Bionic 命名空间合法性）**：所有 `DT_NEEDED` 严禁包含 `/` 路径字符，严禁出现 AGP 不打包的版本化后缀（如 `.so.78`、`.so.3`，系统白名单除外）。
   - **门禁 3（TikZ 原生契约）**：`arm64-v8a` APK 必含 `libtex_poc.so` 与 `libpdf2svg_bridge.so`，不含已裁撤的 `libgraphite2.so`；`libtex_poc.so` 的 `DT_NEEDED` 不含 `libgraphite2.so` 且动态符号表保留 `Java_app_androidrik_rikkahub_tikz_TectonicNative_compile` / `lastError`；`libpdf2svg_bridge.so` 保留 `Java_app_androidrik_rikkahub_tikz_MupdfSvgBridge_nativePdfToSvg`。
   - **门禁 4（ABI 隔离契约）**：`x86_64` 下不出现 `libpdf2svg_bridge.so`（除非同时提供 `x86_64/libtex_poc.so`）。
2. **`scripts/test_verify_native_16kb.py`**：合成最小 ELF64 头单测覆盖合格段、`p_align = 0x1000`、`p_offset/p_vaddr` 模 16 KB 不同余、非法 `DT_NEEDED` 四类正负样本。
3. **CI 接入**（`.github/workflows/release.yml` 与 `.github/workflows/daily-build.yml`）：
   - 构建前单测步跑 `python3 -B -m unittest discover -s scripts -p test_verify_native_16kb.py`；
   - `Prepare asset` / `Publish nightly` 阶段紧跟 `verify_r8_output.py` 执行 `python3 scripts/verify_native_16kb.py --apk "$apk"`。

---

## 四、验证计划

| 阶段 | 验证项 | 验收标准 |
| --- | --- | --- |
| 静态自测（本机，无设备） | `python -B -m unittest discover -s scripts -p test_verify_native_16kb.py` | 正负样本单测全绿 |
| 构建与全包 ELF 门禁（本机） | `./gradlew assembleDebug assembleRelease` + `python scripts/verify_native_16kb.py --apk <arm64.apk>` + `--apk <x86_64.apk>` | `arm64-v8a` 全部 17 个 `.so`（裁掉 `libgraphite2.so` 后 18→17）与 `x86_64` 全部 12 个 `.so` 100% 通过 4 道门禁 |
| 4 KB 真机回归 | 现有 arm64 真机跑 `TectonicNativeTest`（4 项）+ `MupdfSvgBridgeTest`（7 项）+ 本地 PRoot 工作区启动 | 11 项 TikZ 渲染/中文轮廓/确定性/错误回传全绿；主进程 `cat /proc/<main_pid>/maps` 确认未加载 `libtex_poc.so` / `libicudata78.so`；PRoot 终端正常启动 |
| x86_64 16 KB 冒烟（可选，本机 AVD） | Windows WHPX 建 `google_apis_ps16k;x86_64` AVD（`getconf PAGE_SIZE` = `16384`）装 universal/x86_64 包 | 应用正常冷启动、FTS5 搜索（`libsimple`）/ PDF 解析（`libmupdf_java`）/ PRoot 正常；TikZ 平滑走源码降级且不拉起 `:texcompile` |
| arm64 16 KB 终验（真机或 Apple Silicon AVD） | Pixel 8/9 系 16 KB 模式或 arm64 主机 `google_apis_ps16k;arm64-v8a` 跑 `TectonicNativeTest` + `MupdfSvgBridgeTest` | `getconf PAGE_SIZE` = `16384` 下 `:texcompile` 与主进程 `MupdfSvgBridge` 均正常 `dlopen` 并产出一致 SVG（无需递增 `TikzSvgCache` `CACHE_VERSION`，输出语义未变） |

---

## 五、明确不做

- **不关闭 `useLegacyPackaging = true`**：受 PRoot `nativeLibraryDir` 可执行落盘硬约束，不追求 APK 内未压缩 `.so` 直接 `mmap`。
- **不补齐 `x86_64` 的 TikZ 交叉编译链**：为 `x86_64` 模拟器交叉构建 `libtex_poc` + `graphite2` + `freetype` + `libpng` + `ICU 78` 投入远大于收益，维持 TikZ 原生引擎仅 `arm64-v8a`。
- **不触碰已合格的预编译二进制**：`libicuuc78.so`、`libicudata78.so`、`libc++_shared.so`、`libmupdf_java.so`、`libsimple.so`、`libproot_*.so` 均已满足 16 KB 对齐，不做重编或 `patchelf` 改写。
- **不递增 `TikzSvgCache` 的 `CACHE_VERSION`**：本次仅改 ELF 段对齐与 `graphite2` 链接形态，TeX 引擎版本、bundle、字体与 MuPDF 转换参数完全不变，历史缓存无需失效。
