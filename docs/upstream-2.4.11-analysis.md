# 上游 2.4.11 变更分析（待吸收评估）

> 生成时间：2026-08-24
> 上游起始：`8824e0e8`（我们 master 旧位置）→ `1e3351f7`（当前 master，36 提交）
> 上游版本：2.4.11 tag + 5；
> 状态：**仅同步分析，未吸收**（master 已 ff-only 同步到 `1e3351f7`，已推 origin；特性分支 `feat/restore-proxy` 未动）
>
> 决策：上游仍在持续提交，等这一版稳定后再按需吸收。

## 同步方式

```
git fetch upstream
git checkout master
git merge --ff-only upstream/master
git push origin master
git checkout feat/restore-proxy
```

## 变更规模

78 文件，+2691/-649。含 36 个非合并提交（2.4.9 → 2.4.10 → 2.4.11 → post-2.4.11）。

## 分类评估

### A. 已吸收过（~13 个，无需处理）

这些提交在 2.4.8-2.4.10 批次中已通过选择性吸收纳入我们的分支：

| 提交 | 内容 | 吸收时间 |
|---|---|---|
| `9adc82ec` | jlatexmath R8 keep | 2.4.8 |
| `9b964ca0` | imggen Provider Override | 2.4.8 |
| `f55c8c14` | 流式 null tool_calls 容错 | 2.4.8 |
| `edbd5773` | reasoning max effort | 2.4.8 |
| `0c52b62b` | 加密推理不回放明文 #1719 | 2.4.10 |
| `d1e8effc` | 推理等级刻度移除 | 2.4.10 |
| `de888df2` | DashScope 语音识别修复 | 2.4.10 |
| `97df86ec` | SAF 文件存在性判断 | 2.4.10 |
| `bca21d4d` | .agc 文件支持 | 2.4.10 |
| `c88822d6` | 豆包搜索（我们改了 global 双模式） | 2.4.10 |
| `82758c36` | 英文句首大写 | 2.4.10 |
| `dca7f01c` | 正则排序 #1700 | 2.4.10 |
| `6b37912f` | 移除 claude.md（文档，无关） | — |

### B. 新修复（推荐后续吸收，按优先级排序）

| # | 提交 | 内容 | 价值 | 整合成本 | 说明 |
|---|---|---|---|---|---|
| B1 | `3b4b80a4` | R8 破坏 JWT 解析 → keep Jackson/auth0.jwt | 高 | 极低（+5 行 keep 规则） | **我们正暴露在同样的 bug 下**：webServerJwtEnabled 功能在 release 构建中可能因混淆而静默失效。keep 规则是外部库类名，不受包名迁移影响 |
| B2 | `85402745` | 内联 think 标签误判修复 | 中高 | 中（文件重构 + 包名适配） | **我们分支有同款 bug**：正则 ` thinking([\s\S]*?)` 未锚定 `\A`，消息正文中的 `thinking` 会被误抽为思考块。上游将两段重复逻辑提取为 `transformThinkTags` 扩展函数，正则改为 `\A\s* thinking` 仅匹配开头 |
| B3 | `54030ec4` | Skill frontmatter 改用 SnakeYAML 解析 | 中 | 小（依赖 + parser 替换） | 手写 YAML 解析器在含冒号值/引号/多行等边界会出错；SnakeYAML 2.6 兼容 minSdk 26 |
| B4 | `e6ebcf59` | OpenRouter 加 `session_id` header (#1760) | 视使用 | 中（触及 GenerationHandler/ChatService） | 我们改最多的文件，cherry-pick 需手工适配重试包裹层和包名 |
| B5 | `8b3a1f84` | 小米 MiMo 思考参数 (#1751) | 低 | 极小（8 行，加在已有 when 分支） | 默认供应商列表有 MIMO 但默认禁用 |
| B6 | `f557cef5` | 本地备份导入前覆盖确认 | 中 | 小-中（视文件匹配度） | UX 安全改进；我们备份功能有定制（SettingsJsonMigrator V0），页面对不上则需手动重写 |
| B7+B8 | `91b81fef` / `f167a855` | 模型图标 / 模型能力数据 | 低 | 极低 | 非功能性变更 |

### C. ⚠️ 设计冲突：全局网络配置页（`986b9c39` + `3509406b`）

上游新增了**全局代理 + User-Agent 配置页**（`SettingPreferencesNetworkPage.kt`，378 行），通过 `networkSetting.proxyUrl` 支持 http/socks5 URL + OkHttp Authenticator 基本认证。

这与我们分支的核心特性 **per-provider ProviderProxy** 直接重叠：

- 上游：全局单代理，ProxySelector 级，支持 SOCKS + HTTP + 基本认证
- 我们：每提供商独立代理，OkHttpClient 级，支持 HTTP 基本认证

**若不融合**，两套代理系统共存会导致行为混乱（provider 请求走哪个？哪个优先？）。

**建议融合语义**：provider 级优先于全局——ProviderProxy 非 `None` 时走 provider 级，否则走全局兜底。类似 `retryOption` 的 null=跟随全局模式。

**处理**：暂缓，等有确切需求再做融合方案。

### D. 大件（按需决策）

#### D1. 工作区终端后台运行 + 多 Tab（`3a533a6a` + `f4508dfa`）

说明：用户确认终端是高频场景，价值高。

**架构变化**：将会话生命周期从页面级提升到应用级。

| 维度 | 旧版（我们当前） | 新版（上游 2.4.11） |
|---|---|---|
| 会话归属 | 页面 Composable 内 `produceState` 创建 | `WorkspaceTerminalSessionManager`（app-scoped DI） |
| 生命周期 | 导航离开 → `DisposableEffect` → `session.finishIfRunning()` | 独立于页面，切走 shell 保持运行 |
| 多 Tab | 不支持 | 每个工作区多个 Tab |
| 页面职责 | 创建 + 管理 + 显示 | 只 attach TerminalView 到选中 Tab |

**新增**：`WorkspaceTerminalSessionManager` 240 行。
**重构**：`WorkspaceTerminalPage` 335 行（+455/-135），新增 TabBar + 加号按钮。
**二进制**：`libproot_exec.so` 更新（arm64 233K→267K，估计 proot 修复）。
**DI 注册**：`AppModule.kt` + `ViewModelModule.kt` 各 1 行。

**整合方案**：
- 方案 A：git cherry-pick + 手动改包名——冲突较多（包名差异 + 我们分支对 workspace 页面的改动）
- 方案 B：理解 SessionManager 设计后在分支上重写——按我们的命名风格来，不被冲突卡住

**风险**：旧版页面 `DisposableEffect` 中 `session.finishIfRunning()` 是内存安全兜底；新版清理责任在 SessionManager 的 `closeTab`——迁移时漏掉清理路径会泄漏 termux 进程（proot shell 后台耗电）。

#### D2. 备份选择内容 + 首页 Tab（`7a93c92a`）— 不跟

与我们备份定制冲突风险高（SettingsJsonMigrator V0、远端备份目录），已决定跳过。

#### D3. 其余

| 提交 | 内容 | 决策 |
|---|---|---|
| `c167c70e` | ModelRegistry 注册上下文长度（基建） | 等将来做上下文感知时再拿 |
| `adf333ec` | 上下文消息限制改数字输入 | UX 小改，可随手 |
| `02a0c81c` | 更新依赖 | 谨慎，有冲突风险 |

### E. 不适用（跳过）

| 提交 | 原因 |
|---|---|
| `54b3ba79` | 更新检查修复——我们已移除营销模块 |
| `b270766f` / `693c2ce5` | 版本 bump |
| `1e3351f7` | videogen 模块脚手架（上游在筹备视频生成，观望） |
| `6b37912f` | 删 claude.md（文档） |

## 整合建议顺序

等待上游稳定后，推荐按此顺序吸收：

```
Phase 1（极低风险，立刻收益）
  ├── B1: R8 JWT keep 规则（+5 行）
  └── B5: MiMo 思考参数（8 行）
  └── B7+B8: 模型图标 + 模型数据（资产替换）

Phase 2（中等风险，值）
  ├── D1: 工作区终端后台 + 多 Tab（架构重构，用户高频）
  ├── B2: think 标签修复（逻辑 bug 修复）
  └── B3: SnakeYAML（Skill 稳定性）

Phase 3（视需要）
  ├── B4: OpenRouter session_id（触及核心文件，需手工适配）
  ├── B6: 备份导入确认（视页面匹配度）
  └── C: 全局网络配置页融合（需先做设计决策，再实现）
```

## 注意点

- 所有提交的包名是 `me.rerere.*`，我们的分支是 `app.androidrik.*`，cherry-pick 后每个文件都需要手动改 import
- `GenerationHandler.kt` 和 `ChatService.kt` 是我们改动最多的文件（重试包裹层、MCP 命名净化、工具循环），触及这两个文件的提交需要特别仔细地手工适配
- 上游的 `master` 分支已同步到 `1e3351f7` 并推送到 origin，后续增量新提交可直接 `git fetch upstream && git merge --ff-only upstream/master && git push origin master`