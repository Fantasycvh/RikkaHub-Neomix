package app.androidrik.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.test.platform.app.InstrumentationRegistry

/**
 * 基准参数的唯一来源：全部走 instrumentation 参数，调参不用改代码。
 *
 * Gradle 侧传入方式（键名去掉 `android.testInstrumentationRunnerArguments.` 前缀即可）：
 * ```
 * ./gradlew :app:baselineprofile:connectedReleaseAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.compilation=none \
 *   -Pandroid.testInstrumentationRunnerArguments.iterations=5
 * ```
 * 可配项：`compilation`、`iterations`、`seed`、`scrollRounds`、`seedMessages`，
 * 以及由模块 `build.gradle.kts` 的 `androidComponents` 自动注入的 `targetAppId`。
 */
internal object PerfConfig {

    /** 默认迭代次数：真机建议 8–10；模拟器只用来验证管线是否跑通，3 次足够。 */
    const val DEFAULT_ITERATIONS = 8

    /** 被测 App 的 applicationId（由 app 变体的 `androidComponents` 注入）。 */
    val targetPackage: String
        get() = arg("targetAppId") ?: error(
            "没有拿到 targetAppId：请通过 :app 变体的 connected 任务运行本模块，" +
                "或手动加 -Pandroid.testInstrumentationRunnerArguments.targetAppId=<applicationId>"
        )

    /**
     * 编译模式：
     * - `none`：不装 baseline profile，作为对照组；
     * - `partial`（默认）：用 App 自带的 baseline profile，贴近应用商店安装后的真实状态；
     * - `full`：全量 AOT，排除 profile 覆盖差异，适合比较两版实现。
     */
    fun compilationMode(): CompilationMode = when (arg("compilation")) {
        "none" -> CompilationMode.None()
        "full" -> CompilationMode.Full()
        else -> CompilationMode.Partial(BaselineProfileMode.UseIfAvailable)
    }

    fun iterations(default: Int = DEFAULT_ITERATIONS): Int =
        arg("iterations")?.toIntOrNull()?.takeIf { it > 0 } ?: default

    /** `seed=ui` 表示允许用 UiAutomator 脚本化造 fixture（实验性）；默认只校验、不造数据。 */
    fun uiSeeding(): Boolean = arg("seed") == "ui"

    fun scrollRounds(default: Int = 3): Int =
        arg("scrollRounds")?.toIntOrNull()?.takeIf { it > 0 } ?: default

    fun seedMessages(default: Int = 4): Int =
        arg("seedMessages")?.toIntOrNull()?.takeIf { it > 0 } ?: default

    private fun arg(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }
}
