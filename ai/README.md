# 环境配置

## 准备环境

- 安装CMake
- 安装NDK，并配置`ANDROID_NDK`环境变量

## git submodule

在项目根目录执行以下命令初始化子模块：

```bash
git submodule update --init --recursive
```

注意：必须在git仓库的根目录（`rikkahub/`）执行此命令，不是在 `src/main/cpp/mnn` 目录。

## 构建libMNN.so

进入 `src/main/cpp/mnn` 目录，执行以下命令：

```bash
./build.sh
```

## 移植说明（fork 与上游的关系）

本模块的 provider / 流式层是从上游 RikkaHub 移植并长期维护的，主要参考点：

| 上游 | 内容 | 本仓落点 |
| --- | --- | --- |
| `#1648`（`4b9ff1a99`） | `StreamChunk` 接口 + 四 provider 流式解码器重构 | P1：`ai/ui/StreamChunk.kt`、各 provider 的 `parse*Stream` |
| `#1648` 后续 | `ResponsesEndpoint` / `ResponseProviderCapabilities` 抽象 | P4：`providers/openai/ResponseAPI.kt`，ChatGPT/Grok 账号 provider 复用 |
| `#2186`（`318cffef6`） | ChatGPT 账号类 provider、Codex 后端能力集、账号鉴权 | P4：`providers/chatgpt/`、`providers/grok/`、`providers/AccountAuth.kt` |

**刻意偏离上游**（细节见 `docs/design/drafts/design-upstream-oauth-port.md` 各阶段「刻意偏离」表）：

- OAuth 凭据由 app 层 `SecretCipher` 加密落盘（`v1:` 前缀），ai 层只拿明文令牌；
- `TextGenerationParams.sessionId` 默认 `null`，由 app 层显式传对话 id（上游默认随机 UUID）；
- Codex SSE 宽容解码只作用于 Codex 端点，不扩大到全局 `EventSource`；
- 账号类 provider（ChatGPT / Grok）不接收用户配置的 `baseUrl`/`apiKey`，地址写死在 provider 内。

**历史包袱**：本仓曾用「OpenAI 设备码登录 + `codexAccount`」形态做 ChatGPT 订阅（见
`docs/design/drafts/design-codex-subscription-login.md`）。该链路已在 P5 删除，旧数据由 V5 迁移
（`app` 模块）一次性转成账号类 provider。
