package app.androidrik.rikkahub.ui.pages.chat

// 批次 7 拆出：ChatFilesPickerSheet 自 ChatPage.kt 拆出（同包，private→internal）

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.ui.components.ai.FilesPicker
import app.androidrik.rikkahub.ui.components.ai.createChatFileBySketch
import app.androidrik.rikkahub.ui.components.ai.useCropLauncher
import app.androidrik.rikkahub.ui.components.ai.useMultiCropLauncher
import app.androidrik.rikkahub.ui.components.sketch.SketchDialog
import app.androidrik.rikkahub.ui.components.ui.permission.PermissionCamera
import app.androidrik.rikkahub.ui.components.ui.permission.PermissionManager
import app.androidrik.rikkahub.ui.components.ui.permission.rememberPermissionState
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.hooks.ChatInputState
import app.androidrik.rikkahub.ui.navigation.isAllowedFileType
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

/**
 * 画板叠加请求：[uri] 是垫底图（裁剪结果的临时文件，或跳过裁剪时选中的原图）；
 * [afterResolve] 在画板流程结束后执行善后（删临时文件、推进裁剪队列）；
 * [closeSheet] 见 ChatFilesPickerSheet 里 sketchOver 的注释。
 */
private class SketchOverImage(
    val uri: Uri,
    val afterResolve: () -> Unit = {},
    val closeSheet: Boolean = true,
)

@Composable
internal fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    vm: ChatVM,
    onRequestCompress: () -> Unit,
    onDismiss: () -> Unit,
    /** 「为此会话创建定时任务」（+ 面板入口）：跳转前先同步落库（新聊天目标预选依赖 DB 行） */
    onCreateScheduledTask: () -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    fun importAttachments(uris: List<Uri>): List<Uri> = filesManager.createChatFilesByContents(uris) {
        toaster.show(it, type = ToastType.Error)
    }
    var showInjectionSheet by remember { mutableStateOf(false) }

    fun dismissAll() {
        showInjectionSheet = false
        onDismiss()
    }

    // 「先裁剪后绘制」的画板叠加请求：uri 是垫底图（裁剪结果的临时文件，或跳过裁剪时选中的
    // 原图）。afterResolve 在画板流程结束后调用——裁剪路径用它删临时文件并推进裁剪队列；
    // closeSheet 控制画板收尾后要不要关掉整个 sheet：单张路径收尾即终局，关掉无妨；多图队列
    // 还要继续裁下一张，提前关 sheet 会卸载队列所在的组合，队列就断了
    var sketchOver by remember { mutableStateOf<SketchOverImage?>(null) }
    fun openSketchOverImage(uri: Uri, afterResolve: () -> Unit = {}, closeSheet: Boolean = true) {
        sketchOver = SketchOverImage(uri, afterResolve, closeSheet)
    }

    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val launchCameraCrop = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(importAttachments(listOf(croppedUri)))
            dismissAll()
        },
        onDrawReady = ::openSketchOverImage,
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (setting.displaySetting.skipCropImage) {
                inputState.addImages(importAttachments(listOf(cameraOutputUri!!)))
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
                dismissAll()
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onLaunchCamera: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    val launchImageCrop = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(importAttachments(listOf(croppedUri)))
            dismissAll()
        },
        onDrawReady = ::openSketchOverImage,
    )
    // 多图逐张裁剪（#2306）。单图仍走上面的 useCropLauncher（行为保持不变），多图才用队列：
    // 每裁好一张立刻加入输入框；中途取消时把「当前这张 + 还没裁剪的」原样加入，不丢用户已选，
    // 最后统一关 sheet。队列推进由 useMultiCropLauncher 内部完成，不能靠回调里再 launch 下一张
    // （那个 source 槽会被上一次的 finish 清掉，详见 CropLauncher 里的注释）。
    val launchImagesCrop = useMultiCropLauncher(
        onImageReady = { croppedUri ->
            inputState.addImages(importAttachments(listOf(croppedUri)))
        },
        onDone = { remaining ->
            // remaining 为空 = 全部裁剪完成（每张已在 onImageReady 里加入）；
            // 非空 = 用户中途取消，剩余原图原样加入，避免已挑好的图白选一场。
            if (remaining.isNotEmpty()) {
                inputState.addImages(importAttachments(remaining))
            }
            dismissAll()
        },
        // 队列期间不能关 sheet：提前卸载组合会把队列状态一起带走
        onDrawReady = { uri, resume -> openSketchOverImage(uri, resume, closeSheet = false) },
    )
    // GetMultipleContents 而非 PickMultipleVisualMedia：保留文件浏览入口
    // （系统 Photo Picker 只给相册视图、无目录浏览，照片多时翻找慢；上游 4b36640a 亦已 revert 回此形态）。
    // 视频/音频仍是 GetMultipleContents（PickVisualMedia 本就不覆盖音频）。
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                // 裁剪改成本进程 Compose 对话框（上游 2a0a831e6）：解码在应用进程内完成，
                // HEIF 与 EXIF 方向都原生支持，不再需要先拷到临时文件或把 HEIF 转 JPEG。
                // 单图语义不变：取消裁剪 = 放弃这张，不加入、sheet 保持打开（与旧行为一致）。
                // 单张一律先裁剪：画板入口在裁剪页顶栏（先裁剪后绘制），不想画的直接确认即可。
                // skipCropImage 的已知局限：对单张选图不生效（开了也进裁剪页），只对多张和拍照生效
                if (selectedUris.size > 1) {
                    if (setting.displaySetting.skipCropImage) {
                        inputState.addImages(importAttachments(selectedUris))
                        dismissAll()
                    } else {
                        launchImagesCrop(selectedUris)
                    }
                } else {
                    launchImageCrop(selectedUris.first())
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addVideos(importAttachments(selectedUris))
                dismissAll()
            }
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addAudios(importAttachments(selectedUris))
                dismissAll()
            }
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                val documents = uris.mapNotNull { uri ->
                    val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
                    val mime = filesManager.getFileMimeType(uri) ?: "text/plain"
                    if (isAllowedFileType(fileName, mime)) {
                        val localUri = importAttachments(listOf(uri)).firstOrNull()
                            ?: run {
                                toaster.show(
                                    resources.getString(R.string.chat_input_file_read_failed, fileName),
                                    type = ToastType.Error
                                )
                                return@mapNotNull null
                            }
                        UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime)
                    } else {
                        toaster.show(
                            resources.getString(R.string.chat_input_unsupported_file_type, fileName),
                            type = ToastType.Error
                        )
                        null
                    }
                }
                if (documents.isNotEmpty()) {
                    inputState.addFiles(documents)
                    dismissAll()
                }
            }
        }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    // 画板叠加（先裁剪后绘制 / 跳过裁剪直进画板）：确认=标注图走 sketch 的持久化路径入库；
    // 关闭（含画了又丢弃）=不标注，垫底图直接按普通附件入库。afterResolve 负责裁剪路径的
    // 临时文件删除与队列推进，必须在入库读文件之后执行
    val sketchSaveFailed = stringResource(R.string.sketch_save_failed)
    val scope = rememberCoroutineScope()
    sketchOver?.let { over ->
        SketchDialog(
            image = over.uri,
            onDismiss = {
                sketchOver = null
                inputState.addImages(importAttachments(listOf(over.uri)))
                over.afterResolve()
                if (over.closeSheet) dismissAll()
            },
            onConfirm = { result ->
                sketchOver = null
                scope.launch {
                    val uri = filesManager.createChatFileBySketch(result)
                    if (uri != null) {
                        inputState.addImages(listOf(uri))
                    } else {
                        toaster.show(message = sketchSaveFailed, type = ToastType.Error)
                    }
                    over.afterResolve()
                    if (over.closeSheet) dismissAll()
                }
            },
        )
    }

    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            mcpManager = vm.mcpManager,
            onRequestCompress = {
                // 压缩参数对话框由页面级渲染（容量面板的「压缩上下文」也要用它）
                onRequestCompress()
            },
            onUpdateAssistant = {
                vm.updateAssistantControls(assistant, it)
            },
            onUpdateConversation = {
                // FilesPicker edits only this metadata field; never replace streamed messages
                // with the older conversation snapshot captured by the sheet.
                vm.setWorkspaceCwd(it.workspaceCwd)
            },
            showInjectionSheet = showInjectionSheet,
            onShowInjectionSheetChange = { showInjectionSheet = it },
            onDismiss = { dismissAll() },
            onTakePic = onLaunchCamera,
            onPickImage = { imagePickerLauncher.launch("image/*") },
            onPickVideo = { videoPickerLauncher.launch("video/*") },
            onPickAudio = { audioPickerLauncher.launch("audio/*") },
            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
            onCreateScheduledTask = onCreateScheduledTask,
        )
    }
}
