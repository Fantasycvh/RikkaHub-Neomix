package app.androidrik.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Anchor
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Incognito

/**
 * **会话内的记忆入口**（`docs/design/design-memory-injection-tiering.md` §8.3）。
 *
 * 在此之前，两个记忆控制**只存在于抽屉的会话列表长按菜单**里，聊天页本身没有任何入口 ——
 * 用户想给「这个对话」关记忆或固化锚点，得先退出到抽屉、长按那一行。本按钮把它提到
 * 输入栏的图标行里，与 [SearchPickerButton]、推理等级、上下文占用同处一行：它们回答的
 * 都是同一个问题——"这次请求的上下文里放了什么"。
 *
 * **为什么是一个按钮 + 选择器，而不是两个裸按钮**：两个动作性质不同。
 * - **隐身是状态** ⇒ 必须一眼可见，所以状态直接做在图标上（`Incognito` = 本对话不碰记忆库；
 *   `Brain02` = 正常使用），并由 [ToggleSurface] 的着色强化"开着"的观感；
 * - **固化是一次性动作** ⇒ 不需要一直占一个图标位，放进选择器里即可。
 * 把两者都做成裸图标会让图标行从 4 个变 6 个，横滑更长；而"点一下切隐身、长按固化"
 * 这种分法没有发现性（用户不会去猜能长按）。
 *
 * 抽屉菜单里那两项**保留**：菜单作用于**任意一行**会话（包括不是当前这条的），
 * 是这个按钮够不到的地方，不构成重复。
 *
 * 可见性由调用方决定：助手根本不用记忆（`Assistant.enableMemory == false`）时不该占位。
 */
@Composable
fun MemoryButton(
    /** 本对话是否「不使用记忆」（隐身模式）—— 决定图标与着色 */
    memoryDisabled: Boolean,
    modifier: Modifier = Modifier,
    onToggleMemoryDisabled: (Boolean) -> Unit,
    onFreezeMemory: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        // 开着 = primary 着色（与联网搜索按钮同款语言）；隐身 = 常态色 + Incognito 图标
        ToggleSurface(
            checked = !memoryDisabled,
            onClick = { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (memoryDisabled) HugeIcons.Incognito else HugeIcons.Brain02,
                        // 复用记忆页标题（"记忆"）当无障碍标签：同一个概念不必再多一份译文
                        contentDescription = stringResource(R.string.assistant_page_tab_memory),
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            // 与抽屉菜单同款两行、同款文案与勾选态表达（勾选态用图标前缀，不引入新样式）
            DropdownMenuItem(
                leadingIcon = {
                    if (memoryDisabled) {
                        Icon(HugeIcons.CheckmarkCircle02, null)
                    }
                },
                text = { Text(stringResource(R.string.chat_memory_disabled_toggle)) },
                onClick = {
                    expanded = false
                    onToggleMemoryDisabled(!memoryDisabled)
                },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(HugeIcons.Anchor, null) },
                text = { Text(stringResource(R.string.chat_memory_freeze_action)) },
                onClick = {
                    expanded = false
                    onFreezeMemory()
                },
            )
        }
    }
}
