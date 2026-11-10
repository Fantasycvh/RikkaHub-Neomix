#!/usr/bin/env bash
# 通用宏基准运行脚本：连一台设备 → 跑 :app:baselineprofile 的 connected 任务 → 把 JSON 汇总成表。
#
#   app/baselineprofile/perf/run_bench.sh [serial] [选项]
#
#   --class NAME      基准类（默认 app.androidrik.baselineprofile.JourneyBenchmarks）
#   --tests A,B       只跑指定方法（与 --class 组合成 class=类#方法）
#   --compilation M   none|partial|full（默认 partial，用 App 自带 baseline profile）
#   --iterations N    覆盖每个用例的迭代次数
#   --seed ui         允许用 UiAutomator 造 fixture（默认只校验，缺数据直接失败）
#   --scroll-rounds N 滚动 journey 的轮数
#   --no-summary      只跑不汇总
#
# 前置：设备已连接（adb devices 可见）、:app 的 release 变体可安装（local.properties 配了签名）、
# fixture 已就位（见模块 README.md）。本仓暂无 benchmark 专用变体，任务名若不同用 PERF_TASK 覆盖：
#   PERF_TASK=:app:baselineprofile:connectedReleaseAndroidTest app/baselineprofile/perf/run_bench.sh
# 先确认任务名：
#   ./gradlew :app:baselineprofile:tasks --all | grep -i connected
#
# 注意：本脚本与整条管线在本机 **未运行验证**（无设备），见 README.md。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
TASK="${PERF_TASK:-:app:baselineprofile:connectedReleaseAndroidTest}"
SERIAL="${ANDROID_SERIAL:-}"
CLASS="app.androidrik.baselineprofile.JourneyBenchmarks"
TESTS=""
COMPILATION="partial"
ITERATIONS=""
SEED=""
SCROLL_ROUNDS=""
SUMMARY=1

usage() { sed -n '2,22p' "$0"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --class) CLASS="$2"; shift 2 ;;
    --tests) TESTS="$2"; shift 2 ;;
    --compilation) COMPILATION="$2"; shift 2 ;;
    --iterations) ITERATIONS="$2"; shift 2 ;;
    --seed) SEED="$2"; shift 2 ;;
    --scroll-rounds) SCROLL_ROUNDS="$2"; shift 2 ;;
    --no-summary) SUMMARY=0; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "未知选项：$1" >&2; exit 2 ;;
    *) SERIAL="$1"; shift ;;
  esac
done

command -v adb >/dev/null || { echo "PATH 里没有 adb，先装 Android platform-tools" >&2; exit 2; }

if [[ -z "$SERIAL" ]]; then
  DEVICES="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
  COUNT="$(printf '%s\n' "$DEVICES" | grep -c . || true)"
  if [[ "$COUNT" != "1" ]]; then
    echo "请显式传设备序列号；当前已连接：$(printf '%s' "${DEVICES:-无}")" >&2
    exit 2
  fi
  SERIAL="$DEVICES"
fi
export ANDROID_SERIAL="$SERIAL"
adb -s "$SERIAL" get-state >/dev/null

ARGS=("-Pandroid.testInstrumentationRunnerArguments.compilation=$COMPILATION")
if [[ -n "$ITERATIONS" ]]; then
  ARGS+=("-Pandroid.testInstrumentationRunnerArguments.iterations=$ITERATIONS")
fi
if [[ -n "$SEED" ]]; then
  ARGS+=("-Pandroid.testInstrumentationRunnerArguments.seed=$SEED")
fi
if [[ -n "$SCROLL_ROUNDS" ]]; then
  ARGS+=("-Pandroid.testInstrumentationRunnerArguments.scrollRounds=$SCROLL_ROUNDS")
fi
if [[ -n "$TESTS" ]]; then
  SELECTOR=""
  IFS=',' read -ra NAMES <<<"$TESTS"
  for name in "${NAMES[@]}"; do
    SELECTOR+="$CLASS#$name,"
  done
  ARGS+=("-Pandroid.testInstrumentationRunnerArguments.class=${SELECTOR%,}")
else
  ARGS+=("-Pandroid.testInstrumentationRunnerArguments.class=$CLASS")
fi

echo "设备：$SERIAL"
echo "任务：$TASK"
echo "参数：${ARGS[*]}"
cd "$ROOT"
./gradlew "$TASK" "${ARGS[@]}"

if (( SUMMARY == 0 )); then
  exit 0
fi

OUTPUT_ROOT="$ROOT/app/baselineprofile/build/outputs"
FILES=()
while IFS= read -r file; do
  FILES+=("$file")
done < <(find "$OUTPUT_ROOT" -name "*-benchmarkData.json" 2>/dev/null | sort)

if [[ ${#FILES[@]} -eq 0 ]]; then
  echo "没有找到 *-benchmarkData.json（预期在 $OUTPUT_ROOT 下）；可用 --no-summary 并手动找输出。" >&2
  exit 1
fi
command -v python3 >/dev/null || { echo "PATH 里没有 python3，无法汇总（可加 --no-summary 跳过）" >&2; exit 2; }
echo
python3 "$HERE/summarize.py" "${FILES[@]}"
