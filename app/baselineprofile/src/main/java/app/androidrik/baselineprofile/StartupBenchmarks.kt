package app.androidrik.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import app.androidrik.baselineprofile.journeys.openChat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 启动基准：对比「无 baseline profile」与「用 App 自带 baseline profile」两种编译模式下的冷启动。
 *
 * 本类刻意 **不依赖 fixture**（空数据也能跑），用于验证 baseline profile 本身是否生效；
 * 有数据 + 有交互的 journey 在 [JourneyBenchmarks]。
 *
 * 运行（task 名以 `./gradlew :app:baselineprofile:tasks --all` 为准）：
 * ```
 * ./gradlew :app:baselineprofile:connectedReleaseAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=app.androidrik.baselineprofile.StartupBenchmarks
 * ```
 * 指标：TTID + 帧；本仓 App 未调用 `reportFullyDrawn()`，TTFD 恒缺（后续工作）。
 * 务必在真机上取数：模拟器软件渲染、与宿主机共享资源，只适合验证管线通不通。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    private val runner = BenchmarkRunner(rule)

    @Test
    fun startupCompilationNone() = runner.startup(
        compilationMode = CompilationMode.None(),
    ) {
        openChat()
    }

    @Test
    fun startupCompilationBaselineProfiles() = runner.startup(
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
    ) {
        openChat()
    }
}
