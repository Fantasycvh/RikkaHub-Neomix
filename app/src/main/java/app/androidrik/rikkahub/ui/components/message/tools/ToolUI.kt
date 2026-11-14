package app.androidrik.rikkahub.ui.components.message.tools

import app.androidrik.rikkahub.ui.components.ui.appSkinTextSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tools
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.richtext.HighlightCodeBlock
import app.androidrik.rikkahub.ui.components.richtext.ZoomableAsyncImage
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.utils.JsonInstantPretty
import app.androidrik.rikkahub.utils.getStringContent
import app.androidrik.rikkahub.utils.jsonPrimitiveOrNull

/**
 * 工具调用的渲染上下文, 预解析好工具入参与输出, 避免各渲染器重复解析
 */
data class ToolUIContext(
    val tool: UIMessagePart.Tool,
    /** 工具入参 ([UIMessagePart.Tool.input] 的 JSON 解析结果) */
    val arguments: JsonElement,
    /** 输出文本部件解析出的 JSON, 工具未执行时为 null */
    val content: JsonElement?,
    /** 该工具调用是否在生成中 */
    val loading: Boolean,
    /** 当前助手绑定的工作区 id（present 文件等需要 workspace 上下文时使用） */
    val workspaceId: String? = null,
    /** Trusted memory library binding, never derived from tool arguments. Null forbids repository lookups. */
    val memoryStoreId: String? = null,
)

/**
 * 单个工具的 UI 渲染器
 *
 * 在 [ToolUIRegistry] 注册后, 聊天消息中对应的工具调用将使用该渲染器展示;
 * 未注册的工具 fallback 到接口的默认实现 (通用标题/图标 + JSON 详情)
 */
interface ToolUIRenderer {
    /** 渲染器对应的工具名 */
    val toolName: String

    /** 折叠步骤的图标 */
    fun icon(context: ToolUIContext): ImageVector = HugeIcons.Tools

    /** 折叠步骤的标题 */
    @Composable
    fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_call_generic, context.tool.toolName)

    /** 步骤展开时是否显示内联摘要 */
    fun hasSummary(context: ToolUIContext): Boolean = false

    /** 步骤展开时的内联摘要 */
    @Composable
    fun Summary(context: ToolUIContext) {
    }

    /**
     * 成功执行后是否切断思考链（交付物类工具：图表、生成的图片）。
     *
     * 这类工具的产物留在链里时会被“只显示最后若干步”折叠掉，本次交付物就看不见了；
     * 返回 true 表示它独占一块、永远可见。生成中 / 失败时不切断（仍是普通步骤）。
     * 注意：切断链不影响 `lastToolIndex`，两者正交。
     */
    fun cutsChain(): Boolean = false

    /** 点击步骤后的详情, 渲染在 BottomSheet 内 */
    @Composable
    fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        DefaultToolPreview(context = context)
    }
}

/** 未注册工具使用的默认渲染器, 全部行为来自 [ToolUIRenderer] 的默认实现 */
private object DefaultToolUIRenderer : ToolUIRenderer {
    override val toolName: String get() = ""

    // 结构化输出卡片化（方向 I，design-ui-overhaul-research.md §9.2）：未注册工具的
    // JSON 对象输出以内联键值摘要展示，不必点开详情 sheet 看裸 JSON 代码块。
    // 只覆盖默认渲染器 ⇒ 已注册的定制渲染器（hasSummary 各有语义）零影响；
    // 生成中 content 为 null、输出不可解析为对象时均为 false，不出现空摘要。
    override fun hasSummary(context: ToolUIContext): Boolean =
        (context.content as? JsonObject)?.values?.isNotEmpty() == true

    @Composable
    override fun Summary(context: ToolUIContext) {
        (context.content as? JsonObject)?.let { StructuredOutputSummary(content = it) }
    }
}

/**
 * 工具 UI 渲染器注册表, 为新工具定制渲染时在 [renderers] 中注册即可
 *
 * 也支持运行时动态注册(插件系统):[register] / [unregister] 会递增 [version],
 * `resolve` 读取该版本号 ⇒ 注册/注销后读取过它的 Compose 内容自动重组。
 */
object ToolUIRegistry {
    private val renderers: Map<String, ToolUIRenderer> = listOf(
        MemoryToolUI,
        SearchWebToolUI,
        ScrapeWebToolUI,
        GetTimeInfoToolUI,
        ClipboardToolUI,
        TextToSpeechToolUI,
        GetScreenTimeToolUI,
        CalendarQueryToolUI,
        CalendarCreateToolUI,
        ChartDisplayToolUI,
        TodoWriteToolUI,
        UseSkillToolUI,
        RecentChatsToolUI,
        ConversationSearchToolUI,
        EditFileToolUI,
        ReadFileToolUI,
        WriteFileToolUI,
        PresentFileToolUI,
        ShellToolUI,
        SubagentToolUI,
        BackgroundRunToolUI,
        TaskStatusToolUI,
        KillTaskToolUI,
        GenerateImageToolUI,
        PhoneUseToolUI,
    ).associateBy { it.toolName }

    private class Contribution(val renderer: ToolUIRenderer)
    private val contributions = mutableMapOf<String, MutableList<Contribution>>()

    private val versionState = mutableIntStateOf(0)

    /** 注册表版本号;Compose 内容读取它即可在动态注册/注销后重组。 */
    val version: Int get() = versionState.intValue

    /** 返回只释放本次贡献的句柄；释放覆盖项后恢复仍存活的前一项或内置渲染器。 */
    fun register(renderer: ToolUIRenderer): AutoCloseable = synchronized(this) {
        val contribution = Contribution(renderer)
        contributions.getOrPut(renderer.toolName) { mutableListOf() }.add(contribution)
        versionState.intValue += 1
        AutoCloseable {
            synchronized(this) {
                val entries = contributions[renderer.toolName]
                if (entries?.remove(contribution) == true) {
                    if (entries.isEmpty()) contributions.remove(renderer.toolName)
                    versionState.intValue += 1
                }
            }
        }
    }

    /** 兼容宿主按名清除动态贡献；插件必须使用 register 返回的句柄。 */
    fun unregister(toolName: String) = synchronized(this) {
        if (contributions.remove(toolName) != null) {
            versionState.intValue += 1
        }
    }

    /** 查找工具对应的渲染器, 未注册时返回默认渲染器 */
    fun resolve(toolName: String): ToolUIRenderer {
        // 读取版本号:动态注册后读取方(Compose)重组,否则新渲染器要等页面重建才生效
        versionState.intValue
        return synchronized(this) {
            contributions[toolName]?.lastOrNull()?.renderer ?: renderers[toolName] ?: DefaultToolUIRenderer
        }
    }
}

/**
 * 默认工具详情: 入参与输出的 JSON 高亮展示
 *
 * @param headerActions 标题栏右侧的附加操作区
 */
@Composable
fun DefaultToolPreview(
    context: ToolUIContext,
    headerActions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_call_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            headerActions?.invoke()
        }
        FormItem(
            label = {
                Text(stringResource(R.string.chat_message_tool_call_label, context.tool.toolName))
            }
        ) {
            HighlightCodeBlock(
                code = JsonInstantPretty.encodeToString(context.arguments),
                language = "json",
                style = TextStyle(fontSize = appSkinTextSize(10.sp), lineHeight = appSkinTextSize(12.sp))
            )
        }
        if (context.tool.output.isNotEmpty()) {
            FormItem(
                label = {
                    Text(stringResource(R.string.chat_message_tool_call_result))
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    context.tool.output.fastForEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> HighlightCodeBlock(
                                code = runCatching {
                                    JsonInstantPretty.encodeToString(
                                        JsonInstant.parseToJsonElement(part.text)
                                    )
                                }.getOrElse { part.text },
                                language = "json",
                                style = TextStyle(fontSize = appSkinTextSize(10.sp), lineHeight = appSkinTextSize(12.sp))
                            )

                            is UIMessagePart.Image -> ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

/** 内联结构化摘要最多展示的顶层键数，超出部分合并为提示行 */
private const val STRUCTURED_SUMMARY_MAX_KEYS = 6

/** 单个值的预览截断长度（字符）；完整内容始终在详情 sheet 里可看 */
private const val STRUCTURED_SUMMARY_VALUE_MAX_CHARS = 120

/**
 * 结构化输出内联摘要（方向 I「结构化输出卡片」）：JSON 对象的顶层键值行。
 *
 * 值的形态化：对象/数组只显示元素计数（`{…}×3`），字符串/数值/布尔截断预览——
 * 摘要行回答「输出了什么」，完整值点开详情 sheet 看（渲染器 Preview 未动）。
 */
@Composable
internal fun StructuredOutputSummary(content: JsonObject) {
    val entries = content.entries.toList()
    val shown = entries.take(STRUCTURED_SUMMARY_MAX_KEYS)
    val hidden = entries.size - shown.size
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { (key, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
                Text(
                    text = formatStructuredSummaryValue(value),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (hidden > 0) {
            Text(
                text = stringResource(R.string.chat_message_tool_summary_more, hidden),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

private fun formatStructuredSummaryValue(value: JsonElement): String = when (value) {
    is JsonObject -> "{…}×${value.size}"
    is JsonArray -> "[…]×${value.size}"
    // JsonNull 是 JsonPrimitive 的子类型，必须先于 JsonPrimitive 匹配
    is JsonNull -> "null"
    is JsonPrimitive -> value.content.let { content ->
        if (content.length > STRUCTURED_SUMMARY_VALUE_MAX_CHARS) {
            content.take(STRUCTURED_SUMMARY_VALUE_MAX_CHARS) + "…"
        } else {
            content
        }
    } ?: "null"
    else -> value.toString()
}
