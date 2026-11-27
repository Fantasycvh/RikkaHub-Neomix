#!/usr/bin/env bash
# Reuse the recovered sources/dependencies without modifying their baseline.
set -euo pipefail
: "${NDK:=$HOME/ndk}"
: "${ANDROID_LIBS_BASE:=/root/android-libs}"
: "${GRAPHITE2_SOURCE:=/root/graphite2-1.3.15}"
: "${NATIVE_BUILD_ROOT:=$HOME/neomix-native-16kb}"
: "${GRAPHITE2_LINKAGE:=static}"
case "$GRAPHITE2_LINKAGE" in shared) shared=ON ;; static) shared=OFF ;; *) exit 2 ;; esac
prefix="$NATIVE_BUILD_ROOT/deps-$GRAPHITE2_LINKAGE"
build="$NATIVE_BUILD_ROOT/graphite-$GRAPHITE2_LINKAGE"
base=$(realpath "$ANDROID_LIBS_BASE")
destination=$(realpath -m "$prefix")
case "$base/" in "$destination/"*) echo 'Output must not contain the baseline' >&2; exit 2 ;; esac
case "$destination/" in "$base/"*) echo 'Output must be outside the baseline' >&2; exit 2 ;; esac
mkdir -p "$prefix" "$build"
cp -a "$base/." "$prefix/"
# The recovered .pc files contain absolute prefixes (some omit prefix variables).
python3 - "$prefix" "$base" <<'PY'
import pathlib, sys
prefix, base = sys.argv[1:]
for p in pathlib.Path(prefix, 'lib/pkgconfig').glob('*.pc'):
    p.write_text(p.read_text().replace(base, prefix))
PY
cmake -S "$GRAPHITE2_SOURCE" -B "$build" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS="$shared" \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DGRAPHITE2_NTRACING=ON \
  -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384' \
  -DCMAKE_INSTALL_PREFIX="$prefix"
cmake --build "$build" --parallel "${NATIVE_JOBS:-4}"
cmake --install "$build"
if [ "$GRAPHITE2_LINKAGE" = static ]; then
  # Only remove the copied dynamic libraries from this isolated prefix.
  rm -f -- "$prefix"/lib/libgraphite2.so*
  python3 - "$prefix/lib/pkgconfig/graphite2.pc" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
p.write_text(p.read_text().replace('Cflags:', 'Cflags: -DGRAPHITE2_STATIC'))
PY
fi
printf 'ANDROID_LIBS=%s\n' "$prefix"
