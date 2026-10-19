package app.androidrik.rikkahub.ui.components.ai

import android.net.Uri
import android.util.Log
import app.androidrik.common.android.Logging
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.ui.components.sketch.SketchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SketchAttachment"

/**
 * 把画板确认后的结果存成聊天附件。
 *
 * 走和相册导入相同的持久化路径：字节落到 `filesDir/upload/` 并登记 managed_files，返回 `file://` Uri。
 * 文件名/扩展名按草图的实际类型（PNG 或图片上标注后的 JPEG）生成，每次都是新的 UUID 文件名，
 * 不会覆盖任何已有附件；保存失败返回 null，由调用方提示用户（`sketch_save_failed`）。
 *
 * 另写一条**应用内**结构化日志（`Logging`，设置 → 日志页可导出），便于区分真实保存失败与其他
 * 提示：`stage=save_chat_file outcome=success|failed`，只带 mime、字节数、异常类名，
 * 不含图片内容、URI 或完整异常栈；logcat 的 `Log.e` 仍保留。
 */
internal suspend fun FilesManager.createChatFileBySketch(result: SketchResult): Uri? = withContext(Dispatchers.IO) {
    val mimeType = result.mimeType
    runCatching {
        val bytes = result.encode()
        val uri = createChatFilesByByteArrays(
            byteArrays = listOf(bytes),
            displayName = "sketch.${result.extension}",
            mimeType = mimeType,
        ).firstOrNull()
        if (uri == null) {
            Logging.log(
                TAG,
                "stage=save_chat_file outcome=failed error=EmptySaveResult mime=$mimeType bytes=${bytes.size}",
            )
        } else {
            Logging.log(TAG, "stage=save_chat_file outcome=success mime=$mimeType bytes=${bytes.size} saved=true")
        }
        uri
    }.onFailure {
        Log.e(TAG, "Failed to save sketch", it)
        Logging.log(TAG, "stage=save_chat_file outcome=failed error=${it.javaClass.simpleName} mime=$mimeType")
    }.getOrNull()
}
