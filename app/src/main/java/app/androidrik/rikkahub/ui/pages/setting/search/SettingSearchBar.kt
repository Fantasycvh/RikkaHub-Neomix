package app.androidrik.rikkahub.ui.pages.setting.search

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Search01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.ui.CardGroup

/**
 * 设置页里的搜索入口：一个占位式搜索框（点击进搜索页，不在本页做输入）。
 *
 * 放在页面正文第一行（大标题之下、第一组设置之上），而不是塞进顶栏：
 * 顶栏图标和返回键挤在一起，用户扫视时容易当成「返回」的一部分而忽略；
 * 正文里一个整行搜索框的可见性与「这是一个输入框」的暗示都强得多。
 *
 * 视觉上沿用 [CardGroup] 单行卡片，和下面的设置分组是同一套卡片语言。
 */
@Composable
fun SettingSearchBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CardGroup(modifier = modifier) {
        item(
            onClick = onClick,
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.Search01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            headlineContent = {
                Text(
                    text = stringResource(R.string.setting_global_search_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}
