package app.androidrik.rikkahub.ui.hooks

import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 编辑消息时的 parts 合并顺序（issue #2152-B「消息内附件可编辑」）。
 *
 * [mergeEditedParts] 是 [ChatInputState.getContents] 的纯逻辑内核：删除 / 替换 / 追加
 * 附件后最终落到消息里的顺序都由它决定，所以这里逐条钉住顺序。
 */
class MergeEditedPartsTest {

    private val imageA = UIMessagePart.Image("file:///a.png")
    private val imageB = UIMessagePart.Image("file:///b.png")
    private val imageC = UIMessagePart.Image("file:///c.png")

    @Test
    fun `新消息_文本在最前_附件按添加顺序跟随`() {
        val result = mergeEditedParts(
            text = "看这两张图",
            attachments = listOf(imageA, imageB),
            originalParts = null,
        )
        assertEquals(
            listOf(UIMessagePart.Text("看这两张图"), imageA, imageB),
            result,
        )
    }

    @Test
    fun `新消息_纯附件不生成空文本 part`() {
        val result = mergeEditedParts(text = "", attachments = listOf(imageA), originalParts = null)
        assertEquals(listOf(imageA), result)
    }

    @Test
    fun `编辑_替换附件后位置不变`() {
        // 原消息：图A、文本、图B —— 替换 A 之后新图必须还排在最前
        val original = listOf(imageA, UIMessagePart.Text("旧文本"), imageB)
        val replacedA = UIMessagePart.Image("file:///a-new.png")
        val result = mergeEditedParts(
            text = "旧文本",
            attachments = listOf(replacedA, imageB),
            originalParts = original,
            replacements = mapOf(imageA to replacedA),
        )
        assertEquals(
            listOf(replacedA, UIMessagePart.Text("旧文本"), imageB),
            result,
        )
    }

    @Test
    fun `编辑_替换附件的同时改文本`() {
        val original = listOf(imageA, UIMessagePart.Text("旧文本"), imageB)
        val replacedA = UIMessagePart.Image("file:///a-new.png")
        val result = mergeEditedParts(
            text = "新文本",
            attachments = listOf(replacedA, imageB),
            originalParts = original,
            replacements = mapOf(imageA to replacedA),
        )
        assertEquals(
            listOf(replacedA, UIMessagePart.Text("新文本"), imageB),
            result,
        )
    }

    @Test
    fun `编辑_删除附件`() {
        val original = listOf(imageA, UIMessagePart.Text("文本"), imageB)
        val result = mergeEditedParts(
            text = "文本",
            attachments = listOf(imageB),
            originalParts = original,
        )
        assertEquals(listOf(UIMessagePart.Text("文本"), imageB), result)
    }

    @Test
    fun `编辑_追加附件排在末尾`() {
        val original = listOf(imageA, UIMessagePart.Text("文本"))
        val result = mergeEditedParts(
            text = "文本",
            attachments = listOf(imageA, imageC),
            originalParts = original,
        )
        assertEquals(listOf(imageA, UIMessagePart.Text("文本"), imageC), result)
    }

    @Test
    fun `编辑_清空文本时文本 part 整条消失`() {
        val original = listOf(imageA, UIMessagePart.Text("文本"), imageB)
        val result = mergeEditedParts(
            text = "",
            attachments = listOf(imageA, imageB),
            originalParts = original,
        )
        assertEquals(listOf(imageA, imageB), result)
    }

    @Test
    fun `编辑_纯图片消息补文本时文本放最前`() {
        val original = listOf(imageA, imageB)
        val result = mergeEditedParts(
            text = "补一句说明",
            attachments = listOf(imageA, imageB),
            originalParts = original,
        )
        assertEquals(
            listOf(UIMessagePart.Text("补一句说明"), imageA, imageB),
            result,
        )
    }

    @Test
    fun `编辑_替换后又被移除的新图不会残留原图`() {
        // 用户替换了 A 又把替换后的图删掉：两条都不该出现
        val original = listOf(imageA, UIMessagePart.Text("文本"))
        val replacedA = UIMessagePart.Image("file:///a-new.png")
        val result = mergeEditedParts(
            text = "文本",
            attachments = emptyList(),
            originalParts = original,
            replacements = mapOf(imageA to replacedA),
        )
        assertEquals(listOf(UIMessagePart.Text("文本")), result)
    }
}
