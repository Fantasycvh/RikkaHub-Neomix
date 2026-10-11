#!/usr/bin/env bash
# Tectonic Android POC 交叉编译脚本（WSL Ubuntu 24.04 内执行）
# 前置：rustup(stable) + aarch64-linux-android target + NDK r27c(linux-x86_64) 于 ~/ndk
#       /root/android-libs 下有交叉编译的 libpng/graphite2/freetype + termux libicu + fontconfig stub
# 详见 docs/tikz-rendering-tectonic-postmortem.md 8.4
set -euo pipefail

: "${NDKBIN:=$HOME/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin}"
export NDKBIN
export CC_aarch64_linux_android=$NDKBIN/aarch64-linux-android26-clang
export CXX_aarch64_linux_android=$NDKBIN/aarch64-linux-android26-clang++
export AR_aarch64_linux_android=$NDKBIN/llvm-ar
export RANLIB=$NDKBIN/llvm-ranlib
: "${ANDROID_LIBS:=/root/android-libs}"
export PKG_CONFIG_PATH=$ANDROID_LIBS/lib/pkgconfig
export PKG_CONFIG_ALLOW_CROSS=1

cd "$(dirname "$0")"
cargo build --release --target aarch64-linux-android
# AGP 的 jniLibs 不接受版本化文件名（libicuuc.so.78），patchelf 改写 NEEDED
patchelf --replace-needed libicuuc.so.78 libicuuc78.so target/aarch64-linux-android/release/libtex_poc.so
echo "OK: target/aarch64-linux-android/release/libtex_poc.so"
