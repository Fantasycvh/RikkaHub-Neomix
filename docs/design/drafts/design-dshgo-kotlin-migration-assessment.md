# dsh-go → Kotlin 迁移评估：以插件设计重构为先

> 状态：**评估稿，未立项**。前提范围：只做插件设计重构，UI 复用本仓现有 Compose 界面，富文本由
> BetterText 承接，玻璃/超椭圆等视觉重设计延后。
> 日期：2026-11-03。代码基线：dsh-Go `8e36cdb`（外部仓库 `ccch1mneyyy/dsh-go`），本仓 `13846d07f`。
> 方法：三路只读实测（dsh-go 插件栈 / 本仓目标侧 / 引擎托管与最小客户端），行数为 `find`/`grep`/`wc`
> 实测；人日为估算，标注区间与点位。
> 与既有设计的关系：**不取代** [`design-plugin-composable-runtime.md`](../completed/design-plugin-composable-runtime.md
> （rev2）。rev2 的「契约化装配优先、UI 插槽 P5 暂缓、不接引擎宿主」结论被本评估的成本数据支持；
> 本评估补的是**迁移面**、**分层成本**与**决策门**。

## 〇、结论

- **难度分两段**：插件设计重构本身是中等规模（T-A 约 35–55 人日，2–3 人 6–8 周）；整体迁移的难度
  集中在「是否把 DSH 引擎搬上手机」这个**独立决策**上，而不在插件重构。
- **相对初版下调**：初版把「插件重构」与「搬 dsh-go 整套 wire 协议 + 引擎托管」混在一起，得出
  210–320 人日；实测拆分后全量 T-A…T-E 为 **137–218 人日（点 ~177）**，只做 T-A 是 **6–8 周**。
- **最大风险不是工作量，是造出第二套插件系统**：本仓已有 4,443 行在役插件系统与 rev2 决策，必须
  改造而非新建。
- **能白拿的资产很多**：dsh-go 的宿主桥 4,237 行 Node 零依赖、零 Flutter 耦合，可原样复用；5 份 JSON
  Schema 与 fixtures 可直接当验收 oracle；Kotlin 原生层 47,890 行中约 89% 零 Flutter 耦合。

## 一、范围前提

| | 内容 |
|---|---|
| **要做** | ① 插件设计重构（manifest / capability / contribution / 视图与效果契约）② 声明式视图层（JSON → Compose）③ 接四个贡献面到现有 UI ④（条件）DSH 远端/本机引擎接入 |
| **复用** | 本仓现有 Compose UI、Koin、Navigation 3；BetterText（markdown + KaTeX + highlight）；现有 PRoot rootfs 与 `ProotShellRunner`；`charts/`；`haze/`（后续视觉重设计用） |
| **延后** | 玻璃/超椭圆、全量 UI 重设计、dsh-go 特色页面（pets / forum / bots / phone control / remote / WebRTC） |

## 二、实测规模账本

### 2.1 dsh-go 侧（外部仓库 `8e36cdb`）

| 层 | 实测规模 | 备注 |
|---|---:|---|
| 手写 Dart 总量 | **246,930 行** | `app/lib` 222,229（UI 153,669 / state 31,984 / 其余 36,576）+ `packages` 24,701 |
| 插件栈 App 侧 | **36,991 行** | 含 `ui/chat/dsh_ui` 的 13,605 行 JSON 渲染器；不含渲染器为 23,386 |
| 插件栈测试 | 24,319 行 | 103 文件；只移植 fixture 与断言即可 |
| 宿主桥 | **4,237 行 JS** | 16 文件，`node:` 内置、无 npm 依赖、无 Flutter 耦合，装于 guest `/opt/dsh-go/dsh-go-bridge` |
| 协议 Schema | 5 份 / 102,666 B / 3,512 行 | `sdk/plugin-kit/schema/*.schema.json` |
| 设计 + 作者文档 | 2,953 + 1,916 行 | 7 份设计稿 + 19 份作者文档 |
| Kotlin 原生层 | **47,890 行** | main 36,065 + JVM 测试 11,825；89% 零 `io.flutter` 耦合，插件税仅 759 行 |
| 引擎构建管线 | 476 + 1,530 + 1,939 + 202 + 11,952 | `build-runtime.sh` / `rtbuild.py` / 15 个钉死补丁 / boot cache / runtime 测试 |

### 2.2 本仓侧（目标实现面）

| 事实 | 实测 | 影响 |
|---|---|---|
| 现有插件系统 | **4,443 行**：`plugin-api` 136 + `plugin-manager` 647 + `data/plugin` 2,589 + 插件页 1,071 | 重构的起点，不是白纸 |
| 插件能力模型 | 7 个能力，其中 `HISTORY_READ` / `CONVERSATION_MUTATE` **只有 UI 文案、无执行点** | 能力合流时要补执行点 |
| 插件形态 | 编译期 Kotlin / WebView JS 沙箱（466 行）/ JSON 规则 / 未来远端 = **4 套** | 收敛为一套 manifest 是本次最大增量 |
| 定义分散 | `plugin_states` / `plugin_rules` / `js_plugins` 三套 DataStore + KV 目录 | 需合一并带 schema 版本 |
| 现成动态 UI 接缝 | 工具卡 `ToolUIRegistry`（版本号驱动）、插件详情面板 `SettingsContent()` | 只需加协议层，不必改机制 |
| 接缝成本 | composer strip 最低（`ChatPage.kt:563` 的 `PlanBar` 旁）；新 message-part 类型最高（sealed `when` 全链路） | 决定一期接哪些面 |
| 富文本 | BetterText 7 个类型化插槽（`codeBlock/image/mathBlock/table/details/inlineMath/inlineLink`） | JSON 视图卡可挂 `codeBlock` 槽，不改库 |
| 图表 | `ChartSpec.fromJson` 已能解析，但只有线/柱/散点，且仅工具调用可达 | 指标、表单、步骤为净新增 |
| 引擎底座 | proot 5.1.107.96 静态库已在 jniLibs；rootfs 下载/解压/CA-DNS 补丁、FGS、端口/token 鉴权先例、OkHttp 5.4 + xz 均已有 | 约 60% 原语具备，缺 Node 供给与守护级监管 |

### 2.3 可原样复用（0 人日）

桥接 4,237 行、plugin-kit 运行时与 5 份 schema、runtime 构建脚本/补丁/locks/boot cache、协议
fixtures（`packages/dsh_client/test/fixtures`、`sdk/plugin-kit/test/parity`）、Kotlin runtime 中
2,211 行可直搬代码、runtime 测试 11,952 行（当 oracle）。

## 三、成本模型（分层，人日）

| 层 | 内容 | 人日 | 依赖 |
|---|---|---:|---|
| **T-A 插件设计重构**（不含引擎） | manifest / capability 合流；`ContributionRegistry`；声明式视图层（JSON → Compose，8–12 组件）；接缝：工具卡 / composer strip / 消息操作 / 详情面板；caps 协商 + 限额 + 降级；配置 schema 表单；三套 DataStore 合一 | **35–55（点 45）** | 无 |
| **T-B 远端 DSH 主机** | 登录 token→cookie、unary RPC 信封、`/api/remote.mux` + `$events`、插件 wire 客户端（contributions / views / invoke / stream）、多主机 | **25–40（点 32）** | T-A 定稿 |
| **T-C 本机引擎托管** | P0 spike（3–4d，有否决权）、构建产物 + manifest、Kotlin runtime 移植（2,211 直搬 + 2,222 适配）、FGS/保活、token 抓取、安全模式/LKG、下载式安装 | **35–55（点 45）** | G2 决策门 |
| **T-D 完整协议与生态** | v1.2 能力、v1.3 文件、配置页、市场/安装、网页岛（建议一期砍） | **30–50（点 40）** | T-B / T-C |
| **T-E 聊天绑定** | DSH session/transcript → 现有 `Conversation` 模型、审批/提问、模型与凭据设置 | **12–18（点 15）** | T-B |
| **合计** | | **137–218（点 ~177）** | 3 人 5–6 个月 |

口径说明：已去重（插件 wire 客户端、视图渲染器各只计一次）；T-C 不含 direct engine（该路线在
targetSdk 37 下不可用，一期直接砍）。只做 T-A 时，2–3 人 6–8 周，与 rev2 文档「2–4 周试点 + 第一层
1.5–3 个月」的区间一致。

## 四、插件设计：保留 / 采纳 / 重设计

### 4.1 保留（本仓已验证的纪律）

窄契约 + 能力闸门（声明 ∩ 审批、可不重启撤销）；`PluginHttpGateway` 出口白名单与 SSRF/DNS-rebinding
防御；WebView 沙箱关网；`PluginKeyValueStore` 配额与原子写；工具卡注册表；安装时审批。

### 4.2 采纳（dsh-go 的形式，不接它的引擎）

| 项 | 采纳内容 |
|---|---|
| 清单 | `id/version/title{zh,en}/icon/wants/capabilities/contributes/limits`；wire 名不改，未来接引擎零改造 |
| 贡献面 | `composer.actions` ≤2、`message.actions` ≤2、`composer.strip` ≤1、`panels` ≤4；label 上限 10/12/24/16 |
| 视图协议 | 固定组件目录 + 有界样式 + caps 协商 + 加法版本；视图 ≤32 KiB / ≤120 节点 / ≤8 层 |
| 效果 | 白名单 7 种 + **3 秒执行窗口** + link 每次确认 |
| 数据 | `rev`/`ETag` + **≤4 Hz 补丁合并**；连续违规降级 `degraded` |

### 4.3 重设计（本次重构的增量价值）

1. **一套内核**：4 种插件形态收敛到单一 `PluginManifest + ContributionRegistry + PluginHost`；本地
   实现＝直接调用，远端实现＝走 wire。
2. **存储合一**：三套 DataStore + KV 目录 → 一套带 schema 版本的 store。
3. **能力合流**：DSH 的 capabilities 与现有 7 个能力合并，并给 `HISTORY_READ`/`CONVERSATION_MUTATE`
   补上真正的调用点。
4. **API 去 Compose 化**：`@Composable SettingsContent()` 不进契约，改为配置 schema 由宿主渲染。
5. **接缝收敛到 6 个**，用密封类型保证编译器不静默丢：工具卡、composer strip、消息操作、插件详情
   面板、设置分区、抽屉底部。不复制 dsh-go「24 个位置只有 5 个能用、41% 退化成网页岛」的教训。
6. **解析与渲染分层**：parse（纯函数、可 golden）→ `ViewNode` 树 → Compose renderer；预算/截断/占位
   是 parse 的输出，不是渲染时抛错。

### 4.4 一期接缝取舍

| 接缝 | 侵入性 | 结论 |
|---|---|---|
| 工具卡（`ToolUIRegistry`） | 低，已是动态注册表 | **一期必做**，零成本生态入口 |
| 插件详情面板（`SettingsContent()` 处） | 低，已是面板宿主 | 一期做 |
| composer strip（`PlanBar` 旁） | 最低，兄弟 composable | 一期做 |
| 消息操作（`ChatMessageActions`） | 中，需穿参 | 二期 |
| 设置分区 / 抽屉底部 | 中，新 `Screen` 与入口 | 二期 |
| 新 message-part 类型 | 高，sealed `when` 全链路 | **不做**，用工具卡与 `codeBlock` 槽替代 |

## 五、分期与决策门

- **P0（1 周，5d）**：冻结组件目录与限额表，产出 manifest / view 两份 Kotlin schema + 解析器。
- **P1（3 周，20d）**：统一 manifest / capability / `ContributionRegistry`；工具卡接声明式视图（先 8 个组件）。
- **P2（3 周，18d）**：四个贡献面接现有 UI；限额与安全回归。
- **P3（3 周，15d）**：配置表单 + 状态存储合一 + 插件详情页重构。
- **决策门 G1（T-A 后）**：插件需要真实数据源时才进 T-B（远端主机，先不碰 Node）。
- **决策门 G2（T-B 后，3–4d spike）**：proot 里跑 Node + `dsh web` + token 抓取；不过则永久停在
  远端主机形态。

## 六、风险

| 风险 | 影响 | 对策 |
|---|---|---|
| 造出第二套插件系统 | 高 | 改造 `PluginManager`，不平行新建；沿用 rev2 结论 |
| 视图协议欠规格 | 高（安全回归） | 限额、动作白名单、caps、未知组件占位、来源标记缺一不可 |
| 把引擎托管当插件系统做 | 高（预算错配） | 独立立项，remote-first，G2 前不碰 Node |
| 聊天模型适配 | 高（隐藏大头） | 分支树 vs append-only transcript 需专门设计；T-E 可能低估 |
| 托管形态 | 中 | APK 74.3 MB → 捆绑 runtime 会到 220–270 MB；**建议下载式安装 + sha256/镜像** |
| 许可 | 中 | 直接搬 dsh-go 代码/协议实现受 AGPL 与品牌素材限制；只借鉴形式可规避 |

## 七、待拍板

1. 接受 **T-A 先行、引擎后置**，还是一期就要本机引擎？
2. 插件作者面是否沿用 `package.json` 的 `dshGo` 清单 + 冻结 schema（建议沿用）？
3. 市场/安装是否在范围（+9d，可砍）？网页岛是否一期就砍（建议砍，只留「在网页版打开」）？
4. 是否需要模型输出的 ` ```dsh-ui ` 卡（+3d，非协议必需）？
5. 是否同步修掉三个既有缺陷：两套 JS 运行时并存（WebView 沙箱 + QuickJS）、`SettingsContent()` 进
   API、两个空转能力？

## 八、附：复测口径

```bash
# dsh-go（外部仓库）
find app/lib -name '*.dart' -not -path '*/l10n/gen/*' -not -name '*.g.dart' -print0 | xargs -0 cat | wc -l
find app/lib/plugins app/lib/ui/plugins app/lib/ui/chat/dsh_ui -name '*.dart' -print0 | xargs -0 cat | wc -l
grep -ri 'dart\|flutter' runtime/bridge/dsh-go-bridge | wc -l            # 0
wc -l runtime/build-runtime.sh runtime/lib/rtbuild.py

# 本仓
find plugin-api plugin-manager -name '*.kt' -print0 | xargs -0 cat | wc -l
find app/src/main/java/app/androidrik/rikkahub/data/plugin -name '*.kt' -print0 | xargs -0 cat | wc -l
grep -n 'ToolUIRegistry' app/src/main/java/app/androidrik/rikkahub/ui/components/message/tools/ToolUI.kt
```
