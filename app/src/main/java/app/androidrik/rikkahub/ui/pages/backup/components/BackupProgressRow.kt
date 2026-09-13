package app.androidrik.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.sync.BackupProgress
import app.androidrik.rikkahub.utils.fileSizeToString

/**
 * 备份/恢复进度行：确定进度时显示「已完成/总量 · 百分比」，总量未知时显示不确定进度条。
 *
 * **刻意只用数字与百分比**（`1.2 GB / 2.8 GB · 43%`），不引入阶段文案与 6 语言资源——
 * 阶段名对判断「是否卡死」价值有限，而数字已足够（见 `docs/design-backup-workspace-files.md` §9.1）。
 *
 * @param progress null 时不渲染（无进行中任务）
 */
@Composable
fun BackupProgressRow(
    progress: BackupProgress?,
    modifier: Modifier = Modifier,
) {
    if (progress == null) return

    val fraction = progress.fraction
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // 总量未知（如 chunked 下载拿不到 Content-Length）
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (progress.totalBytes != null) {
                    "${progress.processedBytes.fileSizeToString()} / ${progress.totalBytes.fileSizeToString()}"
                } else {
                    progress.processedBytes.fileSizeToString()
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (fraction != null) {
                Text(
                    text = "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
