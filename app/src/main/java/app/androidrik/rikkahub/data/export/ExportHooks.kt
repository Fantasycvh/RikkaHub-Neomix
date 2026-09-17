package app.androidrik.rikkahub.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Stable
class ExporterState<T>(
    private val data: T,
    private val serializer: ExportSerializer<T>,
    private val context: Context,
    private val scope: CoroutineScope,
    private val createDocumentLauncher: ManagedActivityResultLauncher<String, Uri?>,
) {
    val value: String
        get() = serializer.exportToJson(data)

    val fileName: String
        get() = serializer.getExportFileName(data)

    fun exportToFile(fileName: String = this.fileName) {
        createDocumentLauncher.launch(fileName)
    }

    fun exportAndShare(fileName: String = this.fileName) {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val cacheDir = File(context.cacheDir, "export")
                cacheDir.mkdirs()
                val file = File(cacheDir, fileName)
                file.writeText(value)
                file
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, null))
        }
    }

    internal fun writeToUri(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(value.toByteArray())
            }
        }
    }
}

@Composable
fun <T> rememberExporter(
    data: T,
    serializer: ExportSerializer<T>,
): ExporterState<T> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingState by remember { mutableStateOf<ExporterState<T>?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { pendingState?.writeToUri(it) }
    }

    val state = remember(data, serializer) {
        ExporterState(
            data = data,
            serializer = serializer,
            context = context,
            scope = scope,
            createDocumentLauncher = createDocumentLauncher,
        ).also { pendingState = it }
    }

    return state
}

@Stable
class ImporterState<T>(
    private val serializer: ExportSerializer<T>,
    private val context: Context,
    private val scope: CoroutineScope,
    private val openDocumentLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
    /**
     * 取**最新**回调的读取器，而不是回调本身。
     *
     * 为什么必须这样：本实例由 `remember(serializer)` 缓存（只按 serializer 作 key），
     * 因此**首次组合时传入的 onResult 会被永久捕获**。而调用方闭包里的数据会随后变化
     * （最典型：数据流的初始值是 `Settings.dummy()`，真实设置要晚一两帧才到），
     * 于是过期的闭包会用「初始 dummy 数据 + 本次导入」覆盖真实数据。
     *
     * 真机实测过后果：导入预设时把用户既有的 **14 个注入条目覆盖成了 1 个**。
     * 用 `rememberUpdatedState` 包一层读取器即从**结构上**消除这一类 bug——
     * 所有调用点（含将来新增的）都不必自己记得防。
     */
    private val currentOnResult: () -> (Result<T>) -> Unit,
) {
    fun importFromFile() {
        openDocumentLauncher.launch(arrayOf("*/*"))
    }

    internal fun handleUri(uri: Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val fileName = serializer.getUriFileName(context, uri)
                if (fileName != null && !fileName.endsWith(".json", ignoreCase = true)) {
                    return@withContext Result.failure<T>(IllegalArgumentException("Not a JSON file"))
                }
                serializer.import(context, uri)
            }
            currentOnResult()(result)
        }
    }
}

@Composable
fun <T> rememberImporter(
    serializer: ExportSerializer<T>,
    onResult: (Result<T>) -> Unit,
): ImporterState<T> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingState by remember { mutableStateOf<ImporterState<T>?>(null) }

    // 始终指向最新回调（见 ImporterState.currentOnResult 的说明）
    val currentOnResult by rememberUpdatedState(onResult)

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { pendingState?.handleUri(it) }
    }

    val state = remember(serializer) {
        ImporterState(
            serializer = serializer,
            context = context,
            scope = scope,
            openDocumentLauncher = openDocumentLauncher,
            currentOnResult = { currentOnResult },
        ).also { pendingState = it }
    }

    return state
}
