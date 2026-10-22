# releases —— 发布说明存档

本目录按 tag 存档已发布版本的 release 正文原文（便于在仓库内检索"某版到底改了什么"）。
GitHub 上的权威出处：[Releases](https://github.com/Fantasycvh/RikkaHub-Neomix/releases)。

| 版本 | 日期 | 资产 |
|---|---|---|
| [`1.0.28-beta.7.md`](1.0.28-beta.7.md) | 2026-10-22 | ✅ arm64-v8a APK（**首个带资产的版本**） |
| [`1.0.28-beta.6.md`](1.0.28-beta.6.md) | 2026-10-21 | 仅 tag |

约定：

- 资产只发 **arm64-v8a**，文件名 `Neomix-<versionName>-arm64-v8a.apk`；签名密钥为自签 `CN=neomix`
  （证书 SHA-256 见各版说明），与 `beta.1`…`beta.6` 同一把 ⇒ 可覆盖升级；**换密钥需卸载重装**。
- 每次发布后在说明里给出 **SHA-256**，并用 GitHub API 回读资产 `digest` 与本地产物比对。
