# SoLab（v2.1.0 / 2.3）借鉴评估

> 评估：**2026-08-28**（原 §一 / §三 中的 SoLab 部分）+ **2026-08-31**（原附录，2.3 增量）。许可证 AGPL-3.0。
> 定位：基于 [Kelivo](https://github.com/Chevey339/kelivo) 二次开发的「AI 驱动 APK 逆向工作台」（Flutter + Kotlin）。**逆向域与本仓无关**，可取的是纯客户端工程件。
> 本地参考副本：`<work>\projects\solab-open-source-2.0\`、`<work>\projects\solab-open-source-2.3\`。
>
> 拆分说明：本文件对应原 §一 的 SoLab 部分、§三 的第二个来源、以及原「附录：SoLab 2.3 增量分析」；同节中的 rikkahub-agent 部分见 [`rikkahub-agent.md`](rikkahub-agent.md)。
> 返回 [索引](../../peer-apps-benchmark.md)。

## 一、它是什么

### SoLab：AI 驱动的 APK 逆向工作台

Flutter（Kelivo 底座：世界书/记忆/指令注入/多 provider）+ Kotlin 逆向引擎（rizin 全套 so + Ghidra Sleigh、Unicorn/Unidbg、Keystone、DexKit、LIEF、xAnSo、Blutter ×19 个 Flutter 版本 runner）。核心流程：选中 APK → 内置助手完成 分析 → 定位 → 修改 → 打包 → 签名；并支持作 MCP server 让桌面 AI 远程调用手机上的逆向能力。附带 20 类去广告规则 JSON（广告 SDK / 会员校验 / 反调试检测特征）与 `apk_mod_entry_*` Playbook 知识书。

## 二、值得借鉴的机制（源码级验证）

### 来自 SoLab（理念为主，Kotlin 侧可直接参考）

1. **工具 schema 三档加载**：`ToolLoadPolicy{none/light/full}` + 纯规则域路由（`tool_router.dart`，零额外 LLM 调用）——闲聊不携带工具 schema；`get_tool_map` 元工具两级懒查（目录 → 单工具详查 → LRU 缓存，按 assistant 启用集做 cacheKey）。本地工具超过 20 个后这套决定可用性。
2. **工具结果外置 + 反翻页**：超长结果截 16KB 预览 + resultId，`get_tool_result` 续读**仅一次**（offset 必须 == previewChars），再翻页报 `paging_blocked` 并指示"回原工具用更窄 query"——把长结果 token 成本压到常数级。
3. **previewToken 变更审批**（~200 行，`apk_mutation_preview_service.dart`）：dryRun 发证（operation + 参数指纹 = 去掉控制参数后的 canonical JSON，30 分钟过期）→ 执行验签 → 指纹不匹配返回 expectedArguments 让模型恢复原参数（防"批准 A 执行 B"）→ 写成功后失效同产物旧 token。对任何破坏性工具通用。
4. **ToolCallLoopGuard**（~150 行可逐行翻译）：滑动窗口（12）存 `工具名|参数指纹`；polling 豁免；**写成功后 `advanceState()` 清空读历史**（状态推进语义）；与预算状态机（四阶段 token 上限）联动，预算耗尽返回**结构化 ask_user 载荷**申请扩容而非死错误。
5. **KeepAliveService 保活四件套**（479 行 Kotlin，注释即 ROM 制裁对策手册）——对我们保活功能的直接补强：
   - **API 35+ 必须用 specialUse 而非 dataSync**：Android 15 对 dataSync 有 6h 强限时，到点进程冻结。**印证我们 KeepAliveService 选 specialUse 是对的；上游 2.4.14 的 dataSync 方案在 Android 15 有上限**。
   - **WifiLock(low-latency)**：Wi-Fi radio 省电后"进程活着但入站 SYN 收不到、工具全部超时"——我们保活只做了 wake lock，这是增量。
   - `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 用法可照抄；"引擎死了但通知还在"检测思路与我们 onTaskRemoved 重断言一致。
6. **MCP server（host 模式）**：双协议兼容（Streamable HTTP + HTTP+SSE）、token 可选鉴权、工具定义与聊天内**单一来源**（含 loop guard/审批/截断全复用）、写工具共享 1 条串行门 + 读工具 2 条门 + 挂死熔断 2 分钟冷却（事故教训注释：native 挂死 = MCP 永久不可用）、重型工具 taskId 异步 + `mcp_task_status` 轮询。RikkaHub 已有 Ktor web 模块，是现成地基。
7. **世界书 seed 三重迁移**：~~版本号 key + 内容 sha256 表~~ **✅ 已落地（`2d30c528`，09-30）**——实现比原设想更简：固定 entryId 定位（省掉 sha256 表）+ (内容,位置) 历史指纹识别未改动旧副本 + 保留用户开关状态 + 已删不复活（`SeedMigration.kt` 接 PreferencesStore 读路径；现注册 Learning Mode v2，世界书留泛化点）。
8. **Blutter 隔离 runner 模式**：`isolatedProcess="true"` Service（98 行）+ AIDL + ParcelFileDescriptor 零拷贝传 fd，每作业一进程用完即弃（native segfault 不伤主进程）；四级降级版本匹配（exact_engine → exact_snapshot → exact_dart → minor fallback）并把匹配证据返回给 AI；大索引 jsonl.gz 流式解析（O(1) 内存），教训注释：全量载 List 直接 OOM。
9. **记忆管线**（Kelivo 系，~6000 行，模块边界干净）：Gatekeeper → Extract → Smart Add → Distiller 四段式，水位线 + 窗口推进；**gatekeeper malformed 不推进水位线**（宁重跑不漏记）；Smart Add 的 MERGE/CONFLICT 目标必须在本地检索候选集内（堵 LLM 幻觉 id）；旧会话只注入 summary 不注入原文。
10. 小件：政策文本常量单一来源（Agent 系统提示与 MCP 工具描述共用同一份，防漂移）；规则字典 JSON + 用户规则页 + 远程订阅源；PROCESS_TEXT 全局划词进 AI。

## 三、SoLab 2.3 增量分析（2026-08-31，vs 2.0；原「附录」）

源码包 `<local>\Solab2.3.zip`（version 2.3.0+72 vs 2.0 的 2.1.0+70）。规模：1243 文件有改动 + 72 项新增，无删除；Kotlin 侧 305、core/services 125、测试 301（goldens/perf 测试目录新增，测试文化建设明显）。逆向域新增（DPatch 模式、CryptoSig 密码常量扫描、FLAG_SECURE 补丁、BlutterDiff 等）与我们无关，以下是聊天侧可借鉴增量：

1. **`HttpSendRetry`（45 行，`lib/core/services/api/http_send_retry.dart`）**：发送层重试——瞬态故障（Socket/Timeout/ClientException）+ {408,429,500,502,503,504}，指数退避 500ms×2、maxAttempts=3；buildRequest 每次重构（body 不可复用）；**明确"只在响应头到达、流未消费前判定，不对已建立 SSE 流做中途续传"**；业务 4xx 不重试（"重放同样的请求不会成功"）。与我们重试实现同思路但更极简，且"不碰中途流"的取舍与我们 #1812 设计稿（design-partial-failure-draft.md）的分流判定互补。
2. **`ToolCallLoopGuard.forget()`（tool_call_loop_guard.dart 新增 6 行）**：失败调用（超时/异常/网络中断）从指纹窗口移除——"失败的调用没有可信结果，网络恢复后相同参数重试是合理恢复路径，不应被 LOOP_DETECTED 拦截"。**将来移植 LoopGuard 时必须带上的补丁**。
3. **`mcp_structured_image.dart`（363 行）**：MCP 工具结果图片处理——结构化 metadata `mcpResult{version, imageUris}` + markdown 平铺（保持原始内容顺序）+ 旧格式（私有码点定界符/JSON envelope）只读迁移 + **文本转义防伪造附件**。直接对应上游 issue #1703/#1761（MCP 图片结果在 Gemini 报错、下一轮 `Invalid type for 'input'`），是现成的迁移范式。
4. **`safe_resize_image.dart`（353 行）**：图片解码尺寸钳制——maxEdge + maxPixels 双上限、contain/cover 两种 fit、递减式逼近。对应上游 issue #1710（发送前图片压缩）与我们 turbo R 系列 OOM 防护的补强。
5. **行为测试契约（值得照抄的测试用例，非代码）**：
   - `chat_input_bar_conversation_switch_send_test.dart`：「发送未决时切换会话，发送锁必须解锁」——上游 #1820（切对话回复消失）同族问题的正确语义；
   - `message_generation_service_regeneration_test.dart`：「用户 re 生成不能认领下一组用户消息之后的回复」——再生成的归属语义。
   - 另有 message_list_view 的 file_processing/tool_extent/card_visibility 系列测试。
6. **timeline_projection.dart**：消息列表投影层——ToolUIPart 的 `providerId/fallbackOrdinal`（无 id 时用序号且与真实 id 不相交）+ `memoToken` 记忆化令牌，渲染与估算共享一次遍历。长会话消息列表性能设计，可对照我们的 MessagePartBlock 渲染。
7. 小件：frosted 毛玻璃组件、thinking_sheen 思考光泽动效、token_detail_popup。

（新增 Dart 目录 `lib/features/chat/widgets/frosted/`、`test/goldens/`、`test/perf/`；Kotlin 新增 ApkArchiveTool/ApkZipCache/AsmFastIndex/PpPostings 等均为逆向引擎性能件。）
