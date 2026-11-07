package app.androidrik.rikkahub.ui.pages.chat

// 批次 7 拆出：ChatContextCapacitySheet 外壳 + HiddenSessionBanner 自 ChatPage.kt 拆出（同包，private→internal）

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.ai.ContextUsage
import app.androidrik.rikkahub.data.ai.ContextCapacity
import app.androidrik.rikkahub.data.ai.computeContextCapacity

/**
 * 容量面板的外壳：**打开时只读组装一次**（跑一遍与真实请求同源的 transformer 链），
 * 再交给 [ContextCapacitySheet] 渲染。不落库、不发请求。
 */
@Composable
internal fun ChatContextCapacitySheet(
    vm: ChatVM,
    assistantName: String,
    modelName: String,
    contextUsage: ContextUsage,
    onCompress: () -> Unit,
    onDismiss: () -> Unit,
) {
    val capacity by produceState<ContextCapacity?>(initialValue = null, vm) {
        val inspection = runCatching { vm.inspectPromptAssembly(caller = "capacity") }.getOrNull()
        value = inspection?.let {
            computeContextCapacity(
                usedTokens = contextUsage.usedTokens,
                windowTokens = contextUsage.windowTokens,
                segments = it.segments,
                sentHistoryCount = it.sentHistoryCount,
                totalMessageCount = it.totalMessageCount,
                localTotalTokens = it.totalTokens,
            ).copy(omittedTransformers = it.omittedTransformers)
        }
    }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
    ) {
        ContextCapacitySheet(
            assistantName = assistantName,
            modelName = modelName,
            capacity = capacity,
            onCompress = onCompress,
            onDismiss = onDismiss,
        )
    }
}

/**
 * 隐形会话的会话内横幅（design-proactive-task-management.md §二.3）：隐藏会话不出现在
 * 任何列表，这里是用户被任务详情页/通知带进来之后的**唯一**可见解释与出口——
 * 「移出隐藏」= 收养进列表（数据层支持再隐藏，v1 不给普通会话露出隐藏入口）。
 */
@Composable
internal fun HiddenSessionBanner(onUnhide: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.chat_hidden_banner_text),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f, fill = false),
            )
            TextButton(onClick = onUnhide) {
                Text(stringResource(R.string.chat_hidden_unhide_action))
            }
        }
    }
}
