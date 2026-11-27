# tex-poc —— `libtex_poc.so` 源码（TikZ 渲染的 Tectonic 引擎）

app 的 TikZ 渲染链：

```
TectonicNative.kt（Kotlin）
  └─ libtex_poc.so（本目录的产物）── Tectonic 编译 TeX → PDF
        └─ libpdf2svg_bridge.so（Gradle CMake 桥）── mupdf 把 PDF 转 SVG（文字转轮廓）
```

## 现状

- `app/src/main/jniLibs/arm64-v8a/libtex_poc.so` 是**预编译产物，Gradle 不参与编译**；Graphite2 以 PIC 静态库并入，仓内不再签入独立 `libgraphite2.so`。
- `libpdf2svg_bridge.so` 由 `app/src/main/cpp/CMakeLists.txt` 以纯 C 构建，仅 arm64，MuPDF 仍由 `:document` 打包。保留 `useLegacyPackaging = true` 供 PRoot 从 `nativeLibraryDir` 执行。
- 仅 **arm64-v8a**（`abiFilters` 只含 arm64-v8a / x86_64，本库只出 arm64）。

## 产物与 JNI 入口

- 产物：`target/aarch64-linux-android/release/libtex_poc.so` → 拷到 `app/src/main/jniLibs/arm64-v8a/`
- JNI 入口（**符号名与 Java 包名绑定**）：
  - `Java_app_androidrik_rikkahub_tikz_TectonicNative_compile`
  - `Java_app_androidrik_rikkahub_tikz_TectonicNative_lastError`

  ⇒ 改包名时必须同步改 `src/lib.rs` 里的符号名；否则运行时 `UnsatisfiedLinkError`。

## 构建（WSL Ubuntu 内执行）

前置（缺一不可）：

1. rustup stable + `aarch64-linux-android` target
2. Android NDK r27c（linux-x86_64），默认在 `~/ndk`（可用 `NDKBIN` 覆盖）
3. 原依赖前缀 `$ANDROID_LIBS_BASE`（默认 `/root/android-libs`）和 Graphite2 1.3.15 源码 `$GRAPHITE2_SOURCE`（默认 `/root/graphite2-1.3.15`）；原环境保留于 `D:\A-projects-relay\WSL\Ubuntu` 的 Ubuntu。`build-graphite2.sh` 复制依赖到新前缀，重定向 `.pc` 路径，构建静态 Graphite2，保留原环境。
4. `patchelf` 0.18.0：改写 ICU NEEDED，显式使用 `--page-size 16384`。

```bash
cd tools/tex-poc            # 在 WSL 里即 /mnt/<drive>/.../tools/tex-poc
export NATIVE_BUILD_ROOT="$HOME/neomix-native-16kb"
bash build-graphite2.sh
export CARGO_TARGET_DIR="$NATIVE_BUILD_ROOT/target-static"
bash build-android.sh
cp "$CARGO_TARGET_DIR/aarch64-linux-android/release/libtex_poc.so" ../../app/src/main/jniLibs/arm64-v8a/
python3 ../../scripts/verify_native_16kb.py
```

默认前缀为 `$NATIVE_BUILD_ROOT/deps-static`（可用 `ANDROID_LIBS` 覆盖）。脚本显式配置 target linker、编译器、目标专用 pkg-config 路径与两个 16 KB 链接参数，不依赖 `/root/.cargo/config.toml`。`TECTONIC_PKGCONFIG_FORCE_SEMI_STATIC=1` 令依赖支持库对存在 `.a` 的目标发出 `static=` 链接指令；Graphite2 必须有 `.a` 且无同名 `.so`，C/C++ 编译也显式定义 `GRAPHITE2_STATIC`。ICU 与 NDK C++ 运行时继续动态链接。

仅复现对齐修复的动态阶段时，**两个脚本都设置** `GRAPHITE2_LINKAGE=shared`，并使用另一 `CARGO_TARGET_DIR`；最终 APK 门禁只接受静态阶段。不要删除原依赖前缀的文件，也不要在旧 `/root/tex-poc` 内重编旧包名源码。

`build-android.sh` 执行 `cargo build --locked --release --lib --target aarch64-linux-android` + `patchelf`。
依赖版本锁定在 `Cargo.lock`（tectonic 0.17 + tectonic_bundles 0.4，**关掉默认特性**以避开 reqwest→openssl：
本引擎在设备上完全离线运行，bundle 随包）。

构建后对所有待发布 APK 执行 `python scripts/verify_native_16kb.py --apk <apk>`；默认源码扫描不覆盖 Gradle/AAR 产物，也不证明设备运行兼容。设备终验须在 16 KB 内核关闭页大小兼容模式下执行。

## 不在本目录的相关资产

- TeX bundle 与字体在 app 内：`app/src/main/assets/tex/tectonic-bundle-v3.tikzbundle`、
  `app/src/main/assets/tex/droidsans.ttf`（不进本目录，避免重复）

## 复现用例

- `hex_host_matrix.sh`：宿主 TeX Live（上游 pgf）对照矩阵，确认 `#hex` 颜色在 pgf options 中的限制
- `hex_variants.py`：真机 v1–v5 隔离（需要 `adb`，按脚本内 `REMOTE` 路径推送）
- `hexvars/`、`test_hexop.tex`、`test_hexvariants.tex`：最小复现输入
- `test_*.tex`：TikZ 渲染回归输入

## deprecated/

`dvisvgm` 链已于 2026-09-07/08 废弃（PDF→SVG 改由 mupdf 桥接管，详见仓库
`docs/tikz-rendering-tectonic-postmortem.md` 8.4.9），相关源码与脚本**仅作存档**，见 `deprecated/README.md`。
