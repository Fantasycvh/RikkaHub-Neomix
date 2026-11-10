package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.files.*
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.ui.*
import app.androidrik.rikkahub.ui.context.LocalToaster
import kotlinx.coroutines.*
import java.util.UUID

@Composable
internal fun SkinPackageManager(skin: AppSkin, selected: String?, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SkinPackageStore(context) }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var items by remember { mutableStateOf(emptyList<SkinPackageManifest>()) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var deleting by remember { mutableStateOf<SkinPackageManifest?>(null) }
    var id by remember { mutableStateOf(selected?.takeIf { it.startsWith("package:") }?.removePrefix("package:") ?: UUID.randomUUID().toString()) }
    var name by remember { mutableStateOf("我的皮肤") }
    var version by remember { mutableStateOf("1") }
    fun run(action: suspend () -> Unit) { scope.launch {
        busy = true
        try { action(); items = withContext(Dispatchers.IO) { store.list() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { toaster.show(failure.message ?: "皮肤包操作失败") }
        finally { busy = false }
    } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = pending; pending = null
        if (uri != null && bytes != null) run { withContext(Dispatchers.IO) {
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
        }; toaster.show("皮肤包已导出") }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            val result = withContext(Dispatchers.IO) {
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { readLimited(it, SkinPackageArchive.MAX_BYTES + 512 * 1024) }
                store.install(bytes)
            }
            toaster.show("已安装 ${result.name} v${result.version}")
        }
    }
    LaunchedEffect(Unit) { run {
        items = withContext(Dispatchers.IO) { store.list() }
        items.firstOrNull { "package:${it.id}" == selected }?.let { name = it.name; version = (it.version + 1).toString() }
    } }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("皮肤资源包", style = MaterialTheme.typography.titleLarge)
            Text("包含全局样式、字体、应用内 Logo、聊天背景和槽位纹理。聊天专属外观继续使用原主题/材质分享入口。")
            AppButton(enabled = !busy, onClick = { open.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("安装或升级 ZIP") }
            Text("导出当前解析后的全局样式", style = MaterialTheme.typography.titleMedium)
            AppOutlinedTextField(id, { id = it }, label = { Text("稳定 ID（升级时保持相同）") }, enabled = !busy)
            AppOutlinedTextField(name, { name = it }, label = { Text("名称") }, enabled = !busy)
            AppOutlinedTextField(version, { version = it }, label = { Text("递增版本号") }, enabled = !busy)
            AppOutlinedButton(enabled = !busy && id.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && name.isNotBlank() && name.length <= 128 && (version.toIntOrNull() ?: 0) > 0,
                onClick = { run {
                    pending = withContext(Dispatchers.IO) { store.exportSkin(id, name, version.toInt(), skin) }
                    save.launch("$id-v$version.skin.zip")
                } }) { Text("导出当前皮肤 ZIP") }
            HorizontalDivider()
            Text("已安装 ${items.size}/32")
            items.forEach { item ->
                Text("${item.name} · v${item.version}\n${item.id}")
                Row {
                    AppTextButton(enabled = !busy, onClick = { onSelect("package:${item.id}") }) { Text(if (selected == "package:${item.id}") "已选择" else "选择") }
                    AppTextButton(enabled = !busy, onClick = { run {
                        pending = withContext(Dispatchers.IO) { store.exportInstalled(item.id) }
                        save.launch("${item.id}-v${item.version}.skin.zip")
                    } }) { Text("导出") }
                    AppTextButton(enabled = !busy, onClick = { deleting = item }) { Text("卸载") }
                }
            }
            Text("选择后用户覆写仍优先。卸载后回退默认；共享资源保留，避免影响仍在使用的字体或图片覆写。")
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            AppTextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") }
        }
    }
    deleting?.let { item -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("卸载 ${item.name}？") },
        text = { Text("移除已安装方案；保留用户覆写及共享资源。重新安装后可恢复原选择。") },
        confirmButton = { AppTextButton(onClick = { deleting = null; run { withContext(Dispatchers.IO) { store.uninstall(item.id) } } }) { Text("卸载") } },
        dismissButton = { AppTextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
internal fun SkinResourceControls(skin: AppSkin, onChange: (AppSkin) -> Unit, onBusy: (Boolean) -> Unit, enabled: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var role by remember { mutableStateOf("logo") }
    var busy by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(skin)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        busy = true; onBusy(true)
        try {
            val hash = withContext(Dispatchers.IO) { BubbleTextureStore(context).importImage(uri) }
            onChange(latest.copy(resources = if (role == "logo") latest.resources.copy(logo = hash) else latest.resources.copy(chatBackground = hash)))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { toaster.show(failure.message ?: "图片导入失败") }
        finally { busy = false; onBusy(false) }
    } }
    Text("皮肤图片资源", style = MaterialTheme.typography.titleMedium)
    Row {
        AppTextButton(enabled = !busy && enabled, onClick = { role = "logo"; picker.launch(arrayOf("image/*")) }) { Text("导入应用内 Logo") }
        AppTextButton(enabled = !busy && enabled, onClick = { role = "background"; picker.launch(arrayOf("image/*")) }) { Text("导入聊天背景") }
    }
    Text("背景在助手未指定自己的图片或渐变时生效；Logo 不修改系统桌面图标。图片最多 1024px。")
    AppTextButton(enabled = !busy && enabled, onClick = { onChange(skin.copy(resources = AppSkinResources())) }) { Text("清除皮肤图片") }
}
