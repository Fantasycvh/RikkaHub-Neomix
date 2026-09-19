package app.androidrik.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import app.androidrik.rikkahub.data.model.InjectionMigrationDirection
import app.androidrik.rikkahub.data.model.Lorebook
import app.androidrik.rikkahub.data.model.PromptPreset
import app.androidrik.rikkahub.data.model.countMigratableInjections
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02

/**
 * 注入位置批量迁移卡（**双向工具，不做强制**，#1542 Tier A 及其反向）。
 *
 * 两个方向各自独立显示（有可迁移条目才显示对应行）：
 * - 系统区（系统提示词前/后）→ 对话末尾：缓存友好（该位置会改写 system 本体、作废整段前缀缓存）；
 * - 对话末尾 → 系统提示词后：系统级强调（接受缓存代价，用户的选择优先于缓存策略）。
 *
 * 位置是用户的表达，迁移只是批量重排工具——手动单条编辑仍可自由选任何位置，
 * 新建条目的默认位置也保持系统区（不强制缓存友好）。
 */
@Composable
fun CacheMigrationCard(
    presets: List<PromptPreset>,
    lorebooks: List<Lorebook>,
    onMigrate: (InjectionMigrationDirection) -> Unit,
) {
    val forwardCount = countMigratableInjections(presets, lorebooks, InjectionMigrationDirection.TO_BOTTOM_OF_CHAT)
    val backwardCount = countMigratableInjections(presets, lorebooks, InjectionMigrationDirection.TO_AFTER_SYSTEM_PROMPT)
    if (forwardCount == 0 && backwardCount == 0) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            if (forwardCount > 0) {
                MigrationRow(
                    hint = stringResource(R.string.prompt_page_cache_migration_hint, forwardCount),
                    action = stringResource(R.string.prompt_page_cache_migration_action),
                    onMigrate = { onMigrate(InjectionMigrationDirection.TO_BOTTOM_OF_CHAT) },
                )
            }
            if (backwardCount > 0) {
                MigrationRow(
                    hint = stringResource(R.string.prompt_page_cache_migration_back_hint, backwardCount),
                    action = stringResource(R.string.prompt_page_cache_migration_back_action),
                    onMigrate = { onMigrate(InjectionMigrationDirection.TO_AFTER_SYSTEM_PROMPT) },
                )
            }
        }
    }
}

@Composable
private fun MigrationRow(
    hint: String,
    action: String,
    onMigrate: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Alert02,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onMigrate) {
            Text(action)
        }
    }
}
