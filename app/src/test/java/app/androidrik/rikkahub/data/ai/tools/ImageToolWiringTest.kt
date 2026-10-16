package app.androidrik.rikkahub.data.ai.tools

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生图工具（上游 #2123）的**接线守卫**。
 *
 * 为什么这两条要用源码扫描：它们都是「删掉/忘记加**不会报错**、只会静默退化」的约束 ——
 * 行为测试碰不到，因为它们不是函数逻辑，而是"有没有接上"：
 *
 * 1. **装配门控**：没配生图模型就不该注册这个工具。丢了门控的后果是双向的——
 *    用不上的用户白付一段工具 schema（每次请求都下发），而真调用起来只会拿到
 *    `image_generation_unavailable`。工具侧的门控函数（`resolveImageGenerationModel`）有单测，
 *    但"ChatToolFactory 到底有没有用它"只能看源码。
 * 2. **卡片注册**：`GenerateImageToolUI` 不注册进 `ToolUIRegistry` 时**不会报错**，会静默回退成
 *    默认渲染器（标题 + 一坨 JSON）。而"能在聊天界面里看到图"正是这个需求的全部意义
 *    （上游 issue 的评论串里，作者原话是"问题统一为没办法在聊天界面直接显示图片"）。
 */
class ImageToolWiringTest {

    private fun srcRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(srcRoot(), relative).readText()

    @Test
    fun `image tool segment is gated on a configured image model`() {
        val factory = source("app/androidrik/rikkahub/data/ai/tools/ChatToolFactory.kt")
        val gate = "if (settings.resolveImageGenerationModel() != null) {"
        assertTrue(
            "装配点缺少门控：没配生图模型的用户会白白多付一段工具 schema，且调用只会失败",
            factory.contains(gate),
        )
        val gateAt = factory.indexOf(gate)
        val segmentAt = factory.indexOf("ToolSegment(\n                \"image\"", gateAt)
        assertTrue(
            "生图工具段必须落在门控之内（否则等于没门控）",
            segmentAt > gateAt,
        )
    }

    @Test
    fun `generate_image renderer is registered`() {
        val registry = source("app/androidrik/rikkahub/ui/components/message/tools/ToolUI.kt")
        assertTrue(
            "GenerateImageToolUI 没注册：图会在消息流里退化成一段 JSON，而「看得见图」正是本需求的目的",
            registry.contains("GenerateImageToolUI,"),
        )
    }

    @Test
    fun `tool name matches what the renderer registers for`() {
        // 工具名写错一个字符不会有编译错误，只会让卡片静默失效 —— 用两侧源码对齐来钉住
        val tools = source("app/androidrik/rikkahub/data/ai/tools/ImageTools.kt")
        val renderer = source("app/androidrik/rikkahub/ui/components/message/tools/GenerateImageToolUI.kt")
        assertTrue(tools.contains("IMAGE_TOOL_NAME = \"generate_image\""))
        assertTrue(
            "渲染器必须用同一个常量而不是自己再写一遍字符串",
            renderer.contains("override val toolName: String = IMAGE_TOOL_NAME"),
        )
    }
}
