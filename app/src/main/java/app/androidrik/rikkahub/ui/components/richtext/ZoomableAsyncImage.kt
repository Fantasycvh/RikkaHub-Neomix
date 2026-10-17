package app.androidrik.rikkahub.ui.components.richtext

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.ui.ImagePreviewDialog
import app.androidrik.rikkahub.ui.components.ui.LocalConversationImageUrls
import app.androidrik.rikkahub.ui.components.ui.LocalExportContext
import app.androidrik.rikkahub.ui.modifier.shimmer
import app.androidrik.rikkahub.ui.theme.LocalDarkMode
import app.androidrik.rikkahub.utils.imagePreviewPages
import app.androidrik.rikkahub.utils.imagePreviewStartIndex

@Composable
fun ZoomableAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
) {
    var showImageViewer by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val placeholder = if(LocalDarkMode.current) R.drawable.placeholder_dark else R.drawable.placeholder
    val export = LocalExportContext.current
    val coilModel = ImageRequest.Builder(context)
        .data(model)
        .placeholder(placeholder)
        // 加载失败（文件被删 / content 授权失效）时也显示占位图，别留一块空白
        .error(placeholder)
        .crossfade(false)
        .allowHardware(!export)
        .build()
    var loading by remember { mutableStateOf(false) }
    // 消息里的图片：如果它属于「当前会话的图片列表」，全屏后可以左右切换；
    // 否则（工具输出图、Markdown 内嵌图、生图结果）保持原有的单图预览。
    val conversationImages = LocalConversationImageUrls.current
    val previewPages = remember(conversationImages, model) {
        imagePreviewPages(conversationImages, model)
    }
    AsyncImage(
        model = coilModel,
        contentDescription = contentDescription,
        modifier = modifier
            .shimmer(isLoading = loading)
            .clickable {
                showImageViewer = true
            },
        contentScale = contentScale,
        alpha = alpha,
        alignment = alignment,
        onLoading = {
            loading = true
        },
        onSuccess = {
            loading = false
        },
        onError = {
            loading = false
        },
    )
    if (showImageViewer) {
        ImagePreviewDialog(
            images = previewPages,
            initialIndex = imagePreviewStartIndex(previewPages, model),
        ) {
            showImageViewer = false
        }
    }
}
