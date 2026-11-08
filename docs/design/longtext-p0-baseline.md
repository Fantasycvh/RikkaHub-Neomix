# 长文 P0：固定样本与设备基线

本阶段只增加测试设施。基线代码起点为 `3d8db3c64`（流式快照合并、节点复用，以及可选生成高度动画），不包含长文懒加载或编辑优化。

## 测量范围

- 直接挂载生产 `ChatList`、`ChatInput`、`FullScreenEditor`，使用真实 Android/Compose 帧时钟。
- 测试驱动协议为 `input_connection_no_accessibility_v1`，数据 schema=2。触摸使用 `UiAutomation.FLAG_DONT_USE_ACCESSIBILITY` 的事件注入；焦点通过 Compose 遍历/编辑区点击取得，选区和输入使用真实 InputConnection。测试不启动无障碍节点树抓取，减少自动化本身的文本事件与 Binder 开销。
- 普通分段 prose、无换行 dense、Markdown 混排 mixed，各 10,000 / 50,000 / 100,000 个 Unicode 码点，默认每场景 3 次。
- 另提供单大代码块 code、长列表 list、长表格 table，用于专项压力测量。样本全为 BMP 字符，码点数与 UTF-16 长度一致；以生成器版本和 UTF-8 SHA-256 固定身份。
- 场景：静态滚动 scroll、滚动同时流式回放 stream、普通输入 edit_inline、生产全屏对话框 edit_full。
- 流式回放从距末尾 3,200 字的位置开始，分 40 次发布，每次 80 字，目标间隔 50ms；手势与回放并发，保留实际发布时间。它衡量 UI 快照消费，不涵盖网络、Provider 解码、GenerationHandler 或 ChatService 的端到端开销。
- 固定使用默认字号、标准布局、无玻璃效果、无工作区补全、不可用 TTS。生成高度动画默认开启，可用参数关闭；两种设置分别建基线。

## 数据隔离

`-PlongTextBaseline=true` 使用独立包 `app.androidrik.rikkahub.longtextbaseline.debug`。

部分 PJX110/ColorOS 环境会在目标进程报 `Unable to find instrumentation info`，即使注册可见且 `--force-queryable` 安装仍无效（已有仓库验证记录）。显式 `--shared-package` 改用正常 Debug 包和 `-PlongTextBaseline=shared`；这会覆盖安装 Debug APK，但不清除数据。

两条路径均使用专用 `LongTextBaselineRunner`，替换生产 Application，跳过生产 DI、数据库迁移、后台清理和账号初始化。测试仅注册所需 UI 依赖，使用 Room 内存库、独立临时设置文件及固定内存会话。测试启动时断言 Application 类型；未通过隔离断言则拒绝测量。

不填写 API 密钥、不发起模型请求、不使用用户对话作为样本。正式版包不参与。

## 运行

在仓库根目录执行，需 Python 3.10+、可用 JDK/Android SDK，以及已授权的 Android 12+ 真机：

```powershell
python scripts/longtext_baseline.py run --serial 6c841f95

# PJX110 上已记录的新包可见性问题：
python scripts/longtext_baseline.py run --serial 6c841f95 --shared-package

# 冒烟；确认测试设施可用后再跑完整矩阵：
python scripts/longtext_baseline.py run --shared-package --sizes 10000 --kinds prose --iterations 1

# 高度动画关闭，单独保存一组基线：
python scripts/longtext_baseline.py run --shared-package --animation false

# 单个巨大区块压力样本：
python scripts/longtext_baseline.py run --shared-package --kinds code,list,table --scenarios scroll

python scripts/test_longtext_baseline.py
python scripts/longtext_baseline.py compare _measure/longtext/BEFORE _measure/longtext/AFTER
```

默认构建并安装正确的目标 APK 和测试 APK。`--skip-build` 仅复用已有构建，脚本仍校验 APK applicationId；测试中的隔离 Application 断言提供第二道保护。每次使用新的 run-id。

正式流程默认每份样本（一个长度和一种内容）使用新 instrumentation 进程，在该进程中完成各场景与重复轮次。这样避免将所有样本串在同一进程的缓存、无障碍事件与内存累积混入长度对比。`--single-process` 只用于诊断，两种进程策略拒绝自动互比。

输出在忽略入库的 `_measure/longtext/<run-id>/`：

- `raw.jsonl`：逐帧总耗时、布局/绘制耗时、deadline、首帧标志、丢回调数；逐次输入/选区延迟；内存与 ART 计数；场景配置、样本 hash、刷新率和热状态。
- `summary.json`、`report.md`：先计算每轮 P95/P99 和 deadline miss，再取多轮指标中位数，不把不同轮次的帧混成一个样本。
- `metadata.json`、APK hash、`source.patch`、新增源文件快照、设备/屏幕/密度/IME、运行前后电池/热状态、构建与 instrumentation 日志。
- 每个场景完成即写一行，失败时保留已完成的部分。预期行数不符或 instrumentation 未明确成功，脚本以失败退出。

## 指标解释与限制

这是 Debug 构建的组件回归基线，不是 Release Macrobenchmark，也不能直接换算成用户观察到的平均 FPS。后续比较应保持相同构建方式、设备、布局、字号、动画设置、刷新率和温度条件。

通过 `Window.OnFrameMetricsAvailableListener` 采集，使用每帧 `DEADLINE` 判断 `TOTAL_DURATION >= DEADLINE`；首绘帧不计入稳定帧耗时与 deadline miss。全屏编辑必须找到实际 Dialog Window，不能误采底层 Activity。缺少帧、缺少 deadline、回调丢失或达到中等热状态，会标记质量问题，自动比较拒绝该组数据。

输入指标为对真实 TextField 的 InputConnection 提交单个字符到下一次 draw 完成的代理延迟，包含测试派发开销。它绕过当前中文键盘的候选词逻辑，以固定提交行为。选区通过 InputConnection.setSelection 移到开头、中间、末尾，断言实际选区；每次输入断言文本长度确实增加。它不等于 IME 拼音组合、物理触屏到发光延迟；中文组合输入、撤销、跨段复制和旋转恢复仍需后续专项验收。

Java heap 峰值是工作负载步骤间稀疏采样，PSS 仅记录场景前后，不称作精确内存峰值。ART 不支持的计数保留空值，不补零。当前场景切换到宿主下一帧的耗时也不能等同于冷启动或文档完整加载耗时。

初始解析和窗口/键盘动画有固定等待时间，但不固定 JIT 编译状态，不强制 GC，不修改系统刷新率/动画/无障碍设置。被测 Activity 或 Dialog 各自请求 60Hz，记录实际刷新率和逐帧 deadline；系统可以不遵守请求。自动比较逐场景校验刷新率和驱动协议，不把普通与全屏窗口的差异归因于单一编辑器因素。完整矩阵的重复测量用于暴露运行波动，不能据单轮结果承诺优化收益。

未来 P1 用相同脚本重测编辑场景；P3/P4 重测滚动与流式场景。工作区补全当前不启用，因此它的优化收益需要增补相应工作区样本后再评价。

### P1 补充：实际全屏切换

`edit_full` 直接挂载全屏编辑器，不覆盖聊天输入框背后的宿主开销。新增可选场景 `edit_handoff_full`：挂载生产 `ChatInput`，取得焦点，通过进程内 Compose semantics 点击展开，随后在实际 Dialog Window 上执行与 P0 相同的选区/输入负载。窗口切换与初始稳定等待不计入稳态指标，不创建无障碍服务或导出长文本节点树。默认 P0 矩阵不变。

```powershell
# 复测当前版本的真实全屏路径：
python scripts/longtext_baseline.py run --shared-package --sizes 10000,50000,100000 --kinds prose --scenarios edit_handoff_full

# 原样重放已归档的 application/test APK 配对（两者 SHA-256 均需匹配 metadata）：
python scripts/longtext_baseline.py run --shared-package --reference-run _measure/longtext/REFERENCE --scenarios edit_inline,edit_full

# 已构建当前测试 APK，给旧目标应用增加相同的 handoff 驱动：
python scripts/longtext_baseline.py run --shared-package --reference-run _measure/longtext/REFERENCE --current-handoff-driver --scenarios edit_handoff_full
```

`--current-handoff-driver` 仅允许 `edit_handoff_full`，该路径调用的 `ChatInput` 接口在 P0/P1 间保持一致，绕过已改签名的独立 `FullScreenEditor` 入口。它不会自动构建测试 APK，需事先 `:app:assembleDebugAndroidTest -PlongTextBaseline=shared`；其他旧版应用不能据此推定 ABI 兼容。元数据分别保存目标来源/revision、当前源文件快照及实际安装的两份 APK hash。较早的 P0 metadata 未记录测试 APK hash，应使用已核对并归档两份 hash 的后续重放记录。

旧 schema=1 诊断流程通过 accessibility 节点查找/选区，曾采到 78/108 行后在设备的无障碍事件发送链路触发 JNI Abort（pending `DeadSystemRuntimeException`）；单独新进程 10 万字编辑可通过。按样本分进程仍出现同类中止，因此更换驱动协议。旧数据与日志保留为诊断证据，不作为完整基线，也不据此判断应用正常使用必然崩溃。

参考：[FrameMetrics](https://developer.android.com/reference/android/view/FrameMetrics)、[UiAutomation 的无障碍开销控制](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_USE_ACCESSIBILITY)、[Release Macrobenchmark 的构建要求](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)。
