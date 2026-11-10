# dsh-Go（ccch1mneyyy/dsh-Go）评估 —— 把 DSH 引擎装进 Android

> 对应总表 §一 #15。深度：**文档 + 源码抽样**（2026-11-06 固定提交 `cf2f7c861e1098661a3a2039b3a4cb1633790102` 逐篇读 README / 架构 / 用户指南 / 设计文档，关键实现按源码目录抽样核对；**未安装 APK、未真机运行、未跑其测试**）。许可证：主体 **AGPL-3.0**；`sdk/`、`examples/` 为 MIT（README 声明）；名称、标识、Deepy/鲸娘素材另有条款（`ASSETS.md`）。定位：**DeepSeek Harness 的 Android 客户端**——Flutter 原生界面 + 纯 Dart DSH 客户端 + PRoot 里的 Ubuntu 24.04 / Node / DSH 引擎，可本机跑也可遥控远程主机。和本仓（原生 Compose 聊天客户端、AI 能力走 API 抽象层）**不同架构、不同命题**。
> [返回索引](../../peer-apps-benchmark.md)

## 一句话结论

**代码可移植 ≈ 0，机制可借。** 值得拿的是三套产品机制——运行时安装/更新的完整状态机（含失败兜底与「边装边设置」）、危险操作分级 +「问 AI」、以及更新分发契约；再加一套严格的协议/SDK 工程纪律。插件协议已在设计稿 [`design-plugin-composable-runtime.md`](../../design/drafts/design-plugin-composable-runtime.md) §十一 系统复盘（基线 `8e36cdbf3`，2026-11-03），**本轮不新增立项**；内嵌引擎、手机操控、插件市场、分享/回放服务不照搬。

## 一、它是什么

- **技术栈**（`docs/architecture.md:5-12`）：UI 是 Flutter 3.47（Dart 3.13）+ Riverpod 3；原生层 Kotlin 以 Flutter 插件形式写在 `app/` 内，负责解压运行时、管理 PRoot 进程、前台服务；DSH 客户端是纯 Dart 包 `packages/dsh_client`（HTTP/SSE/WebSocket，无 Flutter 依赖，本机与远程共用）；运行时 = Ubuntu 24.04 base + Node + `@deepseek-ai/dsh`，经 PRoot 运行（免 Root、免 Termux）。
- **本地连接**（`docs/architecture.md:82-86`）：原生层起 `dsh web --host 127.0.0.1 --port 3080 --no-open`，从 stdout 解析 token；Dart 侧用 token 换 cookie，此后 HTTP/WS 带 cookie、不带 `Origin`；WebView 兜底直接开 `?token=` URL。
- **三种主机**（`docs/guide/README.md:9`、`docs/guide/zh/03-engine-hosts.md`）：本机引擎 / SSH 主机 / DSH Remote（`packages/dsh_remote`：WebRTC / LAN / TURN / 中继）。工具卡跟着会话记录走，三种主机都显示；原生插件插槽只在装了 `dsh-go-bridge` 的主机上出现（当前本机，SSH 计划中，中继不支持）。数据是**按主机划分**的：对话、工作区、插件、模型渠道都属于某台主机，换主机就换一整套数据（`docs/guide/zh/03-engine-hosts.md:3`、`docs/guide/zh/02-models.md:19`）。
- **功能面（1.0.11，`docs/guide/README.md:7-18`）**：对话（图片/语音/插话与排队/提问与授权/消息菜单）、模型与渠道、本机引擎与远程主机、**手机操控**（无障碍读屏 + 点按输入 + 悬浮条）、网页模式、宠物、分享与回放、插件（安装/开关/更新/卸载、原生设置与网页编辑）、外观、DS Bot、定时任务。
- **分发**：APK 侧载，不上 Google Play；Android 8.0（API 26）起，仅 arm64 / x86_64（无 32 位 ARM）（`README.md:187-195`）。首个公开版 `1.0.0` 在 2026-10-30，此前 0.9.x 已把宠物/网页模式/平板/英语/分享卡铺齐（`docs/changelog.md:293-297,583-631`）。
- **规模与节奏**（GitHub API，2026-11-06 `[远端]`）：仓库 2026-10-25 创建，12 天；12,487★ / 566 forks / 314 open issues；提交数约 700+（GitHub `commits?per_page=1` 的 Link 末页为 707）。最新 release **v1.0.11（2026-11-05）**，arm64 APK 194,097,985 B（约 194 MB，含运行时 tar.xz）；changelog 显示 1.0.10（11-04）→ 1.0.11（11-05）**日更**。
- **演进速度**（本地 clone 核对）：§十一 基线 `8e36cdbf3`（2026-11-03）到 `cf2f7c86`（2026-11-06）**3 天 704 个提交**。评估这类「高速移动靶」只对固定提交负责，本文所有结论都钉在 `cf2f7c86`。
- **工程形状**（`AGENTS.md`）：`app/`（Flutter）、`packages/dsh_client`、`packages/dsh_remote`、`runtime/`（打补丁的 DSH + `runtime/bridge/dsh-go-bridge` + `runtime/patches/pins.json`）、`sdk/plugin-kit/`、`tooling/`、`market/`、`services/share-worker/`。测试体量不小：固定提交树清点 `app/test/` 937 个 `.dart`、路径含 `golden` 的 1,062 个、`runtime/tests/` 271 个文件（2026-11-06 清点）——**快不等于粗**，但也不能替代真机验证（其 `AGENTS.md` 自己写明 CI 绿 ≠ 手机 OK）。
- **许可与品牌**：主体 AGPL-3.0，`sdk/`、`examples/` MIT；README 声明名称、标识、Deepy 小鲸鱼与鲸娘形象不在 AGPL 授权内（`ASSETS.md`），修改版须换名称与素材。**只取机制，不摘代码与素材。**

## 二、值得借鉴（按价值排序）

### 2.1 运行时安装/更新：从「能装」到「可托管」（本仓最接近的落点）

dsh-Go 把「本机引擎」当成一个可安装、可启动、可失败、可重置的**运行时对象**，而不是一次性下载：

- 状态机：`notInstalled / installing / installed / starting / running / stopping / error`，配 `bundledVersion` / `installedVersion` / `baseUrl` / `token` / `error`（`docs/architecture.md:56-67`）。
- 平台通道：`dsh/runtime` 提供 `install / start / stop / getLogs / getLogSnapshot / reset`；事件流推 `progress`（extract/verify/configure 分步 done/total）、`status`、`logs`（约 100 ms 一批；`LogEntry.seq` 进程内递增，**重启引擎或重置都不归零**；App 先订阅再取快照，只接 `seq > throughSeq` 的批次，批次与快照不重叠也不漏行，`docs/architecture.md:44-73`）。这条「先订阅再取快照」的顺序是为了**日志不丢行**，值得直接借用。
- 首次启动并行（`docs/design/onboarding-parallel-setup.md`）：解压运行时约 150 MB xz → 750 MB，实测量级 40 s–2 min。方案是把解压丢给原生 worker + 前台服务 + 唤醒锁，用户同时做「外观 → 连接 DeepSeek」；底部常驻玻璃胶囊 `◔ 运行环境 42%`，点开是状态弹层（进度 / 剩余时间 / 日志；失败时给重试 / 改用远程主机 / 查看日志）。解压完自动进首页；App 被杀后用 `onboarding_setup_step` 回到当前页并重新解压。
- 失败与安全模式：连续两次启动失败会自动进安全模式、不加载第三方插件（`docs/guide/zh/03-engine-hosts.md:16-25`）；启动早期堆内存耗尽也计入自动安全模式（`docs/changelog.md:102`）；安全模式下部分功能不加载（手机操控「安全模式下不加载」，`docs/guide/zh/04-phone-control.md:3`）；「以安全模式启动 / 恢复正常模式」是设置页一等动作（`docs/design/settings-ia.md:99`）。解压**没有断点续传**，进程被杀就从头解（`docs/design/onboarding-parallel-setup.md:230`）。
- 重置/卸载：`reset` 删已解压运行时、保留 `/root` 用户数据需另行确认（`docs/architecture.md:54`）；卸载与重置只在设置页出现。

**本仓对照**（2026-11-06 只读审计核对，未跑真机）：workspace 已有 rootfs 下载/解压/patch 全套，且**已经有安装状态机**——`WorkspaceRepository.kt:205-238` 的 INSTALLING→READY→BROKEN、取消恢复原状态、重装清镜像摘要；UI 有测速/可改 URL/进度/重装（`WorkspaceDetailPage.kt:1462-1610`），下载失败按测速可用镜像依次回退、安装前关闭该工作区终端（`WorkspaceDetailVM.kt:443-480`）。**真正的缺口是「事务与托管」**：① 下载只校验 HTTP 状态/长度，**无 SHA-256**；② 安装事务不可回滚——解压到 staging 后**先删旧 `linux/` 再 rename**，rename/patch 失败旧环境直接丢（`RootfsInstaller.kt:32-47`）；③ 只有安装/重装/删除，没有卸载、重置分级、安装日志与失败三选一；④ 没有长驻引擎，所以也没有 start/stop/restart 与引擎级 safe mode（本仓 `SafeModeActivity` 是应用崩溃安全模式，不是引擎 safe mode）。优先补 ①②（和 `Yuihub` 镜像站两件套同区域，不动下载层），再补 ③ 的 UX，约 2–4 天。dsh-Go 的「首启边解压边设置」诉求本仓没有对应场景（没有运行时首装门禁），不照搬。

**冷启动测量方法可借、优化不必搬**（`docs/design/runtime-cold-start.md`）：模拟器分阶段实测（进程 / App 侧 / exec 链 / DSH 启动 / 地址行 / 就绪 / 首个列表），warm/cold 分开、3–5 次取中位数；结论是 PRoot 把 DSH 启动期约 2.8 万次路径类 syscall 放大（引擎段 1.93 s vs 绕开 PRoot 1.07 s），两项低风险优化（原生并行拉起 + `dsh web` 启动期路径缓存）把「点图标 → 列表」从 3.02 s 降到 2.46 s；Node 编译缓存、去 shim、改 proot 参数都被明确否决。本仓 PRoot 工作区命令是短生命周期，**优化对象不同**；可借的是「先量、再定预算、记录否决项」的方法，以及「App 侧串行等待」这类结构性检查（本仓工作区创建 → 首命令返回可照此量基线，约 0.5 天）。

### 2.2 危险操作分级 + 「问 AI」：本仓审批层的缺口

`docs/design/risk-notices-and-ask-ai.md` 先盘点 `lib/` 下 **42 处确认**（39 个 AlertDialog + 3 个弹层），再把与风险/权限相关的收敛成三档，删除类保留居中对话框、只统一颜色（`:11`、`:99-100`）：

- **高 · 每次确认**：放开一整类保护（「始终免确认」「自动执行命令」「完全权限」「访问手机文件」「允许构建脚本」）——底部弹层、红主键写具体动作、**没有「不再提示」**。
- **中 · 首次确认**：风险有边界或只是跳系统设置——底部弹层、主色主键、「不再提示」默认勾上。
- **低 · 就地说明**：入口副标题一句话。
- 「不确定？问 AI」：**不直接发送**，新开对话把问题预填进输入框（本机引擎前面加 `/dsh-go-guide` 技能；SSH 主机把说明页当附件带上，中继主机只在输入框上方提示「回答可能不准确」）；理由是「发出去要花钱、会占对话、当前主机可能连不上」。

对高风险操作的「解除限制」（系统设置 / 密码验证码 / 操作 DSH Go 自身）还要**按住 1.5 s** 才生效，附五条免责声明（`docs/guide/zh/04-phone-control.md:97-116`），且任务进行中不能开、关闭只要点一下。

**本仓对照**：已有工具审批与「本对话都允许」（`ChatToolFactory.kt`、`SubagentApproval.kt`、`JsPluginTools.needsApproval` 等），但**尚未逐页审计**是否存在同等的「风险分级 + 统一组件 + 问 AI 出口」（标「未核实」）。可行做法不是先移植交互，而是先借它的**盘点方法**：列出本仓所有危险确认（工具审批 / 插件授权 / 删除 / 授权跳转），按「是否放开一类保护」定档，再决定哪些合并、哪些保留居中对话框。先做 workspace 三类危险操作，约 2–3 天。

### 2.3 协议 / SDK 工程链（P5 启动时才用）

设计稿 §十一 已复盘 `dshGo` 清单 + 声明式视图协议（v1 冻结、四类插槽 + 工具卡、3 s invoke、caps 协商、违规 5 次降级、`presentationMeta` 工具卡全主机）。截至 `cf2f7c86` 新增的是**数量与流程**，不是新结论：

- 插槽从 5 类扩到 **10 个已合并位置**（新增：加号面板工具、会话操作 `session.actions`、回合脚注 `turn.note`、插件详情页面板、弹层面板；右侧栏位置在未合并分支），每处都有数量/字数/大小/频率/时间上限（`docs/design/plugin-platform.md:26-44`）。
- 新增对作者的**稳定性承诺**（只加不改 / 新位置 caps 协商 / 单项坏只坏自己 / 连续 5 次违规降级 / 几何归 App / 署名不可覆盖；`plugin-platform.md:46-61`）。
- 新增接缝的固定 6 步流程（桥接 `limits.js`/`manifest.js` → SDK `sync-bridge.mjs`、schema、types、`check.js`、`testing.js`、`gen-types`、`sync-skill` → 客户端解析 → App provider → 测试/golden → 双语文档；`plugin-platform.md:74-83`）。
- 工程钉死：`runtime/patches/pins.json` 逐文件 SHA-256；`versions.md` 写旧 App 行为；文案 `l10n_merge.py --check`、指南 `guide_sync.py --check`；插件协议改动的提交说明必须写 `NA protocol v1 change: …`（`AGENTS.md`）；手机操控 / PRoot / 直接启动 / 更新安装**必须真机验证**，CI 绿 ≠ 手机 OK。
- 版本号三套分离：协议主版本 `v`（不兼容才升）、视图格式 `v1/v2`（并存）、`minApp`，外加 `engines.dsh` 声明支持的 DSH 版本；老 App 遇不认识组件画灰色占位、属性/插槽/效果一律忽略，v1 golden 不因 v2 更新（`docs/plugins/versions.md:5-35`）。
- 市场覆盖数据（2026-10-28 对 dshfind 索引 5,781 包静态扫描）：带浏览器半侧 4,116 个、注册 slot 3,424 个，而 App 只接 9 个 slot——插件维度 **34% 全可见 / 27% 部分 / 39% 一处都看不到**（`docs/design/plugin-ui-v3.md:11,61-65` `[自述]`）。这组数字是「原生插槽覆盖有上限、市场不宜先做」的独立佐证。
- 文档即契约：`docs/maintenance/` 61 篇「契约」文档（如 `fs-text-bom-contract.md`、`session-list-isolation-contract.md`）与同名补丁/测试一一对应；`docs/design/` 30 篇设计记录（`docs/design/` 目录清点）。
- SDK 工具链：`@dsh-go/plugin-kit` 含类型、组件构造器、`toolCard`、caps 帮助、`createTestHost`、`check` CLI 与 schema（`sdk/plugin-kit/README.md`）。

**本仓判断不变**：P5 暂缓；若启动，先借「schema + check + 测试宿主 + 新增接缝流程」这四件，市场与 npm 宿主不做。

### 2.4 更新分发契约（想做应用内更新时再借）

`docs/design/delta-update.md` 是一份少见的、把「更新」写成可验证契约的文档：

- 清单 `update.json` + 整包；增量补丁格式 `DSHDLT01`（80 B 头 + COPY/DATA 操作流），按 **zip 条目**生成，**生成时先应用一遍、SHA-256 对上才落盘**；补丁小于整包 60% 才发布（超过就删，省下的流量不值得多一条要维护的路径）。
- App 端契约：只认「已装 APK 的 SHA-256」选补丁；下载后校验补丁；应用前校验旧 APK 与目标长度；应用后长度 + SHA-256 必须等于清单；**任何一步不对就删补丁与半成品、本次改下整包**，不再试别的补丁。安全性不降级：拼出的文件仍要走签名预检与系统校验。
- 实测（1.0.7→1.0.8）：整包 205,180,162 B，换运行时那版补丁 85.5% 被丢弃；只改 Dart 代码估算约 6.5%（≈13 MB）；`make` 0.7 s / `apply` 0.3 s / 峰值内存 22 MB。
- 发版侧：`tooling/release_assets.py --delta-from N` 从最近 N 个 release 取旧 APK 生成补丁，`publish-release.sh` 每条线路回下核对，三份清单同发。
- 更新链路（`docs/release.md:408-422,459-486`）：启动 6 s 后每天最多检查一次；多线路同源清单（内测 → 公开桶 → GitHub → 加速镜像 → GitHub API，「上次成功的源排最前」）；校验 SHA-256、包名/签名预检、PackageInstaller 安装，要求可用空间 ≥ 包体 ×2 + 64 MB。信任链写得明确：**SHA-256 只防传输损坏与只改 APK 的镜像，签名才是最后防线**（镜像同时改清单与 APK 能对上摘要，但签不出发布签名）。

**本仓对照**：目前**没有应用内更新检查/下载/安装**（`git grep` 0 命中 `PackageInstaller` / `releases/latest` / 更新检查代码），更新靠用户去 Release 页。若要做，先做「整包更新器 + SHA-256 + 签名预检 + 失败回落」（3–5 天），增量补丁是第二步（另 1–2 周，且要先有发版管线）；**不建议**直接复刻 `DSHDLT01`，它与 APK zip 结构强耦合。

### 2.5 主机能力矩阵（文档级）

dsh-Go 把「哪台主机能用什么」写成明确矩阵：工具卡全主机；插件插槽仅装桥接的主机；中继主机不支持桥接；远程主机不保证有某项技能时用附件兜底（`risk-notices-and-ask-ai.md:7`）。它对第三档（Remote）的降级写得很具体：只能发图片、App 内打不开网页版、插件只能开关、无 DS Bot 与导出（`docs/guide/zh/03-engine-hosts.md:88-90`、`README.md:183`）。本仓已有本地 PRoot 与 SSH 两套后端，且代码里已有 per-backend 能力位（`Workspace.kt:34-42`：`rootfs` / `localFiles` / `backgroundTasks`），但缺**面向用户与文档的能力矩阵和降级文案**。借这张矩阵的写法（能力 × 主机 × 降级文案），约 0.5 天、纯文档，可先做。

## 三、不照搬

| 方向 | 理由 |
|---|---|
| Flutter / Riverpod 架构 | 本仓是 Compose + Kotlin，迁移收益不抵成本；dsh-Go 的 UI 选择（玻璃质感、WebView 兜底）与本仓 M3 体系不同 |
| 内嵌 Ubuntu + Node + DSH 引擎 | 约 750 MB rootfs、194 MB APK、`targetSdk 28` 规避 Android 10+ 私有目录 exec 限制（`docs/architecture.md:78-79`）；本仓 AI 层是 API 抽象，不宿主 Node 引擎；设计稿 §8.1 已把 JS 运行时选型定为「不升级」 |
| npm 插件宿主 + 插件市场 | 本仓无 Node 宿主；市场是独立仓库 + worker 的基础设施，本仓设计明确不做（§十一 §11.5） |
| 手机操控（无障碍读屏/点按） | 需要无障碍 + 悬浮窗 + 系统权限，合规与风险边界完全不同；只把其「披露 / 应用白名单 / 多路停止 / 按住解锁」当**判据样本**，不立项 |
| 分享链接 / 回放服务 | 需要 share-worker + 多条分发线路 + 回放页；本仓已有导出/系统分享（`ui/pages/chat/Export.kt` 等），链接分享是服务器产品 |
| 增量补丁格式 | `DSHDLT01` 绑死 APK zip 条目布局；要做更新应先用标准整包 + 校验 + 回落 |
| 宠物 / 社区 / 增长向产品 | 不同产品策略；本文不评估 |
| AGPL 代码与品牌素材 | 主体 AGPL-3.0、素材另有限制；**只取机制，不摘代码** |

## 四、建议顺序（有需求才立项）

| # | 内容 | 来源 | 成本 | 说明 |
|---|---|---|---|---|
| 1 | workspace rootfs 安装事务化：SHA-256 校验 + staging 原子切换（失败保留旧环境）+ 取消/重试/安装日志；再补卸载与重置分级 | dsh-Go §2.1 | 2–4 天 | 现有安装状态机与镜像回退不动；先补事务（①②），再补 UX（③） |
| 2 | 工具审批风险分级 + 「不确定？问 AI」预填对话 | dsh-Go §2.2 | 2–3 天 | 先覆盖 workspace 命令 / 文件删除 / 插件授权三类 |
| 3 | PRoot 工作区冷启动基线测量（创建 → 首命令返回；文件工具路径翻译） | dsh-Go §2.1 方法 | 0.5–1 天 | 只出基线文档与预算，不写优化 |
| 4 | 主机能力矩阵文档（能力 × 本机/SSH × 降级文案） | dsh-Go §2.5 | 0.5 天 | 代码已有 per-backend 能力位，只补用户可见矩阵 |
| 5 | 应用内整包更新器（清单 + SHA-256 + 签名预检 + 回落） | dsh-Go §2.4 | 3–5 天 | 仅在有分发痛点时立项；增量补丁不跟进 |
| 6 | 插件协议 schema + check + 测试宿主 | dsh-Go §2.3 | 随 P5 | 现在不做，等 P5 解冻再按 §十一 的边界取 |

## 五、落地记录

（暂无。落地后回写此处与总表 §三。）

## 六、证据索引（固定提交 `cf2f7c861e1098661a3a2039b3a4cb1633790102`）

| 主题 | 文件 |
|---|---|
| 产品说明与下载 | [README.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/README.md) |
| 架构 / 平台通道 / 打包 | [docs/architecture.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/docs/architecture.md) |
| 用户指南（12 篇，中英） | [docs/guide/README.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/docs/guide/README.md) |
| 冷启动测量与优化 | [docs/design/runtime-cold-start.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/docs/design/runtime-cold-start.md) |
| 首次启动并行安装 | [docs/design/onboarding-parallel-setup.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/docs/design/onboarding-parallel-setup.md) |
| 增量更新契约 | [docs/design/delta-update.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/docs/design/delta-update.md) |
| 插件平台方向与接缝 | [docs/design/plugin-platform.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/docs/design/plugin-platform.md) |
| 插件协议 / 视图 / 接缝 | [native-plugins-protocol.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/docs/design/native-plugins-protocol.md) · [native-plugins-views.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/docs/design/native-plugins-views.md) · [plugin-seams.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/docs/design/plugin-seams.md) |
| 风险提醒与问 AI | [docs/design/risk-notices-and-ask-ai.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/docs/design/risk-notices-and-ask-ai.md) |
| 引擎主机 / 安全模式 / 远程降级 | [docs/guide/zh/03-engine-hosts.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/docs/guide/zh/03-engine-hosts.md) |
| 插件版本纪律 | [docs/plugins/versions.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/docs/plugins/versions.md) |
| 手机操控（负样本 + 披露设计） | [docs/guide/zh/04-phone-control.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/docs/guide/zh/04-phone-control.md) |
| 插件市场治理 | [market/README.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/market/README.md) · [docs/design/plugin-market.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/docs/design/plugin-market.md) |
| 插件 SDK | [sdk/plugin-kit/README.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/sdk/plugin-kit/README.md) |
| 版本与发布节奏 | [docs/changelog.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/docs/changelog.md) · [v1.0.11 release](https://github.com/ccch1mneyyy/dsh-Go/releases/tag/v1.0.11)（`[远端]`） |
| 仓库纪律（真机验证 / 协议改动说明 / 生成物） | [AGENTS.md](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/AGENTS.md) · [runtime/patches/pins.json](https://github.com/ccch1mneyyy/dsh-Go/blob/cf2f7c861e1098661a3a2039b3a4cb1633790102/runtime/patches/pins.json) |

> 相对上一轮（设计稿 §十一 基线 `8e36cdbf3`，2026-11-03）：插件协议新增 5 个位置与稳定性承诺、`plugin-config` / `managed-skill-install` 文档；运行时侧新增日更发布，以及一次**尚未真机验证**的引擎升级（1.0.11 升到 DSH 0.2.2-rc.1，changelog 自述只在 x86_64 模拟器 + 测试模型上走过）。**对 §十一「P5 暂缓、市场不做」的结论无影响。**

## 七、2026-11-06 → 11-10 增量（抽样，`cf2f7c86..8f31b2e`，542 提交）

> 同步方式：本地克隆已 fetch 到 `origin/main` = `8f31b2ed8`（2026-11-10 16:23）。4 天 **542 提交**（+164,468/−8,745 行，1,092 文件），日更节奏未变；分桶：fix 197 / phone 121 / feat 110 / test 33 / chore 16 / docs 10；文件热度 app/android 1,179 次、app/lib 785、app/test 377、runtime/tests 64、docs 60+。发布 `1.0.12`（11-07）→ `1.0.15`（11-10），新增 `docs/design/{android-shell,self-update-installation,companion*}.md` 与 `docs/testing/*`、`docs/phone-eval/`。深度＝**标题全量分桶 + 文档/变更抽样**（不逐条读 patch）。**总评：§一架构结论与 §三不照搬清单全部不变（代码可移植 ≈0，P5 暂缓、市场不做）；本轮产出 1 个本仓静态核验实证候选（16 KB 内存页）+ 5 条判据级记账，无新立项。**

### 7.1 实证候选：16 KB 内存页兼容（P2，本仓已发现具体缺口）

- dsh-Go 侧：1.0.9 起做 Android 17 适配，其中自连 ADB 的 TLS 库 Conscrypt 升 2.8.0「在 16 KB 内存页的设备上也能加载」；1.0.15 changelog 仍明写「未在真机验证」。
- **本仓静态核验（2026-11-10，未真机）**：`app/build.gradle.kts` = `targetSdk 37`（Android 17）/ compileSdk 37.2 / `useLegacyPackaging = true`；全仓 14 个 `src/main/jniLibs/**/*.so` 中 **3 个 PT_LOAD `p_align = 0x1000`（4 KB）**：
  - `app/src/main/jniLibs/arm64-v8a/libtex_poc.so`（TikZ 的 Tectonic 引擎，13.5 MB，`tools/tex-poc/` NDK r27c Rust 交叉编译，Gradle 不参与编译，README.md:204/228）
  - `libpdf2svg_bridge.so`（mupdf PDF→SVG 桥，13 KB，`app/src/main/cpp/pdf2svg_bridge.c`，预编译入库）
  - `libgraphite2.so`（TeX 字体整形依赖，`$ANDROID_LIBS` 交叉编译产物，tools/tex-poc/README.md:31-34）
  - 其余 11 个（`libsimple` / `libmupdf_java` / `libproot_exec` / `libproot_loader` / `libicudata78` / `libicuuc78` / `libc++_shared`）均 ≥ `0x4000` 合格；x86_64 侧无此三库（只有 arm64）。
- 影响：16 KB 页设备上这三个库无法按页对齐加载（`UnsatisfiedLinkError`/加载失败），TikZ 渲染与 PDF→SVG 链断；本仓 TikZ 有回退链（纯 Kotlin 子集 → PDF 位图，README.md:229）能兜住**显示**，但 PDF 导出/转换路径是否全兜住**待核**。
- 修法（未实施）：重编时加 `-Wl,-z,max-page-size=16384`——`libtex_poc` 改 `tools/tex-poc/build-android.sh` 的 `RUSTFLAGS`（或 `.cargo/config` link-arg）；`libpdf2svg_bridge.so` / `libgraphite2.so` 用 NDK clang 加同一参数重编；三个库改完需模拟器 16 KB 页镜像或真机回归。
- **设备要求（2026-11-10 核实）**：
  - **重编与静态校验不需要设备**：只需 NDK/交叉工具链加 `-Wl,-z,max-page-size=16384`（NDK r28+ 已默认 16 KB，r27c 需显式传）；然后静态验 `llvm-readelf -l` 的 `LOAD ... Align ≥ 0x4000`、`zipalign -c -P 16 -v 4`（APK 内 .so 未压缩且 16 KB 对齐）。
  - **运行验证需要 16 KB 页内核**：AOSP 明确「只有内核为 16 KB 的 arm64 目标才支持 16 KB 页面」（`CONFIG_ARM64_16K_PAGES`，Android 15 起随开发者选项启用）。可选路径：
    - **真机**：Pixel 8 / 8 Pro / 8a / 9 / 9 Pro / 9 Pro XL 刷官方 16 KB 内核镜像（需解锁 bootloader、会清数据；AOSP「Flash Pixel with 16KB kernel」）。
    - **模拟器**：SDK 仓库确有 `ps16k`（16 KB Page Size）镜像，**arm64-v8a 与 x86_64 都有**（API 35 / 36 / 36.1 / 37.0 / 37.1，例如 `system-images;android-36;google_apis_ps16k;x86_64`）。x86_64 镜像可在 Windows/Linux x86_64 主机（WHPX/HAXM）上建 16 KB 页 AVD；arm64 镜像只能在 arm64 主机（Apple Silicon Mac）上跑。
    - AOSP Cuttlefish 的 x86_64 16 KB 用户态模拟（Linux 主机，非 Android Studio 模拟器）。
  - **本机的可行范围（Windows x86_64 + SDK emulator 36.6.11 + WHPX 可用）**：可以下载 `ps16k;x86_64` 建 16 KB 页 AVD，做「App 能装、能启动、JNI 不崩、`adb shell getconf PAGE_SIZE` = 16384」的整体冒烟。**但这三个坏库只有 arm64 产物**，x86_64 AVD 加载不到它们（TikZ 走既有回退链），所以本机 AVD 验证不了修复；验这三个库仍需 arm64：Pixel 8/9 系刷 16 KB 内核，或 arm64 Mac 上的 `ps16k;arm64-v8a` 镜像（x86_64 主机跑不了 arm64 镜像）。
  - **重编前置（按本仓现状）**：`libtex_poc` 走 `tools/tex-poc/build-android.sh`（WSL Ubuntu + rustup `aarch64-linux-android` + NDK r27c linux-x86_64 + `/root/android-libs` 交叉依赖 libpng/graphite2/freetype/termux libicu + `patchelf`）；`libgraphite2` 就在该交叉依赖里（需从源码重编）；`libpdf2svg_bridge` 源码是自包含的 `app/src/main/cpp/pdf2svg_bridge.c`，链接 MuPDF 1.26.8 的 `libmupdf_java`（该库已 `0x4000` 合格，不必动）。三者都只出 arm64；`libmupdf_java`/`libsimple`/`libproot_*`/icu/`c++_shared` 复核均 ≥ `0x4000`。
  - 结论：**重编 + 静态校验不需要设备**；这三个库的**运行验收**要么 Pixel 8/9 系真机、要么 arm64 主机模拟器。可选的替代验收：给三个库补 x86_64 交叉产物后用本机 x86_64 ps16k AVD 复现/验证（graphite2/icu/tex_poc 都得能出 x86_64，工作量远大于重编本身，不推荐）。

- 判决：**候选 P2**（静态缺口确定、改动面小；但无法在本机真机验证 16 KB 链路，落地需设备/模拟器配套）。

### 7.2 判据级记账（不立项）

1. **引擎更新「只替换 App 管理的运行时部件」**（1.0.15）：DSH/Node/pnpm/桥接及其目录换新，`apt` 安装的工具、默认位置 npm 全局包、Python/pip 包、配置、工作区**全部保留**；「确认不了现有环境兼容 / 更新清单损坏 / 旧进程没停干净」就停止更新、**原样保留旧环境**并提示重试，而不是重置。→ 强化本文 §2.1 建议 #1：本仓 rootfs 更新仍缺 SHA-256 与 staging 原子切换，这条补上「部分更新 + 拒绝重置」的产品语义。
2. **安装回执契约**（`docs/design/self-update-installation.md`，新增）：dispatch 完成 / Activity resume / CLI 确认**都不算安装成功**；必须核对实际安装包 + 当前签名集 + build number + 摘要，任一变化即要求重新取证；未知结果不自动重试、不重建命令/路径重放；日志不落路径与配对密钥。→ 本仓无应用内更新（`PackageInstaller` 0 命中，沿用原结论）；若做更新器，先按此契约。
3. **Android 命令通道的权限边界**（`docs/design/android-shell.md`，新增）：工具不能请求授权/配对/切 root；必须显示完整命令与实际后端/UID；**取消 ≠ 回滚**；终止只打自有进程组；「shell 是高权限能力不是沙箱」明写（同 UID 的 rootfs 代码不被桥接令牌隔离）。→ 本仓 workspace 是 PRoot 自有后端，无此通道，只记判据。
4. **系统 IME 证据采集纪律**（`docs/testing/ime-diagnostics.md`，新增）：show/hide **请求** ≠ 键盘**可见**；日志里的包名 ≠ 生产者认证（只认当前 PID）；固定事件允许列表 + 字节/事件上限 + 只进用户主动导出的诊断，不进 AI prompt。→ 本仓 IME 四连击属 TextField 宿主拓扑问题（AGENTS.md 规范 + `scripts/audit_ime_trap.py` 静态审计），无特权 logcat 通道；将来做 IME 诊断面板再借这条判据。
5. **「未接收的输入按原顺序放回队列」**（1.0.9 changelog）：准备失败/中断时不丢待发输入。本仓有 `MessageQueue`（`ChatVM.kt:303`、`ChatPage.kt:675` `resumeMessageQueue`），但恢复**顺序**语义未逐行核对（**待核**），不单独立项。

### 7.3 结论

- §二 六项建议顺序不变；§2.1「安装事务化」因 1.0.15 语义更明确而略升优先级（仍等用户需求）。
- 新增 §7.1 的 16 KB 页候选，与本仓 TikZ/PDF 原生库直接相关，已挂进 [peer-verdicts §五](../../peer-verdicts-2026-11-08.md) 候选池。
- 高速移动靶纪律：本文结论只对 `8f31b2e` 负责；下次同步按台账同步点（而非 ref 区间）判断欠账。
