package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import app.androidrik.rikkahub.ui.components.ui.AppButton as Button
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.files.*
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.message.ChatBubbleSurface
import app.androidrik.rikkahub.ui.components.ui.Select
import app.androidrik.rikkahub.ui.context.LocalToaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BubbleLayersEditor(title: String, initial: ChatBubble, onSave: (ChatBubble) -> Unit, onDismiss: () -> Unit, staticGlass: Boolean = false) {
    var draft by remember { mutableStateOf(initial.copy(stack = initial.effectiveStack())) }
    var newKind by remember { mutableStateOf(BubbleLayerKind.TEXTURE) }
    var selectingLayer by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    val context = LocalContext.current
    val store = remember(context) { BubbleTextureStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    fun alterStack(transform: (BubbleStack) -> BubbleStack) {
        draft = draft.copy(stack = transform(requireNotNull(draft.stack)))
    }
    fun change(id: String, transform: (BubbleLayer) -> BubbleLayer) {
        alterStack { stack ->
            stack.copy(layers = stack.layers.map { if (it.id == id) transform(it) else it })
        }
    }
    fun move(id: String, delta: Int) {
        alterStack { current ->
            val index = current.layers.indexOfFirst { it.id == id }
            if (index < 0 || index + delta !in current.layers.indices) current else {
                val list = current.layers.toMutableList()
                java.util.Collections.swap(list, index, index + delta)
                current.copy(layers = list)
            }
        }
    }
    fun runOperation(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { toaster.show("操作失败：${failure.message.orEmpty()}") }
            finally { busy = false }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = selectingLayer
        selectingLayer = null
        if (uri != null && id != null) runOperation {
            val asset = withContext(Dispatchers.IO) { store.importImage(uri) }
            change(id) { it.copy(texture = it.texture.copy(assetId = asset)) }
        }
    }
    val materialPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runOperation {
            val imported = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { store.importMaterial(readLimited(it, AppearanceArchive.MAX_TOTAL + 1024 * 1024)) }
                    ?: error("无法读取材质包")
            }
            draft = imported.copy(stack = imported.effectiveStack())
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val data = pendingExport
        pendingExport = null
        if (uri != null && data != null) runOperation {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(data) } ?: error("无法保存材质包")
            }
            toaster.show("材质包已保存")
        }
    }
    val stack = requireNotNull(draft.stack)
    val valid = stack.layers.all { layer -> !layer.enabled || when (layer.kind) {
        BubbleLayerKind.SOLID -> isAppearanceHex(layer.colorHex)
        BubbleLayerKind.GRADIENT -> layer.gradient.colors.all { isAppearanceHex(it, false) }
        BubbleLayerKind.PATTERN -> isAppearanceHex(layer.pattern.colorHex)
        BubbleLayerKind.NOISE -> true
        BubbleLayerKind.TEXTURE -> layer.texture.assetId != null
        BubbleLayerKind.SHADER -> layer.shader.colors.size == 3 && layer.shader.colors.all { isAppearanceHex(it, false) }
    } }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            ChatBubbleSurface(draft.normalized(), MaterialTheme.colorScheme.primaryContainer) {
                Text("聊天气泡预览 · Preview", Modifier.padding(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = { materialPicker.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("导入材质包") }
                TextButton(enabled = !busy && valid, onClick = {
                    runOperation {
                        val bytes = withContext(Dispatchers.IO) { store.exportMaterial(draft) }
                        pendingExport = bytes
                        exportPicker.launch("bubble-material.neomix.zip")
                    }
                }) { Text("导出材质包") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("显示气泡背景")
                Switch(checked = draft.visible, onCheckedChange = { draft = draft.copy(visible = it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("玻璃背景")
                Switch(checked = stack.glass, onCheckedChange = { value -> alterStack { it.copy(glass = value) } })
            }
            if (stack.glass) Text(if (staticGlass) "此槽位使用静态透光效果。" else "玻璃采样聊天背景；此处预览和图片导出使用静态透光效果。")
            Text("图层：${stack.layers.size}/${BubbleStack.MAX_LAYERS}（后面的图层绘制在上方）")
            stack.layers.forEachIndexed { index, layer -> key(layer.id) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}. ${layer.kind.layerLabel()}", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Switch(checked = layer.enabled, onCheckedChange = { enabled -> change(layer.id) { it.copy(enabled = enabled) } })
                            TextButton(enabled = index > 0, onClick = { move(layer.id, -1) }) { Text("上移") }
                            TextButton(enabled = index < stack.layers.lastIndex, onClick = { move(layer.id, 1) }) { Text("下移") }
                            TextButton(onClick = { alterStack { current -> current.copy(layers = current.layers.filterNot { it.id == layer.id }) } }) { Text("删除") }
                        }
                        Select(options = BubbleBlend.entries, selectedOption = layer.blend,
                            onOptionSelected = { blend -> change(layer.id) { it.copy(blend = blend) } },
                            optionToString = { when (it) { BubbleBlend.NORMAL -> "正常"; BubbleBlend.MULTIPLY -> "正片叠底"; BubbleBlend.SCREEN -> "滤色"; BubbleBlend.OVERLAY -> "叠加" } })
                        Text("图层不透明度：${(layer.opacity * 100).toInt()}%")
                        Slider(layer.opacity, onValueChange = { value -> change(layer.id) { it.copy(opacity = value) } })
                        BubbleLayerControls(layer, busy, store, selectTexture = {
                            selectingLayer = layer.id; imagePicker.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
                        }, update = { transform -> change(layer.id, transform) })
                    }
                }
            } }
            Select(options = BubbleLayerKind.entries, selectedOption = newKind,
                onOptionSelected = { newKind = it }, optionToString = { it.layerLabel() })
            TextButton(enabled = stack.layers.size < BubbleStack.MAX_LAYERS, onClick = {
                alterStack { current -> if (current.layers.size >= BubbleStack.MAX_LAYERS) current else
                    current.copy(layers = current.layers + BubbleLayer(kind = newKind,
                        opacity = if (newKind == BubbleLayerKind.TEXTURE) 0.35f else 1f)) }
            }) { Text("添加图层") }
            Text("整体不透明度：${(draft.opacity * 100).toInt()}%")
            Slider(draft.opacity, onValueChange = { draft = draft.copy(opacity = it) })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
                Button(enabled = valid && !busy, onClick = { onSave(draft.normalized()) }) { Text("保存") }
            }
        }
    }
}

@Composable
private fun BubbleLayerControls(layer: BubbleLayer, busy: Boolean, store: BubbleTextureStore,
    selectTexture: () -> Unit, update: ((BubbleLayer) -> BubbleLayer) -> Unit) {
    when (layer.kind) {
        BubbleLayerKind.SOLID -> AppearanceColorField("颜色（留空跟随主题）", layer.colorHex) { value -> update { it.copy(colorHex = value) } }
        BubbleLayerKind.GRADIENT -> GradientEditor(layer.gradient) { value -> update { it.copy(gradient = value) } }
        BubbleLayerKind.PATTERN -> {
            Select(options = BubblePatternKind.entries, selectedOption = layer.pattern.kind,
                onOptionSelected = { value -> update { it.copy(pattern = it.pattern.copy(kind = value)) } },
                optionToString = { when (it) { BubblePatternKind.DOTS -> "圆点"; BubblePatternKind.GRID -> "网格"; BubblePatternKind.STRIPES -> "斜纹" } })
            AppearanceColorField("图案颜色（留空自动选择）", layer.pattern.colorHex) { value -> update { it.copy(pattern = it.pattern.copy(colorHex = value)) } }
            Text("图案间距：${layer.pattern.spacingDp.toInt()} dp")
            Slider(layer.pattern.spacingDp, valueRange = 4f..64f, onValueChange = { value -> update { it.copy(pattern = it.pattern.copy(spacingDp = value)) } })
            Text("图案强度：${(layer.pattern.opacity * 100).toInt()}%")
            Slider(layer.pattern.opacity, onValueChange = { value -> update { it.copy(pattern = it.pattern.copy(opacity = value)) } })
        }
        BubbleLayerKind.NOISE -> {
            Text("颗粒强度：${(layer.noise.strength * 100).toInt()}%")
            Slider(layer.noise.strength, onValueChange = { value -> update { it.copy(noise = it.noise.copy(strength = value)) } })
        }
        BubbleLayerKind.SHADER -> BubbleShaderControls(layer.shader) { value -> update { it.copy(shader = value) } }
        BubbleLayerKind.TEXTURE -> {
            val exists = layer.texture.assetId?.let { store.file(it)?.isFile } == true
            Text(if (exists) "已选择图片纹理" else "请选择纹理图片（PNG / JPEG / WebP）")
            TextButton(enabled = !busy, onClick = selectTexture) { Text(if (exists) "更换图片" else "导入图片") }
            Select(options = BubbleTextureMode.entries, selectedOption = layer.texture.mode,
                onOptionSelected = { value -> update { it.copy(texture = it.texture.copy(mode = value)) } },
                optionToString = { when (it) { BubbleTextureMode.TILE -> "平铺"; BubbleTextureMode.MIRROR -> "镜像平铺"; BubbleTextureMode.COVER -> "铺满" } })
            if (layer.texture.mode != BubbleTextureMode.COVER) {
                Text("纹理宽度：${layer.texture.sizeDp.toInt()} dp")
                Slider(layer.texture.sizeDp, valueRange = 8f..512f, onValueChange = { value -> update { it.copy(texture = it.texture.copy(sizeDp = value)) } })
            }
            Text("水平偏移：${layer.texture.offsetXDp.toInt()} dp")
            Slider(layer.texture.offsetXDp, valueRange = -2048f..2048f, onValueChange = { value -> update { it.copy(texture = it.texture.copy(offsetXDp = value)) } })
            Text("垂直偏移：${layer.texture.offsetYDp.toInt()} dp")
            Slider(layer.texture.offsetYDp, valueRange = -2048f..2048f, onValueChange = { value -> update { it.copy(texture = it.texture.copy(offsetYDp = value)) } })
        }
    }
}

private fun BubbleLayerKind.layerLabel(): String = when (this) {
    BubbleLayerKind.SOLID -> "纯色"; BubbleLayerKind.GRADIENT -> "渐变"; BubbleLayerKind.PATTERN -> "图案"
    BubbleLayerKind.NOISE -> "颗粒噪声"; BubbleLayerKind.TEXTURE -> "图片纹理"
    BubbleLayerKind.SHADER -> "内置 Shader"
}
