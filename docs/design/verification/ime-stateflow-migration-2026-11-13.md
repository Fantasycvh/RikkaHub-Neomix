# 设置类输入框写穿回灌：剩余 46 处迁移（阶段二）验证记录

日期：2026-11-13。承接 [2026-11-12 根治记录](ime-textfield-echo-2026-11-12.md)（提交 `1808d418f`，本地未推送）。
本阶段把 `.tmp/audit-ime/stateflow-audit.md` 附录 C 列出的 48 处高风险里**剩余 46 处**全部迁到
`ui/hooks/PersistedTextFieldState.kt#rememberPersistedTextFieldState`（前 2 处已在上次提交内完成）。

## 范围与批次

| 批次 | 文件 | 处数 | 要点 |
| --- | --- | --- | --- |
| 助手提示词 | `AssistantPromptPage.kt`、`PropertyEditor.kt`、`AssistantDetailVM.kt` | 11 | messageTemplate / systemMessageTemplate / 预设消息 / 角色卡 5 槽（共用 `CardPartField`）/ 正则 name·findRegex·replaceString；VM 新增字段级 transform（按消息 id、正则 id 定位；`trim()` 移到提交时，正则里恢复可输入空格）。PropertyEditor 的 header 名/值、body key 用 `state =`；body JSON 因语法高亮必须走 legacy `value:` 重载 → 用**本地 TextFieldState 作唯一数据源桥接**（`value = state.text`、`onValueChange = { setTextAndPlaceCursorAtEnd(it) }`），保留 `HighlightCodeVisualTransformation` |
| 备份同步 | `backup/tabs/S3Tab.kt`、`WebDavTab.kt` | 9 | endpoint/accessKey/secretKey/bucket/region、webdav url/username/password/path；`trim()` 移到提交；`resetKey = settings.init`（`Settings.dummy()` 占位 → 真实设置加载后翻一次）。密码框用**等长 `OutputTransformation`** 掩码替代状态版缺失的 `visualTransformation` |
| 设置·搜索/知识库 | `SettingSearchDetailPage.kt`、`SettingKnowledgeBaseDetailPage.kt` | 19 | SearXNG 5 / Perplexity 2（D1 数值投影）/ Jina 2（trim）/ Grok 3 / CustomJs 3（脚本字段因高亮沿用桥接）；`save()` 由组合期 `settings.copy` 快照写改为 `vm.updateSettings { }` transform。KB 名称 + topK/threshold/dimensions 三个 D1 数值投影，解析成功才写、`0.` 之类中间态留本地 |
| 设置·其余 | `SettingPreferencesNetworkPage.kt`、`SettingPreferencesGeneralPage.kt`、`SettingModelPromptPage.kt`、`SettingModelPage.kt` | 7 | 代理 host/username/password（port 已有本地权威 `portText`，**豁免不动**并注记）；重试间隔/半截字数（原 `remember(settings)` 缓冲每回声重建）；6 个模型提示词共用一个编辑器（打开 sheet 即换值，重置先比较再 `setTextAndPlaceCursorAtEnd`）；上下文窗口覆盖 `1M`/`256k` 不再被逐键重格式化，解析失败不落库 + `isError` |

统一改动：`value/onValueChange` → `state = rememberPersistedTextFieldState(externalValue, onCommit, resetKey)`；
`isError`/`supportingText` 从本地 `state.text` 计算（两分支都渲染 `Text`，高度恒定）；
写回优先 `settingsStore.update { }` 字段级 transform（`SettingVM.updateSettings(transform)`、
`AssistantDetailVM.updateAssistantField`），不再「读 `settings.value` → 改整对象 → 写回」；
`resetKey` 一律用实体 id（消息 id / 正则 id / 槽位 / 搜索服务 id / 知识库 id / `settings.init`），
禁止用值本身或列表下标（只在无 id 且不支持重排的 header/body 列表用「下标 + 长度」）。

## 验证

| 检查 | 结果 |
| --- | --- |
| `:app:compileDebugKotlin` | ✅ 0 error（仅 1 条既有 `Slider` 弃用告警 + 已修一条 `!!` 告警） |
| `:app:assembleDebug` + 真机安装 | ✅ |
| `:app:testDebugUnitTest` 全量 | ✅ 3449 项，0 失败/错误，2 跳过 |
| `python scripts/audit_ime_trap.py` | ✅ `A=0  A'=0（豁免 4）  B=4  C=35  D=6  E=37`（E 由 46 降至 37，剩余为已判定豁免的 provider/media/lorebook 形态） |
| `python -B scripts/test_audit_ime_trap.py` | ✅ 11/11 |

### 真机抽检（PJX110，Android 15，debug 包，均为临时助手，验证后已删除）

注入节奏与阶段一一致：逐字符 120 ms；中间光标用 `KEYCODE_DPAD_LEFT` 后插字符验证光标不被重置。

| 用例 | 结果 |
| --- | --- |
| 系统提示词（本地 `TextFieldState`，对照）120 ms 注入 `a..j` + 中间插 `X` | `abcdefgXhij` ✅ |
| 聊天内容模板（本批迁移）120 ms 注入 `a..j` + 中间插 `X` | `abcdefghXij` ✅（预览卡实时同步） |
| 助手名称（阶段一）追加 `a..j` + 中间插 `X` | `tabcdefghXij` ✅ |
| 自定义 Header 名/值（`trim()` 只在提交） | `X-Test` / **`a b c`**（输入中间空格全程保留）✅ |
| 自定义 Body JSON（高亮桥接）输入 `[1,2]` | 语法高亮正常、无「无效 JSON」提示；中途错误态文案随本地文本更新 ✅ |
| 离页补写 | 停止输入后直接返回，重新进入各页值完整（去抖 250ms 内草稿由 `onDispose` 补写）✅ |
| 临时助手删除 | 列表不再出现，设备用户数据未改 ✅ |

未做真机抽检的页面：S3/WebDAV、偏好网络/通用、模型提示词、上下文覆盖、搜索详情、知识库详情——
这些字段写入的是**全局设置/真实数据**，为避免污染用户配置只做了编译 + 单测 + 静态审计验证；
后续可在备份/恢复流程里再补一次交互抽检。

## 已知残留（不在本次范围）

1. `AssistantPromptPage` 的**系统提示词**大文本域已是本地 `rememberTextFieldState`（不抖），但仍逐键
   `onUpdate(assistant.copy(systemPrompt = …))` 整对象写 → 建议后续加 `vm.updateSystemPrompt` + 去抖。
2. `BackupVM.updateSettings(settings.copy(...))`、`KnowledgeBaseVM` 的 `onUpdate(kb.copy(...))` 仍是
   组合期实体快照写（本批只迁了状态流；两个字段在同一帧内先后提交仍有互相覆盖窗口，概率极低）。
   建议给这两个 VM 加字段级 transform 入口。
3. `SettingVM.updateSettings` 走 `viewModelScope`：页面整页返回且 VM 同时 `onCleared` 时，hook 的
   `onDispose` 补写理论上可能被取消（导航过渡通常 > 250 ms，风险低）。根治需注入 `AppScope`。
4. 代理端口 `portText`：已有本地权威缓冲（回灌免疫），但仍是逐键整份写盘，属存量。
5. `PropertyEditor` 的 header/body 列表无持久 id，`resetKey = index to size`：250 ms 去抖窗口内
   「加一行又立刻删」可能丢未提交草稿。
6. 搜索详情的 CustomJs 脚本、PropertyEditor 的 JSON body 用 legacy `value:` 桥接（为保语法高亮）：
   `value` 源是同步本地态，不回灌不抖；若真机见到异常可退回 `state =` 并放弃高亮。
7. D 类（`imePadding` 顺序）存量 6 处、规则 2 存量 4 处未动（与本次写穿无关）。

## 复核命令

```bash
python scripts/audit_ime_trap.py          # 期望 exit 0，A/A' 均为 0
python -B scripts/test_audit_ime_trap.py  # 期望 OK（11 项）
bash scripts/build.sh ":app:testDebugUnitTest"
bash scripts/build.sh ":app:assembleDebug"
```
