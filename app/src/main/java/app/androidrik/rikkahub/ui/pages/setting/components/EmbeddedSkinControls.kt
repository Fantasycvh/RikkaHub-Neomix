package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.ui.*
import app.androidrik.rikkahub.ui.theme.AppSkinTheme
import app.androidrik.rikkahub.ui.theme.ResolvedAppSkin
import app.androidrik.rikkahub.ui.theme.resolveColors
import app.androidrik.rikkahub.ui.theme.JetbrainsMono

@Composable
fun EmbeddedSkinControls(draft: AppSkin, onChange: (AppSkin) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    AppOutlinedButton(onClick = { editing = true }, modifier = Modifier.testTag("embedded_skin_open")) {
        Text("网页、终端和画布")
    }
    if (editing) EmbeddedSkinEditor(draft, onSave = { onChange(draft.copy(embedded = it)); editing = false },
        onDismiss = { editing = false })
}

@Composable
internal fun EmbeddedSkinEditor(skin: AppSkin, onSave: (AppSkinEmbedded) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(skin.embedded) }
    val web = draft.web
    val terminal = draft.terminal
    val canvas = draft.canvas
    val valid = listOf(web.backgroundHex, web.foregroundHex, web.accentHex, terminal.foregroundHex,
        terminal.backgroundHex, terminal.cursorHex, canvas.workspaceHex, canvas.selectionHex, canvas.newPaperHex)
        .all { isSkinEmbeddedColor(it.orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("网页、终端和画布", style = MaterialTheme.typography.titleLarge)
            Text("留空继承默认颜色，格式为 #RRGGBB。这里先编辑草稿，返回后仍需保存全局样式。")
            Row { Text("自有网页跟随皮肤", Modifier.weight(1f)); Switch(web.enabled,
                { draft = draft.copy(web = web.copy(enabled = it)) }) }
            EmbeddedColorField("网页底色", web.backgroundHex) { draft = draft.copy(web = web.copy(backgroundHex = it)) }
            EmbeddedColorField("网页文字", web.foregroundHex) { draft = draft.copy(web = web.copy(foregroundHex = it)) }
            EmbeddedColorField("网页强调色", web.accentHex) { draft = draft.copy(web = web.copy(accentHex = it)) }
            Text("仅对声明支持皮肤的 HTML 生效；远程网页、作者指定的 CSS 和 SVG 颜色保持自身定义。本机字体在网页内回退到对应通用字体。")
            HorizontalDivider()
            Row { Text("终端跟随应用配色", Modifier.weight(1f)); Switch(terminal.followAppColors,
                { draft = draft.copy(terminal = terminal.copy(followAppColors = it)) }) }
            EmbeddedColorField("终端文字", terminal.foregroundHex) { draft = draft.copy(terminal = terminal.copy(foregroundHex = it)) }
            EmbeddedColorField("终端底色", terminal.backgroundHex) { draft = draft.copy(terminal = terminal.copy(backgroundHex = it)) }
            EmbeddedColorField("终端光标", terminal.cursorHex) { draft = draft.copy(terminal = terminal.copy(cursorHex = it)) }
            Text("终端字号：${terminal.fontSizeSp.toInt()} sp，叠加全局字号比例")
            Slider(terminal.fontSizeSp, { draft = draft.copy(terminal = terminal.copy(fontSizeSp = it)) }, valueRange = 8f..24f,
                modifier = Modifier.semantics { contentDescription = "皮肤 终端字号" })
            Text("保留等宽字体；终端程序显式设置的 ANSI、真彩色与 OSC 配色优先。")
            HorizontalDivider()
            EmbeddedColorField("画布工作区", canvas.workspaceHex) { draft = draft.copy(canvas = canvas.copy(workspaceHex = it)) }
            EmbeddedColorField("画布选择框", canvas.selectionHex) { draft = draft.copy(canvas = canvas.copy(selectionHex = it)) }
            EmbeddedColorField("新画纸底色", canvas.newPaperHex) { draft = draft.copy(canvas = canvas.copy(newPaperHex = it)) }
            Text("新画纸底色同时用于显示和导出；已打开的画纸、底图和笔画颜色不会随换肤改写。")
            if (valid) AppSkinTheme(ResolvedAppSkin(skin.copy(embedded = draft.normalized())), MaterialTheme.colorScheme) {
                val colors = terminal.normalized().resolveColors(MaterialTheme.colorScheme)
                Surface(color = androidx.compose.ui.graphics.Color(colors.background), shape = MaterialTheme.shapes.medium) {
                    Text("$ echo skin\nskin", Modifier.fillMaxWidth().padding(16.dp),
                        color = androidx.compose.ui.graphics.Color(colors.foreground),
                        fontFamily = JetbrainsMono, fontSize = appSkinTextSize(terminal.fontSizeSp.sp))
                }
            }
            Row {
                AppTextButton(onClick = { draft = AppSkinEmbedded() }) { Text("重置此组") }
                AppTextButton(onClick = onDismiss, modifier = Modifier.testTag("embedded_skin_cancel")) { Text("取消") }
                AppButton(enabled = valid, onClick = { onSave(draft.normalized()) },
                    modifier = Modifier.testTag("embedded_skin_save")) { Text("应用到草稿") }
            }
        }
    }
}

@Composable
private fun EmbeddedColorField(label: String, value: String?, onChange: (String?) -> Unit) {
    AppOutlinedTextField(value.orEmpty(), { onChange(it.takeUnless(String::isEmpty)) },
        label = { Text(label) }, placeholder = { Text("继承默认") }, singleLine = true,
        isError = !isSkinEmbeddedColor(value.orEmpty()), modifier = Modifier.fillMaxWidth().testTag("embedded_$label"))
}
