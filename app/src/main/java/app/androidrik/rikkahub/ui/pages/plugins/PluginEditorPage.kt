package app.androidrik.rikkahub.ui.pages.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.plugin.js.JsPluginInstaller
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.CustomColors
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * JS 插件修订编辑器（R2，设计稿 §五.1「应用内编辑」入口；仅 debug 构建可达）。
 *
 * 整页编辑器（AGENTS「TextField 宿主规范」）：Column + verticalScroll 子项常驻，
 * 不内联任何 Lazy 容器；保存草稿/提交预检都走 AppScope，离开页面不取消写盘。
 *
 * 编辑的是**已安装插件**：提交 = 同 id 修订（origin=EDITOR），走 installOrUpdate 的
 * 预检→代次切换链路；禁止改 id（改 id 等于新建插件，授权无法沿用）。
 */
@Composable
fun PluginEditorPage(pluginId: String) {
    val installer: JsPluginInstaller = koinInject()
    val appScope: AppScope = koinInject()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val nav = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var source by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // 初始内容：草稿优先，其次当前生效源码
    LaunchedEffect(pluginId) {
        source = installer.loadDraft(pluginId) ?: installer.currentSource(pluginId)
    }

    suspend fun submit() {
        val text = source.orEmpty()
        busy = true
        errorText = null
        try {
            val definition = app.androidrik.rikkahub.data.plugin.js.JsPluginManifest.parse(text)
                .getOrElse {
                    errorText = "清单解析失败: ${it.message}"
                    return
                }
            if (definition.id != pluginId) {
                errorText = "不能修改插件 id（${definition.id} ≠ $pluginId）"
                return
            }
            installer.installOrUpdate(
                definition,
                approvedCapabilities = emptySet(),
                origin = app.androidrik.rikkahub.data.plugin.revision.RevisionOrigin.EDITOR,
            )
                .onSuccess {
                    toaster.show(message = "修订已激活", type = ToastType.Success)
                    nav.popBackStack()
                }
                .onFailure { errorText = it.message }
        } finally {
            busy = false
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("编辑修订") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
                actions = {
                    TextButton(
                        enabled = !busy && source != null,
                        onClick = {
                            appScope.launch {
                                installer.saveDraft(pluginId, source)
                                toaster.show(message = "草稿已保存", type = ToastType.Success)
                            }
                        },
                    ) { Text("存草稿") }
                    TextButton(
                        enabled = !busy && !source.isNullOrBlank(),
                        onClick = { appScope.launch { submit() } },
                    ) { Text("提交预检") }
                },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when {
            source == null -> Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                errorText?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = source.orEmpty(),
                    onValueChange = { source = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    minLines = 24,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodySmall.fontSize),
                    supportingText = { Text("提交 = 预检 → 激活新代次；失败不影响当前生效版本", style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}
