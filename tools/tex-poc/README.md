# tex-poc —— `libtex_poc.so` 源码（TikZ 渲染的 Tectonic 引擎）

app 的 TikZ 渲染链：

```
TectonicNative.kt（Kotlin）
  └─ libtex_poc.so（本目录的产物）── Tectonic 编译 TeX → PDF
        └─ libpdf2svg_bridge.so（预编译桥）── mupdf 把 PDF 转 SVG（文字转轮廓）
```

## 现状

- `app/src/main/jniLibs/arm64-v8a/libtex_poc.so` 是**预编译产物，Gradle 不参与编译**（与 `libpdf2svg_bridge.so`、`libsimple.so` 相同）。
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
3. 交叉编译依赖目录 `$ANDROID_LIBS`（默认 `/root/android-libs`）：
   libpng / graphite2 / freetype 的交叉编译版 + termux libicu + fontconfig stub，
   并带 `lib/pkgconfig/*.pc`（脚本会用它做 pkg-config 探测，`PKG_CONFIG_ALLOW_CROSS=1`）
4. `patchelf`：脚本用它把 `libicuuc.so.78` 的 NEEDED 改写成 `libicuuc78.so`——AGP 的 jniLibs 不接受版本化 .so 文件名

```bash
cd tools/tex-poc            # 在 WSL 里即 /mnt/<drive>/.../tools/tex-poc
bash build-android.sh
cp target/aarch64-linux-android/release/libtex_poc.so ../../app/src/main/jniLibs/arm64-v8a/
```

`build-android.sh` 只做 `cargo build --release --target aarch64-linux-android` + `patchelf`。
依赖版本锁定在 `Cargo.lock`（tectonic 0.17 + tectonic_bundles 0.4，**关掉默认特性**以避开 reqwest→openssl：
本引擎在设备上完全离线运行，bundle 随包）。

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
