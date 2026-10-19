package app.androidrik.rikkahub.ui.components.sketch

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Redo
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.Undo
import app.androidrik.common.android.Logging
import app.androidrik.rikkahub.R

private const val TAG = "SketchDialog"

/**
 * 全屏的画板：用手指或触控笔画一张草图，确认后得到一张图片。
 *
 * @param image 垫在下面的图片，在它上面圈画标注；为空时是一张白纸
 * @param aspectRatio 白纸的宽高比，为空时占满屏幕。之后还可以在画板里改
 */
@Composable
fun SketchDialog(
    onDismiss: () -> Unit,
    onConfirm: (SketchResult) -> Unit,
    image: Uri? = null,
    aspectRatio: Float? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { SketchState(aspectRatio) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // 打开时就带着底图的话，图读完之前一直是忙的：不然这段时间落笔或者确认，
    // 导出的是没垫上底图的白纸
    var loadingBackground by remember { mutableStateOf(image != null) }
    // 底图读失败之后保持不能画、不能确认，但可以重新选一张或者关掉
    var loadFailed by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val busy = loadingBackground || loadFailed || exporting

    val loadFailedMessage = stringResource(R.string.sketch_dialog_image_load_failed)
    val exportFailedMessage = stringResource(R.string.sketch_dialog_export_failed)

    suspend fun loadBackground(uri: Uri) {
        loadingBackground = true
        loadFailed = false
        val background = loadSketchBackground(context, uri)
        // 读图在 IO 上做，但收尾的状态更新和提示必须显式回主线程：
        // continuation 恢复到的线程不保证是 Main，Toast 在没调过 Looper.prepare 的线程上会崩
        withContext(Dispatchers.Main.immediate) {
            if (background != null) {
                state.useBackground(background)
            } else {
                loadFailed = true
                // 应用内的提示显示在画板这个窗口的下面，看不到，这里用系统的
                Toast.makeText(context, loadFailedMessage, Toast.LENGTH_SHORT).show()
            }
            loadingBackground = false
        }
    }
    LaunchedEffect(image) {
        if (image != null) loadBackground(image)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { loadBackground(uri) }
    }

    // 画了东西之后退出要先确认，在屏幕边缘起笔时容易误触返回手势。导出中禁止退出：
    // 这时候关掉窗口，已经开始的导出就没着落了
    fun close() {
        if (exporting) return
        if (state.isBlank) onDismiss() else confirmDiscard = true
    }

    // 导出要画一整张图，放到后台线程做；失败时提示，而不是让确认按钮一直没反应
    fun export() {
        // 这里直接读最新的状态值，不依赖上一帧算出来的 busy：同一帧里的第二次点击也要挡住
        if (exporting || loadingBackground || loadFailed || state.isBlank) return
        exporting = true
        scope.launch {
            val result = try {
                withContext(Dispatchers.Default) { state.toResult() }
            } catch (e: CancellationException) {
                exporting = false
                throw e
            } catch (e: Throwable) {
                // Default 上抛出的异常恢复到哪个线程不保证，Toast 和状态复位显式回主线程
                withContext(Dispatchers.Main.immediate) {
                    Toast.makeText(context, exportFailedMessage, Toast.LENGTH_SHORT).show()
                    // 应用内结构化日志：只记异常类名，不含图片内容/URI/完整栈；与 load_background 区分
                    Logging.log(TAG, "stage=export outcome=failed error=${e.javaClass.simpleName}")
                    exporting = false
                }
                return@launch
            }
            // 回调固定回主线程，调用方可以只在主线程上碰 UI 和状态
            withContext(Dispatchers.Main.immediate) {
                try {
                    onConfirm(result)
                } finally {
                    exporting = false
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // 对话框有自己的窗口，系统栏图标的深浅要另外跟着画板的底色设置
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val containerColor = MaterialTheme.colorScheme.surfaceContainer
        val lightContainer = containerColor.luminance() > 0.5f
        SideEffect {
            if (window != null) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightContainer
                    isAppearanceLightNavigationBars = lightContainer
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = containerColor,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 只避开系统栏和挖孔，不管键盘：写字时键盘弹出来，画纸的大小不能跟着变
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
            ) {
                SketchTopBar(
                    state = state,
                    actionsEnabled = !busy,
                    closeEnabled = !exporting,
                    paperMenuEnabled = !loadingBackground && !exporting,
                    onClose = { close() },
                    onChooseImage = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onConfirm = { export() },
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                        ) {
                            SketchCanvas(state = state, modifier = Modifier.fillMaxSize())
                            // 浮在画纸上面而不是挤占它的位置：画纸的尺寸不能因为调色而变
                            if (state.pickingColor) {
                                SketchColorPicker(
                                    state = state,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(8.dp),
                                )
                            }
                        }
                        SketchToolbar(state = state)
                    }
                    // 忙的时候盖住画纸和工具栏：读图、导出期间不能落笔、换工具，导出期间也不能改步骤
                    if (busy) {
                        SketchBusyOverlay(showProgress = loadingBackground || exporting)
                    }
                }
            }
        }
    }

    if (state.writingAt != null) {
        SketchTextInput(onConfirm = state::write, onDismiss = state::cancelText)
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.sketch_dialog_discard_title)) },
            text = { Text(stringResource(R.string.sketch_dialog_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onDismiss()
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

// 关闭、撤销、重做、清空、选画纸和确认
@Composable
private fun SketchTopBar(
    state: SketchState,
    actionsEnabled: Boolean,
    closeEnabled: Boolean,
    paperMenuEnabled: Boolean,
    onClose: () -> Unit,
    onChooseImage: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, enabled = closeEnabled) {
            Icon(HugeIcons.Cancel01, contentDescription = stringResource(android.R.string.cancel))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = state::undo, enabled = actionsEnabled && state.canUndo) {
            Icon(HugeIcons.Undo, contentDescription = stringResource(R.string.sketch_dialog_undo))
        }
        IconButton(onClick = state::redo, enabled = actionsEnabled && state.canRedo) {
            Icon(HugeIcons.Redo, contentDescription = stringResource(R.string.sketch_dialog_redo))
        }
        IconButton(onClick = state::clear, enabled = actionsEnabled && !state.isBlank) {
            Icon(HugeIcons.Delete02, contentDescription = stringResource(R.string.sketch_dialog_clear))
        }
        SketchPaperMenu(state = state, onChooseImage = onChooseImage, enabled = paperMenuEnabled)
        Spacer(Modifier.weight(1f))
        FilledIconButton(onClick = onConfirm, enabled = actionsEnabled && !state.isBlank) {
            Icon(HugeIcons.Tick02, contentDescription = stringResource(android.R.string.ok))
        }
    }
}

// 忙的时候盖在画纸和工具栏上：遮罩下面的手势一律吞掉，免得读图、导出期间改了画板状态
@Composable
private fun SketchBusyOverlay(showProgress: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (showProgress) {
            CircularProgressIndicator()
        }
    }
}
