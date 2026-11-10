# app/baselineprofile：通用 Compose macrobenchmark 管线

> **状态：未运行验证。** 本机没有连接任何设备/模拟器，也没有跑 Gradle。代码按 AndroidX Macrobenchmark
> 1.4.1 的稳定 API 编写，任务名与 Gradle 接线只做了静态核对；第一次在真机上跑之前，请按下面的
> 「首次运行清单」逐条确认，并把不符合预期的地方回填到本文档。

本模块原来是「Baseline Profile 生成器 + 启动基准」两件套，现在补成一条可复用的宏基准管线：

| 部分 | 职责 |
| --- | --- |
| `BaselineProfileGenerator.kt` | 生成 baseline profile（启动 → 聊天页 → 发一条 Markdown → 滚一下） |
| `StartupBenchmarks.kt` | 启动基准：`CompilationMode.None` vs `Partial(Require)` |
| `JourneyBenchmarks.kt` | **新增**：3 条通用 journey（冷启动到聊天页、长对话滚动、输入并发送） |
| `PerfConfig.kt` | **新增**：参数唯一来源（包名/编译模式/迭代次数/造数据开关） |
| `PerfMetrics.kt` | **新增**：统一指标集合（`StartupTimingMetric` + `FrameTimingMetric`） |
| `BenchmarkRunner.kt` | **新增**：收敛 `measureRepeated` 样板参数 |
| `journeys/ChatJourneys.kt` | **新增**：可复用 journey 片段（只认 testTag，不认文案） |
| `fixtures/FixtureSpec.kt` | **新增**：fixture 契约（文本标记 + 确定性长 Markdown 样本） |
| `fixtures/FixtureVerifier.kt` | **新增**：fixture 前置校验 / 实验性 UiAutomator 造数据 |
| `perf/run_bench.sh` | **新增**：一台设备一条命令跑完 + 汇总 |
| `perf/summarize.py` | **新增**：把 `*-benchmarkData.json` 汇总成表（P50/P90/P99 + 丢帧率） |

设计参考 Miffan 的 `7442ea738` 系列（见 `docs/references/other projects/miffan.md` 的 2026-11-03→11-09
增量节），但受本仓约束改了两处关键设计，见下文「与参考实现的差异」。

## 首次运行清单（没有设备时请勿跳过）

1. 一台**真机**（API 28+，推荐 33+）已连接：`adb devices` 有且只有一台，或显式传序列号。
2. `:app` 的 `release` 变体能装到机器上：签名配置来自 `local.properties`
   （`storeFile`/`storePassword`/`keyAlias`/`keyPassword`）。宏基准要求**非 debuggable**的包，
   debug 变体不行；release 变体同时必须 `android:profileable`（Android 12+ 用 profiling 工具的前提），
   本仓主 manifest 目前没有声明，若启动基准报 "not profileable"，需要在 `:app` 侧补
   `<profileable android:shell="true" />`（属 App 改动，本任务未做）。
3. 桌面无锁屏、系统动画/省电模式不影响（Macrobenchmark 会自行调整动画与电量策略，但别开省电模式跑）。
4. fixture 就位（下一节）。**没有 fixture 时 `JourneyBenchmarks` 会直接失败**——这是刻意的：
   不在错误的数据集上产出「看起来正常」的指标。
5. Python 3（只跑 `summarize.py` 时需要；标准库即可）。

## fixture（数据就位）

`com.android.test` 模块拿不到 App 的 Room/仓库类，也没有权限直接写 App 私有目录，所以 fixture 只能
「通过 UI 写入 + 通过无障碍文本校验」。标记文本是 `perf-fixture`（`FixtureSpec.MARKER`）。

三种就位方式：

1. **手工（推荐，最稳）**：在被测 App 里发 4 条左右长 Markdown 消息，每条正文以 `perf-fixture` 开头
   （标题/列表/表格/代码块/公式都带上）。之后 App 会把这个对话记为 `lastConversationId`，冷启动直接
   恢复它，journey 就能在「有数据的长对话」里跑。
2. **实验性自动造数据**：加 `-Pandroid.testInstrumentationRunnerArguments.seed=ui`（或脚本参数
   `--seed ui`），`FixtureVerifier` 会用 UiAutomator 往输入框 `setText` 长文本并点发送，默认 4 条。
   限制：需要先配好任意一个模型（地址可以无效，离线时请求快速失败，但用户消息会落库）；首次安装的
   引导必须已经完成；**该路径未在真机验证过**。
3. **后续（需改 App）**：像 Miffan 那样在 app 里加一个 debug/benchmark 专用广播接收器
   （`am broadcast -a <pkg>.PERF_FIXTURE`）直接写数据库。这是最可靠的方案，但需要动 `app` 源码，
   本任务的范围不允许。

Fixture 校验发生在基准类的 `@BeforeClass`（不计入测量），结束后会 `am force-stop` 被测进程，
保证每次测量都从冷启动/前台恢复开始。

## 运行

```bash
# 一条命令：跑全部 3 条 journey + 汇总
app/baselineprofile/perf/run_bench.sh --serial <serial>

# 只跑滚动 journey，5 次迭代，无 profile 对照组
app/baselineprofile/perf/run_bench.sh <serial> --tests longConversationScroll --iterations 5 --compilation none

# 启动基准（不依赖 fixture）
app/baselineprofile/perf/run_bench.sh <serial> \
  --class app.androidrik.baselineprofile.StartupBenchmarks --tests startupCompilationNone,startupCompilationBaselineProfiles

# 底层等价命令（任务名不确定时先 `./gradlew :app:baselineprofile:tasks --all | grep -i connected`）
./gradlew :app:baselineprofile:connectedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.androidrik.baselineprofile.JourneyBenchmarks \
  -Pandroid.testInstrumentationRunnerArguments.compilation=partial \
  -Pandroid.testInstrumentationRunnerArguments.iterations=8 \
  -Pandroid.testInstrumentationRunnerArguments.seed=ui
```

> 任务名说明：Android Studio 模板里的 `connectedBenchmarkReleaseAndroidTest` 需要 `:app` 有一个
> `benchmark` build type，本仓没有（只有 `debug`/`release`），所以这里写的是
> `connectedReleaseAndroidTest`。若 AGP 生成的变体名不同，按 `--all | grep -i connected` 的实际名字用
> `PERF_TASK` 覆盖。

### 参数

| instrumentation 参数 | 对应脚本参数 | 默认 | 说明 |
| --- | --- | --- | --- |
| `targetAppId` | 自动注入 | — | 被测包名，由模块 `build.gradle.kts` 的 `androidComponents` 注入 |
| `compilation` | `--compilation` | `partial` | `none` / `partial` / `full` |
| `iterations` | `--iterations` | 8 | 每个用例的重复次数 |
| `seed` | `--seed` | 空 | `ui` 表示允许 UiAutomator 造 fixture |
| `seedMessages` | 无 | 4 | 造数据条数 |
| `scrollRounds` | `--scroll-rounds` | 3 | 滚动 journey 的上下往返轮数 |

## 结果怎么看

- 原始 JSON：`app/baselineprofile/build/outputs/**/connected_android_test_additional_output/**/*-benchmarkData.json`
  （不同 AGP 版本路径略有差异，脚本用 `find` 兜底）。
- 汇总表：`perf/summarize.py <文件/目录>`，列含义：

| 列 | 含义 |
| --- | --- |
| `ttid` | 冷启动到首帧（`timeToInitialDisplayMs` 中位数，ms） |
| `p50/p90/p99` | 逐帧 CPU 耗时（`frameDurationCpuMs`），ms；P90/P99 才是「掉帧手感」 |
| `jank%` | `frameOverrunMs > 0` 的帧占比（错过 deadline 的比例） |
| `frames` | 统计到的总帧数 |

**没有 `ttfd`**：本仓 App 没有调用 `reportFullyDrawn()`（也没接 Compose `ReportDrawn`），
`StartupTimingMetric` 只能报 TTID。补 `ReportDrawn` 是 App 侧改动，属后续工作。

数值只在「同设备 + 同编译模式 + 同 fixture」下横比有意义：改代码前后各跑一次，比对 P90/P99 与 jank%。

## CI

本仓 CI 目前没有 baseline profile/宏基准流水线（`.github/workflows` 只有构建/发布）。接入建议：

- 需要 **self-hosted runner + 真机**（GitHub 托管 runner 没有设备；模拟器数值不可用）；
- 夜里跑 `--compilation partial --iterations 8`，把 `summarize.py` 输出的表作为 artifact 存档；
- 只有在「同设备同参数」连续多天基线稳定后，才对 P90/丢帧率设阈值报警，避免误报；
- 生成的 baseline profile（`app/src/release/generated/baselineProfiles/baseline-prof.txt`）不要由 CI
  自动提交，人工看过 diff 再提交。

## 已知限制（都是刻意取舍，不是遗漏）

1. **未运行验证**：无设备、无 Gradle 运行。首次真机运行前，任务名、`profileable`、签名、fixture 四条
   都可能需要现场调整。
2. **没有假 provider**：发送 journey 只测「输入 → 发送 → 用户气泡上屏」，**不测流式回复渲染**。
   要测需在 App 里配一个本地 OpenAI 兼容端点（Miffan 用 host 侧 `fake_llm.py` + `adb reverse`），
   属 App/脚本侧后续工作。
3. **不能跨对话导航**：会话列表/抽屉没有 `testTag`，所以 journey 依赖冷启动恢复 `lastConversationId`。
   要测「列表里点开另一个对话」需要先给会话行加 testTag（App 改动）。
4. **滚动靠 `By.scrollable(true)` 找列表**：输入框内部也是 scrollable，若先匹配到输入框，滚动 journey
   的帧会变成在测输入框；届时给消息列表加一个 `testTag`（App 改动）并把选择器换成 `By.res(...)`。
4. **TTFD 缺失**：见上。
5. **只用稳定指标**：不用 `FrameTimingGfxInfoMetric`（实验 API + 模拟器无意义）和 `TraceSectionMetric`
   （需要 App 里埋点）。
6. **模拟器只用于验证管线**：`androidx.benchmark.suppressErrors=EMULATOR` 已经打开，但模拟器上的帧数
   不代表真实体验。

## 与参考实现的差异

- **fixture 不进模块**：Miffan 把 fixture 放在 `app/src/perfFixture`（专用 source set + 广播接收器），
  能确定性写 Room，是最优解；本任务限定「只改 `app/baselineprofile/**`」，所以改成「手工/UI 造数据 +
  启动前校验 + 缺数据直接失败」。
- **没有 fake LLM**：同上，流式回复 journey 未做。
- **Gradle 未改**：`androidx.baselineprofile` 1.5.0-beta01 插件本身就会在 TestExtension 上设置
  `android.experimental.self-instrumenting=true`（已反编译插件确认），`targetProjectPath` 也已就位，
  所以本模块的 Gradle 配置不需要动；`:app` 侧同样未动。

## 后续工作

1. `:app` 加 `benchmark` build type（debug 签名、`nonMinifiedRelease` 对照）+ `<profileable>` 清单，
   让任务名回到 `connectedBenchmarkReleaseAndroidTest`，并避免依赖 release 签名。
2. `app/src/perfFixture` 广播接收器 + `scripts/perf/fake_llm.py`（确定性长对话 + 可控流式回复）。
3. App 侧接 `ReportDrawn`（拿 TTFD）与关键路径 `Trace.beginSection`（用 `TraceSectionMetric` 量
   「打开对话」「流式首帧」等分段耗时）。
4. 会话列表行加 `testTag`，补「打开别的对话 / 切 Tab」journey。
5. 真机跑通后回填本节的实测结论与设备信息。
