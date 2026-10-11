# deprecated —— dvisvgm 链（已废弃，仅存档）

2026-09-07/08 起，PDF→SVG 改为**自写 JNI 桥调 mupdf**（源码 `app/src/main/cpp/pdf2svg_bridge.c`，
产物 `libpdf2svg_bridge.so`），dvisvgm 链及其产物 `libdvisvgmexec.so` 已从 app 移除。
本目录文件**按原样存档，不参与当前构建**，保留原因只有一条：符号劫持 / kpathsea / fontconfig 那几个
坑的记录价值（详见 `docs/tikz-rendering-tectonic-postmortem.md`）。

| 文件 | 原始作用 |
|---|---|
| `dvisvgm_jni.cpp` | 把 `dvisvgm_main_impl` 包成 JNI 入口 `..._TectonicNative_dvisvgmMain` |
| `final_build.sh` | 一次性链接 `libdvisvgmexec.so`（用 version-script 只导出 1 个 JNI 符号） |
| `rebuild_jni.sh` | 只重编 `dvisvgm_jni.o` 再链接 |
| `check_jni.sh` / `check_jni_sig.sh` | 同步/校验 JNI 源与导出符号 |
| `symcheck.sh` | 检查 `libtex_poc.so` 与 `libdvisvgmexec.so` 的 `kpse_`/`Fc` 符号冲突（同进程符号劫持会 SIGABRT） |
| `diag1.sh` | 真机 tombstone 头部抓取 |
| `make_pgp_test.py` | 往 pgfplots 用例里注入 `pgfsys-dvisvgm.def` 的一次性实验脚本 |
| `kpsestub.c` / `fcstub.c` / `c-auto.h` / `fontmap.map` | dvisvgm 的 kpathsea / fontconfig 桩与配置 |

## 关于路径

存档脚本里原本写死的机器路径已替换成环境变量占位，运行前需自行指定：

- `${TEX_POC_DIR}` → 本目录的上级（即 `tools/tex-poc`）
- `${APP_REPO_DIR}` → 仓库根

其余 `/root/...` 是本链当初的 WSL 环境（dvisvgm 源码、kpse stub、android-libs），未包含在本仓库内。

## 未纳入仓库的原 POC 内容

原 POC 目录里的 `push/`（产物暂存区：TeX 字模 + `libtex_poc.so`/`libdvisvgmexec.so`/libicu 等 .so 副本）
与 `assets/`（`tectonic-bundle*.tikzbundle`、`simhei.ttf`、`droidsans.ttf` 的工作副本）都**没有**进来：
前者是产物，后者与 app 内已跟踪资产重复（且 md5 已不同）。
