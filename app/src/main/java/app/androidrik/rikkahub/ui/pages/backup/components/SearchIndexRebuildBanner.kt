package app.androidrik.rikkahub.ui.pages.backup.components

import app.androidrik.rikkahub.ui.components.ui.appSkinRoundedShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.RikkaHubApp
import app.androidrik.rikkahub.data.db.fts.SearchIndexRebuildSignal
import app.androidrik.rikkahub.data.db.fts.SearchIndexRebuildState

/**
 * 恢复换库后的搜索索引重建进度横幅。
 *
 * 背景：逻辑对象快照不含 FTS 表，换库后必须在启动期全量重建；大库重建要十几秒到几十秒，
 * 期间搜索结果是空的。之前用户完全看不到这件事，只能等。横幅挂在根 UI 上（任何页面可见），
 * 重建成功自动消失，失败提供重试（磁盘标记仍在，下次冷启动也会重试）。
 */
@Composable
fun SearchIndexRebuildBanner(content: @Composable () -> Unit) {
    val state by SearchIndexRebuildSignal.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
        content()
        when (val current = state) {
            is SearchIndexRebuildState.Running -> Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = appSkinRoundedShape(12f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                    Column {
                        Text("正在重建搜索索引", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (current.total > 0) "${current.done} / ${current.total} 个会话" else "正在准备…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            is SearchIndexRebuildState.Failed -> Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = appSkinRoundedShape(12f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("搜索索引重建失败", style = MaterialTheme.typography.titleSmall)
                        Text(
                            current.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    TextButton(onClick = {
                        (context.applicationContext as? RikkaHubApp)?.retrySearchIndexRebuild()
                    }) { Text("重试") }
                }
            }
            else -> Unit
        }
    }
}
