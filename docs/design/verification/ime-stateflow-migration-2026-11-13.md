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

## 阶段三（同日收尾）：存量残留清理

针对阶段二的复核列出的残留，再做一轮「就地清掉 + 真机复验」：

| 残留项 | 处理 | 证据 |
| --- | --- | --- |
| 系统提示词大文本域逐键整对象写（每键重写整份 `Settings` + 锁外读-改-写竞态） | `AssistantPromptPage` 改用 `rememberPersistedTextFieldState(resetKey = assistant.id)`；VM 新增 `updateSystemPrompt`（字段级 transform，AppScope）；「回到卡片原文」按钮先比较再 `setTextAndPlaceCursorAtEnd` 同步本地编辑态（旧实现点了按钮框里还是旧文本） | 真机：120 ms/字符注入 + 中间插字 `abcdefghXij`；点变量 chip 插入 `{{cur_date}}`；输入尾字符后**立刻返回**，重进页面完整保留（`…Xij{{cur_date}}{{system_version}}z{{system_version}}Q`） |
| `SettingVM` / `BackupVM` / `KnowledgeBaseDetailVM` 提交走 `viewModelScope`（离页补写可能与 `onCleared` 赛跑丢最后几个字符） | 三个 VM 统一注入 `AppScope`（`viewModelOf` 自动解析，KB VM 为手动构造补 `appScope = get()`） | 真机：偏好→网络→代理端口输入 `1234` 后**立刻返回**，重进页面显示 `1234`（未丢失）；验证后已把端口清空、代理类型还原为「无」 |
| `BackupVM` / 知识库仍是组合期实体快照写（多字段并发提交互相覆盖） | `BackupVM` 新增 `updateS3Config { }` / `updateWebDavConfig { }` / `updateSettings(transform)`（内部 `settingsStore.update {}`）；S3/WebDAV tab 全部改字段级调用。知识库改 `RagRepository.updateKnowledgeBase(id, transform)`：`Mutex.withLock` 内读盘上最新实体 → transform → 写回，页面只传 `{ it.copy(...) }` | 编译 + 单测 + 静态复核（`dao` 读最新 + 串行锁）；官方口径仍是「进程内串行」，未引入 Room 事务（`AppDatabase` 注入不在该批范围） |
| 代理端口逐键整份写 | 端口迁移到 `rememberPersistedTextFieldState`（解析只在提交时），落盘 `vm.updateSettings { }` transform | 同上真机端口用例（1234 去抖落盘，清空后为 0） |

**阶段三验证**：`:app:assembleDebug` ✅；`:app:testDebugUnitTest` **3449 项 0 失败/错误、2 跳过** ✅；`audit_ime_trap.py` **A=0 A'=0（豁免 4）、B=4 C=35 D=6 E=37** ✅（E 未再增加）；`test_audit_ime_trap.py` 11/11 ✅；临时助手与代理设置均已还原/删除。

**阶段三后仍存的存量**（已评估为低危或超范围，不再动）：

1. `backup/tabs/ImportExportTab.kt:261`、`ReminderTab.kt:35`、`SnapshotBackupTab.kt:119` 仍是 `vm.updateSettings(settings.copy(...))` 快照写（非文本输入触发，单次点击，快照窗口极小）；`BackupVM/SettingVM` 的快照重载保留供它们使用。
2. `PropertyEditor` 的 header/body 行无持久 id（`resetKey = index to size`）：250 ms 去抖窗口内「加一行又立刻删」可能丢未提交草稿。
3. 高亮类字段（PropertyEditor JSON body、CustomJs 脚本）仍走 legacy `value:` 桥接；`value` 源是同步本地态，不回灌。
4. D 类（`imePadding` 顺序）6 处、规则 2 存量 4 处（表单内联在 Lazy item）未动，属宿主拓扑而非写穿。

## 已知残留（阶段二结束时，已被阶段三处理大部分，保留历史）


1. ~~`AssistantPromptPage` 的系统提示词逐键整对象写~~ → **阶段三已修**（`updateSystemPrompt` + hook 去抖）。
2. ~~`BackupVM` / 知识库的组合期实体快照写~~ → **阶段三已修**（字段级 transform + `AppScope`；KB 走 Repository 内串行读-改-写）。
3. ~~`SettingVM` 用 `viewModelScope` 导致离页补写可能被取消~~ → **阶段三已修**（`SettingVM`/`BackupVM`/`KnowledgeBaseDetailVM` 统一注入 `AppScope`）。
4. ~~代理端口逐键整份写~~ → **阶段三已修**（hook 本地权威 + 去抖 + 字段级 transform）。
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
