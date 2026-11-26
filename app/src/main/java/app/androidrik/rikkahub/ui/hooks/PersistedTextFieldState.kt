package app.androidrik.rikkahub.ui.hooks

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

/** 设置类文本域的去抖提交窗口：既避免逐键整份 Settings 写盘，也避免回写竞态。 */
const val PERSISTED_TEXT_COMMIT_DEBOUNCE_MS = 250L

/**
 * 把「异步持久态」的字符串字段接到一个**本地权威**的 [TextFieldState] 上。
 * （AGENTS.md「TextField Host Rules」第 4 条：编辑态唯一权威，单向同步出去，回填先比较。）
 *
 * ## 为什么需要这个 hook（2026-11-12 真机定案）
 *
 * 旧写法是「`value = assistant.name` + `onValueChange = { onUpdate(copy(name = it)) }`」：
 * 值来自 `SettingsStore.settingsFlow` 的**异步投影**，而每次按键又会把整份 `Settings`
 * 落盘（`PreferencesStore.updateLocked` 逐 key 重编码 ~790 KB 文件），落盘之后才
 * `settingsFlow.value = …` 广播回来 —— 于是**旧值会在下一次按键之后才回灌到 TextField 的
 * `value` 参数**。Compose 的 `TextField(value: String)` 遇到外部值变化会重置内部编辑态
 * （光标被移到末尾、输入法组合被打断），用户看到的就是「每敲一个字符抖一下 / 丢字 / 光标跳」。
 *
 * 真机（PJX110，debug 包，助手「基础设定」页名称框）A/B 对照：
 * - 同一设备、同一打字节奏（120 ms/字符，注入 a..j）：
 *   - 写穿回灌的名称框 → `bcdefghija`（应为 `abcdefghij`）；整串 25 字符注入 → 只剩 `aabbxec`
 *   - 已用 `rememberTextFieldState` 的提示词框 → 完整 `abcdefghij`
 * - 新建助手 sheet（`useEditState` 本地态）→ 10 字符完整。
 * ⇒ 根因是**回灌**，不是 DataStore 写入频率本身；但逐键整份写盘仍应避免，故此处带
 * [debounceMillis] 去抖与「离开页面补写」。
 *
 * ## 约定
 *
 * - **输入框是编辑态唯一权威**：本地 [TextFieldState] 只初始化一次（`resetKey` 变化时重建），
 *   之后**绝不**把持久态回灌进活跃编辑框（外部并发修改不会反映到本框，见下）。
 * - **单向写出**：停止输入 [debounceMillis] 后提交一次；离开组合时把未提交的草稿补写，
 *   去抖窗口内返回不丢字。
 * - `onCommit` 必须是**取消安全**的：页面（组合）销毁时仍要能落盘，所以调用方应把写入
 *   交给存活期更长的 scope（如 `AppScope` / ViewModel 自己的 `AppScope`），不要用
 *   `rememberCoroutineScope()`（组合销毁即取消）。
 * - `resetKey` 用于「同一组合位置换实体」时重建本地态（如先渲染 `Assistant()` 占位、
 *   设置加载完成后换成真实助手）；**不要**用外部值本身当 key——那正是旧写法抖动的根因。
 *
 * @param externalValue 持久态里的当前值；只用于本地态**首次**初始化（以及 `resetKey` 变化时重建）。
 * @param onCommit 单向写出回调（去抖后一次 / 离开页面补写一次）。
 * @param debounceMillis 去抖窗口；0 表示逐键立即提交（不丢字但写盘频繁）。
 * @param resetKey 本地态身份；变化即重建 TextFieldState。
 */
@Composable
fun rememberPersistedTextFieldState(
    externalValue: String,
    onCommit: (String) -> Unit,
    debounceMillis: Long = PERSISTED_TEXT_COMMIT_DEBOUNCE_MS,
    resetKey: Any? = Unit,
): TextFieldState = key(resetKey) {
    PersistedTextFieldState(externalValue, onCommit, debounceMillis)
}

@Composable
private fun PersistedTextFieldState(
    externalValue: String,
    onCommit: (String) -> Unit,
    debounceMillis: Long,
): TextFieldState {
    // 只在首次组合（或 resetKey 变化后）用持久态初始化；此后它就是编辑态权威。
    val state = rememberTextFieldState(externalValue)
    val commit by rememberUpdatedState(onCommit)
    var lastCommitted by remember { mutableStateOf(state.text.toString()) }

    // 单向写出：编辑态 → 持久层。drop(1) 跳过初始值（不是用户编辑）。
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .drop(1)
            .collectLatest { text ->
                if (debounceMillis > 0) delay(debounceMillis)
                if (text != lastCommitted) {
                    lastCommitted = text
                    commit(text)
                }
            }
    }

    // 去抖窗口内离开页面（返回 / 切页 / 弹层关闭）时补写草稿：
    // LaunchedEffect 随组合销毁被取消，不补写就会丢掉最后几个字符。
    DisposableEffect(state) {
        onDispose {
            val text = state.text.toString()
            if (text != lastCommitted) commit(text)
        }
    }

    return state
}

/**
 * 同 [rememberPersistedTextFieldState]，但支持**受控外部回填**（例如同一设置项旁有滑杆、预设芯片
 * 或清除按钮会改写同一个底层值）：
 * - 自己提交出去的值经 DataStore/VM 回流时，`externalValue == lastCommitted` → **忽略回声**，
 *   绝不打断活跃 IME 或重置光标；
 * - 真正来自其他控件的变更（`externalValue != lastCommitted`）才先比较后
 *   `setTextAndPlaceCursorAtEnd` 回填本地编辑态；
 * - 非法中间态（[parse] 返回 `null`）只留在本地，不落库也不覆盖 `lastCommitted`。
 */
@Composable
fun <T> rememberSyncedPersistedTextFieldState(
    externalValue: T,
    format: (T) -> String,
    parse: (String) -> T?,
    onCommit: (T) -> Unit,
    debounceMillis: Long = PERSISTED_TEXT_COMMIT_DEBOUNCE_MS,
    resetKey: Any? = Unit,
): TextFieldState = key(resetKey) {
    SyncedPersistedTextFieldState(
        externalValue = externalValue,
        format = format,
        parse = parse,
        onCommit = onCommit,
        debounceMillis = debounceMillis,
    )
}

@Composable
private fun <T> SyncedPersistedTextFieldState(
    externalValue: T,
    format: (T) -> String,
    parse: (String) -> T?,
    onCommit: (T) -> Unit,
    debounceMillis: Long,
): TextFieldState {
    val state = rememberTextFieldState(format(externalValue))
    val latestFormat by rememberUpdatedState(format)
    val latestParse by rememberUpdatedState(parse)
    val latestCommit by rememberUpdatedState(onCommit)
    var lastCommitted by remember { mutableStateOf(externalValue) }

    // 外部程序化更新（如滑杆/预设/清除按钮）：忽略自己提交回来的回声，仅当外部值真正偏离时才回填。
    LaunchedEffect(externalValue) {
        if (externalValue != lastCommitted) {
            lastCommitted = externalValue
            val formatted = latestFormat(externalValue)
            if (state.text.toString() != formatted) {
                state.setTextAndPlaceCursorAtEnd(formatted)
            }
        }
    }

    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .drop(1)
            .collectLatest { text ->
                if (debounceMillis > 0) delay(debounceMillis)
                val parsed = latestParse(text)
                if (parsed != null && parsed != lastCommitted) {
                    lastCommitted = parsed
                    latestCommit(parsed)
                }
            }
    }

    DisposableEffect(state) {
        onDispose {
            val parsed = latestParse(state.text.toString())
            if (parsed != null && parsed != lastCommitted) {
                lastCommitted = parsed
                latestCommit(parsed)
            }
        }
    }

    return state
}

/**
 * 等长密码掩码：[TextFieldState] 版 `OutlinedTextField` 没有 `visualTransformation`
 * （签名为 `inputTransformation` / `outputTransformation`），所以密码框用
 * `outputTransformation` 兜底。这里把每个字符替换成一个 `•`，**长度保持不变**，
 * 因此文本偏移 / 光标 / 选区映射与明文一致，不会出现"掩码后光标位置错乱"。
 *
 * 用法：`outputTransformation = if (visible) null else HiddenTextTransformation`。
 */
object HiddenTextTransformation : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        if (length > 0) {
            replace(0, length, "•".repeat(length))
        }
    }
}
