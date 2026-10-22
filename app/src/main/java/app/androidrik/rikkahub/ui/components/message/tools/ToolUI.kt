package app.androidrik.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlinx.serialization.json.JsonElement
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
}

/**
 * 工具 UI 渲染器注册表, 为新工具定制渲染时在 [renderers] 中注册即可
 *
 * 也支持运行时动态注册(插件系统):[register] / [unregister] 会递增 [version],
 * `resolve` 读取该版本号 ⇒ 注册/注销后读取过它的 Compose 内容自动重组。
 */
object ToolUIRegistry {
    private val renderers: MutableMap<String, ToolUIRenderer> = listOf(
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
    ).associateBy { it.toolName }.toMutableMap()

    private val versionState = mutableIntStateOf(0)

    /** 注册表版本号;Compose 内容读取它即可在动态注册/注销后重组。 */
    val version: Int get() = versionState.intValue

    /** 注册(或覆盖)一个渲染器;插件启用时调用。 */
    fun register(renderer: ToolUIRenderer) {
        synchronized(this) {
            renderers[renderer.toolName] = renderer
            versionState.intValue += 1
        }
    }

    /** 注销渲染器;插件禁用时调用(未注册时无副作用)。 */
    fun unregister(toolName: String) {
        synchronized(this) {
            if (renderers.remove(toolName) != null) {
                versionState.intValue += 1
            }
        }
    }

    /** 查找工具对应的渲染器, 未注册时返回默认渲染器 */
    fun resolve(toolName: String): ToolUIRenderer {
        // 读取版本号:动态注册后读取方(Compose)重组,否则新渲染器要等页面重建才生效
        versionState.intValue
        return renderers[toolName] ?: DefaultToolUIRenderer
    }
}

internal fun JsonElement?.getStringContent(key: String): String? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.contentOrNull

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
                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
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
                                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
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
