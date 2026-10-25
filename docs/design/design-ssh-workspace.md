# SSH 工作区设计稿（Draft）

> 状态：**Phase 1a + 1b + 2 已实现，并通过单测、WSL OpenSSH 与真机（PJX110 / Android 15）验证**（2026-10-25）。
> 验证：`:workspace:testDebugUnitTest` 全绿（115 项，0 失败，20 项在 Windows 宿主跳过）、
> 新增 `RemoteEnvironmentCacheTest` 3 项全绿、`:app:compileDebugKotlin` 与
> `:app:assembleDebug` 成功；另用 WSL Ubuntu 24.04 的真实 OpenSSH 跑通
> 主机密钥确认/变更拒绝、SFTP 读写/列目录、exec/stdin/cwd、根目录浏览与 PTY 交互 shell；
> 真机侧 Keystore/迁移/Repository/工作区提醒 instrumented 测试 8 项全过，UI 全链路见第八节。
> SSH 首版未包含交互式终端（Phase 3）。
>
> Windows 适配：嵌入式 SSHD 的认证/SFTP 用例用 `VirtualFileSystemFactory` 把 `/remote` 映射到本地
> temp；依赖 POSIX shell、PTY 或 `*` 文件名的用例由 `Assume` 跳过，需在 WSL/Linux/真机补跑。
> 目标不是一次移植上游 `2b633370f`（99 文件 / +13915 行），而是把“本地可验证”的部分先做稳，
> 再决定是否投入远程服务器、终端模块和 fork 独有能力。
>
> 上游参考：`2b633370f feat: 添加远程ssh工作区支持 (#2211)`。
> 本仓相关评估：`docs/upstream-analysis.md`（`2b633370f` 条目，结论为“新功能，单独立项”）。

## 一、结论

SSH 工作区按四段推进，每段都能独立验收：

| 阶段 | 内容 | 验证方式 | 是否依赖真实远程服务器 |
|---|---|---|---|
| **1a** | `WorkspaceBackend` / `WorkspaceLayout` / `LocalWorkspaceBackend`，本地实现包住现有 `WorkspaceManager` | workspace 单测 + 现有测试 | 否 |
| **1b** | SSH backend：JSch 连接池、SFTP、exec channel、主机指纹、取消/重连 | 嵌入式 Apache SSHD 单测 | 否 |
| **2** | DB `type/ssh_config`、能力模型、Repository/Tools/提示词/选择器、SSH 编辑页 | 编译 + 单测 + WSL OpenSSH；真机待补 | 真机仍需验证 |
| **3** | `:terminal` 模块、`TerminalTransport`、SSH 交互终端、本地 PTY 迁移 | 本地终端回归 + Docker SSH 终端 | 本地 Docker 即可 |

只有 WAN 延迟、运营商 NAT 超时、真实主机指纹变化后的用户操作，才必须用公网 VPS；这些放在最后。

## 二、为什么不能直接 cherry-pick

本仓工作区与上游基线已深度分叉：

- `WorkspaceManager` 多出后台任务、行窗口读取、写入冲突检测、Shell 兼容模式、Skills/upload 挂载；
- `WorkspaceRepository` 40+ 个公开方法，包含 Rootfs 安装、压缩导出、预览大小、后台任务；
- `WorkspaceTools` 多出 `workspace_present_file`、`workspace_run_background/_task_status/_kill_task`、文档抽取；
- 工作区 UI 与上游基线差异在 800–2300 行/文件。

因此 Phase 1a 只做**新增抽象**，不改现有调用方；Phase 2 才逐项把调用方迁到 backend，并处理 fork 独有能力。

## 三、Phase 1a：后端抽象（本次）

新增：

- `workspace/.../WorkspaceBackend.kt`
  - `WorkspaceBackend`：单工作区执行环境接口（存储区相对路径 + 环境内绝对路径两套寻址）。
  - `WorkspaceLayout`：`workspaceDir`、`writableRoots`、路径换算、可写判定。
- `workspace/.../LocalWorkspaceBackend.kt`
  - 包住 `WorkspaceManager`，保持本地 PRoot/文件系统实现不变。
  - `writeAbsoluteText` 走 shell（Rootfs 内软链只有 PRoot 内能正确解析），与上游同口径。
- `WorkspaceManager`
  - `layout`：`workspaceDir=/workspace`，可写区 `/workspace`、`/tmp`（与现有工具审批口径一致）。
  - `backend(root, shellCompatibilityMode)`。
  - `resolveFile(...)`、`resolveRootfsFile(...)` 转为可复用入口。

明确不做：

- 不引入 `WorkspaceType` / `WorkspaceCapabilities`（Phase 2 和 DB 一起做）。
- 不引入 JSch、不改 `:terminal`、不改 Repository/Tools/UI。
- 不改 `WorkspaceBindMount` 的 `writable` 语义；本仓当前没有“可写挂载”，`/skills` 等仍按只读处理。

## 四、Phase 1b：SSH backend（已实现，本地验证）

已移植：

- `workspace/.../ssh/SshConfig.kt`（认证类型、主机公钥、连接配置、探测结果）
- `SshConnection.kt`（JSch 连接、SFTP 锁、exec、PTY shell、取消语义）
- `SshConnectionPool.kt`（按工作区复用、空闲 5 分钟断开、Closeable）
- `SshShell.kt` / `SshWorkspaceBackend.kt`（SFTP 文件、远程 shell、绝对路径）
- 上游 `SshWorkspaceBackendTest`（Apache SSHD 嵌入式服务器）

本地验证覆盖：密码/私钥认证、首次主机指纹、指纹变化拒绝、SFTP 读写/目录/软链、exec 超时/取消、
连接池空闲断开与重连、并发 SFTP + exec。

仍不能本地覆盖：真实 WAN、运营商 NAT、服务器端 PAM/2FA 差异。

## 五、Phase 2：应用集成与 fork 独有能力

### 5.1 数据与能力

- `WorkspaceEntity` 增加 `type`（LOCAL/SSH）与 `ssh_config`（JSON，凭据为 Keystore 密文）。
- DB 从当前 **47** 起新增显式迁移（上游是 31→32 自动迁移，不能照搬）。
- `WorkspaceCapabilities`：`rootfs` / `terminal` / `localFiles` / `fileWriteTools` / `backgroundTasks`。
- `checkIntegrity()` 只检查 `localFiles` 工作区，避免把 SSH 工作区误标 BROKEN。
- SSH 首版能力：`rootfs=false`、`terminal=false`、`localFiles=false`、`fileWriteTools=false`、`backgroundTasks=false`。
- 凭据：`SshWorkspaceSettings` 存密文（[SecretCipher]），编辑时留空沿用旧值；保存/探测前先测连接。
- 应用层已接入：DB 47→48 迁移、Koin 连接池与 VM、仓库路由到 backend、SSH 编辑页/详情页、
  新建类型选择、cwd 选择器与 @ 补全按远程目录、聊天抽屉与文件浏览器按能力裁剪。

### 5.2 fork 独有功能能力矩阵（待拍板）

| 功能 | 本地 | SSH 首版建议 | 理由 |
|---|---|---|---|
| `workspace_read_file` 整文件 | 有 | 有 | SFTP 可读 |
| `workspace_read_file` 行窗口 | 有 | 有（整读后切窗口） | 远程无本地 `File`，按绝对路径整读；受工作区单次读取上限约束，超出时提示改用 `sed`/`head` |
| 文档抽取（PDF/DOCX/PPTX/EPUB） | 有 | 暂缓 | 解析器吃本地 `File`，需先下载临时文件 |
| `workspace_write_file` / `edit_file` | 有 | **隐藏** | 上游结论：SFTP 覆盖写中断会留下空/残缺文件；SSH 用 shell 写 |
| `workspace_present_file` | 有 | 暂缓 | 依赖本地文件落点 |
| `workspace_run_background` 等三件套 | 有 | 暂缓 | 无远程任务注册表；首版隐藏 |
| 写入冲突检测 | 有 | 不适用 | SSH 不开放写文件工具 |
| Shell 兼容模式 | 有 | 隐藏 | 仅本地 PRoot seccomp 相关 |
| Skills / upload / tool_outputs 挂载 | 有 | 不适用 | 远程没有这些挂载 |
| 交互式终端 | 有 | Phase 3 | 需要 TerminalTransport |

### 5.3 提示词与上下文

- 远程工作区不读 `AGENTS.md`（上游选择，少多次网络往返）；本地保留现有行为。
- 远程 shell/发行版按工作区缓存（`RemoteEnvironmentCache`，失败结果保留 60 秒）；配置变化后失效。
- 连不上时只注入“不可达”说明，不阻塞对话；工具调用仍会正常返回连接错误。
- 系统提示区分“远程真实主机 / 非沙箱 / 只有读与 shell 工具”，并提示用 heredoc、sed、patch 改文件。
- 工具描述按能力裁剪：远程不再声称 Rootfs、文档抽取和写文件工具；`cwd` 按远程工作目录换算。

### 5.4 安全与备份

- 凭据使用 Android Keystore AES-GCM 加密后存库；换设备恢复备份后无法解密，必须明确提示重新填写。
- 主机指纹首次确认，变化拒绝；不提供“忽略”按钮。
- JSch + BouncyCastle 增加 R8 keep/dontwarn 与 APK 体积，需 release 实测。

## 六、Phase 3：终端

本地现状：`terminal-view` AAR + 自建 `libtermux.so`，`TerminalSession` 直接绑本地 PTY。

上游做法：vendor termux terminal-emulator 为 `:terminal` 模块，抽 `TerminalTransport`，
本地 PTY 迁到 `LocalPtyTransport`，SSH 用 `SshTerminalTransport`。

风险点：排除 AAR 的 `terminal-emulator`、移动 `termux_pty.cpp`、`pickFirsts`/16KB 对齐、
本地 PRoot 参数（Skills 挂载、兼容模式 cwd）迁移、粘贴死锁/孤进程清理回归。

## 七、决策与遗留

已按建议落地：首版不含交互式终端；后台任务/文档抽取/present_file 在 SSH 下隐藏；
远程不读 `AGENTS.md`；缓存管理排除 SSH 工作区占用。

仍需验证/决策：

1. 真机全链路已覆盖 Keystore 凭据、指纹确认/变化、文件浏览与导入导出；远程提示词注入尚未在
   配置了模型的会话里观测（见上节“仍未覆盖”）。
2. Windows 跳过的 18 项嵌入式 SSHD 用例已由 `SshLiveServerTest` 在 WSL 真实 OpenSSH 上覆盖
   主要路径；仍需在 CI/真机补跑完整矩阵。
3. release 构建（R8）对 JSch/BouncyCastle 的 keep/dontwarn 与 APK 体积影响尚未实测。
4. SSH 工作区的「下载/分享」目前只在详情页提供单文件导出；聊天抽屉隐藏了批量导出以避免误用本地打包逻辑。
5. 交互式终端（Phase 3）依赖 `TerminalTransport`，本阶段未改动 `:terminal`。
6. Docker Desktop 引擎当前启动失败（API 500 / status stopped），本次改用 WSL 内直接运行 OpenSSH，
   不依赖 Docker；需要容器矩阵时再补。

## 八、验证命令

```bash
./gradlew :workspace:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests "app.androidrik.rikkahub.data.ai.transformers.RemoteEnvironmentCacheTest"
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug

# 可选：真实 OpenSSH 集成测试（未设置环境变量时自动跳过）
MSYS_NO_PATHCONV=1 \
  SSH_IT_HOST=127.0.0.1 SSH_IT_PORT=2222 SSH_IT_USER=root \
  SSH_IT_KEY='C:/path/to/private_key' SSH_IT_DIR=/tmp/rikkahub-ssh-it \
  ./gradlew :workspace:testDebugUnitTest --tests "*SshLiveServerTest"

# 真机 androidTest（不会卸载 debug 数据；connectedDebugAndroidTest 会卸装，慎用）
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  -e class app.androidrik.rikkahub.utils.SecretCipherInstrumentedTest \
  app.androidrik.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w \
  -e class app.androidrik.rikkahub.data.db.migrations.Migration_47_48_Test \
  app.androidrik.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w \
  -e class app.androidrik.rikkahub.data.repository.SshRepositoryInstrumentedTest \
  -e sshUser=sshit -e sshPassword=*** -e sshHost=127.0.0.1 -e sshPort=2222 \
  app.androidrik.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w \
  -e class app.androidrik.rikkahub.data.ai.transformers.WorkspaceReminderTransformerInstrumentedTest \
  -e sshUser=sshit -e sshPassword=*** -e sshHost=127.0.0.1 -e sshPort=2222 \
  app.androidrik.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner

# 线路级抓包：本机起 OpenAI 兼容 mock，设备经 reverse 访问
adb reverse tcp:8099 tcp:8099
# 临时提供商 Base Url=http://127.0.0.1:8099/v1，Path=/chat/completions；
# ⚠️ 模型必须勾选「工具」能力，否则 OpenAI provider 不下发 tools（看不到工作区工具）
```

Git Bash 会把 `/tmp/...` 形式的**环境变量值**当成路径转换，导致远程目录变成 `C:/...`；
运行上面的命令时保留 `MSYS_NO_PATHCONV=1`。

Windows 宿主无 POSIX `/bin/sh` 时，依赖真实 shell 的用例会按既有 `Assume` 跳过；
文件系统、布局、路径换算用例必须全部通过。

本次结果：workspace 115 项测试 0 失败（20 项 Windows 跳过，含未配置环境变量时跳过的
`SshLiveServerTest`）；`RemoteEnvironmentCache` 3 项通过；app 编译与 debug APK 打包成功。
`:app:testDebugUnitTest` 全量 2392 项中仅 `DefaultProvidersTest` 的 2 项余额端点断言失败，
与本次改动文件无关（未触碰默认供应商），属既有状态。

真实 OpenSSH（WSL Ubuntu 24.04，OpenSSH 9.6p1）实测通过：
- 首次主机密钥未确认 ⇒ `SshUnknownHostKeyException`；指纹不匹配 ⇒ `SshHostKeyChangedException`；
- 私钥认证、SFTP 写读/大小/列目录、绝对路径写入与导出；
- `executeCommand` 的退出码/stdout/stderr、stdin、远程 `cwd`、根目录浏览、递归删除；
- 带 PTY 的交互式 shell 能执行命令并读到输出。

真机（PJX110 / Android 15，debug 包，adb reverse 到 WSL OpenSSH）实测通过：

- `SecretCipherInstrumentedTest` 4/4：往返解密、随机 IV、篡改密文拒绝、未知格式拒绝；
- `Migration_47_48_Test` 1/1：v47 迁移到 v48 后的列默认值与 SSH 行读写；
- 真实数据迁移：`user_version=48`，167 个会话 / 3932 个节点未丢失新增 `type`、`ssh_config` 列；
- `SshRepositoryInstrumentedTest` 1/1：凭证以 `v1:` 密文落库且不存在明文密码，`rootfsShell`、
  `executeCommand`（stdout/退出码）、SFTP 写读、远程清理全部通过；
- UI 全链路：新建 SSH 编辑页（首次主机密钥确认 → “信任并继续”后保存）、详情页仅显示
  `workspace_read_file` / `workspace_shell` 与 SSH 连接卡（无 Rootfs、无 Shell 兼容、无终端入口）、
  文件页“工作目录/根目录”、远程文件预览与保存、导入（设备 → 远程）、导出（远程 → Downloads）、
  单击文件打开编辑器、主机密钥变化弹窗（显示新指纹并拒绝）、不可达端口测试报错且退出编辑页后配置不变；
- 绑定助手后 @ 补全列出远程绝对路径（`/home/sshit/rikkahub-ui/...`）；验证后已恢复为未绑定；
- `WorkspaceReminderTransformerInstrumentedTest` 2/2：可达时注入 `<workspace>` 远程说明且不含
  `AGENTS.md`，不可达时注入 `currently unreachable`；
- `请求内容` 预览修复后新增「系统追加（合成）」段（分段数 3 → 4，事件 274 token），
  可直接看到 `<workspace>` 全文；此前预览只投影 `buildSystemBlocks`，会静默丢掉 transformer 追加文本；
- 线路级抓包（WSL/Windows 本机 mock OpenAI 端点 + `adb reverse`，临时提供商，验后已删除）：
  system 含唯一 `<workspace>` 块、`persistent remote workspace`、远程目录，且不含 `AGENTS.md`；
  `tools` 恰为 5 个（`get_time_info`、`workspace_read_file`、`workspace_shell`、`install_skill`、`save_skill`），
  无 write/edit/background/present；`workspace_shell` 参数为 `command/cwd/timeout`。
  另确认：模型未声明 TOOL 能力时 OpenAI provider 会**不下发 tools**（模型能力门控，非工作区问题）。

仍未覆盖：

- 远程提示词注入的**不可达分支**尚未在线路级抓包（transformer 输出已覆盖）；
- 本地 Docker SSH（改用 WSL OpenSSH 替代）、release/R8 下 JSch + BouncyCastle 的体积与 keep 规则；
- 交互式终端（Phase 3）与 `:terminal` 相关回归。
