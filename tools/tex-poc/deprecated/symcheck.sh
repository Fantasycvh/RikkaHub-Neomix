#!/bin/bash
# 检查两个 so 的符号冲突：tectonic(内部 fontconfig stub) vs dvisvgmexec(kpathsea stub)
echo "=== libtex_poc.so 导出的 kpse_/Fc 符号 ==="
nm -D /tmp/apk_libtex.so 2>/dev/null | grep -E " T (kpse_|Fc)" | head -8
echo "=== libdvisvgmexec.so 导出的 kpse_/Fc 符号 ==="
nm -D ${APP_REPO_DIR:?set APP_REPO_DIR}/app/src/main/jniLibs/arm64-v8a/libdvisvgmexec.so | grep -E " T (kpse_|Fc)" | head -8
echo "=== libdvisvgmexec.so 导出符号总数 ==="
nm -D ${APP_REPO_DIR:?set APP_REPO_DIR}/app/src/main/jniLibs/arm64-v8a/libdvisvgmexec.so | grep -c " T "