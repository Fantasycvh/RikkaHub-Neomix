package app.androidrik.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.image.resolveImageGenerationModel
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 助手的**生图**分类（上游 #2123 的 `generate_image` 工具）。
 *
 * 为什么单开一类，而不是挂在「本地工具」或「记忆」下：
 *
 * - 它不是**本地**工具（`LocalToolOption` 那一组是跑在设备上的 JS 引擎/剪贴板/时间/朗读），
 *   而 `generate_image` 是走**设置里配置的生图模型**的远程调用；
 * - 也不是记忆——它和记忆没有任何共享机制，只是曾经被拿来类比"审批开关住在哪"。
 *
 * 本页要把**两件事分清楚**（这也是这个工具最容易让人困惑的地方）：
 *
 * 1. **能不能生图** = 设置里有没有配置可用的生图模型。没配时工具**根本不会被装配**
 *    （门控在 `ChatToolFactory`，判定就是本页显示的那次解析），此时下面的开关点了也没有效果。
 * 2. **动手前要不要问一句** = [app.androidrik.rikkahub.data.model.Assistant.imageGenApproval]。
 *    默认关：上限（单次 ≤4 张、每轮 ≤2 次）已经兜住"失控花钱"这个真风险，默认零摩擦。
 */
@Composable
fun AssistantImagePage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // **与装配门控同源**：直接调用工具装配用的那个判定函数，而不是另写一份"有没有配模型"。
    // 两处一旦各写一份，就会出现"本页说已配置、工具却没装配"这种最难查的不一致。
    val imageModel = settings.resolveImageGenerationModel()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_image)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroup {
                item(
                    headlineContent = {
                        Text(stringResource(R.string.assistant_page_image_gen_approval))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.assistant_page_image_gen_approval_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = assistant.imageGenApproval,
                            onCheckedChange = { vm.update(assistant.copy(imageGenApproval = it)) },
                        )
                    }
                )
                item(
                    headlineContent = {
                        Text(stringResource(R.string.assistant_page_image_model_title))
                    },
                    supportingContent = {
                        Text(
                            text = imageModel?.let {
                                stringResource(R.string.assistant_page_image_model_configured, it.displayName)
                            } ?: stringResource(R.string.assistant_page_image_model_missing),
                        )
                    },
                    // 只读信息项：生图模型是**全局**设置（设置 → 生图），不在助手维度上选，
                    // 所以这里不给开关，只如实显示工具当前会拿哪个模型。
                )
            }
        }
    }
}
