package app.androidrik.rikkahub.ui.components.ai

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelType
import app.androidrik.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 模型启停（[Model.enabled]）的唯一行为契约：**停用的模型不再出现在模型选择器里**。
 *
 * 为什么这些断言值得存在：本功能的可见效果全在 Compose 的列表构建里，而 Composable 内的
 * 过滤条件无法上 JVM 单测——故把判定抽成 [selectableOf] / [hasSelectableOf] 两个纯函数，
 * 在此锁住。若日后有人「顺手」改成只按类型过滤，这些用例会立刻失败。
 *
 * 同时锁住**反向**契约：停用**不阻断使用**（与 `ProviderSetting.enabled` 同取舍）——
 * 这一点无法在这里断言（属 `findModelById` 的语义），故在 [Model.enabled] 的 kdoc 里写明。
 */
class ModelSelectabilityTest {

    private fun model(
        name: String,
        type: ModelType = ModelType.CHAT,
        enabled: Boolean = true,
    ) = Model(modelId = name, displayName = name, type = type, enabled = enabled)

    private fun provider(vararg models: Model) = ProviderSetting.OpenAI(
        id = Uuid.random(),
        name = "P",
        models = models.toList(),
    )

    // ---- selectableOf ----

    @Test
    fun `disabled models are excluded`() {
        val models = listOf(model("a"), model("b", enabled = false), model("c"))
        assertEquals(listOf("a", "c"), models.selectableOf(ModelType.CHAT).map { it.modelId })
    }

    @Test
    fun `type still filters alongside enabled`() {
        val models = listOf(
            model("chat", ModelType.CHAT),
            model("image", ModelType.IMAGE),
            model("off-chat", ModelType.CHAT, enabled = false),
        )
        assertEquals(listOf("chat"), models.selectableOf(ModelType.CHAT).map { it.modelId })
        assertEquals(listOf("image"), models.selectableOf(ModelType.IMAGE).map { it.modelId })
    }

    @Test
    fun `all disabled yields empty list rather than falling back to showing them`() {
        // 关键：不做「全停用就全都显示」的兜底——那会让停用看起来失效
        val models = listOf(model("a", enabled = false), model("b", enabled = false))
        assertTrue(models.selectableOf(ModelType.CHAT).isEmpty())
    }

    @Test
    fun `default value is enabled so existing data keeps working`() {
        // 序列化缺省（旧数据没有该字段）必须落成 true，否则升级后模型会整体消失
        assertTrue(Model(modelId = "legacy").enabled)
    }

    // ---- hasSelectableOf ----

    @Test
    fun `provider with only disabled models of that type reports none selectable`() {
        val p = provider(model("a", enabled = false), model("b", ModelType.IMAGE))
        assertFalse(p.hasSelectableOf(ModelType.CHAT))
        // 但它的图片模型仍可选 —— 判定是按类型分别算的
        assertTrue(p.hasSelectableOf(ModelType.IMAGE))
    }

    @Test
    fun `provider with at least one selectable model reports true`() {
        val p = provider(model("a", enabled = false), model("b"))
        assertTrue(p.hasSelectableOf(ModelType.CHAT))
    }

    @Test
    fun `empty provider reports none selectable`() {
        assertFalse(provider().hasSelectableOf(ModelType.CHAT))
    }
}
