# 原生库 16 KB 对齐与 TikZ 加载验收

关联：[修订设计稿](../drafts/design-native-16kb-alignment.md)。实施涵盖对齐、主进程加载收口、Graphite2 静态化和 APK/CI 门禁。静态门禁与 4 KB 真机回归不等于 arm64 16 KB 终验通过。

## 构建来源

- 使用恢复的 Ubuntu WSL，原 `/root/android-libs`、`/root/tex-poc`、Graphite2 源码和旧构建目录保留。
- 新构建根为 `/root/neomix-native-16kb`：`deps-shared` / `target-shared` 保存对齐后的动态阶段，`deps-static` / `target-static` 保存静态阶段；动态验收 APK 另存为 `shared-reference.apk`。
- NDK r27c `27.2.12479018`、Rust `1.98.0`、patchelf `0.18.0`；Tectonic 使用当前仓库源码与 Cargo.lock，Graphite2 1.3.15 使用恢复的源码和原功能配置。外部依赖哈希、原始 `.pc` 和源码指纹见 [native-build-inputs.json](../../../tools/tex-poc/native-build-inputs.json)。源码树指纹算法为：排除任一路径分量以 `build` 开头或为 `.git` 的文件，按相对 POSIX 路径排序，将路径到文件 SHA-256 的映射以 `json.dumps(sort_keys=True, separators=(',', ':'))` 序列化后再取 SHA-256。
- 最终 `build-graphite2.sh`、`build-android.sh` 默认配置复建产物，与已上机验收的静态库 SHA-256 一致：`7018bd201a030fd1fc3cfd1dd3457b542d6395e5038631468795327fc3b7565e`。
- Cargo 输出明确包含 `cargo:rustc-link-lib=static=graphite2`；最终 TeX 的 `DT_NEEDED` 不含 Graphite2。ICU、NDK C++ 运行时、MuPDF 等已有合格预编译件未改写。
- 桥接层由 app 的纯 C CMake target 构建，仅 arm64；通过 `-L/-l` 链接 MuPDF，MuPDF 仍由 `:document` 唯一打包；桥接库的 `DT_NEEDED` 保留裸名 `libmupdf_java.so`。

## 验证结果

| 项目 | 结果与证据 |
| --- | --- |
| Python 原生门禁测试 | 17 项通过，覆盖对齐、依赖、架构、缺库、JNI 定义/可见性/函数类型、损坏 ELF 和重复 ZIP 项 |
| 现有 R8 校验脚本测试 | 9 项通过 |
| 源码预编译库扫描 | 12 个库，0 错误；该数字不含 CMake 与 AAR 产物 |
| 动态阶段 debug APK | arm64 18、x86_64 12、universal 30 个库，阶段对应门禁全部通过 |
| 静态阶段 debug 验收 APK | arm64 17、x86_64 12、universal 29 个库，最终门禁全部通过 |
| release 构建与最终门禁 | 通过；arm64 17、x86_64 12、universal 29 个库，均 0 错误；R8 最终产物检查通过（579 required classes） |
| TikZ JVM 测试 | PgfNormalizeTest 15 项通过，0 跳过/失败/错误 |
| 动态阶段真机 | 15 项通过：Tectonic 6、MuPDF 桥 8、SVG 缓存 1 |
| 静态阶段真机 | 20 项通过：同上 15 项，加 PRoot 执行冒烟 1、MessageFtsManager 4 |
| 主进程加载 | `packagedProbeAndCompilationKeepTexOutOfMainProcess` 在探测、隔离编译前后读取 `/proc/self/maps`，均未加载 TeX/专用 ICU；真实渲染由 `:texcompile` 完成 |
| 渲染与缓存兼容 | 3 组固定样例的 3 PDF + 3 SVG 均逐字节一致，保留 CACHE_VERSION |
| arm64 16 KB 关闭兼容模式终验 | 未执行；当前真机为 4 KB 页，不能据此标记完整兼容通过 |
| x86_64 16 KB AVD 冒烟 | 未执行（可选）；只做了该 ABI 的构建与静态门禁 |

真机为 PJX110（序列号 `6c841f95`），`getconf PAGE_SIZE = 4096`。安装前核对包元数据，使用独立 `app.androidrik.rikkahub.nativeverification.debug`，正常应用与正常 debug 数据未覆盖。设备对独立 androidTest 包启动报告 `Unable to find instrumentation info`，因此使用临时同包 instrumentation 宿主，把原 androidTest 测试编入隔离 debug 验收包后执行；release 构建未使用该宿主；验收结束后，本轮新建的两个 nativeverification 安装已清理。

本地日志：`.tmp/native16kb-gradle-shared.log`、`.tmp/native16kb-gradle-shared-self.log`、`.tmp/native16kb-gradle-static-self.log`、`.tmp/native16kb-gradle-release.log`、`.tmp/native16kb-device-shared.log`、`.tmp/native16kb-device-static.log`。原生构建日志在上述 WSL 新构建根内。

PRoot 测试实际检查安装提取后的 executable/loader，并通过 PRoot 执行 Android `/system/bin/sh`；这证明本次打包仍支持提取与执行，不代表已验收完整 Linux rootfs 或终端 UI。中文/pgfplots SVG 测试已实际经过 MuPDF PDF 解析与转换。

## 动态与静态样例对照

两个阶段各自重新编译 PDF 并转换 SVG，不通过 SVG 缓存生成比较样本。六个文件逐字节相同：

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| font-sizes.pdf | 3882 | `cb9ecd23327211d8dc33495b1dcd242816d0c8ad35adc285658c096e3a30ba0d` |
| font-sizes.svg | 8902 | `f8e76810ed241379346551aedbd64f46ec242885f1b2160f33ef47f8b8e6df50` |
| pgfplots.pdf | 4063 | `f07915911bcbfc1d0c254ae87e8b949e387d7c39f09412811cbdc13b2b753f82` |
| pgfplots.svg | 12540 | `3ed14cec94a454f7a758b833707334013d54eecd57960bff4b701ded4385724c` |
| positioning-cjk.pdf | 4555 | `dd951e4091b409fae6fe83b94f4026210a857198d995fbfd30ef77cfe822229c` |
| positioning-cjk.svg | 13186 | `c5eee5730d6db451ee331fde6ba836a8da8b65321cb5b4a7467668a3585bd83a` |

## 后续验收

在 arm64 16 KB 真机/AVD 上确认 `PAGE_SIZE=16384` 且关闭页大小兼容模式，再运行真实编译/转换与 PRoot/搜索回归；记录实际系统版本、兼容模式开关和测试结果。完整 Linux 工作区启动、终端 UI、主线程耗时及 RSS 收益仍需单独验收；本次不作未测量的性能承诺。

## Release 产物

使用正常 application ID `app.androidrik.rikkahub`，版本 `1.0.28-beta.25 (329)`；初次验收仅构建；后续按用户要求进行了标准装机，见下节。装机未涉及远端推送。

| APK | 字节数 | SHA-256 |
| --- | ---: | --- |
| app-arm64-v8a-release.apk | 64567169 | `371d48796c0651dc215c9eea0b06127b7fc0179c05b36e961b0baf1cb2ffbf0c` |
| app-x86_64-release.apk | 47173636 | `f0802d9b1890d7096ad5ec5dedc73bf1adf4f2c8ec30079a3e9360c0e531e825` |
| app-universal-release.apk | 72347119 | `951643c370b746f457f78ead4c8635ff013870f7da020110aef31398828dbe09` |

文件位于 `app/build/outputs/apk/release/`。临时同包 debug 验收 APK 含测试类，只供隔离验收；release 未包含该测试宿主。

## 后续标准装机（2026-11-28）

按用户要求分别执行 `:app:assembleRelease --max-workers=2` 与 `:app:assembleDebug --max-workers=2`，未使用隔离 property 或临时测试 init 脚本。两次构建均成功。

| 版本 | 包名 | 安装与启动 | 设备/本地 APK SHA-256 |
| --- | --- | --- | --- |
| release | `app.androidrik.rikkahub` | `adb install -r` 成功，冷启动 `Status: ok` | `371d48796c0651dc215c9eea0b06127b7fc0179c05b36e961b0baf1cb2ffbf0c`，一致 |
| debug | `app.androidrik.rikkahub.debug` | `adb install -r` 成功，冷启动 `Status: ok` | `3a88c519fbdb9b9a50800179384681fc04a933a0a016731245ef7dc949874d4a`，一致 |

设备仍为 PJX110 / `6c841f95`，两版均为 `1.0.28-beta.25 (329)`。保留原应用数据，没有卸载或清除正式/debug 资料。release 正式签名指纹已核对，release 的原生库和 R8 门禁、debug 的原生库门禁通过；标准 debug APK 无临时 instrumentation 声明和 TectonicNativeTest 类。

release 另存到 `app/release/Neomix-1.0.28-beta.25-arm64-v8a.apk`；标准 debug 位于 `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`（113292501 字节）。构建日志为 `.tmp/native16kb-install-release-build.log` 与 `.tmp/native16kb-install-debug-build.log`。
