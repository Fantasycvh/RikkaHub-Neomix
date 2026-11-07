package app.androidrik.rikkahub.ui.pages.chat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.Book04
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Wrench01
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isEmptyUIMessage
import app.androidrik.ai.ui.semanticRole
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.export.ExportDisplayConfig
import app.androidrik.rikkahub.data.export.effectiveExportDisplayConfig
import app.androidrik.rikkahub.data.export.messageExportDisplayConfig
import app.androidrik.rikkahub.data.export.resolveExportModelLabel
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.ui.components.message.ChatMessageNarrationStep
import app.androidrik.rikkahub.ui.components.message.ChatMessageServerToolStep
import app.androidrik.rikkahub.ui.components.message.MessagePartBlock
import app.androidrik.rikkahub.ui.components.message.ThinkingStep
import app.androidrik.rikkahub.ui.components.message.groupMessageParts
import app.androidrik.rikkahub.ui.components.richtext.MarkdownBlock
import app.androidrik.rikkahub.ui.components.ui.AutoAIIcon
import app.androidrik.rikkahub.ui.components.ui.BitmapComposer
import app.androidrik.rikkahub.ui.components.ui.ChainOfThought
import app.androidrik.rikkahub.ui.components.charts.ChartCard
import app.androidrik.rikkahub.ui.components.charts.ChartSpec
import app.androidrik.rikkahub.ui.components.ui.ChainOfThoughtScope
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.context.Navigator
import com.dokar.sonner.rememberToasterState
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.LocalUserBubbleColor
import app.androidrik.rikkahub.ui.theme.RikkahubTheme
import app.androidrik.rikkahub.ui.theme.adaptiveContentColor
import app.androidrik.rikkahub.utils.exportImage
import app.androidrik.rikkahub.utils.getActivity
import app.androidrik.rikkahub.utils.jsonPrimitiveOrNull
import app.androidrik.rikkahub.utils.toLocalString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private const val TAG = "ChatExport"

/** PNG 导出分页捕获参数：单页高度（每页一张 PNG，一次分享多张；不受单 Bitmap 高度上限约束） */
private val PAGE_CAPTURE_HEIGHT_PX = 15_000

/** 页数上限（防异常输入导致页数爆炸；15000px/页 ≈ 300 万 px 内容） */
private const val MAX_EXPORT_PAGES = 200

/** 整页是否纯背景色：逐行精确比较（真空白页每行同色；含任何内容必有色差行） */
private fun isBlankPage(bitmap: Bitmap): Boolean {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return true
    val row = IntArray(w)
    val ref = IntArray(w)
    bitmap.getPixels(ref, 0, w, 0, 0, w, 1)
    for (y in 1 until h) {
        bitmap.getPixels(row, 0, w, 0, y, w, 1)
        if (!row.contentEquals(ref)) return false
    }
    return true
}

internal suspend fun exportToImage(
    context: Context,
    scope: CoroutineScope,
    density: Density,
    conversation: Conversation,
    messages: List<UIMessage>,
    settings: Settings,
    options: ImageExportOptions = ImageExportOptions()
): List<File> {
    val timestamp = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    val composer = BitmapComposer(scope)
    val activity = context.getActivity()
        // 原先此处只弹 Toast 后 **静默 return** —— 调用方随即报「已导出」并触发续作，
        // 又一处假成功。改为抛错，由调用方统一走失败分支。
        ?: error("Failed to get activity for image export")

    // PNG 是逐页捕获：TikZ 图表须预编译落入缓存（TikzJax 同步读缓存出图），
    // 否则异步渲染在捕获瞬间未就绪 → 该页缺图（HTML 走同步管线不受影响）
    collectTikzCodeBlocks(messages).forEach { code ->
        compileTikzToSvg(context, code)
    }

    // 会话所属助手名（自定义文案的 {assistant} 占位符）：逐页捕获共用同一份
    val assistantName = settings.assistants
        .find { it.id == conversation.assistantId }
        ?.name
        ?.takeIf { it.isNotBlank() }
    // 会话级覆盖优先，否则跟随全局（三个导出器共用同一决策点）；
    // 逐条消息再叠「消息级覆盖」（三级：消息 > 会话 > 全局），由 ExportedChatMessage 取用
    val displayConfig = effectiveExportDisplayConfig(
        conversationOverride = conversation.exportDisplayConfig,
        global = settings.exportDisplayConfig,
    )
    val exportContent: @Composable () -> Unit = {
        CompositionLocalProvider(LocalSettings provides settings) {
            ExportedChatImage(
                conversation = conversation,
                messages = messages,
                assistantName = assistantName,
                displayConfig = displayConfig,
                messageOverrides = conversation.exportDisplayOverrides,
                options = options
            )
        }
    }
    val widthDp = 540.dp

    // 长对话高度可能超过单 Bitmap 硬件上限（16384~32768px）——分页每页一张 PNG，
    // 一次分享全部（ACTION_SEND_MULTIPLE）。UNSPECIFIED 测量（无高度约束）得到内容
    // 真实全高，按页高切页；最后一页取剩余高度（自然不产空白页）。
    val totalHeightPx = composer.measureContentHeight(
        activity = activity,
        width = widthDp,
        screenDensity = density,
        content = exportContent,
    )
    val pageCount = if (totalHeightPx > 0) {
        ((totalHeightPx + PAGE_CAPTURE_HEIGHT_PX - 1) / PAGE_CAPTURE_HEIGHT_PX)
            .coerceAtMost(MAX_EXPORT_PAGES)
    } else {
        1
    }

    val files = mutableListOf<File>()
    try {
        for (pageIndex in 0 until pageCount) {
            val offsetPx = pageIndex * PAGE_CAPTURE_HEIGHT_PX
            val pageHeightPx = minOf(PAGE_CAPTURE_HEIGHT_PX, totalHeightPx - offsetPx).coerceAtLeast(1)
            val pageBitmap = composer.composableToBitmapPage(
                activity = activity,
                width = widthDp,
                pageHeight = density.run { pageHeightPx.toDp() },
                offset = density.run { offsetPx.toDp() },
                screenDensity = density,
                content = exportContent,
            )
            val name = "$timestamp-p${pageIndex + 1}.png"
            files += writeBitmapToRetention(context, pageBitmap, name)
            // 每页分别存入相册（独立文件名）——相册是 PNG 的主留存路径（见设计稿 §六.5）
            context.exportImage(activity, pageBitmap, name)
            pageBitmap.recycle()
        }

        // 兜底补页：图片/Mermaid 等异步内容就绪后实际高度可能高于量测值 → 量测偏小会截掉尾部。
        // 继续逐页捕获，直到出现整页纯背景为止（逐行精确判定，误判率为零）
        var overflowOffsetPx = pageCount * PAGE_CAPTURE_HEIGHT_PX
        while (files.size < MAX_EXPORT_PAGES) {
            val overflow = composer.composableToBitmapPage(
                activity = activity,
                width = widthDp,
                pageHeight = density.run { PAGE_CAPTURE_HEIGHT_PX.toDp() },
                offset = density.run { overflowOffsetPx.toDp() },
                screenDensity = density,
                content = exportContent,
            )
            if (isBlankPage(overflow)) {
                overflow.recycle()
                break
            }
            val name = "$timestamp-p${files.size + 1}.png"
            files += writeBitmapToRetention(context, overflow, name)
            context.exportImage(activity, overflow, name)
            overflow.recycle()
            overflowOffsetPx += PAGE_CAPTURE_HEIGHT_PX
        }

        if (files.isEmpty()) error("No pages rendered")

        return files
    } catch (e: CancellationException) {
        // 取消必须向上传播（调用方据此区分"用户返回"与"导出失败"）
        throw e
    } catch (e: Exception) {
        // **不再吞异常**：写盘/渲染失败必须冒泡，否则卡片会误报「已导出」（设计稿 §1.2 ①）
        Log.e(TAG, "exportToImage failed", e)
        throw e
    }
}

/** 把一页 PNG 原子写入留存区（返回落点文件），供后续分享。 */
private suspend fun writeBitmapToRetention(
    context: Context,
    bitmap: Bitmap,
    displayName: String,
): File {
    val bytes = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        out.toByteArray()
    }
    return writeExportBytes(
        displayName = displayName,
        bytes = bytes,
        mimeType = "image/png",
    ).file
}

data class ImageExportOptions(val expandReasoning: Boolean = false)

@Composable
private fun ExportedChatImage(
    conversation: Conversation,
    messages: List<UIMessage>,
    assistantName: String? = null,
    /** 生效的导出显示配置（会话覆盖优先，否则全局；解析点在 [exportToImage]）。 */
    displayConfig: ExportDisplayConfig,
    /** 按消息覆盖（messageId → config）；逐条消息优先于 [displayConfig]。 */
    messageOverrides: Map<String, ExportDisplayConfig> = emptyMap(),
    options: ImageExportOptions = ImageExportOptions()
) {
    val navBackStack = remember { mutableStateListOf<NavKey>() }
    val navigator = Navigator(navBackStack)
    val toasterState = rememberToasterState()
    RikkahubTheme {
        CompositionLocalProvider(
            LocalNavController provides navigator,
            LocalToaster provides toasterState
        ) {
            Surface(
                modifier = Modifier.width(540.dp) // like 1080p but with density independence
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = conversation.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            Text(
                                text = LocalDateTime.now().toLocalString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Use painterResource for the logo
                        val painter = painterResource(id = R.mipmap.ic_launcher_foreground)
                        Image(
                            painter = painter,
                            contentDescription = "Logo",
                            modifier = Modifier.size(60.dp)
                        )
                    }

                    // Messages
                    messages.forEach { message ->
                        ExportedChatMessage(
                            message = message,
                            assistantName = assistantName,
                            displayConfig = displayConfig,
                            messageOverrides = messageOverrides,
                            options = options,
                            prevMessage = messages.getOrNull(messages.indexOf(message) - 1)
                        )
                    }

                    // Watermark
                    Column {
                        Text(
                            text = stringResource(R.string.export_image_warning),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ExportedChatMessage(
    message: UIMessage,
    prevMessage: UIMessage? = null,
    assistantName: String? = null,
    displayConfig: ExportDisplayConfig,
    messageOverrides: Map<String, ExportDisplayConfig> = emptyMap(),
    options: ImageExportOptions = ImageExportOptions()
) {
    if (message.parts.isEmptyUIMessage()) return
    val context = LocalContext.current
    val settings = LocalSettings.current
    // 导出标签统一走三级优先级的生效配置（消息 > 会话 > 全局；PNG / Markdown / HTML 同一份解析，
    // 见 data/export/ExportDisplayLabel.kt）。图标种子与显示文案**分开**：自定义文案不能拿去选图标。
    val messageDisplayConfig = messageExportDisplayConfig(
        base = displayConfig,
        messageOverride = messageOverrides[message.id.toString()],
    )
    val exportLabel = remember(message, settings.providers, messageDisplayConfig, assistantName) {
        resolveExportModelLabel(
            message = message,
            providers = settings.providers,
            config = messageDisplayConfig,
            assistantName = assistantName,
        )
    }
    val showModelLabel = message.role == MessageRole.ASSISTANT &&
        prevMessage?.role == MessageRole.USER &&
        exportLabel.label.isNotBlank()
    // 导出是静态图：解说也进思考链一起展示（export 内 collapse 关掉，全部步骤都渲染），
    // 角色守卫照旧生效，用户消息里掺的工具调用不会被折进链里。
    val groupedParts = remember(message.parts, message.role) {
        message.parts.groupMessageParts(role = message.role, wholeTurnFolding = true)
    }
    val messageContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .widthIn(max = (540 * 0.9).dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = if (message.semanticRole == MessageRole.USER) Alignment.End else Alignment.Start
        ) {
            groupedParts.forEach { block ->
                when (block) {
                    is MessagePartBlock.ThinkingBlock -> {
                        if (block.steps.isNotEmpty()) {
                            ChainOfThought(
                                steps = block.steps,
                                collapsedVisibleCount = block.steps.size
                            ) { step ->
                                when (step) {
                                    is ThinkingStep.ReasoningStep -> {
                                        ExportedReasoningStep(
                                            reasoning = step.reasoning,
                                            expanded = options.expandReasoning
                                        )
                                    }

                                    is ThinkingStep.ToolStep -> {
                                        ExportedToolStep(
                                            tool = step.tool
                                        )
                                    }

                                    // ServerTool 已从正文常驻状态条移进思考链（本仓无 isFinished/FAILED）
                                    is ThinkingStep.ServerToolStep -> {
                                        ChatMessageServerToolStep(tool = step.tool)
                                    }

                                    // 过程解说：导出图里也是一行可读的说明
                                    is ThinkingStep.NarrationStep -> {
                                        ChatMessageNarrationStep(text = step.text)
                                    }
                                }
                            }
                        }
                    }

                    is MessagePartBlock.ChartBlock -> {
                        val spec = remember(block.tool.input) { ChartSpec.fromJson(block.tool.inputAsJson()) }
                        spec?.let { ChartCard(spec = it) }
                    }

                    is MessagePartBlock.ContentBlock -> {
                        when (val part = block.part) {
                            is UIMessagePart.Text -> {
                                if (part.text.isNotBlank()) {
                                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                        if (message.semanticRole == MessageRole.USER) {
                                            // 与屏显同源：用户气泡走角色覆写（LocalUserBubbleColor），缺省 primaryContainer
                                            val userBubbleColor = LocalUserBubbleColor.current
                                                ?: MaterialTheme.colorScheme.primaryContainer
                                            Card(
                                                shape = MaterialTheme.shapes.medium,
                                                colors = CardDefaults.cardColors(
                                                    containerColor = userBubbleColor,
                                                    contentColor = adaptiveContentColor(userBubbleColor),
                                                )
                                            ) {
                                                MarkdownBlock(
                                                    content = part.text,
                                                    modifier = Modifier.padding(8.dp)
                                                )
                                            }
                                        } else {
                                            if (settings.displaySetting.showAssistantBubble) {
                                                Card(
                                                    shape = MaterialTheme.shapes.medium,
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                                    )
                                                ) {
                                                    MarkdownBlock(
                                                        content = part.text,
                                                        modifier = Modifier.padding(8.dp)
                                                    )
                                                }
                                            } else {
                                                MarkdownBlock(
                                                    content = part.text,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            is UIMessagePart.Image -> {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(part.url)
                                        .allowHardware(false)
                                        .crossfade(false)
                                        .build(),
                                    contentDescription = "Image",
                                    modifier = Modifier
                                        .sizeIn(maxHeight = 300.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                )
                            }

                            else -> {
                                // Other parts are not rendered in image export for now
                            }
                        }
                    }
                }
            }
        }
    }

    if (showModelLabel) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            AutoAIIcon(
                // 图标只认模型身份；显示文案可能被用户改成任意文字
                name = exportLabel.iconSeed ?: exportLabel.label,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(36.dp)
            )

            Text(
                text = exportLabel.label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .weight(1f, fill = false)
            )
        }
    }
    messageContent()
}

@Composable
private fun ChainOfThoughtScope.ExportedReasoningStep(
    reasoning: UIMessagePart.Reasoning,
    expanded: Boolean
) {
    val duration = reasoning.finishedAt?.let { endTime ->
        endTime - reasoning.createdAt
    } ?: (kotlin.time.Clock.System.now() - reasoning.createdAt)

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = {},
        icon = {
            Icon(
                painter = painterResource(R.drawable.deepthink),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
        },
        label = {
            Text(
                text = stringResource(R.string.deep_thinking),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        },
        extra = if (duration > 0.seconds) {
            {
                Text(
                    text = duration.toString(DurationUnit.SECONDS, 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        } else {
            null
        },
        contentVisible = expanded,
        content = {
            MarkdownBlock(
                content = reasoning.reasoning,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    )
}

@Composable
private fun ChainOfThoughtScope.ExportedToolStep(
    tool: UIMessagePart.Tool
) {
    val memoryAction = runCatching {
        tool.inputAsJson().jsonObject["action"]?.jsonPrimitiveOrNull?.contentOrNull
    }.getOrNull()
    val title = when (tool.toolName) {
        "memory_tool" -> when (memoryAction) {
            "create" -> stringResource(R.string.chat_message_tool_create_memory)
            "edit" -> stringResource(R.string.chat_message_tool_edit_memory)
            "delete" -> stringResource(R.string.chat_message_tool_delete_memory)
            else -> stringResource(R.string.chat_message_tool_call_generic, tool.toolName)
        }

        "search_web" -> {
            val query = runCatching {
                tool.inputAsJson().jsonObject["query"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
            }.getOrDefault("")
            stringResource(R.string.chat_message_tool_search_web, query)
        }

        "scrape_web" -> stringResource(R.string.chat_message_tool_scrape_web)
        else -> stringResource(R.string.chat_message_tool_call_generic, tool.toolName)
    }
    ControlledChainOfThoughtStep(
        expanded = true,
        onExpandedChange = {},
        icon = {
            Icon(
                imageVector = when (tool.toolName) {
                    "memory_tool" -> when (memoryAction) {
                        "create", "edit" -> HugeIcons.Book04
                        "delete" -> HugeIcons.Book02
                        else -> HugeIcons.Wrench01
                    }

                    "search_web" -> HugeIcons.Search01
                    "scrape_web" -> HugeIcons.Earth
                    else -> HugeIcons.Wrench01
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
        },
        label = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        contentVisible = false,
        content = null,
    )
}
