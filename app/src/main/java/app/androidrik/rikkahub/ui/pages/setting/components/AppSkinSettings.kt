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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.datastore.ChatFontFamily
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.ui.*
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.data.files.SkinFontStore
import app.androidrik.rikkahub.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppSkinSettings(display: DisplaySetting, onSelect: (String?) -> Unit,
    onEdit: (AppSkin, AppSkin) -> Unit, onReset: () -> Unit, onClearOverrides: () -> Unit) {
    val presets by AppSkinRegistry.shared.skins.collectAsState()
    val resolved = rememberAppSkin(display)
    var editing by remember { mutableStateOf<AppSkin?>(null) }
    var packages by remember { mutableStateOf(false) }
    var candidate by remember(display.appSkinId) { mutableStateOf(display.appSkinId) }
    CardGroup(title = { Text("应用皮肤") }) {
        item(headlineContent = { Text("全局样式方案") }, supportingContent = {
            Column {
                Select(options = listOf<String?>(null) + presets.map { it.id }, selectedOption = candidate,
                    onOptionSelected = { candidate = it }, optionToString = { id ->
                        presets.firstOrNull { it.id == id }?.name ?: if (id == null) "跟随默认" else "方案不可用（已回退默认）"
                    })
                Text("控制全局字体、圆角和基础组件间距；配色继续沿用主题页，皮肤也可提供配色。")
                Text("用户覆写会保留；聊天专属配置仍在下方设置。")
                Row {
                    AppTextButton(onClick = { editing = resolved.skin }) { Text("编辑全局样式") }
                    AppTextButton(onClick = onClearOverrides, enabled = display.appSkinOverrides != AppSkinOverrides()) { Text("清除覆写") }
                }
                AppTextButton(onClick = onReset) { Text("恢复默认皮肤") }
                AppTextButton(onClick = { onSelect(candidate) }) { Text("直接应用方案（保留覆写）") }
                AppTextButton(onClick = { packages = true }) { Text("皮肤资源包") }
                AppTextButton(onClick = {
                    val target = SkinSelection(candidate, AppSkinOverrides())
                    SkinTrialController.shared.start(display, target, resolveAppSkin(target.applyTo(display)).skin, "整套皮肤（暂不使用用户覆写）")
                }) { Text("试穿整套皮肤") }
            }
        })
    }
    if (packages) SkinPackageManager(resolved.skin, display.appSkinId, { onSelect(it) }, { packages = false }, onTry = { id ->
        val target = SkinSelection(id, AppSkinOverrides())
        SkinTrialController.shared.start(display, target, resolveAppSkin(target.applyTo(display)).skin, "已安装皮肤")
        packages = false
    })
    editing?.let { initial -> AppSkinEditor(initial, onTry = { value ->
        SkinTrialController.shared.start(display, SkinSelection(null, AppSkinOverrides.from(value)), value, "编辑中的皮肤")
        editing = null
    }, onSave = { value ->
        onEdit(initial, value); editing = null
    }, onDismiss = { editing = null }) }
}

@Composable
internal fun AppSkinEditor(initial: AppSkin, onSave: (AppSkin) -> Unit, onDismiss: () -> Unit, onTry: ((AppSkin) -> Unit)? = null) {
    var draft by remember { mutableStateOf(initial.normalized()) }
    var importing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try {
                val imported = withContext(Dispatchers.IO) { SkinFontStore(context).importFont(uri) }
                draft = draft.copy(typography = draft.typography.copy(family = ChatFontFamily.CUSTOM,
                    customFontPath = imported.relativePath, customFontName = imported.displayName))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { toaster.show("字体导入失败：${failure.message.orEmpty()}") }
            finally { importing = false }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!importing) onDismiss() }) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("编辑全局样式", style = MaterialTheme.typography.titleLarge)
            AppSkinTheme(ResolvedAppSkin(skin = draft), MaterialTheme.colorScheme) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(draft.spacing.groupItemDp.dp)) {
                    CardGroup {
                        item(headlineContent = { Text("皮肤预览 · Preview") }, supportingContent = { Text("标题、正文、按钮和输入框共享同一份样式。") })
                        formItem {
                            AppOutlinedTextField("输入框预览", {}, readOnly = true, modifier = Modifier.fillMaxWidth())
                            AppButton(onClick = {}) { Text("示例按钮") }
                        }
                    }
                }
            }
            Select(options = ChatFontFamily.entries.filter { it != ChatFontFamily.CUSTOM || draft.typography.customFontPath.isNotBlank() },
                selectedOption = draft.typography.family, onOptionSelected = { draft = draft.copy(typography = draft.typography.copy(family = it)) },
                optionToString = { when (it) { ChatFontFamily.DEFAULT -> "系统字体"; ChatFontFamily.SERIF -> "衬线字体";
                    ChatFontFamily.MONOSPACE -> "等宽字体"; ChatFontFamily.CUSTOM -> "自定义：${draft.typography.customFontName}" } })
            AppTextButton(enabled = !importing, onClick = { fontPicker.launch(arrayOf("font/*", "application/octet-stream", "application/x-font-ttf", "application/x-font-opentype")) }) { Text("导入全局字体") }
            Text("自定义字体保存在本机并参与完整备份；主题分享暂不携带字体文件。")
            Row {
                Text("Expressive 排版")
                Switch(draft.typography.expressive, onCheckedChange = { value ->
                    draft = draft.copy(typography = draft.typography.copy(expressive = value))
                }) }
            Text("标题和按钮使用 Google Sans Flex 的强调字阶（仅英文与数字生效，中文回退系统字体）。")
            SkinSlider("字号比例", draft.typography.sizeRatio, 0.75f..1.5f) { value -> draft = draft.copy(typography = draft.typography.copy(sizeRatio = value)) }
            SkinSlider("全局圆角", draft.shapes.largeDp, 0f..32f) { value -> draft = draft.copy(shapes = draft.shapes.copy(
                extraSmallDp = value / 4, smallDp = value / 2, mediumDp = value * 0.75f, largeDp = value, extraLargeDp = value * 1.75f)) }
            SkinSlider("卡片外圆角", draft.shapes.cardGroupOuterDp, 0f..48f) { value -> draft = draft.copy(shapes = draft.shapes.copy(cardGroupOuterDp = value)) }
            SkinSlider("卡片内圆角", draft.shapes.cardGroupInnerDp, 0f..24f) { value -> draft = draft.copy(shapes = draft.shapes.copy(cardGroupInnerDp = value)) }
            SkinSlider("输入框圆角", draft.shapes.inputDp, 0f..48f) { value -> draft = draft.copy(shapes = draft.shapes.copy(inputDp = value)) }
            Row { Text("胶囊按钮"); Switch(draft.shapes.buttonDp == null, onCheckedChange = { value ->
                draft = draft.copy(shapes = draft.shapes.copy(buttonDp = if (value) null else 16f))
            }) }
            draft.shapes.buttonDp?.let { radius -> SkinSlider("按钮圆角", radius, 0f..48f) { value -> draft = draft.copy(shapes = draft.shapes.copy(buttonDp = value)) } }
            SkinSlider("页面留白", draft.spacing.pageDp, 0f..32f) { value -> draft = draft.copy(spacing = draft.spacing.copy(pageDp = value)) }
            SkinSlider("分区间距", draft.spacing.sectionDp, 0f..40f) { value -> draft = draft.copy(spacing = draft.spacing.copy(sectionDp = value)) }
            SkinSlider("卡片间距", draft.spacing.groupItemDp, 0f..16f) { value -> draft = draft.copy(spacing = draft.spacing.copy(groupItemDp = value)) }
            SkinSlider("表单水平留白", draft.spacing.formHorizontalDp, 8f..32f) { value -> draft = draft.copy(spacing = draft.spacing.copy(formHorizontalDp = value)) }
            SkinSlider("表单垂直留白", draft.spacing.formVerticalDp, 4f..24f) { value -> draft = draft.copy(spacing = draft.spacing.copy(formVerticalDp = value, formGapDp = value)) }
            Select(options = AppSkinMotion.entries, selectedOption = draft.motion,
                onOptionSelected = { draft = draft.copy(motion = it) }, optionToString = { if (it == AppSkinMotion.EXPRESSIVE) "灵动动效" else "标准动效" })
            SkinPanelsControls(draft) { draft = it }
            EmbeddedSkinControls(draft) { draft = it }
            SkinResourceControls(draft, { draft = it }, { importing = it }, enabled = !importing)
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onTry != null) AppOutlinedButton(enabled = !importing, onClick = { onTry(draft.normalized()) }) { Text("跨页面试穿") }
                AppTextButton(enabled = !importing, onClick = onDismiss) { Text("取消") }
                AppButton(enabled = !importing, onClick = { onSave(draft.normalized()) }) { Text("保存") }
            }
        }
    }
}

@Composable
private fun SkinSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text("$label：${"%.2f".format(value)}")
    Slider(value, onValueChange = onChange, valueRange = range,
        modifier = Modifier.semantics { contentDescription = "皮肤 $label" })
}
