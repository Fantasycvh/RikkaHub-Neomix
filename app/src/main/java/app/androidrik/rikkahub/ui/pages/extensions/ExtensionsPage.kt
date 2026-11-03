package app.androidrik.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import app.androidrik.rikkahub.R
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Robot01
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Zap
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.pages.setting.SettingVM
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun ExtensionsPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    // P0 回滚开关：关 = 隐藏任务中心入口，旧 Agent 运行列表保留
    val settings by koinViewModel<SettingVM>().settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.extensions_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.extensions_page_section_extensions)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.QuickMessages) },
                        leadingContent = { Icon(HugeIcons.Zap, null) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_quick_messages)) },
                        supportingContent = { Text(stringResource(R.string.extensions_page_quick_messages_desc)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Prompts) },
                        leadingContent = { Icon(HugeIcons.Book03, null) },
                        headlineContent = { Text(stringResource(R.string.extensions_page_prompts)) },
                        supportingContent = { Text(stringResource(R.string.extensions_page_prompts_desc)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Skills) },
                        leadingContent = { Icon(HugeIcons.Puzzle, null) },
                        headlineContent = { Text(stringResource(R.string.extensions_page_agent_skills)) },
                        supportingContent = { Text(stringResource(R.string.extensions_page_agent_skills_desc)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Workspaces) },
                        leadingContent = { Icon(HugeIcons.Folder01, null) },
                        headlineContent = { Text(stringResource(R.string.extensions_page_workspace)) },
                        supportingContent = { Text(stringResource(R.string.extensions_page_workspace_desc)) },
                    )
                    if (settings.taskCenterEnabled) {
                        item(
                            onClick = { navController.navigate(Screen.TaskCenter) },
                            leadingContent = { Icon(HugeIcons.Rocket01, null) },
                            headlineContent = { Text(stringResource(R.string.task_center_title)) },
                            supportingContent = { Text(stringResource(R.string.task_center_desc)) },
                        )
                    } else {
                        // 任务中心开启后子代理运行/后台命令/定时任务统一进任务中心；旧列表仅回滚态可见
                        item(
                            onClick = { navController.navigate(Screen.AgentRuns) },
                            leadingContent = { Icon(HugeIcons.Rocket01, null) },
                            headlineContent = { Text(stringResource(R.string.agent_runs_title)) },
                            supportingContent = { Text(stringResource(R.string.agent_runs_desc)) },
                        )
                    }
                    item(
                        onClick = { navController.navigate(Screen.SubagentPresets) },
                        leadingContent = { Icon(HugeIcons.Robot01, null) },
                        headlineContent = { Text(stringResource(R.string.subagent_page_title)) },
                        supportingContent = { Text(stringResource(R.string.subagent_page_desc)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Plugins) },
                        leadingContent = { Icon(HugeIcons.Package, null) },
                        headlineContent = { Text(stringResource(R.string.extensions_page_plugins)) },
                        supportingContent = { Text(stringResource(R.string.extensions_page_plugins_desc)) },
                    )
                }
            }
        }
    }
}
