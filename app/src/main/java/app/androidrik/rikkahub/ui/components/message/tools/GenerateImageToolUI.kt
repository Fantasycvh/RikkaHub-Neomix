package app.androidrik.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.tools.IMAGE_TOOL_NAME
import app.androidrik.rikkahub.data.ai.tools.parseGeneratedImagePaths
import app.androidrik.rikkahub.utils.normalizeGeneratedImagePath
import app.androidrik.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `generate_image` 的会话内渲染（上游 #2123 的落地形态，见
 * `docs/design/design-agent-image-tool.md` §3.1）。
 *
 * **图片就显示在工具步骤里**——这是本需求的关键一半：上游那个 issue 的评论串里，作者说得最清楚的
 * 一句是「问题统一为**没办法在聊天界面直接显示图片**」，而不是"生成不出来"。
 *
 * 为什么不做成助手消息的一等图片部件：那要额外解决"标记为 UI-only + 装配侧过滤 + 落库与分支
 * 一致性"三件事；而图一进消息历史，下一轮就会被当输入模态再发一遍（一张上千 token）。
 * 走工具卡则**图片只以本地路径的形式存在于上下文里**，卡片按路径加载——代价是模型下一轮
 * "看不到"自己生成的图（这是刻意的，也是 issue 原文要求的）。
 *
 * 点击图片进入缩放查看由 [ZoomableAsyncImage] 自带（沿用消息流里图片的同一交互）。
 */
object GenerateImageToolUI : ToolUIRenderer {

    override val toolName: String = IMAGE_TOOL_NAME

    /**
     * 生成的图是本次交付物：切断思考链，让它独占一块。
     *
     * 否则生图前常伴随搜索 / 读文件，步骤数超过折叠阈值时图片会被“只显示最后若干步”
     * 藏起来，用户看不到本次生成的图。
     */
    override fun cutsChain(): Boolean = true

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Image02

    @Composable
    override fun title(context: ToolUIContext): String {
        val paths = remember(context.content) { parseGeneratedImagePaths(context.content) }
        return when {
            context.loading -> stringResource(R.string.chat_message_image_tool_pending)
            paths.isEmpty() -> stringResource(R.string.chat_message_image_tool_failed)
            else -> stringResource(R.string.chat_message_image_tool_title, paths.size)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        val filesDir = LocalContext.current.applicationContext.filesDir
        // 备份恢复/跨包恢复后，工具输出里的绝对路径可能还指着旧包的 files/images/；
        // 文件已经恢复到当前包，这里按当前 filesDir 重新解析，否则会显示「已失效或无法读取」
        val paths = remember(context.content, filesDir) {
            parseGeneratedImagePaths(context.content).map { normalizeGeneratedImagePath(filesDir, it) }
        }
        if (paths.isEmpty()) return

        // 单图给大预览，多图走等宽网格：单图是常态（默认 num=1），给大一点才看得出效果
        val tileWidth = if (paths.size == 1) 240.dp else 132.dp
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            paths.forEach { path ->
                ZoomableAsyncImage(
                    model = path,
                    contentDescription = null,
                    modifier = Modifier
                        .width(tileWidth)
                        .height(tileWidth)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }

    /**
     * 详情页：把**本次调用的入参**摊开。
     *
     * 图片本身在 [Summary] 里已经能看（还能点开缩放），详情页再放一遍是重复；这里补的是
     * 「用的什么提示词、什么尺寸、几张」——排查"为什么出来这张图"时真正要看的东西。
     */
    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val args = context.arguments.jsonObject
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val size = args["size"]?.jsonPrimitive?.contentOrNull
        val num = args["num"]?.jsonPrimitive?.contentOrNull

        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_message_image_tool_prompt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = prompt, style = MaterialTheme.typography.bodyMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (size != null) {
                    Text(
                        text = stringResource(R.string.chat_message_image_tool_size, size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (num != null) {
                    Text(
                        text = stringResource(R.string.chat_message_image_tool_num, num),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val paths = remember(context.content) { parseGeneratedImagePaths(context.content) }
            if (paths.isEmpty() && !context.loading) {
                Text(
                    text = context.content?.jsonObject?.get("hint")?.jsonPrimitive?.contentOrNull
                        ?: stringResource(R.string.chat_message_image_tool_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
