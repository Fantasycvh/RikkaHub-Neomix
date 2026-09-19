package app.androidrik.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.model.Lorebook
import app.androidrik.rikkahub.data.model.PromptPreset
import app.androidrik.rikkahub.data.model.countSystemRewritingInjections
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02

/**
 * #1542 复核 Tier A：一键迁移提示卡。
 *
 * 检测到 `BEFORE/AFTER_SYSTEM_PROMPT` 位置的注入条目时显示——该位置会**改写 system 消息
 * 本体**（请求位置 0），任何变化都作废整段前缀缓存。主要来源：默认种子「Learning Mode」
 * （旧装用户）与 ST 导入的 position=1 条目。
 *
 * 无此类条目时返回 Unit 不渲染（调用方放在列表首位即可）。
 */
@Composable
fun CacheMigrationCard(
    presets: List<PromptPreset>,
    lorebooks: List<Lorebook>,
    onMigrate: () -> Unit,
) {
    val count = countSystemRewritingInjections(presets, lorebooks)
    if (count == 0) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = HugeIcons.Alert02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = stringResource(R.string.prompt_page_cache_migration_hint, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onMigrate,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.prompt_page_cache_migration_action))
            }
        }
    }
}
