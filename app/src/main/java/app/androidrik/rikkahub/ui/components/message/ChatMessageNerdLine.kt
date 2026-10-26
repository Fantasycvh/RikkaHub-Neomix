package app.androidrik.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.utils.estimateTokens
import app.androidrik.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Zap
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.utils.formatNumber
import app.androidrik.rikkahub.utils.toFixed
import java.time.Duration
import kotlin.math.roundToInt

/**
 * 显示消息的技术统计信息（如 token 使用量）
 */
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
    loading: Boolean = false,
) {
    val settings = LocalSettings.current.displaySetting

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = modifier.padding(horizontal = 4.dp),
            ) {
                val usage = message.usage
                if (settings.showTokenUsage && usage != null) {
                    // Input tokens
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Upload02,
                                contentDescription = "Input",
                                tint = color,
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        content = {
                            Text(text = "${usage.promptTokens.formatNumber()} tokens")
                            // Cached tokens：追加命中率（cached 是 prompt 的子集，各 provider 语义已统一）
                            if (usage.cachedTokens > 0) {
                                val rate = (usage.cachedTokens.toDouble() / usage.promptTokens * 100)
                                    .roundToInt()
                                Text(
                                    text = "(${usage.cachedTokens.formatNumber()} cached, $rate%)"
                                )
                            }
                        }
                    )
                    // Output tokens
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Download04,
                                contentDescription = "Output",
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        content = {
                            Text(text = "${usage.completionTokens.formatNumber()} tokens")
                        }
                    )
                    // TPS
                    if (message.finishedAt != null) {
                        val duration = Duration.between(
                            message.createdAt.toJavaLocalDateTime(),
                            message.finishedAt!!.toJavaLocalDateTime()
                        )
                        val tps = usage.completionTokens.toFloat() / duration.toMillis() * 1000
                        val seconds = (duration.toMillis() / 1000f).toFixed(1)
                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Zap,
                                    contentDescription = "Speed",
                                    modifier = Modifier.size(12.dp)
                                )
                            },
                            content = {
                                Text(text = "${tps.toFixed(1)} tok/s")
                            }
                        )

                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Clock02,
                                    contentDescription = "Duration",
                                    modifier = Modifier.size(12.dp)
                                )
                            },
                            content = {
                                Text(text = "${seconds}s")
                            }
                        )
                    }
                }
                // 工具循环步数（上游 660b5d65f 最小面）：>1 步才显示；无本地化单位，
                // 与 tokens/tok/s 同风格硬编码
                if (message.stepCount > 1) {
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Refresh,
                                contentDescription = "Steps",
                                tint = color,
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        content = {
                            Text(text = "${message.stepCount} steps")
                        }
                    )
                }
                // 生成中实时 tok/s：每 500ms 用「已生成 token / 已用时长」滚动刷新。
                // 闸门用 loading（正在生成的那条）而不是 finishedAt==null——取消后的消息
                // 可能没有 finishedAt，纯靠它会在 UI 上空转计时。
                if (settings.showTokenUsage && loading && message.finishedAt == null) {
                    LiveTpsItem(message = message, color = color)
                }
                // 生成来源「路由 · 模型」（#1805）：来自消息落盘的双快照（存名不存 id，
                // 删模型/重建不悬空）；旧消息无快照时不显示。供多中转核对与成本/质量归因。
                if (settings.showGenerationSource) {
                    val source = listOfNotNull(
                        message.providerNameSnapshot?.takeIf { it.isNotBlank() },
                        message.modelNameSnapshot?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (source.isNotEmpty()) {
                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Cpu,
                                    contentDescription = "Source",
                                    modifier = Modifier.size(12.dp)
                                )
                            },
                            content = {
                                Text(text = source)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatsItem(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        icon()
        content()
    }
}

/**
 * 生成中的实时 tok/s（每 500ms 滚动）。
 *
 * 口径与终值一致（completion tokens / 实际生成时长），区别只在分子来源：
 * 流式过程中 provider 的 usage 通常为空 ⇒ 用 [estimateTokens] 对正文 + 思考文本估算；
 * 一旦 usage 到达（部分 provider 中途给）就改用真实值。
 * 前 1 秒不显示：分母太小，数值噪声会让读数跳得毫无意义。
 */
@Composable
private fun LiveTpsItem(message: UIMessage, color: Color) {
    var tick by remember(message.id) { mutableLongStateOf(0L) }
    LaunchedEffect(message.id) {
        while (isActive) {
            delay(500)
            tick++
        }
    }
    val end = run {
        tick // 读取以建立 recompose 订阅
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    }
    val millis = Duration
        .between(message.createdAt.toJavaLocalDateTime(), end.toJavaLocalDateTime())
        .toMillis()
    if (millis < 1_000) return
    val tokens = message.usage?.completionTokens?.takeIf { it > 0 }
        ?: message.parts.sumOf { part ->
            when (part) {
                is UIMessagePart.Text -> estimateTokens(part.text)
                is UIMessagePart.Reasoning -> estimateTokens(part.reasoning)
                else -> 0
            }
        }
    val tps = tokens.toFloat() / millis * 1000
    StatsItem(
        icon = {
            Icon(
                imageVector = HugeIcons.Zap,
                contentDescription = "Speed (live)",
                tint = color,
                modifier = Modifier.size(12.dp)
            )
        },
        content = {
            Text(text = "${tps.toFixed(1)} tok/s")
        }
    )
}
