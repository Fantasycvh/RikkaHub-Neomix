package app.androidrik.rikkahub.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val TAG = "CoroutineUtils"

fun <T> Flow<T>.toMutableStateFlow(
    scope: CoroutineScope,
    initial: T
): MutableStateFlow<T> {
    val stateFlow = MutableStateFlow(initial)
    scope.launch {
        runCatching {
            this@toMutableStateFlow.collect { value ->
                stateFlow.value = value
            }
        }.onFailure {
            // 生命周期结束是正常取消；不能把关闭订阅当成配置损坏并直接杀进程。
            if (it is CancellationException) throw it
            it.printStackTrace()
            Log.e(TAG, "Error while collecting flow: ${it.message}", it)

            Runtime.getRuntime().halt(1)
        }
    }
    return stateFlow
}
