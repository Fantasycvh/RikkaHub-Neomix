package app.androidrik.rikkahub.ui.pages.setting.components

import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.AvatarFrameParams
import app.androidrik.rikkahub.data.datastore.ChainOfThoughtCustomStyle
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.components.ui.drawAvatarFrameParams
import app.androidrik.rikkahub.ui.theme.toComposeColorOrNull
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * 轻量个性化的两个 JSON 可导入编辑器（design-light-personalization.md 追加轮）：
 * - [AvatarFrameParamsSheet]：头像框 CUSTOM_PARAMS 档的环参数
 * - [ChainOfThoughtCustomSheet]：思维链 CUSTOM 档的容器/强调/几何
 *
 * 均在 ModalBottomSheet 内编辑（LazyColumn 内联 TextField 的 IME 陷阱，见 AGENTS.md
 * TextField Host Rules）；JSON 导入=粘贴对话框、导出=复制剪贴板（与主题导入导出同套路，
 * `JsonInstant` ignoreUnknownKeys+coerce，未知字段/非法值可容忍）。
 */

@Composable
private fun JsonImportExportButtons(
    exportJson: () -> String,
    onImported: () -> Unit,
    decode: (String) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val exportedMsg = stringResource(R.string.setting_personalization_json_exported)
    val importedMsg = stringResource(R.string.setting_personalization_json_imported)
    var showImportDialog by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showImportDialog = true }) {
            Text(stringResource(R.string.setting_personalization_json_import))
        }
        OutlinedButton(onClick = {
            scope.launch {
                clipboard.setClipEntry(
                    ClipEntry(ClipData.newPlainText("style", exportJson()))
                )
            }
            toaster.show(exportedMsg)
        }) {
            Text(stringResource(R.string.setting_personalization_json_export))
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(stringResource(R.string.setting_personalization_json_import)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = jsonText,
                        onValueChange = {
                            jsonText = it
                            errorMessage = null
                        },
                        label = { Text("JSON") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8,
                        isError = errorMessage != null,
                        supportingText = errorMessage?.let { msg -> { Text(msg) } },
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            decode(jsonText)
                            showImportDialog = false
                            jsonText = ""
                            toaster.show(importedMsg)
                            onImported()
                        } catch (e: Exception) {
                            errorMessage = e.message
                        }
                    },
                    enabled = jsonText.isNotBlank(),
                ) {
                    Text(stringResource(R.string.setting_personalization_json_import))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SchemeNameDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_personalization_scheme_name_title)) },
        text = {
            // 单字段也走滚动+imePadding：AlertDialog 窗口不与 IME 协调（AGENTS.md TextField Host Rules）
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.setting_personalization_scheme_name_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(name.trim())
                    onDismiss()
                },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.setting_theme_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun HexField(label: String, value: String, onValueChange: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(modifier = Modifier.size(24.dp).clip(CircleShape)) {
            drawCircle(color = value.toComposeColorOrNull() ?: Color.Gray)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text(stringResource(R.string.setting_personalization_hex_optional)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(format(value), style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarFrameParamsSheet(
    params: AvatarFrameParams,
    onSave: (AvatarFrameParams) -> Unit,
    onSaveScheme: (String, AvatarFrameParams) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    var kind by remember { mutableStateOf(params.kind) }
    var colorHex by remember { mutableStateOf(params.colorHex) }
    var secondaryHex by remember { mutableStateOf(params.secondaryColorHex ?: "") }
    var strokeWidth by remember { mutableStateOf(params.strokeWidthDp) }
    var dash by remember { mutableStateOf(params.dashDp) }
    var gap by remember { mutableStateOf(params.gapDp) }
    var showSchemeNameDialog by remember { mutableStateOf(false) }

    fun snapshot(): AvatarFrameParams = AvatarFrameParams(
        kind = kind,
        colorHex = colorHex,
        secondaryColorHex = secondaryHex.takeIf { it.isNotBlank() },
        strokeWidthDp = strokeWidth.coerceIn(0.5f, 12f),
        dashDp = dash.coerceIn(1f, 16f),
        gapDp = gap.coerceIn(1f, 16f),
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_display_page_avatar_frame_params_title),
                style = MaterialTheme.typography.titleLarge,
            )

            // 实时预览：每帧按当前参数重画
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                val snapshot = snapshot()
                val fallbackPrimary = MaterialTheme.colorScheme.primary
                val fallbackTertiary = MaterialTheme.colorScheme.tertiary
                Canvas(modifier = Modifier.size(56.dp)) {
                    drawAvatarFrameParams(snapshot, fallbackPrimary, fallbackTertiary)
                }
            }

            Text(
                text = stringResource(R.string.setting_display_page_avatar_frame_param_kind),
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("ring", "gradient", "double", "dashed", "glow").forEach { k ->
                    OutlinedButton(
                        onClick = { kind = k },
                        enabled = kind != k,
                    ) { Text(k) }
                }
            }

            HexField(
                label = stringResource(R.string.setting_display_page_avatar_frame_param_color),
                value = colorHex,
                onValueChange = { colorHex = it },
            )
            if (kind == "gradient" || kind == "double") {
                HexField(
                    label = stringResource(R.string.setting_display_page_avatar_frame_param_secondary),
                    value = secondaryHex,
                    onValueChange = { secondaryHex = it },
                )
            }

            LabeledSlider(
                label = stringResource(R.string.setting_display_page_avatar_frame_param_width),
                value = strokeWidth, range = 0.5f..8f, format = { "%.1fdp".format(it) },
                onChange = { strokeWidth = it },
            )
            if (kind == "dashed") {
                LabeledSlider(
                    label = stringResource(R.string.setting_display_page_avatar_frame_param_dash),
                    value = dash, range = 1f..16f, format = { "%.0fdp".format(it) },
                    onChange = { dash = it },
                )
                LabeledSlider(
                    label = stringResource(R.string.setting_display_page_avatar_frame_param_gap),
                    value = gap, range = 1f..16f, format = { "%.0fdp".format(it) },
                    onChange = { gap = it },
                )
            }

            JsonImportExportButtons(
                exportJson = { JsonInstant.encodeToString(snapshot()) },
                // 导入后保持 sheet 打开：decode 写的是本 sheet 的本地态，关掉就丢了，
                // 用户需要看到回填值再点「保存」才落库
                onImported = {},
                decode = { text ->
                    val imported = JsonInstant.decodeFromString<AvatarFrameParams>(text)
                    kind = imported.kind
                    colorHex = imported.colorHex
                    secondaryHex = imported.secondaryColorHex ?: ""
                    strokeWidth = imported.strokeWidthDp
                    dash = imported.dashDp
                    gap = imported.gapDp
                },
            )

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { showSchemeNameDialog = true }) {
                    Text(stringResource(R.string.setting_personalization_save_as_scheme))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                Button(onClick = { onSave(snapshot()) }) {
                    Text(stringResource(R.string.setting_theme_page_save))
                }
            }
        }
    }

    if (showSchemeNameDialog) {
        SchemeNameDialog(
            onConfirm = { name -> onSaveScheme(name, snapshot()) },
            onDismiss = { showSchemeNameDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChainOfThoughtCustomSheet(
    style: ChainOfThoughtCustomStyle,
    onSave: (ChainOfThoughtCustomStyle) -> Unit,
    onSaveScheme: (String, ChainOfThoughtCustomStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    var containerHex by remember { mutableStateOf(style.containerColorHex ?: "") }
    var accentHex by remember { mutableStateOf(style.accentColorHex ?: "") }
    var corner by remember { mutableStateOf(style.cornerRadiusDp) }
    var lineWidth by remember { mutableStateOf(style.timelineLineWidthDp) }
    var showTimeline by remember { mutableStateOf(style.showTimeline) }
    var showSchemeNameDialog by remember { mutableStateOf(false) }

    fun snapshot(): ChainOfThoughtCustomStyle = ChainOfThoughtCustomStyle(
        containerColorHex = containerHex.takeIf { it.isNotBlank() },
        accentColorHex = accentHex.takeIf { it.isNotBlank() },
        cornerRadiusDp = corner.coerceIn(0f, 28f),
        timelineLineWidthDp = lineWidth.coerceIn(0.5f, 4f),
        showTimeline = showTimeline,
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_display_page_cot_custom_title),
                style = MaterialTheme.typography.titleLarge,
            )
            HexField(
                label = stringResource(R.string.setting_display_page_cot_custom_container),
                value = containerHex,
                onValueChange = { containerHex = it },
            )
            HexField(
                label = stringResource(R.string.setting_display_page_cot_custom_accent),
                value = accentHex,
                onValueChange = { accentHex = it },
            )
            LabeledSlider(
                label = stringResource(R.string.setting_display_page_cot_custom_corner),
                value = corner, range = 0f..28f, format = { "%.0fdp".format(it) },
                onChange = { corner = it },
            )
            LabeledSlider(
                label = stringResource(R.string.setting_display_page_cot_custom_line_width),
                value = lineWidth, range = 0.5f..4f, format = { "%.1fdp".format(it) },
                onChange = { lineWidth = it },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.setting_display_page_cot_custom_show_timeline))
                Switch(checked = showTimeline, onCheckedChange = { showTimeline = it })
            }

            JsonImportExportButtons(
                exportJson = { JsonInstant.encodeToString(snapshot()) },
                // 导入后保持 sheet 打开：decode 写的是本 sheet 的本地态，关掉就丢了，
                // 用户需要看到回填值再点「保存」才落库
                onImported = {},
                decode = { text ->
                    val imported = JsonInstant.decodeFromString<ChainOfThoughtCustomStyle>(text)
                    containerHex = imported.containerColorHex ?: ""
                    accentHex = imported.accentColorHex ?: ""
                    corner = imported.cornerRadiusDp
                    lineWidth = imported.timelineLineWidthDp
                    showTimeline = imported.showTimeline
                },
            )

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { showSchemeNameDialog = true }) {
                    Text(stringResource(R.string.setting_personalization_save_as_scheme))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                Button(onClick = { onSave(snapshot()) }) {
                    Text(stringResource(R.string.setting_theme_page_save))
                }
            }
        }
    }

    if (showSchemeNameDialog) {
        SchemeNameDialog(
            onConfirm = { name -> onSaveScheme(name, snapshot()) },
            onDismiss = { showSchemeNameDialog = false },
        )
    }
}
