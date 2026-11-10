package app.androidrik.rikkahub.ui.theme

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.ui.components.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject

private val LocalTrialHost = compositionLocalOf { false }

@Composable
internal fun SkinTrialHost(content: @Composable () -> Unit,
    update: (suspend ((DisplaySetting) -> DisplaySetting) -> Unit)? = null, timeoutMillis: Long = 120_000) {
    if (LocalTrialHost.current) { content(); return }
    val controller = SkinTrialController.shared
    val trial by controller.trial.collectAsState()
    val undo by controller.undo.collectAsState()
    val busy by controller.busy.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(trial?.token) { error = null; trial?.let {
        val elapsed = (System.nanoTime() - it.startedNanos) / 1_000_000
        delay((timeoutMillis - elapsed).coerceAtLeast(0))
        while (controller.busy.value && controller.trial.value?.token == it.token) delay(100)
        controller.cancel(it.token)
    } }
    DisposableEffect(Unit) {
        val lease = controller.attachHost()
        onDispose { lease.close() }
    }
    CompositionLocalProvider(LocalTrialHost provides true) {
        Box {
            content()
            if (trial != null || undo != null) {
                val store: SettingsStore? = if (update == null) koinInject<SettingsStore>() else null
                val writer: suspend ((DisplaySetting) -> DisplaySetting) -> Unit = update ?: { transform ->
                    requireNotNull(store).update { it.copy(displaySetting = transform(it.displaySetting)) }
                }
                // Rescue controls use known defaults, even if the trial is unreadable.
                AppSkinTheme(ResolvedAppSkin(), lightColorScheme()) {
                    Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().fillMaxWidth(), shadowElevation = 8.dp) {
                        Column(Modifier.padding(12.dp)) {
                            Text(trial?.let { "试穿：${it.name} · 两分钟后自动撤销" } ?: "皮肤已应用，可撤销本次更改")
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Row {
                                AppTextButton(enabled = !busy, onClick = {
                                    if (trial != null) controller.cancel(trial?.token) else {
                                      val expected = undo
                                      scope.launch {
                                        try { controller.undo(writer, expected = expected) }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) { error = failure.message }
                                      }
                                    }
                                }) { Text("撤销") }
                                if (trial != null) AppButton(enabled = !busy, onClick = {
                                  val token = trial?.token
                                  scope.launch {
                                    try { controller.apply(writer, token = token) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { error = failure.message }
                                  }
                                }) { Text("确认应用") }
                                else AppTextButton(enabled = !busy, onClick = { controller.dismissUndo() }) { Text("完成") }
                            }
                        }
                    }
                }
            }
        }
    }
}
