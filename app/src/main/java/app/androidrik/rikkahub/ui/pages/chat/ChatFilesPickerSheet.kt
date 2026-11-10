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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.FileProvider
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.ui.components.ai.FilesPicker
import app.androidrik.rikkahub.ui.components.ai.useCropLauncher
import app.androidrik.rikkahub.ui.components.ai.useMultiCropLauncher
import app.androidrik.rikkahub.ui.components.ui.permission.PermissionCamera
import app.androidrik.rikkahub.ui.components.ui.permission.PermissionManager
import app.androidrik.rikkahub.ui.components.ui.permission.rememberPermissionState
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.hooks.ChatInputState
import app.androidrik.rikkahub.ui.navigation.isAllowedFileType
import com.dokar.sonner.ToastType
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

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

    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val launchCameraCrop = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(importAttachments(listOf(croppedUri)))
            dismissAll()
        },
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
                // 单图语义不变：取消裁剪 = 放弃这张，不加入、sheet 保持打开（与旧行为一致）；
                // 多图才进入逐张裁剪队列。
                if (setting.displaySetting.skipCropImage) {
                    inputState.addImages(importAttachments(selectedUris))
                    dismissAll()
                } else if (selectedUris.size > 1) {
                    launchImagesCrop(selectedUris)
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
