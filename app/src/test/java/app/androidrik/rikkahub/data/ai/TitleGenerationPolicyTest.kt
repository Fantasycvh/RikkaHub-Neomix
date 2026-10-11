package app.androidrik.rikkahub.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自动标题」开关的判定单测。
 *
 * 为什么值得单测：`generateTitle` 依赖 Android（`ChatService`），JVM 测不到；而这三条判据一旦退化成
 * 「关掉开关后手动「重新生成标题」也不工作」或「关掉开关还在偷偷调模型」，都属于**静默行为偏差**——
 * 用户只会觉得"点了没反应"或"钱莫名少了"，看不出是开关的问题。
 */
class TitleGenerationPolicyTest {

    @Test
    fun `an automatic run is skipped while the switch is off`() {
        assertFalse(
            shouldGenerateTitle(force = false, autoEnabled = false, currentTitle = ""),
        )
        assertFalse(
            "即使标题是空的：关着就是关着（也因此不会因为没配标题模型而弹提示——根本没尝试）",
            shouldGenerateTitle(force = false, autoEnabled = false, currentTitle = "  "),
        )
    }

    @Test
    fun `a manual run still works while the switch is off`() {
        assertTrue(
            "手动「重新生成标题」不受开关约束（与会话列表、抽屉、Web API 的 force=true 一致）",
            shouldGenerateTitle(force = true, autoEnabled = false, currentTitle = "已有的标题"),
        )
        assertTrue(shouldGenerateTitle(force = true, autoEnabled = true, currentTitle = "已有的标题"))
    }

    @Test
    fun `with the switch on it only names a chat that has no title yet`() {
        assertTrue(shouldGenerateTitle(force = false, autoEnabled = true, currentTitle = ""))
        assertTrue(
            "纯空白也算没标题（老逻辑如此，别改）",
            shouldGenerateTitle(force = false, autoEnabled = true, currentTitle = " \n "),
        )
        assertFalse(
            "已经有标题（含用户手改过的）就不覆盖",
            shouldGenerateTitle(force = false, autoEnabled = true, currentTitle = "改过的标题"),
        )
    }
}
