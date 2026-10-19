# 媒体生成（图片/视频）移植

> 2026-10-19。把上游 RikkaHub 3.0.0 的媒体生成子系统移植进本 fork：
> 新模块 `:mediagen` + 3 张表 + 仓库/文件落盘 + 设置 + 服务/前台服务 + 页面/导航 + 备份兼容。
> 目标不是"全量对齐上游"，而是先让**硅基流动**可用，并保留以后接其它供应商的接口。

## 1. 模块与供应商

`mediagen/` 为纯 Kotlin 库（依赖 `:common`、okhttp、序列化、协程），只保留：

| 适配器 | 类型 | 能力 | 备注 |
|---|---|---|---|
| `openai` | OpenAI 兼容 | 图片（含 `/images/edits` 输入图） | baseUrl 可改 ⇒ **硅基流动图片直接用这个** |
| `siliconflow` | 硅基流动原生 | 视频（T2V/I2V） | `POST /video/submit` → `POST /video/status` 轮询 |

上游另外四个供应商（Aliyun/Volcengine/MiniMax/OpenRouter）的**设置子类保留**：旧设置/备份里的
JSON 能正常反序列化，但 `MediaGenerationManager` 只注册上面两个适配器，调用其它类型会明确返回
`Provider not ported yet`；UI 的新建菜单与模型选择也只放行已接入的供应商。要接新供应商，
实现 `MediaGenerationProvider<T>` + 在 Manager 注册 + UI 放行即可。

## 2. 硅基流动协议（实测 + 官方文档）

见 [官方文档：视频提交](https://api-docs.siliconflow.cn/docs/api/video-submit-post) /
[视频状态](https://api-docs.siliconflow.cn/docs/api/video-status-post) /
[图片生成](https://api-docs.siliconflow.cn/docs/api/images-generations-post)。

- 图片：`POST /v1/images/generations`；OpenAI 风格 `size/n` 与原生 `image_size/batch_size` 都接受，
  返回同时含 `data[]`（OpenAI 兼容别名）与 `images[]`。**两者都只在有限时间内有效**（图片 1 小时、
  视频 10 分钟）⇒ 生成成功后必须立即下载到 `filesDir`，记录里只存相对路径。
- 视频：`submit` 必填 `model/prompt/image_size`（`1280x720`/`720x1280`/`960x960`），I2V 需 `image`
  （本地图编码为 base64 data URI）；`status` 用 **POST**，状态
  `Succeed|InQueue|InProgress|Failed`，成功取 `results.videos[].url`。

## 3. 数据与文件

- DB 升到 **43**：`media_creation_session/node/record` 三表（AutoMigration 42→43，纯新增，无重建）。
  `record` 的 `params/inputs/outputs` 是 JSON，文件路径相对于 `filesDir/media_creation/<sessionId>/`。
- 仓库 `MediaCreationRepository` 负责会话/节点/记录与文件下载落盘；服务 `MediaCreationService`
  负责提交、轮询、取消、NonCancellable 的 taskId 持久化与通知；`MediaCreationForegroundService`
  是 `dataSync` 前台服务。
- 设置新增 `Settings.mediaGenerationProviders`（独立于聊天 providers），DataStore key
  `media_generation_providers`；旧设置/旧备份缺字段 ⇒ 空列表默认，不报错。

## 4. 备份

- 导出：`media_creation/` 加入 `BACKED_UP_FILE_FOLDERS` 的**递归**目录，恢复端新增对应分支
  （复用带 zip-slip 防护的平铺写入）。
- 导入原版 3.x 整库备份：媒体三表结构与上游**逐列一致**（已用真实备份核对），
  `LegacyBackupConverter` 按同名表自动复制；媒体文件按 `media_creation/<sessionId>/…` 恢复。
  目标表必要列缺失时按表跳过并记录，避免单个可选表让整库恢复失败。
  翻译历史（`translation_history`）仍不导入。

## 5. UI 与导航

- 页面：会话列表、生成页、Composer（提示词/素材/参数）、记录项（图片/视频、分享、再来一次、改一改）；
- 路由：`Screen.MediaCreationSessions`、`Screen.MediaCreation(sessionId)`、`Screen.SettingMedia`；
  入口：聊天抽屉「媒体创作」、设置首页「媒体」、通知点开直达会话、`ACTION_MEDIA_CREATION`；
- 设置页：供应商新增/编辑/删除；模型面板、参数面板（视频分辨率等）。

## 6. 验证

- `:mediagen`：**33 tests**（OpenAI 兼容 10 + SiliconFlow 视频 16 + 能力/设置/Manager 7），0 失败；
- app JVM：**210 suites / 1916 tests**，0 失败；隔离真机 Room：**59/59**（含 42→43 迁移 2 项）；
- 真机端到端（图片）：配置 SiliconFlow（OpenAI 兼容）→ 输入提示词 → 生成 → 图片落盘并渲染成功；
- 真机端到端（视频）：SiliconFlow `Wan-AI/Wan2.2-T2V-A14B` 提交 → `POST /video/status` 轮询
  约 3 分钟后成功，产出 5 秒视频落盘为 `files/media_creation/<session>/<node>/out_0.mp4`
  （629 KB）并生成封面图，页面可播放；
- 媒体设置页「+」菜单只显示已接入的 OpenAI/SiliconFlow；`file_paths.xml` 暴露 `media_creation/`
  使分享可用。

## 7. 未完成与边界

1. 视频已做真机端到端验收（T2V）；I2V 的首帧链路只做了本地校验与单测；
2. **I2V 模型 id 修正**：`Wan-AI/Wan2.2-I2V-A14B` 在视频接口上并不存在（实测 400/20012），
   已改为文档与实际探测都有效的 `Wan-AI/Wan2.2-I2V-14B-720P`；旧设置里存了错误 id 的需要删除重建。
   同时加了模型级约束：T2V 拒绝图片输入、I2V 必须且只能带一张首帧，避免发注定失败的请求；
3. 画板已移植并接入媒体素材与聊天附件，见 [画板移植](design-sketch-port.md)。用户后续允许引入 `RemoteFileStore`，现已接入独立 S3 素材存储：仅要求公网 URL 的请求上传，未配置/失败仍明确报错，见 [媒体素材远端存储](design-media-upload.md)；
4. 未移植供应商（含用户旧备份里的 OpenRouter 配置）会被 UI 隐藏、运行期明确失败，不会静默降级；
5. 已知 P2 语义问题（同步接口下 taskId/续查死代码、取消竞态、恢复不清理旧媒体目录）未修；
6. 未做 release/lint/整仓 release 构建验证；未提交 git。
