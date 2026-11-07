# P1 完整性能对照：P0 基线 vs P1 注册装配（2026-11-06/07）

> 关闭 §十六 16.1 的「P1 完整性能对照」门槛。设备 PJX110（id `6c841f95`，ColorOS 15），
> 用户提供智谱 API key（glm-4.7，`open.bigmodel.cn` OpenAI 兼容端点）。未推送；key 不入库存档。

## 1. 方法

- **两侧构建**：同一协议、同一注入配置、同一设备。
  - P0 基线：worktree `F:/build/worktrees/plugin-perf-baseline` @ `113772825`（beta.17，即
    `83064c441^`，P1 三提交的直接父级）。
  - P1：worktree `F:/build/worktrees/plugin-perf-head` @ `d68a6bb9c`（当前 master，
    `USE_REGISTERED_TRANSFORMER_ASSEMBLY=true`）。
- **仪器**（两工作树同等补齐，验收回滚窗口后随开关一并删除）：
  - `GenerationHandler`：首个含生成内容 chunk 的 logcat 探针（`perf: first generation chunk received`）。
  - `ChatService` 实发路径转换链装配计时：P0 = `buildInputTransformerChain()`+输出链（旧序），
    P1 = `transformerRegistry.snapshot(...)`；均报 `perf: transformer assembly Xus`。
  - 共享 `ui/components/webview/WebView.kt` factory：`PerfProbe: webview created` 计数。
  - `turn prep phases` 分段计时两侧既有（作为首 token 起点）。
- **隔离包**：applicationIdSuffix 临时改为 `.perfp0`/`.perfp1`（构建文件改动未提交），
  不触碰用户在用的 `.debug` 包数据。
- **注入**：DataStore `settings.preferences_pb` 由脚本从零合成（providers + chat_model 两键 +
  首启默认键），两侧字节一致。模型 glm-4.7（abilities TOOL/REASONING）。
- **协议**：全新安装状态 → 冷启动 → 2 次预热（弃）→ 10 次主轮；固定消息 `ping`，Gboard IME 下
  adb 注入（fcitx 下 `input text` 必坏字）；每轮等待首 chunk+8s 收敛；流式过滤 logcat 捕获
  （`ChatService:V GenerationHandler:V PerfProbe:V *:S`）。**注意：设备 Tethering E2BIG 日志
  高频刷屏，环形缓冲数秒即滚没——必须流式捕获，`logcat -d` 轮询不可用。**
- 首 token 口径：`turn prep phases` 时间戳 → 首个生成 chunk 时间戳（logcat -v time，ms 精度），
  含压缩/记忆/工具/装配/网络全程；服务端延迟占主导。

## 2. 结果（n=12=2 预热+10 主轮；稳态=剔除 2 预热）

| 指标 | P0（旧序装配） | P1（注册装配） | 结论 |
|---|---|---|---|
| 装配 median（稳态） | **54.5us** | **98.5us** | +44us（+81%），与 §十五 微基准（48→68us）同向同量级 |
| 装配 p95（稳态） | 130us | 233us | 绝对值 ≤0.25ms，相对 3.5s 轮延迟可忽略 |
| 首 token median（稳态） | 3555.5ms | 4323.5ms | 见 §3 归因说明：无应用侧回归信号 |
| 首 token 范围（全 12 轮） | 2416–6322ms | 2483–6656ms | 分布高度重叠 |
| prep 分段 median | memories 33ms / tools 3ms | memories 32.5ms / tools 3ms | 一致 |
| WebView 创建 | 0 | 0 | 装配与生成全程未创建 WebView（§14.1 红线达成） |
| PSS 起点/终点 | 237.9MB / 292.0MB | 240.7MB / 288.2MB | 终点 −3.8MB，无回归 |
| FATAL EXCEPTION | 0 | 0 | — |

逐轮明细（assembly us / 首 token ms）：

```text
P0: t1 2631/4157(首跑JIT) t2 77/2416 t3 60/3887 t4 40/3215 t5 130/6322 t6 67/4322
    t7 66/2766 t8 35/3717 t9 57/4680 t10 52/3394 t11 39/2660 t12 42/3246
P1: t1 328/4606 t2 108/4554 t3 91/2483 t4 98/6521 t5 151/3865 t6 79/3386
    t7 85/2743 t8 233/5933 t9 93/4782 t10 99/2911 t11 187/4822 t12 155/6656
```

## 3. 归因说明（首 token +768ms median 差）

两侧首 token 分布几乎重叠（P0 2416–6322，P1 2483–6656），且为**先后串行**的两批请求，
glm-4.7 服务端（含 thinking）延迟为主变量。应用侧可归因差异只有装配 +44us——比首 token
中位数小 4 个数量级，不可能解释 768ms。结论：**装配注册化在端到端口径无可观测回归**；
如需把服务端方差也钉死，需要两侧交替采样（微基准 §十五 已用该手法，此处未做）。

## 4. 门槛判定

§16.1「P1 完整性能对照」完成判据（有数字、有条件、有结论）满足：

- 数字：上表。
- 条件：同设备、同协议、同注入配置、全新安装状态、同模型；隔离包不污染用户数据。
- 结论：装配注册化带来 +44us 中位数开销（与微基准一致，可忽略）；首 token、内存、WebView
  均无回归证据。

剩余门槛：P1 决策门书面记录（默认判据停第一层，待用户确认后写入设计文档）。
验收回滚窗口结束后统一删除：`USE_REGISTERED_TRANSFORMER_ASSEMBLY` 开关与旧序分支、
两工作树的性能探针、`.perfp0/.perfp1` 构建后缀（或整工作树删除）。

原始数据：`F:/build/perf-harness-p0p1/results_p0|p1/`（logcat_full.txt、meminfo、wallclock）；
驱动与分析脚本同目录（bench.sh / analyze.py / make_pb.py）。
