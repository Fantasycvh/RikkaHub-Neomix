package app.androidrik.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode

/**
 * 收敛 [MacrobenchmarkRule.measureRepeated] 的样板参数：包名、指标、编译模式、迭代次数全部来自
 * [PerfConfig]，journey 只负责写 setup/measure 两段。
 *
 * 这样新增一条 journey 只需要一个 `@Test` + 两个 lambda，指标口径与默认迭代次数不会各写各的。
 */
internal class BenchmarkRunner(private val rule: MacrobenchmarkRule) {

    /**
     * 启动型 journey：固定 [StartupMode.COLD]，指标 = TTID/TTFD + 帧。
     * setup 固定 `pressHome()`，measure 里再 `startActivityAndWait()`，与官方宏基准模板一致。
     */
    fun startup(
        compilationMode: CompilationMode = PerfConfig.compilationMode(),
        iterations: Int = PerfConfig.iterations(),
        measure: MacrobenchmarkScope.() -> Unit,
    ) = rule.measureRepeated(
        packageName = PerfConfig.targetPackage,
        metrics = PerfMetrics.startup(),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = iterations,
        setupBlock = { pressHome() },
        measureBlock = measure,
    )

    /**
     * 交互型 journey：指标只取帧，默认 WARM（进程已存在，不复测冷启动；
     * 启动开销由启动型 journey 单独负责）。
     */
    fun frames(
        iterations: Int = PerfConfig.iterations(),
        startupMode: StartupMode = StartupMode.WARM,
        setup: MacrobenchmarkScope.() -> Unit,
        measure: MacrobenchmarkScope.() -> Unit,
    ) = rule.measureRepeated(
        packageName = PerfConfig.targetPackage,
        metrics = PerfMetrics.frames(),
        compilationMode = PerfConfig.compilationMode(),
        startupMode = startupMode,
        iterations = iterations,
        setupBlock = setup,
        measureBlock = measure,
    )
}
