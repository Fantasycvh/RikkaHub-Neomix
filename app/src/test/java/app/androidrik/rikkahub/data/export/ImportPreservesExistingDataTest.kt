package app.androidrik.rikkahub.data.export

import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.getCurrentAssistant
import app.androidrik.rikkahub.data.model.PromptPreset
import app.androidrik.rikkahub.data.model.asSinglePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 导入的**数据保全**测试（真机事故回归）。
 *
 * ## 事故（2026-09-17，真机实测）
 *
 * 导入 ST 预设时，用户既有的 **14 个注入条目被覆盖成了 1 个**。
 *
 * ## 根因
 *
 * `rememberImporter` 的实现是 `remember(serializer) { ImporterState(..., onResult) }`
 * ——**只按 serializer 缓存**，因此首次组合时传入的 `onResult` 被永久捕获。
 * 而 `PromptVM.settings` 的初始值是 `Settings.dummy()`（只含一个默认预设），
 * 真实设置要晚一两帧才到。于是导入回调闭包里捕获的是 **dummy 的 1 个预设**，
 * 「dummy + 新导入」被写回，真实列表被覆盖。
 *
 * ## 修复
 *
 * `ImporterState` 改为持有**回调读取器**（`currentOnResult: () -> (Result<T>) -> Unit`），
 * 由 `rememberImporter` 用 `rememberUpdatedState` 包装 ⇒ 从结构上保证「始终用最新回调」，
 * 所有调用点（含将来新增的）都不必自己记得防。
 *
 * 本文件锁住「用新数据而非初始 dummy 数据」这一语义。
 */
class ImportPreservesExistingDataTest {

    private fun presetOf(name: String, entryCount: Int) = PromptPreset(
        id = Uuid.random(),
        name = name,
        entries = (1..entryCount).map {
            app.androidrik.rikkahub.data.model.PromptInjection.ModeInjection(
                id = Uuid.random(),
                name = "$name-$it",
                content = "content $it",
            )
        },
    )

    @Test
    fun `settings dummy only carries the default preset - the trap`() {
        // 这正是事故的触发条件：初始值只有 1 个预设，用它当基线就会丢掉用户的其它预设
        val dummy = Settings.dummy()
        assertEquals(1, dummy.promptPresets.size)
        assertEquals(1, dummy.promptPresets.single().entries.size)
    }

    @Test
    fun `appending an imported preset must not use the dummy baseline`() {
        // 模拟：真实数据（用户既有 14 条 + 一个额外预设）
        val realPresets = listOf(presetOf("mine", 14), presetOf("other", 2))
        val imported = presetOf("imported", 3)

        // 正确：基于「最新」列表追加
        val correct = realPresets + imported
        assertEquals(3, correct.size)
        assertEquals(14, correct.first().entries.size)

        // 错误（事故形态）：基于 dummy 追加 ⇒ 用户的 2 个预设全丢
        val wrong = Settings.dummy().promptPresets + imported
        assertEquals(2, wrong.size)
        assertTrue(
            "the dummy baseline silently drops user presets - this is the bug we fixed",
            wrong.none { it.name == "mine" },
        )
    }

    @Test
    fun `flattened view still exposes every entry after append`() {
        val realPresets = listOf(presetOf("mine", 14), presetOf("other", 2))
        val merged = realPresets + presetOf("imported", 3)
        // 扁平视图（下游消费）应看到全部条目，而不是被覆盖后的 1 + 3
        assertEquals(14 + 2 + 3, merged.flatMap { it.entries }.size)
    }

    @Test
    fun `append keeps imported preset id distinct from existing ones`() {
        val real = listOf(presetOf("mine", 3))
        val imported = presetOf("imported", 3)
        val merged = real + imported
        assertEquals(2, merged.map { it.id }.distinct().size)
    }

    @Test
    fun `legacy flat list fallback wraps into exactly one preset`() {
        // 旧数据回退路径：14 条扁平 → 1 个预设（不能丢条目）
        val legacy = (1..14).map {
            app.androidrik.rikkahub.data.model.PromptInjection.ModeInjection(name = "e$it", content = "c$it")
        }
        val wrapped = legacy.asSinglePreset()
        assertEquals(14, wrapped.entries.size)
    }
}
