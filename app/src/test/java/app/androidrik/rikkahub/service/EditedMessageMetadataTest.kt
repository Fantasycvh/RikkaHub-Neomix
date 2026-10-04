package app.androidrik.rikkahub.service

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑消息时的**元信息继承**（上游 #1991 现象 B）。
 *
 * 为什么单独钉：`ChatService.editMessage` 会新建一条消息作为同节点的候选分支，
 * 而新建的默认构造只带内容 —— 若不显式继承，切到新分支时模型名/图标全空
 * （上游实测，fork 同款）。这里把「继承什么、不继承什么」写成可执行的契约。
 *
 * 说明：本测试只覆盖继承规则本身（纯数据变换，从 `editMessage` 的实现抽取对照），
 * 真机行为（拨到新分支看头部）仍需设备验证。
 */
class EditedMessageMetadataTest {

    private fun assistantMessage(
        text: String,
        modelId: Uuid? = Uuid.random(),
        modelName: String? = "gpt-image-2",
        providerName: String? = "中转 A",
    ) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
        modelId = modelId,
        modelNameSnapshot = modelName,
        providerNameSnapshot = providerName,
        annotations = listOf(UIMessageAnnotation.PartialFailure),
    )

    /** 与 `editMessage` 中构造新分支的规则保持一致。 */
    private fun editedCopyOf(
        source: UIMessage,
        newParts: List<UIMessagePart>,
        role: MessageRole = source.role,
    ): UIMessage {
        val base = UIMessage(role = role, parts = newParts, annotations = source.annotations)
        return base.copy(
            modelId = source.modelId,
            modelNameSnapshot = source.modelNameSnapshot,
            providerNameSnapshot = source.providerNameSnapshot,
            createdAt = source.createdAt,
        )
    }

    @Test
    fun `edited branch keeps model and provider attribution`() {
        val source = assistantMessage("original")
        val edited = editedCopyOf(source, listOf(UIMessagePart.Text("edited")))

        assertEquals("模型归属必须继承（否则头部空白）", source.modelId, edited.modelId)
        assertEquals(source.modelNameSnapshot, edited.modelNameSnapshot)
        assertEquals(source.providerNameSnapshot, edited.providerNameSnapshot)
        assertEquals("时间戳继承：分支是同一轮的变体，不该跳到「现在」", source.createdAt, edited.createdAt)
    }

    @Test
    fun `edited branch keeps annotations and new content`() {
        val source = assistantMessage("original")
        val edited = editedCopyOf(source, listOf(UIMessagePart.Text("edited")))

        // #1906 引用/其它注解照旧继承
        assertEquals(source.annotations, edited.annotations)
        assertEquals(
            "edited",
            (edited.parts.single() as UIMessagePart.Text).text,
        )
    }

    @Test
    fun `edited branch deliberately drops usage and finish state`() {
        val source = assistantMessage("original").copy(
            usage = app.androidrik.ai.core.TokenUsage(promptTokens = 100, completionTokens = 50),
            finishReason = "stop",
            finishIncomplete = true,
        )
        val edited = editedCopyOf(source, listOf(UIMessagePart.Text("edited")))

        // 这两类描述的是「被替换掉的那次生成」：套在用户手改后的文本上会把手动编辑
        // 误报成模型产出（含 token 账本与「是被截断的」标记）
        assertNull(edited.usage)
        assertNull(edited.finishReason)
        assertTrue(!edited.finishIncomplete)
    }
}
