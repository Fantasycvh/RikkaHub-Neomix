# Miffan（Ayuilos/Miffan）评估 —— 跨应用划词翻译（PROCESS_TEXT 弹窗）

> 对应总表 §一 #14。深度：**源码**（本地副本 `F:\build\projects\_miffan-analysis\Ayuilos-Miffan-8988130\`，2026-10-31 逐文件读核验）。
> 许可：AGPL-3.0（2026-10-31 核验 LICENSE）。同源 RikkaHub fork（包名 `me.ayuilos.miffan`，311★，活跃更新）。
> 定位：Android AI 客户端 + IM（类 Grok-Bot）模式 + ChatGPT 订阅登录 + **跨应用划词悬浮翻译** + 远程云电脑控制。本次只评估划词翻译一项。
> [返回索引](../peer-apps-benchmark.md)

## 一句话结论

跨应用划词翻译的**最省力正解**：`ACTION_PROCESS_TEXT` 对话框式 Activity（零权限、无无障碍、无悬浮窗）。
而本仓其实已有 90% 底层设施（`GenerationHandler.translateText` 甚至更强），缺口只有一个轻量弹窗壳——半天级移植。

## 它是什么（实现剖析，四层）

### 1. 入口层：对话框式 Activity 冒充悬浮窗

- `app/src/main/java/me/ayuilos/miffan/ui/activity/ProcessTextTranslatorActivity.kt`（约 45 行薄壳）。
  类注释明说：*"This is an Activity rather than an overlay, so it does not require draw-over-other-apps access."*
  任何 App 选中文字 → 系统选择菜单 "Translate with Miffan" → 拉起。**零权限**：不用 AccessibilityService、不用 `SYSTEM_ALERT_WINDOW`。
- 清单声明（`AndroidManifest.xml:143-149`）：`excludeFromRecents="true"` + `exported="true"`。
- 对话框主题（`values/themes.xml:6-14`）是体验关键：`windowIsFloating=true` + `windowBackground=transparent` +
  `backgroundDimAmount=0.32` + `windowMinWidthMajor=90%`。看着像悬浮窗，实际是普通任务栈 Activity。
- 窗口尺寸：`minOf(screenWidthDp * 0.92f, 600f)` dp、高 `WRAP_CONTENT`；`setFinishOnTouchOutside(true)` 点外即关。
- **图标皮肤 × alias 体系**：每个桌面图标皮肤配一个 activity-alias，各挂一套 `PROCESS_TEXT` intent-filter
  （label "Translate with Miffan"），enabled 互斥启停——菜单文案跟随皮肤。

### 2. UI 层：`ui/pages/translator/ProcessTextTranslatorWindow.kt`

- 源文本可编辑（`OutlinedTextField`），结果 `SelectionContainer + Text` 可再选中复制；复制按钮 + 取消/重翻。
- 模型选择器过滤 `listedFor(ModelType.CHAT, textOnly = true)`（"Chat models that also draw are no use for translating"）。
- **自动开翻的竞态双防护**（84-90 行，弹窗冷启动 + DataStore 异步加载场景的核心）：
  ```kotlin
  var translationStarted by rememberSaveable(selectedText) { mutableStateOf(false) }
  LaunchedEffect(selectedText, settings.init) {
      vm.initializeInputText(selectedText)
      if (!settings.init && !translationStarted) {   // 等 DataStore 加载完，防拿 dummy 设置发请求
          translationStarted = true                   // 防旋转/重建后重复触发
          vm.translate()
      }
  }
  ```
- 目标语言 9 项固定清单（`TranslatorPage.kt:211-223`：简中/英/繁中/日/韩/法/德/意/西），换语言即重翻。

### 3. 状态层：`TranslatorVM.kt`

标准 `viewModelScope` + `StateFlow`；`currentJob?.cancel()` 后再起；错误走 `SharedFlow` 显示在结果区（非 toast）。
模型 fallback 链：`translateModeId` → 找不到回落 `chatModelId`。**无翻译历史**（弹窗用完即弃）。

### 4. 服务层：`data/ai/TranslationHandler.kt`（双分支）

- 普通模型：可自定义提示词模板（`{source_text}`/`{target_lang}` 占位符，`data/ai/prompts/Translation.kt`）→
  `streamText` 流式 + `StreamChunkHandler` 合并；reasoning 由 `translateThinkingBudget` 控制；`flowOn(IO)`。
- Qwen-MT 专属分支：`ModelRegistry.QWEN_MT.match()` 命中时不走提示词，`generateText` + `customBody` 注入
  `translation_options`（`source_lang: auto` + `target_lang`），temperature 0.3 / topP 0.95。

## 方案本质与取舍

**本质是「用任务栈冒充悬浮窗」**：牺牲"选中即弹"的自动性（须经系统菜单点一下），换零权限、零 ROM 兼容负担、
系统级生命周期。对比无障碍+悬浮窗方案（触发自动，但要 2 项敏感权限、易被杀、ROM 差异大）——对 LLM 客户端
（翻译本就要等模型出流）是明显更优的 ROI。

| 维度 | Miffan（PROCESS_TEXT） | 无障碍+悬浮窗 |
|---|---|---|
| 权限 | 0 | 2 项敏感权限 |
| 触发 | 手动（系统菜单） | 自动（选中即弹） |
| 兼容性 | 系统保证 | 各 ROM 差异大 |
| 文本上限 | 受源 App 限制 | 可取全屏文本 |

## 与本仓对照（2026-10-31 核验）——已有 90% 设施，且底层更强

| 设施 | Miffan | 本仓现状 |
|---|---|---|
| `translateText` 流式 | ✅ TranslationHandler | ✅ **更强**：`GenerationHandler.kt:1715` 同款双分支 + `withGenerationRetry` 重试 + `usageRecorder.wrap(UsageSource.TRANSLATION)` 用量统计 + `MAX_TRANSLATE_CHARS` 200k 截断 |
| `translateModeId` 设置 | ✅ | ✅ `PreferencesStore.kt:1073` + SettingModelPage 配置 UI |
| `translatePrompt` / thinking budget | ✅ | ✅ |
| 完整翻译页 | ✅ TranslatorPage | ✅ **且更多**：`TranslationHistoryRepository` 历史留档 + `restoreHistory` |
| `ACTION_TRANSLATE` 入口 | — | ✅ `RouteActivity.kt:364` → Screen.Translator |
| **轻量弹窗 Activity** | ✅ | ❌ **唯一缺口** |

本仓 `PROCESS_TEXT` 现挂在 `RouteActivity`（`RouteActivity.kt:374` → ShareHandler 全量主界面）——划词会打开整个
App，比轻弹窗**重得多也慢得多**（起完整导航栈）。

## 值得借鉴（按价值）

1. **弹窗壳移植（半天级，主件）**：新建 `ProcessTextTranslatorActivity`（抄 45 行壳）+ `ProcessTextTranslatorWindow`
   Composable，复用本仓 `TranslatorVM`。manifest 把 `PROCESS_TEXT` filter 从 `RouteActivity` 挪到新 Activity。
   VM 小改：历史留档在弹窗场景照留（`historyRepository.add` 是 fire-and-forget，比 Miffan 强）。
2. **竞态双防护**（`rememberSaveable(selectedText)` + `settings.init` 门控）——弹窗冷启动场景直接照搬。
3. **Qwen-MT 专属分支**：本仓 `GenerationHandler` 已有同款，确认无缺口。

## 不照搬 / 已知坑

- **图标预置体系冲突**：Miffan 是 alias 体系（每皮肤一个 PROCESS_TEXT alias）；本仓是真 Activity 预置
  （`LauncherRikkaHubActivity`，ROM hook 校验 alias 会失败）。filter 挂新 Activity 一处即可，不受切图标影响，
  但菜单文案无法跟随皮肤——接受。
- **冷启动延迟**：弹窗 Activity 起在主进程，Application 全量初始化后才出窗；Miffan 用 `settings.init` 门控 +
  自动翻译掩盖延迟。需同样处理，必要时评估 baseline profile 收益。
- **弹窗主题**：本仓 `Theme.Rikkahub` + 液态玻璃体系，弹窗需独立透明主题，避免玻璃门控逻辑误入。

## 落地记录

- 2026-10-31 **已实施并真机验证通过**：
  - 新增 `app/src/main/java/app/androidrik/rikkahub/ui/activity/ProcessTextTranslatorActivity.kt`（对话框壳，
    含 `ensureKoinStarted()` 幂等补齐——本仓 Koin 延迟启动，弹窗冷启动直达必须补，SafeModeActivity 同款）
  - 新增 `ui/pages/translator/ProcessTextTranslatorWindow.kt`（复用 `TranslatorVM`/`ModelSelector`/`Locales`/
    `translationLanguageName`，模型与目标语言改动即时重翻，错误显示在结果区不弹 toast）
  - `TranslatorVM.translate(settingsOverride)` 加可选参数（弹窗改模型后 settingsStore 异步未落，直传快照）；
    `initializeInputText` 幂等守卫放 VM（进程死亡重建后 rememberSaveable 会误报已初始化）
  - `themes.xml` 加 `Theme.Rikkahub.ProcessText`（透明 + 浮窗 + 0.32 dim）；strings 加 `process_text_translate_label`
  - manifest：`PROCESS_TEXT` filter 从 RouteActivity 迁出（ACTION_SEND/ACTION_TRANSLATE 不动），挂到新 Activity
    一处（不随图标预置切换）
  - 验证：compileDebugKotlin / processDebugMainManifest / processDebugResources 全过
- 2026-10-31 **真机验证通过（6c841f95，debug 包）**，过程中修掉三个问题：
  1. **弹窗里打开共享 ModelSelector 即崩**（`IllegalStateException: No Navigator provided`）：
     ModelItem 长按跳供应商设置页强依赖 LocalNavController，弹窗不在导航栈里。改为弹窗自带
     轻量模型选择器（搜索 + radio，无导航依赖），见 `PopupModelButton`。
  2. **"Translation model not found"**：`GenerationHandler.translateText` 补 chatModelId 兜底
     （Miffan 同款）——但这只解决「没配翻译模型」；真正的主坑是下一条。
  3. **`Settings.init` 语义反转坑**：本仓 `Settings.dummy() = Settings(init = true)`（未加载占位是
     true，磁盘读出后变 false），与 Miffan 的 init 语义**相反**。照抄 Miffan 守卫
     `if (settings.init)` 导致自动翻译拿 dummy 空 providers 发车报「模型未找到」。正确守卫是
     `if (!settings.init)`（等真正从磁盘读出）。已修 + 代码注释留档。
  - 最终链路全通：其他 App 上层弹窗（零权限）→ 全文进输入框 → 自动开翻 → glm-4.5-air 流式
    出结果 → 复制按钮出现；模型选择器（上次崩溃点）正常打开可切换；无新增 crash。
- 2026-10-31 **系统选择菜单真机实测（ColorOS）——两套菜单体系，可用性不同**：
  - **ColorOS 全局选择菜单**（短信 App 实测）：长按 → ⋮ 溢出 → **可滚动列表**，含完整第三方
    PROCESS_TEXT（全选/便签速记/朗读/Neomix(旧装)/…/翻译）→ 点「翻译」拉起弹窗自动翻译，
    **全链路通过**。旧版同源多包（neomix / neomix.debug / rikkahub）也在列表里抢位。
  - **App 自带 ActionMode 工具栏**（微注记 wenote 实测，用户确认）：剪切/复制/粘贴/分享 + ⋮，
    其溢出只注入白名单项（全选/朗读/AI搜索——WPS 走 `lingxi` 定制集成），**第三方一律不进**，
    本仓入口同样缺失。这类 App 里只能走 分享 → ACTION_SEND（ShareHandler）兜底。
  - **别用自家 App 判断入口缺失**：Compose 输入框的选择工具栏不聚合 PROCESS_TEXT（Compose 已知
    限制，只有剪切/复制/粘贴），要用系统 EditText 的选择菜单测。
  - `android:priority="100"` 加在 filter 上（Miffan 同款），不影响解析但可能影响排序，保留。
  - 注意包名随分支变（applicationId per-branch），`pm list packages | grep rikkahub` 会漏掉
    `app.androidrik.neomix(.debug)` 这类包名。
