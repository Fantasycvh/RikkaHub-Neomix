package app.androidrik.rikkahub.ui.hooks

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

class ChatInputState {
    val textContent = TextFieldState()
    var messageContent by mutableStateOf(listOf<UIMessagePart>())
    var editingMessage by mutableStateOf<Uuid?>(null)

    /**
     * #1906：输入框待发的引用（引用某条历史消息后，在输入框上方显示预览卡）。
     *
     * 与 [editingMessage] 是**两件独立的事**：编辑一条历史消息时，引用来自被编辑消息
     * 自身的注解（见 `ChatService.editMessage`），不由这里承载；此字段只描述「下一条
     * 新消息引用谁」。发送或清空输入时置 null。
     */
    var pendingQuote by mutableStateOf<UIMessageAnnotation.QuotedMessage?>(null)

    private var editingParts: List<UIMessagePart>? = null
    private var editingAttachmentUrls: Set<String> = emptySet()

    /**
     * 编辑历史消息时「原附件 → 替换后的附件」。
     *
     * 替换必须记这层映射，否则 [getContents] 按原 parts 顺序回填时找不到原附件，
     * 新图会被当成「新增附件」追加到序列末尾——纯图片消息里看不出差别，但
     * `[图片, 文本]` 这类顺序会当场变成 `[文本, 图片]`。
     */
    private var replacedAttachments: Map<UIMessagePart, UIMessagePart> = emptyMap()

    fun clearInput() {
        textContent.setTextAndPlaceCursorAtEnd("")
        messageContent = emptyList()
        editingMessage = null
        pendingQuote = null
        editingParts = null
        editingAttachmentUrls = emptySet()
        replacedAttachments = emptyMap()
    }

    fun isEditing() = editingMessage != null

    fun setMessageText(text: String) {
        textContent.setTextAndPlaceCursorAtEnd(text)
    }

    fun appendText(content: String) {
        textContent.setTextAndPlaceCursorAtEnd(textContent.text.toString() + content)
    }

    fun setContents(contents: List<UIMessagePart>) {
        val lastTextIndex = contents.indexOfLast { it is UIMessagePart.Text }
        val text = if (lastTextIndex >= 0) {
            (contents[lastTextIndex] as UIMessagePart.Text).text
        } else {
            ""
        }
        textContent.setTextAndPlaceCursorAtEnd(text)
        messageContent = contents.filter { it !is UIMessagePart.Text }
        editingParts = contents
        editingAttachmentUrls = contents.mapNotNull { it.attachmentUrlOrNull() }.toSet()
        replacedAttachments = emptyMap()
    }

    fun getContents(): List<UIMessagePart> {
        val text = textContent.text.toString()
        if (!isEditing()) {
            return mergeEditedParts(text = text, attachments = messageContent, originalParts = null)
        }
        return mergeEditedParts(
            text = text,
            attachments = messageContent,
            originalParts = editingParts,
            replacements = replacedAttachments,
        )
    }

    fun isEmpty(): Boolean {
        // 纯图片/纯附件消息：无文本但有内容时不算空（对齐 5902feab）
        return textContent.text.isBlank() && messageContent.isEmpty()
    }

    fun addImages(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Image(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addVideos(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Video(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addAudios(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Audio(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addFiles(uris: List<UIMessagePart.Document>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach {
            newMessage.add(it)
        }
        messageContent = newMessage
    }

    /**
     * 原地替换一张图片附件（编辑历史消息 = 替换，编辑新消息 = 换图）。
     *
     * 旧图片**不在这里删盘**：历史分支可能仍引用它（与 [shouldDeleteFileOnRemove]
     * 同一约定，落盘清理沿用会话层的既有策略）。新图片 URL 不在
     * `editingAttachmentUrls` 里，所以之后把它移除时会按「本次新增」清理。
     */
    fun replaceImage(target: UIMessagePart.Image, newUrl: String) {
        if (newUrl.isBlank()) return
        val index = messageContent.indexOf(target)
        if (index < 0) return
        val replacement = UIMessagePart.Image(newUrl)
        messageContent = messageContent.toMutableList().also { it[index] = replacement }
        if (isEditing()) {
            replacedAttachments = replacedAttachments + (target to replacement)
        }
    }

    /**
     * 仅删除当前输入组件临时新增的本地文件。
     * 编辑历史消息时，原有附件不在这里删除，由会话层统一做差异清理。
     */
    fun shouldDeleteFileOnRemove(part: UIMessagePart): Boolean {
        val url = part.attachmentUrlOrNull() ?: return false
        if (!url.startsWith("file:")) return false
        return !isEditing() || url !in editingAttachmentUrls
    }

    private fun UIMessagePart.attachmentUrlOrNull(): String? {
        return when (this) {
            is UIMessagePart.Image -> this.url
            is UIMessagePart.Video -> this.url
            is UIMessagePart.Audio -> this.url
            is UIMessagePart.Document -> this.url
            else -> null
        }
    }
}

/**
 * 把「输入框当前状态」合并成要提交的 parts 序列（[ChatInputState.getContents] 的纯逻辑部分）。
 *
 * 单独抽出来是为了单测：附件删除 / 替换 / 追加都靠它决定最终顺序，而 Compose 的
 * `TextFieldState` 在 JVM 单测里不好构造。
 *
 * 语义（沿用既有编辑路径，勿改）：
 * - [originalParts] 为 null（新消息）⇒ 文本在最前，附件按 [attachments] 顺序跟随；
 * - 编辑历史消息 ⇒ 严格按**原 parts 顺序**回填：文本槽位用当前输入框文本替换
 *   （为空则整条 Text part 消失，对齐纯图片消息），附件槽位按原顺序取出仍在的附件，
 *   被替换过的附件取替换后的那份（保持位置），删掉的原附件直接消失；
 * - 不在原消息里的附件（本次新追加的）按插入顺序追加到末尾。
 */
internal fun mergeEditedParts(
    text: String,
    attachments: List<UIMessagePart>,
    originalParts: List<UIMessagePart>?,
    replacements: Map<UIMessagePart, UIMessagePart> = emptyMap(),
): List<UIMessagePart> {
    if (originalParts == null) {
        return if (text.isBlank()) attachments else listOf(UIMessagePart.Text(text)) + attachments
    }
    val editedTextIndex = originalParts.indexOfLast { it is UIMessagePart.Text }
    val remainingAttachments = attachments.toMutableList()
    val merged = mutableListOf<UIMessagePart>()

    originalParts.forEachIndexed { index, part ->
        when {
            index == editedTextIndex -> {
                // 纯图片消息：编辑文本为空时不生成空 Text part（对齐 5902feab）
                if (text.isNotBlank()) {
                    merged.add(UIMessagePart.Text(text))
                }
            }

            part is UIMessagePart.Text -> {
                if (part.text.isNotBlank()) {
                    merged.add(part)
                }
            }

            else -> {
                val currentIndex = remainingAttachments.indexOf(part).takeIf { it >= 0 }
                    ?: replacements[part]?.let { replacement -> remainingAttachments.indexOf(replacement) }
                        ?.takeIf { it >= 0 }
                    ?: -1
                if (currentIndex >= 0) {
                    merged.add(remainingAttachments.removeAt(currentIndex))
                }
            }
        }
    }
    // 原消息无文本（纯图片编辑）且新文本非空：文本放最前
    if (editedTextIndex < 0 && text.isNotBlank()) {
        merged.add(0, UIMessagePart.Text(text))
    }
    // Newly added attachments are appended in insertion order.
    merged.addAll(remainingAttachments)
    return merged
}
