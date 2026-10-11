#!/bin/bash
# 一次性构建 libdvisvgmexec.so（JNI 化 dvisvgm）并拷回 Windows 两侧
set -euo pipefail

SRC=/root/dvisvgm-3.2.1/src
WIN_JNI=${TEX_POC_DIR:?set TEX_POC_DIR}/deprecated/dvisvgm_jni.cpp
NDKBIN=/root/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin

echo "=== 0. 同步 JNI 源（Windows 新版 -> WSL）==="
cp "$WIN_JNI" "$SRC/dvisvgm_jni.cpp"
JCOUNT=$(grep -c "Java_" "$SRC/dvisvgm_jni.cpp")
echo "Java_ count in source: $JCOUNT"
[ "$JCOUNT" -ge 1 ] || { echo "FATAL: no Java_ symbol in source"; exit 1; }

echo "=== 1. 编译 dvisvgm_jni.o ==="
"$NDKBIN/aarch64-linux-android26-clang++" -c "$SRC/dvisvgm_jni.cpp" \
    -I"$SRC" -I"$SRC/.." -I"$SRC/../libs/boost" -I"$SRC/../libs/clipper" \
    -I"$SRC/../libs/variant/include" -I"$SRC/../libs/potrace" -I"$SRC/../libs/xxHash" \
    -I/root/kpse-stub/include -I/root/android-libs/include/freetype2 \
    -DHAVE_CONFIG_H -O2 -fPIC -Wno-mismatched-tags -o "$SRC/dvisvgm_jni.o"

echo "=== 2. 断言 JNI 符号导出 ==="
nm "$SRC/dvisvgm_jni.o" | grep "Java_" || { echo "FATAL: JNI symbol not exported"; exit 1; }
nm "$SRC/dvisvgm_jni.o" | grep "Java_" | head -2

echo "=== 3. 编译 dvisvgm.o（含 dvisvgm_main_impl）==="
"$NDKBIN/aarch64-linux-android26-clang++" -c "$SRC/dvisvgm.cpp" \
    -I"$SRC" -I"$SRC/.." -I"$SRC/../libs/boost" -I"$SRC/../libs/clipper" \
    -I"$SRC/../libs/variant/include" -I"$SRC/../libs/potrace" -I"$SRC/../libs/xxHash" \
    -I/root/kpse-stub/include -I/root/android-libs/include/freetype2 \
    -DHAVE_CONFIG_H -O2 -fPIC -Wno-mismatched-tags -o "$SRC/dvisvgm.o"

nm "$SRC/dvisvgm.o" | grep -E "dvisvgm_main_impl|dvisvgm_main_impl" | head -2

echo "=== 4. 链接共享库（符号隔离：只导出 JNI 入口）==="
# 同进程还加载了 libtex_poc.so（tectonic）；若本库导出 kpse_/Fc 等符号，
# 动态链接器会全局介入，劫持 tectonic 内部同名调用导致状态错乱（真机
# ttbc_output_close SIGABRT）。version-script 只留 JNI 函数。
cat > /tmp/dvisvgmexec.map <<'MAP'
{
  global:
    Java_app_androidrik_rikkahub_tikz_TectonicNative_dvisvgmMain;
  local:
    *;
};
MAP
"$NDKBIN/aarch64-linux-android26-clang++" -shared \
    "$SRC/dvisvgm_jni.o" "$SRC/dvisvgm.o" \
    "$SRC/.libs/libdvisvgm.a" "$SRC/fonts/.libs/libbase14fonts.a" "$SRC/optimizer/.libs/liboptimizer.a" \
    "$SRC/ttf/.libs/libttf.a" \
    "$SRC/../libs/clipper/libclipper.a" "$SRC/../libs/md5/libmd5.a" \
    "$SRC/../libs/potrace/libpotrace.a" "$SRC/../libs/xxHash/libxxhash.a" \
    /root/android-libs/lib/libfreetype.a -lz \
    /root/kpse-stub/lib/libkpathsea.a \
    -Wl,--exclude-libs,ALL \
    -Wl,--version-script=/tmp/dvisvgmexec.map \
    -o /tmp/libdvisvgmexec.so

echo "=== 5. 验证导出 ==="
nm -D /tmp/libdvisvgmexec.so | grep -E "Java_|dvisvgm_main_impl" | head -4
TOTAL=$(nm -D /tmp/libdvisvgmexec.so | grep -c " T ")
echo "exported T symbols: $TOTAL (应为 1，仅 JNI 入口)"
[ "$TOTAL" -eq 1 ] || { echo "FATAL: symbol isolation failed"; exit 1; }
if nm -D /tmp/libdvisvgmexec.so | grep -q " U dvisvgm_main_impl"; then
    echo "FATAL: dvisvgm_main_impl still undefined (C-linkage mismatch)"
    exit 1
fi

echo "=== 6. 拷回 Windows ==="
cp /tmp/libdvisvgmexec.so ${TEX_POC_DIR:?set TEX_POC_DIR}/push/libdvisvgmexec.so
cp /tmp/libdvisvgmexec.so ${APP_REPO_DIR:?set APP_REPO_DIR}/app/src/main/jniLibs/arm64-v8a/libdvisvgmexec.so
ls -la /tmp/libdvisvgmexec.so ${APP_REPO_DIR:?set APP_REPO_DIR}/app/src/main/jniLibs/arm64-v8a/libdvisvgmexec.so
echo "DONE"