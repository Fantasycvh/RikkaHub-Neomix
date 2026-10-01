package app.androidrik.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `computeAIIconByName` 的匹配契约（纯函数，无需 Android）。
 *
 * 本次新增的是 `muse`（上游 `04d6c4ea`：muse-spark / muse-glimmer 属于 Meta 系，走 Meta 图标），
 * 顺带钉住它不被前面更靠前的规则抢走、也不误伤别的名字。
 */
class AIIconMatcherTest {

    @Test
    fun `muse 系模型用 Meta 图标`() {
        assertEquals("meta-color.svg", computeAIIconByName("muse-spark"))
        assertEquals("meta-color.svg", computeAIIconByName("muse-glimmer-30b"))
        assertEquals("meta-color.svg", computeAIIconByName("[反代]Meta/Muse-Spark-1.3-Contributor"))
    }

    @Test
    fun `既有的 Meta 系匹配不变`() {
        assertEquals("meta-color.svg", computeAIIconByName("meta-llama-3.1-8b"))
        assertEquals("meta-color.svg", computeAIIconByName("llama-3.1-70b"))
        // Meta 规则在 ollama 之前判定，`(?<!o)llama` 的作用就是让 "ollama" 落到后面的专用条目
        assertEquals("ollama.svg", computeAIIconByName("ollama"))
    }

    @Test
    fun `muse 不误伤其它名字`() {
        assertEquals(null, computeAIIconByName("usemodel"))
        assertEquals("deepseek-color.svg", computeAIIconByName("deepseek-v4-flash-vision-exp"))
    }
}
