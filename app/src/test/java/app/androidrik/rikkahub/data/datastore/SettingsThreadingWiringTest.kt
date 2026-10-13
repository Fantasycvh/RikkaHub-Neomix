package app.androidrik.rikkahub.data.datastore

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「设置解码不许跑在主线程」的**接线守卫**（源码扫描，与 `MemoryConsolidationWiringTest` /
 * `ThinkingDegenerationWiringTest` 同款做法）。
 *
 * ## 失效形态（为什么值得一条守卫）
 *
 * `SettingsStore` 的 scope 是 `AppScope` = **`Dispatchers.Main`**（见 `RikkaHubApp.AppScope`），
 * 而 `settingsFlow` 用 `toMutableStateFlow(scope, …)` 在该 scope 里 `collect`
 * （`utils/CoroutineUtils.kt`）⇒ **只要流链上没有 `flowOn`，整条上游映射就在主线程执行**：
 * Preferences→Settings 的逐字段解码（含多处 `JsonInstant.decodeFromString`）、内置回填、
 * 以及对 providers/assistants/modeInjections/lorebooks/promptPresets 的逐个 `distinctBy`
 * （后者是 fork 自己的「悬空引用过滤」定制，比上游更重）。
 *
 * 这段代码**没有任何编译期信号**：删掉 `flowOn` 一切照常编译、测试照常绿，只是冷启动
 * 与每次设置变化都回主线程上付一遍解码成本。**唯一的静止证据就是这一行本身**，故扫源码。
 *
 * ## 边界与局限（不要过度解读本测试）
 *
 * - 它**只**断言「`settingsFlowRaw` 的流链里存在 `flowOn(Dispatchers.Default)`」，
 *   **不能**证明运行时真的换了线程（那需要真机 profiler 或 Robolectric 的 Main dispatcher 断言，
 *   本项目 app 模块没有 Robolectric 依赖）。它是**防回退**的钉子，不是行为证明。
 * - `flowOn` 只影响它**上游**的算子；`settingsFlow` 自己的 `distinctUntilChanged()` 与
 *   `MutableStateFlow.value =` 仍在收集方（Main）执行——这正是我们要的（映射在后台、状态更新在主）。
 */
class SettingsThreadingWiringTest {

    @Test
    fun `settings decode chain is pinned off the main thread`() {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
        val file = root.resolve(
            "app/androidrik/rikkahub/data/datastore/PreferencesStore.kt",
        )
        assertTrue("设置存储源文件不存在：${file.path}", file.isFile)
        val source = file.readText()

        assertTrue(
            "settingsFlowRaw 的流链必须显式 flowOn(Dispatchers.Default)：SettingsStore 的 scope 是 " +
                "AppScope(= Dispatchers.Main)，去掉它会让整条解码+去重链回到主线程（编译与测试都不会报）",
            source.contains(".flowOn(Dispatchers.Default)"),
        )
        // 反向钉子：这条流链**不许**再被塞进 Mutex（turbo 曾有 205 个编译错误的先例，
        // 且 retryWhen 是无锁语义）。只扫流链区段、并先剥掉注释与字符串——
        // 否则**源码里那条「勿把 Mutex 插进本流链」的注释本身**就会让断言误报（第一版正是这么挂的）。
        val chain = source.substringAfter("val settingsFlowRaw =").substringBefore("val settingsFlow =")
        val code = chain
            .replace(Regex("""//[^\n]*"""), "")
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        assertTrue(
            "流链的**代码**里不许出现 Mutex/withLock（注释里的警示语不算）",
            !code.contains("Mutex") && !code.contains("withLock"),
        )
    }
}
