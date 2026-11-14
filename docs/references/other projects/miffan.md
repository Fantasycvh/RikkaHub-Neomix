# Miffan（Ayuilos/Miffan）评估 —— 跨应用划词翻译（PROCESS_TEXT 弹窗）

> 对应总表 §一 #14。深度：**源码**（本地副本 `F:\build\projects\_miffan-analysis\Ayuilos-Miffan-8988130\`，2026-10-31 逐文件读核验）。
> 许可：AGPL-3.0（2026-10-31 核验 LICENSE）。同源 RikkaHub fork（包名 `me.ayuilos.miffan`，311★，活跃更新）。
> 定位：Android AI 客户端 + IM（类 Grok-Bot）模式 + ChatGPT 订阅登录 + **跨应用划词悬浮翻译** + 远程云电脑控制。本次只评估划词翻译一项。
> [返回索引](../../peer-apps-benchmark.md

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

## 2026-10-31 → 11-03 增量评估（2026-11-03，114 提交）

基线快照 `8988130`（10-31）→ 远端 11-03；`gh api` 返回 115 条含基线，实质新增 **114**（Ayuilos 112 + re-ovo 2）。总评：**无可吸收项**。

- relay / 云电脑 / IM 4.0 及其合并 · 屏文案翻译 **94 条** → 不适用（用户已明示不吸收该域）。
- 版本 bump / feature 分支 merge 13 条 → 不适用。
- 应用更新体验重做 1 条（`0c4ec3864` 删 `UpdateCard` 345 行、改 Worker + 通知栏安装 + R2 发布）→ 不适用（本仓无应用自更新通道）。
- 上游提交 2 条（`9b7cad09f` = 上游 `1575d179d`，已吸收 `beb6fb1fb`；`bc38edac3` = 上游 `2c4d1979c`，已等价不跟）→ 不重复。
- 已有等价 2 条：`e39a8c2e4` 附件面板矮屏可滚动（`FilesPicker.kt:153-157` 已有 `verticalScroll`）、`e98c4586a` 导出写文件离主线程（`ExportHooks.kt:66-77` 已有 `Dispatchers.IO` + `runCatching`）。

> 证据级别：本批只拉标题 + 命中文件级 patch（无本地克隆），结论限机制/文件级；relay 域按标题分桶（全部含 relay/connector/quota/snapshot）。

## 2026-11-03 → 11-09 增量评估（远端，141 提交，HEAD `11b8fb55b`）

**边界**：上一批台账提交 `13846d07f`（2026-11-03 22:15 +0800 ≈ 11-03T14:15Z）。`gh api repos/Ayuilos/Miffan/commits?since=2026-11-03T00:00:00Z` 共 **153** 条，扣上一批已覆盖的 12 条（11-03T12:18Z 前 10 条 + 2 条 rebase 提交 `77d47a5207`/`7684bee762`，二者已计入上一批「re-ovo 2」）→ 实质新增 **141 条**（Ayuilos 121 + Claude 18 + re-ovo 2；Claude 18 条全部是 11-04 的云电脑窗口批量工作）。

**总评：吸收 0 / 候选 8（P2×3、P3×5，涉 20 提交）/ 已有等价 9 / 不适用 99 / 记账 13。** 本批是 Miffan「IM 4.0 收尾 + 4.9.0/4.9.1 发版」周，主线（云电脑/屏幕/窗口/relay/easy-mode）与本仓无交集；真正可看的是 11-07 的 **perf/bench 批次（W0.1–W1.4）** 与 **4.9.0 code review**，以及 markdown/文案小件。**没有需要整体吸收的机制**：3 个 P2 中 2 个是「本仓已有同款热路径、只差把开销挪出主线程/预览层」，1 个是补通用 Compose 宏基准管线（本仓现有的是长文本专用自研基线）。

> 下文「本仓」取证均为本地源码 grep（`app/src/main/java/app/androidrik/rikkahub`、`ai/`、`bettertext-*`、`scripts/`、`app/baselineprofile`），行号以当前工作树为准。

### 候选（P0–P3）

1. **P2 · 预览/TTS 保留行内代码文本**（`6efec385a` Keep inline code text in previews instead of dropping it）
   - Miffan：`utils/MarkdownUtils.kt` 把 `Regex("```[\\s\\S]*?```|`[^`]*?`")` 拆成「代码块删、行内代码保留 `$1`」；预览里 `` `note.txt` `` 不再消失（理由：预览给「人」看，行内代码是内容不是标记）。
   - 本仓同款缺陷（同源）：`utils/MarkdownUtils.kt:48` 仍是合并正则整段删除 → 凡走 `stripMarkdown()` 的路径都丢行内代码。调用点两处且都在**朗读**：`ui/hooks/TTS.kt:146`、`ui/pages/chat/VoiceMode.kt:95` ⇒ TTS 会跳过所有 `` `…` `` 片段（文件名、命令、网址）。
   - 落点：`MarkdownUtils.kt:48` 拆两条正则 + 补 `MarkdownUtilsTest`（Miffan 同款用例）；顺手核 `extractThinkingTitle` 不受影响。**量级 0.5h**。

2. **P2 · QuickJS 初始化移出启动主线程 + 使用点幂等 init**（`7b602f808` + `9f58005b9`）
   - Miffan：`onCreate` 的 `QuickJSLoader.init()` 挪到 `AppScope`+IO（注释「首屏不需要」），并在 `JavascriptTool.execute` 补幂等 `QuickJSLoader.init()`（后台加载可能还没完成）；`runCatching` 分别包住 icon sync / QuickJS / relay watcher，避免一个失败吞掉其余。
   - 本仓同构点：`RikkaHubApp.kt:148` 在 `onCreate` 主线程同步 `QuickJSLoader.init()`，首帧不依赖它；`data/ai/tools/local/JavascriptTool.kt:71` 直接 `QuickJSContext.create()`，无使用点幂等 init。本仓已有启动 profile/基准（`app/baselineprofile`），这笔是**可测的冷启动收益**。
   - 落点：`RikkaHubApp.kt:147-148` 移到 `doBoot()` Ready 之后的 IO 协程（注意 Koin boot 时序）；`JavascriptTool.kt:71` 前补幂等 init。**量级 0.5d**（0.5h 改动 + Trace 验证）。

3. **P2 · 通用 Compose macrobenchmark 管线**（`7442ea738` 合并 + `2e7a25e87`/`31da3fddd`/`38d4b16a7`/`ad5e5adab`/`6768f1f83`/`388030a6a`/`00dda013b`/`19d070952`/`2f730d43f`）
   - Miffan：在既有 startup baseline 外补「fixture + 旅程 + runner + 汇总」——`app/baselineprofile` 新增 `ImBenchmarks.kt`（冷启动到首屏全绘、打开长会话、滚动、切 tab、流式回包）、`PerfSupport.kt`、`scripts/perf/run_bench.sh`、`scripts/perf/summarize.py`（多轮中位数 + 设备信息 + 帧指标表）、`docs/development/PERF_FIXTURE.md`；`ad5e5adab` 给 bench 变体独立 `.bench` applicationId 并校验 fixture；`6768f1f83` 让 benchmark 按需记录 composable 名；`00dda013b` 加 testTags + 打开会话异步 trace（`ThreadOpenTrace`）+ ReportDrawn 全绘上报；`19d070952` 先装 fixture 再跑启动基准；`2f730d43f` 用 variant API 挂 fixture。
   - 本仓现状（已核）：`app/baselineprofile` 只有 `BaselineProfileGenerator.kt`（启动 + 一次 markdown 发送流程）+ `StartupBenchmarks.kt`（仅 `StartupTimingMetric`，measureBlock 还留着「未等全绘」的 TODO）；**没有**列表滚动/会话打开/tab 切换旅程，没有 benchmark runner/汇总脚本，全仓无 `ReportDrawn`（零命中）。长文本侧另有可复用自研管线：`scripts/longtext_baseline.py` + `app/src/androidTest/java/app/androidrik/rikkahub/perf/LongTextBaselineTest.kt`（FrameMetrics 逐帧、P95/P99、deadline miss，见 `docs/design/completed/longtext-p0-baseline.md`）。
   - 落点：`app/baselineprofile/src/main/java/app/androidrik/baselineprofile/` 增 `ChatBenchmarks.kt`（冷启动→首屏全绘、长会话打开、滚动、流式回包）+ `PerfSupport.kt`（fixture 安装 + 全绘等待）+ `scripts/perf/{run_bench.sh,summarize.py}`；`StartupBenchmarks.kt` 的 measureBlock 补全绘等待；`BaselineProfileGenerator` 增加长会话/流式旅程后重生成 `app/src/release/generated/baselineProfiles/baseline-prof.txt`（现为旧旅程产物）。**量级 2–3d**。

4. **P3 · 引用卡片/预览去掉 Markdown 标记**（`9531ec767`）
   - Miffan：`previewText()` 之前只压空白、不剥 Markdown → 引用条/话题标签显示 `**…**`、`#`；改为 `stripMarkdown()` 后再压空白，并把热点 `Regex("\\s+")` 提成常量。
   - 本仓同构点：`ui/components/message/MessageQuoteCard.kt:88` 的 `Text(text = quote.content)` 直接显示原文，而 `ai/src/main/java/app/androidrik/ai/ui/MessageQuote.kt:24` 存的就是**原始 Markdown 快照**（`parts.filterIsInstance<Text>()` 拼接）⇒ 引用卡片/输入框引用预览会显示 `**粗体**`、`### 标题`。
   - 落点：`MessageQuoteCard.kt:88` 显示层剥标记；**不要**改快照本身（发给模型仍要原文，见 `toPromptBlock`）。**量级 0.5h**。

5. **P3 · 失败提示说人话（原始异常进 Log）**（`9a0b792a1` + `4e42c66fa` + `46b9cfb5d` + `9f58005b9` 撤销页部分）
   - Miffan：把「保存/备份/恢复/导入失败 + `e.message`」的 toast 改成**固定人话 + 下一步**（「备份没存下来，看看手机空间够不够」/「这个文件恢复不了：可能不是对应的备份文件，或者已经损坏」），原始异常走 `Log.w`；顺带修「导出失败被写成恢复失败」的文案错配。
   - 本仓同构点（已核，**仍直出异常原文**）：`ui/pages/imggen/ImgGenPage.kt:735-746`（`imggen_page_save_failed` 带 `e.message`）、`ui/pages/mediacreation/MediaCreationPage.kt:485`、`MediaCreationRecordItem.kt:531`；`ui/pages/backup/BackupVM.kt:92/110/160/190/209/234/252`（`failure.message ?: "…失败"` 直接把异常字符串塞进页面状态）。
   - 落点：图片保存三处 + 备份 VM 七处统一「场景化人话 + `Log.e` 原文」；`9f58005b9` 的「撤销页离开时先终结再弹 toast」本仓 HistoryPage 自研，**待核**后再定。**量级 1d**（含文案；不做全语言可压到 2h）。

6. **P3 · 本地化月日 + 三个 Compose 小件**（`c48dcbdff` Cut repeated work in chat rows and the screen page）
   - Miffan 实质修复：①中文/日文/韩文月日被手拼成「10月 31」→ 用系统本地化 `MMMd` 模式 + 缓存 formatter；②`ZoomableAsyncImage` 的 `ImageRequest` 每次重组新建 → `remember`；③reasoning 计时 tick 50ms→100ms；④Markdown 解析结果按「已稳定文本」进 LruCache（重组/滚回不重解析）；⑤清无用 import。
   - 本仓同构点：①**同款 bug**——`utils/TimeUtil.kt:59-79` 手拼 `appendText(MONTH_OF_YEAR)`+`appendValue(DAY_OF_MONTH)`，`isMonthFirstLocale`（`:84-93`）还把 `CN` 列进「月在前」→ 中文日期头实际是「10月 31」；调用点 `ui/pages/chat/ChatDrawerVM.kt:329`（抽屉日期分组）、`ConversationList.kt:236`。②`ui/components/richtext/ZoomableAsyncImage.kt:42` 的 `ImageRequest.Builder(context)` 未 remember。③`ChatMessageReasoning.kt:151-152` 仍是 `delay(50)`。④解析缓存：本仓长消息已由 `ChatRenderIndex`（预解析 `MarkdownRenderDocument` + `staticDocuments` 状态）覆盖，短消息 `<8000` 字符走 `BetterMarkdown` 同步解析——**是否加全局 LruCache 待实测定夺**，不盲抄。
   - 落点：`TimeUtil.kt`（本地化 MMMd）→ 两个日期头调用点；`ZoomableAsyncImage.kt:42` remember；`ChatMessageReasoning.kt:152` 50→100ms（先核显示精度）。**量级 0.5d**。

7. **P3 · 会话列表行 contentType**（`37978de69` Give thread and chat list rows a content type so scrolling reuses like rows）
   - Miffan：`LazyColumn` 行加 `contentType`，让 header/消息/错误卡/流式行各自复用槽位。
   - 本仓同构点：`ChatDrawer` 的会话列表是**混合 sealed item**（日期头/置顶头/会话行，`ConversationList.kt:194`），但 **`contentType` 只在 header 一处**（`ConversationList.kt:145`），其余行没有；聊天页 `ChatList` 同理（渲染块异质）。
   - 落点：`ConversationList.kt` 的 `items(...)` 按 `ConversationListItem` 类型给 contentType；`ChatList` 按 slice 类型（markdown/tool/notice）补。风险低。**量级 0.5h**。

8. **P3 · DB 复合索引 + 收藏 ref_key 范围查询**（`2eaecd7ff` Add data-layer indexes and range-query favorites lookup，DB 43→44）
   - Miffan：`message_node` 单列 `conversation_id` → `(conversation_id, node_index)`（`WHERE conversation_id=? ORDER BY node_index` 免排序）；`conversation` 增 `(assistant_id, chat_id, update_at)`/`(folder_id)`；收藏「按会话取 node 收藏」的 `LIKE 'node:<id>:%'` 改 `ref_key >= prefix AND ref_key < prefixEnd` 范围扫描（唯一索引才用得上）。
   - 本仓同构点（已核）：`data/db/entity/MessageNodeEntity.kt:19` 仅 `Index("conversation_id")`，而 `MessageNodeDAO.kt:16/21/52` 都 `ORDER BY node_index`；`ConversationEntity.kt` **零索引**，`ConversationDAO.kt:58/64/71/74/77` 全是 `WHERE assistant_id=? AND hidden=0 ORDER BY update_at/last_message_at`；`FavoriteDAO.kt:24` 仍是 `LIKE 'node:'||:conversationId||':%'`（`FavoriteEntity` 虽已有 `ref_key` 唯一索引，LIKE 用不上）。
   - 落点：Room `@Entity(indices=…)`（本仓 DB v64，按 `app/schemas` 流程加 Migration + `Migration_64_65_Test`）+ `FavoriteDAO` 范围查询。**先量测再决定**：收益主要在长会话/大收藏库。**量级 0.5d**。

### 已有等价（本地 file:line 取证）

- `2f28d43c2` 流式回复保持可见 + 不每 chunk 重组所有气泡 → 本仓**更强**：`ChatList.kt:393` `stickToBottom` 闩锁 + `:397`/`:501-508` `snapshotFlow { layoutInfo }` 贴底（只在「本就在底」时跟随，手指拖动退出）+ `:463-464`/`:506-507` `requestScrollToItem` 瞬时重定位（不会像 `scrollToItem` 那样每 chunk 先把尾部闪回顶部）。Miffan 修的两个坑（`LaunchedEffect(timeline)` 每 chunk 重启、`ThreadConnection` 每 chunk 新实例）在本仓不成立：重组靠 Kotlin 2.4.10 **strong skipping**（`gradle/libs.versions.toml:5`）+ `ChatRenderIndex` 预解析/切片（`ui/pages/chat/ChatRenderIndex.kt`）。其 `remember(connectedSegments, online)` 的「共同值只建一次」是好习惯，本仓 `ChatList.kt:339-341` 对 `assistant`/model map 已 `remember`。
- `abd836c26` Chats 列表行操作稳定防整行重组 → 本仓会话行参数是普通 lambda（`ConversationList.kt:194`/`:289`），strong skipping 下按捕获值记忆化；没有 Miffan 那种「每次重组新建 `ImChatActions` data class 传给所有行」的结构 ⇒ 无此 bug。
- `89da3892d` 记住气泡「有没有可显示内容」→ `ChatMessage.kt:544` `remember(messageId, parts, role, wholeTurnFolding) { messageDisplayDocument(...) }`，气泡只渲染自己的 slice。
- `1e927e300` FTS 只索引变化节点 → `data/db/fts/MessageFtsManager.kt:76/83` 明写「增量更新会话的索引：只重写新增或内容变了的消息」，另有 `message_fts_rowid` 普通表做会话→rowid 映射，比该提交更完整。
- `d826918d0`（re-ovo）收藏模型重复导致闪退 → `data/datastore/PreferencesStore.kt:107-110` `normalizeFavoriteModelIds` 已 `distinct()` + 过滤已删模型；同题上游 `dba9fa574` 在 upstream 11-07 批已判等价。
- `604a5eb07` 删会话连带删节点收藏 → `data/repository/ConversationRepository.kt:545-546` `favoriteDAO.deleteNodeFavoritesOfConversation(conversationId)`（同题上游 `3733bac8f` 已落地；另有 `:564` 单节点删除）。
- `5bc366014`（re-ovo）历史图片压缩缓存 → `ai/src/main/java/app/androidrik/ai/util/FileEncoder.kt:27/116-123` `MediaEncodingCache`（Yuihub 3.0 移植，内存 LRU 8MB）；同题上游 `ae599e8d7` 在 11-07 批已**改判等价**（Miffan 走的磁盘持久层属增强，挂账）。
- `e88bbea5b` 世界书长按拖拽排序 → `ui/pages/extensions/LorebookPage.kt:185/252` `rememberReorderableLazyListState` + `ReorderableItem`（同题上游 `88dee920c` 已判等价：本仓整页重构自带拖拽）。
- `f31f96458` 翻译页可选模型/图标动作 → `ui/pages/translator/TranslatorPage.kt:110-116` `ModelSelector(modelId = settings.translateModeId, type = CHAT)` 已具备；「翻译页 prompt 闪一下」属 IM 翻译页实现细节，本仓 `TranslatorVM` 无同构。

### 不适用（99 条）

- **IM 4.0 / 云电脑 / 屏幕 / 窗口 / 远程助手 / relay / 局域网 / easy-mode / IM 页：73 条** → 用户已明示不吸收该域。主体是 11-04 的窗口/屏幕批量（Claude 18 条 `1aa04f601`…`c9dafd9ac` + Ayuilos 16 条）、IM 4.0 feature 分支合并（`11b8fb55b` 等 10 条）、11-03 晚段窗口/迷你盒 4 条（`480b0c18f`/`10d30821f`/`5a5db9931`/`754a15899`），其余 25 条是 11-07 的 easy-mode（`f55fb6df4`/`be632f011`/`ed8de1224`/`79e65d446`/`d28e43def`/`9085725e4`/`0a63f633d`）、IM 会话/伙伴/话题/摘要（`746e3e8e4`/`a3160ff00`/`b8d193d97`/`da94d7d83` 等）、relay/局域网发现（`07d27aab6`/`69b464eb1`/`fbd53e4ca`）、IM 画作/翻译/语言页（`e949381e7`/`c502ba1d6`/`8da5072f3`，其中 `9a0b792a1` 的**原则**已转候选 #5）与 UX tour 等杂件。
- **发版/版本 bump：4 条**（`853d0646f` 4.8.4 / `e85238307` 4.8.5 / `088e3e4cf` 4.9.0 / `13691c4a7` 4.9.1）。
- **营销截图 / README / 发行说明 / 计划文档：22 条**（v5 截图三批、4.9.0 notes、`IM_4_POLISH_PLAN` 系列、UX tour 脚本、`9b60032fd` 任务分派规则等）。
- **`8da5072f3`（任务失败文案）判「不适用（域错配）」**：改动文件是 `ui/im/computer/RemoteTaskDetailPage.kt`（IM 云电脑远程任务），不是手机侧定时任务；本仓定时任务 UI 只显示状态词条（`ui/pages/taskcenter/TaskCenterPage.kt:343` `taskStateLabel`），本就不直出原始异常 ⇒ 无可抄点，详见「需修正旧结论」。
- `47c5250cc`（更新通知先于终端状态发布）、`b368afce9`（远程助手终端 Ctrl+Shift+V）、`c502ba1d6`（Me 页语言名）：本仓无对应设施（自更新下载器/远程助手/应用语言行），复核后不适用。

### 记账（13 条）

- **上游同题/上游批次已覆盖 5 条**：`a79067dfa`+`d73acb595`（selective sync 到 `fe5a28f9a`）、`8cc61b552`（记录 11-05 sync）——本仓 `docs/upstream-analysis.md` 已于 11-07 评估 `903e058b9..fe5a28f9a` 25 提交，**已由 upstream 批次覆盖**；`26309bbedd`（设置/Me 顶部请求局域网权限）= 上游 11-03 批已吸收的「设置页局域网权限入口」；`fdbb3117f`（models.dev 每 4 小时刷新）= 上游 `133f47520`，本仓该批已判 **P3 候选（每日→4h）**，不重复立项。
- `22d3490e8`（perf/data 合并 W1.1–W1.4）：合并提交，子提交（`1e927e300`/`b8d193d97`/`da94d7d83`/`2eaecd7ff` 等）已逐个判定。
- **CI/环境 3 条**：`9e5998c55`（CI pin ubuntu-24.04）、`49e8a669c`/`b13bd768e`（CI/发布构建堆内存）——本仓三条 workflow 用 `ubuntu-latest`，无同构需求。
- **文档/测量 4 条**：`c558e5840`（W0.4 手机测量）、`0fd2bd2be`（模拟器启动 trace）、`8ea6ef7a2`（benchmark 方法/W0 验收）、`0eeb2c00d`（模拟器帧指标/代理/worktree 文档）——只作候选 #3 的方法参考。

### 需修正/补充的旧结论

1. **任务前提修正**：评估任务把 `8da5072f3` 归到「本仓 ScheduledTask 线」，实际它是 IM 云电脑远程任务页；本仓定时任务线（`ui/pages/setting/scheduled/*`、`TaskCenterPage`）不显示原始异常 ⇒ 该条判**不适用**，不是候选。
2. **上一批「无可吸收项」适用窗口**：本批 141 条全部晚于上一批台账提交（`13846d07f` 11-03 22:15），旧结论不构成错误；上一批把 `0c4ec3864`（应用更新体验）判「不适用（本仓无应用自更新通道）」的判据本批复核**仍成立**（全仓无更新下载器，`47c5250cc` 因此也不适用），维持。
3. **补充历史缺陷记录**：本仓 `utils/MarkdownUtils.kt:48` 与 Miffan 同源，行内代码会被 `stripMarkdown()` 整段删除（影响 TTS/VoiceMode），此前台账未记；本批补为候选 #1。
4. **`2f28d43c2` 不立项**：本仓「流式保持可见」已由 `ChatList.kt` 自研贴底机制覆盖（且更强）；不要把 Miffan 的 `ThreadConnection`/`ImChatActions` 结构当通用结论——本仓 `ChatRenderIndex` 每 chunk 重建 render index 是有意取舍（长文本基线在档），不是漏优化。
5. **`c48dcbdff` 的 markdown LruCache 部分不建议直接抄**：本仓长消息预解析在 `ChatRenderIndex`，短消息解析开销待 `scripts/longtext_baseline.py` 量测后再定；先做其中 3 个已确证的分钟级小件（月日本地化 / ImageRequest remember / reasoning tick）。

> 证据级别：**远端（标题 + 文件级 patch）**——无本地克隆；对本批 42 个提交调 `repos/Ayuilos/Miffan/commits/<sha>` 取 `files[].patch`（覆盖全部候选/等价证据），其余按标题分桶。本仓结论为本地源码 grep/读文件取证。

## 2026-11-09 → 11-14 增量评估（2026-11-14 晚，远端，82 提交）

> 同步方式：`gh api`（直连）。锚点 HEAD `11b8fb55b`（PR #70，11-09）。证据级别：**远端：标题全量 + 12 个代表提交的 message/files/patch 抽样**；本仓侧结论全部本地 grep/file:line 取证。

**分桶**：A 性能第二轮（11-10，21 条，流式渲染全线优化）· B IM 伙伴设置第二轮（11-11，~40 条，IM 4.0 域不吸收）· C 版本/改名/翻译（11-12）· D 工具卡片（11-13，7 条）· 记账/杂项。**总评：吸收 0 / 候选 4（全 P3）/ 已有等价 7 / 记账 ~23 / 不适用 ~48。本窗口与本仓「撞题率」历次最高，但结论是正面信号**：Miffan 11-10 性能簇逐条对应本仓 11-09 刚自研完成且带量测基线的 longtext p2 工程（`MarkdownStreamParser` + `ChatRenderIndex` + `_measure/longtext/*`），且本仓实现普遍更深（取消安全落点/半截保留/HEADERS 级日志）。

**候选（4，全 P3）**：

| sha | 实质 | 判定 | 理由/前置 |
|---|---|---|---|
| `51c6b0f2` | **聊天框凭证拦截**（`extractCredential`：sk-/AIzaSy/Bearer 等正则族 + 中英混排用例；发送前提示「发出去就能看到」） | 候选 P3（**唯一无争议，0.5h 级**） | 本仓全仓无输入框凭证检测（`sk-` 零命中）；key 发进聊天会进模型上下文并落库。落点 = composer 发送前 `containsMatchIn` 提示；其「搬进安全卡片」后半属 IM 域不做 |
| `8c354547` | **数据层按帧合流**（流式 chunk 每帧最多一次 UI 更新 + 132 行 `GenerationStreamBatchingTest`） | 候选 P3（量测前置） | 本仓生成流无 per-frame 合流（`withFrameNanos`/`conflate` 生成路径零命中）；收益与 `MarkdownStreamParser` 协同未知——用 `scripts/longtext_baseline.py` 先实测再立项 |
| `baad2774` | 每 Markdown 顶层块 `graphicsLayer` 独立成层（流式早块不重绘） | 候选 P3（低置信） | 本仓无 per-block layer；`ChatRenderIndex` 切片 + Compose draw invalidation 可能已覆盖大部分收益，挂账观察 |
| `26bc942d` | **审批卡上编辑工具参数**（`EditableApprovalSchema` 321 行：按 schema 生成可编辑表单 + 校验 + 编辑标记随 decision 落库） | 候选 P3（中型） | 本仓审批 UI 只有批准/全部批准/拒绝带理由（`ChatMessageTools.kt:147-349`），无参数编辑；需按本仓 `SubagentApproval`/`ApprovalBaselines` 自研，原则值得挂账 |

**已有等价（7）**：`ea461c0e` 增量 Markdown 解析（本仓 `MarkdownStreamParser` appendOnly 投影更强，且 11-09 基线在档）· `207b066d` 砍整页重组（本仓 strong skipping + `ChatRenderIndex`）· `66d1294e` flowOn 缓冲丢尾（本仓 `GenerationHandler.kt:464-502` 取消安全落点 + 半截保留覆盖更全）· `7cefdb28`/`23e95a5d` debug-only HTTP 日志（本仓 `DataSourceModule.kt:311-327` release 不挂拦截器 + HEADERS 级不打 body，更保守）· `e90c9989`/`d6c52553` 朗读格式/voice（`TTSProviderConfigure.kt:1122`）· `c9007d57` stale 提示（本仓无该提示面）。

**记账**：`9c74a89e`+`72d70c4e` 代码块行分片高亮（写在 `me.rerere.highlight` 上游库内，本仓跟库版本即可）· `d7b3b6f1` 四项 chunk 省活细则（挂其 ThreadTimeline，本仓对应热路径频率低）· 归因进工具输出/卡片编辑 3 条（同 26bc942d 域）· 备份闲置提醒 4 条（原则弱候选）· 自更新/云电脑测试 2 条（「本仓无自更新通道」判据复核仍成立）· merge/bump/测量评审文档 ~12 条。

**销账**：上轮候选 #4（引用卡片剥 Markdown `9531ec767`）确认已落地——本仓 `MessageQuoteCard.kt:92` 已调 `stripMarkdown()`；其缓存化（quote 缓存）记账待量测。

> 判决汇总见 [docs/peer-verdicts-2026-11-08.md](../../peer-verdicts-2026-11-08.md) §八。
