package app.androidrik.rikkahub.ui.components.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.dokar.sonner.ToastType
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.ImageNotFound01
import me.rerere.hugeicons.stroke.Share08
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

/**
 * 当前会话内全部消息图片的 URL（已按渲染口径归一化，见 `normalizeLocalFileUrl`）。
 *
 * 由 `ChatList` 在会话渲染处提供，消息里的图片点击时据此在**同一会话内**左右切换；
 * 在别的场景（工作区、生图页、Markdown 内嵌图）默认空列表 ⇒ 退化为单图预览。
 */
val LocalConversationImageUrls = compositionLocalOf<List<String>> { emptyList() }

/**
 * 全屏图片查看器：双指缩放 + 平移 + 左右切换（缩放/翻页由 `image-viewer` 提供，
 * 本仓只在它之上补分享/关闭/失败占位，不另写一套手势实现）。
 *
 * @param images 可左右切换的图片集合（会话内全部图片；单图场景传单元素列表）
 * @param initialIndex 打开时停在第几页（越界会被夹住）
 */
@Composable
fun ImagePreviewDialog(
    images: List<String>,
    initialIndex: Int = 0,
    onDismissRequest: () -> Unit,
) {
    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onDismissRequest() }
        return
    }
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val startIndex = initialIndex.coerceIn(images.indices)
    val state = rememberZoomablePagerState(initialPage = startIndex) { images.size }
    val toaster = LocalToaster.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 加载失败的页: 文件被删 / 权限失效 / 网络失败时给可见占位，不能白屏
    val failedPages = remember(images) { mutableStateMapOf<Int, Boolean>() }
    val currentPage = state.currentPage

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            ImagePager(
                modifier = Modifier.fillMaxSize(),
                pagerState = state,
                imageLoader = { index ->
                    val painter = rememberAsyncImagePainter(images[index])
                    val painterState by painter.state.collectAsState()
                    val failed = painterState is AsyncImagePainter.State.Error
                    LaunchedEffect(failed) { failedPages[index] = failed }
                    // 失败时 intrinsicSize 为 0：交给库会拿 0 做缩放基准，兜一个 1x1
                    val size = painter.intrinsicSize
                    val safeSize = if (size.width > 0f && size.height > 0f) size else Size(1f, 1f)
                    Pair(painter, safeSize)
                },
            )

            if (failedPages[currentPage] == true) {
                // 只是提示层，不带点击：手势仍能穿透到下面的 pager（还能继续左右切换）
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .zIndex(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.ImageNotFound01,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = "图片已失效或无法读取",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
            }

            if (images.size > 1) {
                Text(
                    text = "${currentPage + 1} / ${images.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .zIndex(1f)
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }

            IconButton(
                onClick = onDismissRequest,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .zIndex(1f),
            ) {
                Icon(HugeIcons.Cancel01, contentDescription = "关闭", tint = Color.White)
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1f)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        lifecycleOwner.lifecycleScope.launch {
                            runCatching {
                                val shareUri = filesManager.createImageShareUri(images[currentPage])
                                if (shareUri == null) {
                                    toaster.show(message = "无法分享该图片", type = ToastType.Error)
                                    return@launch
                                }
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/*"
                                    putExtra(Intent.EXTRA_STREAM, shareUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                            }.onFailure {
                                it.printStackTrace()
                                toaster.show(message = it.toString(), type = ToastType.Error)
                            }
                        }
                    }
                ) {
                    Icon(HugeIcons.Share08, contentDescription = "分享", tint = Color.White)
                }
                IconButton(
                    onClick = {
                        lifecycleOwner.lifecycleScope.launch {
                            runCatching {
                                toaster.show("正在保存")
                                val imgUrl = images[currentPage]
                                filesManager.saveMessageImage(context, imgUrl)
                                toaster.show(message = "已保存图片", type = ToastType.Success)
                            }.onFailure {
                                it.printStackTrace()
                                toaster.show(
                                    message = it.toString(),
                                    type = ToastType.Error
                                )
                            }
                        }
                    }
                ) {
                    Icon(HugeIcons.Download01, contentDescription = "保存到相册", tint = Color.White)
                }
            }
        }
    }
}
