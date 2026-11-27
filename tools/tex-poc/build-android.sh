#!/usr/bin/env bash
# Tectonic Android POC 交叉编译脚本（WSL Ubuntu 24.04 内执行）
# 前置：rustup(stable) + aarch64-linux-android target + NDK r27c(linux-x86_64) 于 ~/ndk
#       先用 build-graphite2.sh 准备隔离依赖前缀（静态 graphite2 + 原有其他依赖）
# 详见 docs/tikz-rendering-tectonic-postmortem.md 8.4
set -euo pipefail

: "${NDKBIN:=$HOME/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin}"
export NDKBIN
export CC_aarch64_linux_android=$NDKBIN/aarch64-linux-android26-clang
export CXX_aarch64_linux_android=$NDKBIN/aarch64-linux-android26-clang++
export AR_aarch64_linux_android=$NDKBIN/llvm-ar
export RANLIB=$NDKBIN/llvm-ranlib
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER:-$CC_aarch64_linux_android}"
: "${NATIVE_BUILD_ROOT:=$HOME/neomix-native-16kb}"
: "${GRAPHITE2_LINKAGE:=static}"
: "${ANDROID_LIBS:=$NATIVE_BUILD_ROOT/deps-$GRAPHITE2_LINKAGE}"
case "$GRAPHITE2_LINKAGE" in
  static)
    test -f "$ANDROID_LIBS/lib/libgraphite2.a" && test ! -e "$ANDROID_LIBS/lib/libgraphite2.so" || {
      echo 'Prepare a static-only Graphite2 prefix using build-graphite2.sh' >&2; exit 1;
    }
    export TECTONIC_PKGCONFIG_FORCE_SEMI_STATIC=1
    export CFLAGS_aarch64_linux_android="${CFLAGS_aarch64_linux_android:-} -DGRAPHITE2_STATIC"
    export CXXFLAGS_aarch64_linux_android="${CXXFLAGS_aarch64_linux_android:-} -DGRAPHITE2_STATIC"
    ;;
  shared) unset TECTONIC_PKGCONFIG_FORCE_SEMI_STATIC ;;
  *) echo 'GRAPHITE2_LINKAGE must be static or shared' >&2; exit 2 ;;
esac
export PKG_CONFIG_PATH=$ANDROID_LIBS/lib/pkgconfig
export PKG_CONFIG_LIBDIR=$ANDROID_LIBS/lib/pkgconfig
export PKG_CONFIG_ALLOW_CROSS=1
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"

cd "$(dirname "$0")"
cargo build --locked --release --lib --target aarch64-linux-android
# AGP 的 jniLibs 不接受版本化文件名（libicuuc.so.78），patchelf 改写 NEEDED
artifact="${CARGO_TARGET_DIR:-target}/aarch64-linux-android/release/libtex_poc.so"
patchelf --page-size 16384 --replace-needed libicuuc.so.78 libicuuc78.so "$artifact"
echo "OK: $artifact"
