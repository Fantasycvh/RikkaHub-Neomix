package app.androidrik.rikkahub.ui.components.ai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import app.androidrik.rikkahub.utils.normalizeLocalFileUrl
import app.androidrik.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Exchange01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Video01
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.sketch.SketchDialog
import app.androidrik.rikkahub.ui.components.ui.ImagePreviewDialog
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.hooks.ChatInputState
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun MediaFileInputRow(
    state: ChatInputState,
) {
    val context = LocalContext.current
    val sketchSaveFailed = stringResource(R.string.sketch_save_failed)
    val filesManager: FilesManager = koinInject()
    val managedFiles by filesManager.observe().collectAsState(initial = emptyList())
    val displayNameByRelativePath = remember(managedFiles) {
        managedFiles.associate { it.relativePath to it.displayName }
    }
    val displayNameByFileName = remember(managedFiles) {
        managedFiles.associate { it.relativePath.substringAfterLast('/') to it.displayName }
    }

    fun removePart(part: UIMessagePart, url: String) {
        state.messageContent = state.messageContent.filterNot { it == part }
        if (state.shouldDeleteFileOnRemove(part)) {
            filesManager.deleteChatFiles(listOf(url.toUri()))
        }
    }

    // 替换：只对图片给入口（编辑历史消息时的「换一张」；删除后再加也能达到同样效果，
    // 但会丢位置/多两步，所以给一个直接动作）。旧文件不在这里删盘，见 state.replaceImage。
    val toaster = LocalToaster.current
    var pendingReplacePart by remember { mutableStateOf<UIMessagePart.Image?>(null) }
    val replaceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val target = pendingReplacePart
        pendingReplacePart = null
        if (uri != null && target != null) {
            val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
            if (localUri != null) {
                state.replaceImage(target, localUri.toString())
            } else {
                toaster.show(message = "无法读取所选图片")
            }
        }
    }

    // 在输入区的图片上继续画：确认后把画好的图存成新的聊天附件，再原地替换引用。
    // 原图既不被覆盖也不在这里删盘（历史分支仍可能引用它），与 replaceImage 的约定一致。
    val scope = rememberCoroutineScope()
    var drawingOn by remember { mutableStateOf<UIMessagePart.Image?>(null) }
    drawingOn?.let { part ->
        SketchDialog(
            // 输入区里可能是相对路径或旧包名路径，先规整成当前可读的 file:// 地址
            image = context.normalizeLocalFileUrl(part.url).toUri(),
            onDismiss = { drawingOn = null },
            onConfirm = { result ->
                drawingOn = null
                scope.launch {
                    val url = filesManager.createChatFileBySketch(result)
                    if (url == null) {
                        toaster.show(
                            message = sketchSaveFailed,
                            type = ToastType.Error,
                        )
                        return@launch
                    }
                    // 保存的这会儿那张图已经从输入区移走或换掉：新文件没有引用者，删掉
                    if (part !in state.messageContent) {
                        filesManager.deleteChatFiles(listOf(url))
                        return@launch
                    }
                    state.replaceImage(part, url.toString())
                }
            },
        )
    }

    // 点开预览时从哪张图开始；非空时显示全屏预览，预览里可以左右切换、也能在当前图上进画板。
    // 预览集合就是输入区里的图片（按加进来的顺序），URL 统一走 normalizeLocalFileUrl。
    var previewing by remember { mutableStateOf<UIMessagePart.Image?>(null) }
    previewing?.let { start ->
        val images = state.messageContent.filterIsInstance<UIMessagePart.Image>()
        if (images.isEmpty()) {
            previewing = null
        } else {
            ImagePreviewDialog(
                images = images.map { context.normalizeLocalFileUrl(it.url) },
                initialIndex = images.indexOf(start).coerceAtLeast(0),
                actions = { page ->
                    IconButton(
                        onClick = {
                            previewing = null
                            images.getOrNull(page)?.let { drawingOn = it }
                        }
                    ) {
                        Icon(
                            imageVector = HugeIcons.PaintBoard,
                            contentDescription = stringResource(R.string.sketch_draw_on_image),
                            tint = Color.White,
                        )
                    }
                },
                onDismissRequest = { previewing = null },
            )
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        state.messageContent.fastForEach { part ->
            when (part) {
                is UIMessagePart.Image -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "image",
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        onReplace = {
                            pendingReplacePart = part
                            replaceLauncher.launch("image/*")
                        },
                        onClick = { previewing = part },
                        leading = {
                            Surface(
                                modifier = Modifier.size(34.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                AsyncImage(
                                    model = context.normalizeLocalFileUrl(part.url),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Video -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "video",
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.Video01) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Audio -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "audio",
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.MusicNote03) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Document -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = part.fileName,
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.Files02) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun AttachmentChip(
    title: String,
    leading: @Composable () -> Unit,
    onRemove: () -> Unit,
    onReplace: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            leading()
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(min = 40.dp, max = 180.dp),
            )
            if (onReplace != null) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .size(26.dp)
                        .clickable(onClick = onReplace),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = HugeIcons.Exchange01,
                        contentDescription = "替换图片",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .size(26.dp)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachmentLeadingIcon(
    icon: ImageVector,
) {
    Surface(
        modifier = Modifier.size(34.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun attachmentNameFromUrl(
    url: String,
    fallback: String,
    displayNameByRelativePath: Map<String, String>,
    displayNameByFileName: Map<String, String>,
): String {
    val parsed = runCatching { url.toUri() }.getOrNull()
    val relativePath = parsed?.path?.substringAfter("/files/", missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
    if (relativePath != null) {
        displayNameByRelativePath[relativePath]?.let { return it }
    }

    val storedFileName = parsed?.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    if (storedFileName != null) {
        displayNameByFileName[storedFileName]?.let { return it }
        return storedFileName
    }

    return fallback
}
