#!/usr/bin/env bash
# RikkaHub fork 构建封装：固定缓存迁移路径、pnpm PATH 与并行度限制。
# 用法（在 Git Bash 下，项目任意目录）：
#   bash scripts/build.sh debug          # :app:assembleDebug
#   bash scripts/build.sh release        # :app:assembleRelease --max-workers=2，成功后复制改名到 app/release/
#   bash scripts/build.sh ":app:testDebugUnitTest"   # 任意 gradle task，透传
set -euo pipefail

# 缓存迁移（2026-09-06，用户已做，C 盘旧缓存已清理）：
# 注意：本文件在 Git Bash 生效；PowerShell/CMD 需自行设置同名环境变量。
export GRADLE_USER_HOME=<gradle-home>/gradle
export PUB_CACHE=<gradle-home>/pub-cache

# web 模块 preBuild 需要 pnpm（全局目录不在 Git Bash 默认 PATH）
export PATH="<home>/AppData/Roaming/npm:$PATH"

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

case "${1:-}" in
  debug)
    shift || true
    ./gradlew :app:assembleDebug "$@"
    ;;
  release)
    shift || true
    # 勿用 -Pandroid.injected.build.abi（干扰 AGP splits）；OOM 用 --max-workers 限制而非提高 Xmx
    ./gradlew :app:assembleRelease --max-workers=2 "$@"
    # 复制改名：应用名-版本-架构（发布流程固定格式），产物暂存 app/release/（已 gitignore）
    APK="$(ls app/build/outputs/apk/release/app-arm64-v8a-release.apk 2>/dev/null | head -1)"
    if [ -n "$APK" ]; then
      VERSION="$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)"
      mkdir -p app/release
      cp "$APK" "app/release/Neomix-$VERSION-arm64-v8a.apk"
      echo "APK ready: app/release/Neomix-$VERSION-arm64-v8a.apk"
    else
      echo "warning: arm64 release APK not found" >&2
    fi
    ;;
  *)
    # 其余任意 gradle 参数直接透传（如 ":app:testDebugUnitTest" "--some-flag"）
    ./gradlew "$@"
    ;;
esac
