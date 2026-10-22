package app.androidrik.rikkahub.data.ai.transformers

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.Loader
import io.pebbletemplates.pebble.template.PebbleTemplate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.utils.toLocalDate
import app.androidrik.rikkahub.utils.toLocalTime
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import kotlin.time.toJavaInstant

class TemplateTransformer(
    private val engine: PebbleEngine,
    private val settingsStore: SettingsStore
) : InputMessageTransformer {
    override fun volatileSpans(
        ctx: TransformerContext,
        original: List<UIMessage>,
        transformed: List<UIMessage>,
    ): List<List<IntRange>> {
        val before = original.associateBy { it.id }
        return transformed.map { output ->
            val input = before[output.id] ?: return@map emptyList()
            wrapperOutputRanges(input.countingBody(), output.countingBody())
        }
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = applyTemplates(
        messages = messages,
        // 会话内容模板：由 loader 按助手 id 取（Assistant.messageTemplate）
        chatTemplate = engine.getTemplate(ctx.assistant.id.toString()),
        // 合成内容模板：未设置或不可用（缺 {{ message }}）⇒ null = 保持原行为「不套用」。
        // 只编译一次（而非每条消息一次）。
        systemTemplate = usableSystemTemplate(ctx.assistant.systemMessageTemplate)
            ?.let { engine.getLiteralTemplate(it) },
    )
}

/**
 * 模板应用（纯函数，便于单测覆盖接线）。
 *
 * - **非合成消息**（真实对话轮次）走 [chatTemplate]，上下文含 `time`/`date`（取**消息自身**的
 *   `createdAt`，故多轮之间渲染稳定）。
 * - **合成消息**（`isSynthetic`：system 提示本体 / 注入 / 时间与工作区提醒 / 压缩检查点）默认
 *   **不套用**任何模板——上游 #1790 的取舍：`messageTemplate` 含 `{{time}}/{{date}}`，而合成消息的
 *   `createdAt` 就是**本次请求时间** ⇒ 套用会让 system 前缀每请求都变、prompt 缓存必破。
 *   用户在助手页单独设了 [Assistant.systemMessageTemplate] 时，合成消息改走 [systemTemplate]；
 *   其上下文刻意只含 `message`/`role`（见 [syntheticTemplateContext]），渲染结果与请求时刻无关。
 *
 * 两路都**只替换 Text part**，其它 part（图片/文档等）原样保留。
 */
internal fun applyTemplates(
    messages: List<UIMessage>,
    chatTemplate: PebbleTemplate,
    systemTemplate: PebbleTemplate?,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): List<UIMessage> = messages.map { message ->
    val template = if (message.isSynthetic) {
        systemTemplate ?: return@map message
    } else {
        chatTemplate
    }
    val createdAt = message.createdAt.toInstant(timeZone).toJavaInstant()
    val role = message.role.name.lowercase()
    message.copy(
        parts = message.parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    val context = if (message.isSynthetic) {
                        syntheticTemplateContext(message = part.text, role = role)
                    } else {
                        mapOf(
                            "message" to part.text,
                            "role" to role,
                            // 使用消息本身的发送时间而不是当前时间, 保证多次请求时渲染结果稳定, 不破坏 prompt 缓存
                            "time" to createdAt.toLocalTime(),
                            "date" to createdAt.toLocalDate(),
                        )
                    }
                    part.copy(text = renderTemplate(template = template, context = context))
                }

                else -> part
            }
        }
    )
}

/**
 * [Assistant.systemMessageTemplate] 是否可用：非空**且含 `{{ message }}`**。
 *
 * 缺 `{{ message }}` 时按「未设置」处理（返回 null ⇒ 保持原行为：合成内容原样发送）。
 * 这条守卫是刻意与 [Assistant.messageTemplate] **不对称**的：那边漏写 `{{ message }}` 只是这条
 * 消息内容为空，而这边漏写会让**整段 system 提示 + 全部注入**被渲染成空串后发出去——请求照样成功、
 * 模型却完全收不到系统指令，属于最难排查的静默失败。UI 也会同时给出红字提示。
 */
internal fun usableSystemTemplate(template: String): String? =
    template.takeIf { it.isNotBlank() && it.contains("{{ message }}") }

/**
 * 合成内容的渲染上下文：**只有 `message` 与 `role`，刻意不给 `time`/`date`**。
 *
 * 这是缓存稳定性的**结构性保证**——合成内容每请求重建（system 提示的 createdAt 即请求时刻），
 * 一旦上下文里有时间，渲染结果就会随请求漂移、system 前缀随之失真、prompt 缓存必破。
 * 用户若在模板里写了 `{{ time }}`，Pebble 会渲染成空串（非严格模式），同样是稳定结果。
 */
internal fun syntheticTemplateContext(message: String, role: String): Map<String, Any> =
    mapOf(
        "message" to message,
        "role" to role,
    )

/** 用模板渲染一段文本（抽出来便于单测直接覆盖守卫与上下文不变量）。 */
internal fun renderTemplate(
    template: PebbleTemplate,
    context: Map<String, Any>,
): String {
    val writer = StringWriter()
    template.evaluate(writer, context)
    return writer.toString()
}

class AssistantTemplateLoader(private val settingsStore: SettingsStore) : Loader<String> {
    override fun getReader(cacheKey: String?): Reader? {
        val content = settingsStore.settingsFlow.value.assistants
            .find { it.id.toString() == cacheKey }?.messageTemplate
            ?: return null
        return StringReader(content)
    }

    override fun setCharset(charset: String?) {}

    override fun setPrefix(prefix: String?) {}

    override fun setSuffix(suffix: String?) {}

    override fun resolveRelativePath(
        relativePath: String?,
        anchorPath: String?
    ): String? {
        return relativePath
    }

    override fun createCacheKey(templateName: String?): String? {
        return templateName
    }

    override fun resourceExists(templateName: String?): Boolean {
        return settingsStore.settingsFlow.value.assistants.any { it.id.toString() == templateName }
    }
}
