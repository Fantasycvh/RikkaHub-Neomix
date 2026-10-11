#!/bin/bash
# 重编 dvisvgm_jni.o（含 Java_ JNI 符号）并手动链接 libdvisvgmexec.so
set -euo pipefail
cd /root/dvisvgm-3.2.1/src

# 0. 确保 JNI wrapper 是 Windows 侧最新版（Git Bash 的 cp 常被 MSYS 转换干扰）
cp ${TEX_POC_DIR:?set TEX_POC_DIR}/deprecated/dvisvgm_jni.cpp dvisvgm_jni.cpp
grep -c "Java_" dvisvgm_jni.cpp

# 1. JNI wrapper 对象（含 Java_…_dvisvgmMain 导出）
NDKBIN=/root/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin
"$NDKBIN/aarch64-linux-android26-clang++" -c dvisvgm_jni.cpp \
    -I. -I.. -I../libs/boost -I../libs/clipper -I../libs/variant/include \
    -I../libs/potrace -I../libs/xxHash -I/root/kpse-stub/include \
    -I/root/android-libs/include/freetype2 \
    -DHAVE_CONFIG_H -O2 -fPIC -Wno-mismatched-tags \
    -o dvisvgm_jni.o

echo "=== JNI symbols in object ==="
nm dvisvgm_jni.o | grep Java_ || echo "(none!)"

# 2. dvisvgm.cpp（dvisvgm_main_impl + main wrapper）对象
"$NDKBIN/aarch64-linux-android26-clang++" -c dvisvgm.cpp \
    -I. -I.. -I../libs/boost -I../libs/clipper -I../libs/variant/include \
    -I../libs/potrace -I../libs/xxHash -I/root/kpse-stub/include \
    -I/root/android-libs/include/freetype2 \
    -DHAVE_CONFIG_H -O2 -fPIC -Wno-mismatched-tags \
    -o dvisvgm.o

# 3. 手动链接共享库
"$NDKBIN/aarch64-linux-android26-clang++" -shared \
    dvisvgm_jni.o dvisvgm.o \
    .libs/libdvisvgm.a fonts/.libs/libbase14fonts.a optimizer/.libs/liboptimizer.a \
    ttf/.libs/libttf.a \
    ../libs/clipper/libclipper.a ../libs/md5/libmd5.a \
    ../libs/potrace/libpotrace.a ../libs/xxHash/libxxhash.a \
    /root/android-libs/lib/libfreetype.a -lz \
    /root/kpse-stub/lib/libkpathsea.a \
    -o /tmp/libdvisvgmexec.so

echo "=== final exports ==="
nm -D /tmp/libdvisvgmexec.so | grep -E "Java_|dvisvgm_main|main_impl" | head -5
ls -la /tmp/libdvisvgmexec.so