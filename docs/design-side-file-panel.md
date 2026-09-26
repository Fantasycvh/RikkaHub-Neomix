# 侧边文件栏内联文件管理（设计稿）

> 状态：**已落地**。核心文件浏览/管理此前已由详情页承担（本设计稿的核实结论），本稿增量部分（搜索防抖 250ms + 缓存失效）已于 09-16 落地（`5abe8bf6`，`48e9d806` 记账收尾核实——设计稿 6.5.2 承诺项漏做补齐）。原「设计定稿，未实现」（2026-09-16）标记过时，特此更正。
> 关联：`design-backup-workspace-files.md`（工作区文件备份）、`AGENTS.md`（workspace 模块职责）。

## 一、现状核实（提问预设与实况的出入）

**提问：「允许侧边的文件栏可以管理文件」——但侧边栏现在根本不做文件浏览。**

侧滑文件栏 = `FilesPicker.kt`（`ChatPage.ChatFilesPickerSheet` 以 `ModalBottomSheet` 呈现），
其内容为：

| 区块 | 内容 |
|---|---|
| 附件选择 | 拍照 / 相册 / 视频 / 音频 / 文件（`FlowRow` 一排图标） |
| 工作区 | 绑定信息（`assistant.workspaceId`） |
| 入口 | 扩展管理、压缩上下文、cwd 选择 |
| 工作区行 | 工作区名 + 两个**跳转**按钮：`onNavigateToDetail` / `onNavigateToTerminal` |

即：**"管理文件"已存在，但是以"1 次点击跳转到详情页"的形式**（`onNavigateToDetail`）。
本设计要做的是**把它内联到侧边栏**。

## 二、能力盘点（逻辑层几乎齐备）

### 2.1 已有后端（无需新增）

`workspace` 模块 `WorkspaceFileSystem`：

| 方法 | 用途 |
|---|---|
| `list(root, path)` | 列目录 → `List<WorkspaceFileEntry>` |
| `readText` / `writeText` | 读/写文本 |
| `importBytes` | 导入（带 `resolveConflict` 重名处理） |
| `delete(root, path, recursive)` | 删除（递归由参数控制） |
| **`move`** | 移动/重命名 |
| `glob` / `grep` | 搜索 |

`WorkspaceRepository` 已暴露给 app 层：`listFiles` / `readText` / `writeText` /
`readTextForPreview` / `importFile` / `exportFile` / **`deleteFile`** / **`moveFile`** /
`fileSize`。

`WorkspaceFileEntry(path, name, isDirectory, sizeBytes, updatedAt)`——字段足够渲染列表。

### 2.2 已有 UI（完整文件管理器，但不在侧边栏）

`WorkspaceDetailPage.kt`（907 行）已是一个完整管理器：

- 双**存储区**切换（`WorkspaceStorageArea.FILES` / `LINUX`）+ 路径栏 + 上级/刷新
- 文件卡片：`open`（目录进入 / 文本进编辑器 / 图片预览 / 其他导出后打开）、删除（**已有确认弹窗**）、导出、缩略图
- 导入（SAF `filePicker.launch(arrayOf("*/*"))`）+ **导出到目录**（SAF 目录授权）
- 终端入口

其 private 组件即本设计要复用的目标：
`WorkspaceFilesPage` / `WorkspaceFileCard` / `WorkspaceAreaSelector` / `WorkspacePathBar` /
`EmptyDirectoryState` / `ErrorCard`。

### 2.3 三个缺口（都不在"能力"上）

| # | 缺口 | 说明 |
|---|---|---|
| 1 | **`moveFile` 未接线** | 仓库有方法，但 VM/UI 均无调用 → **重命名/移动目前无入口** |
| 2 | **无 `mkdir`** | `WorkspaceFileSystem` 没有"创建目录"方法（现有 `mkdirs` 都是 `RootfsPatcher`/`RootfsInstaller`/`ProotShellRunner` 的内部副作用）→ 新建文件夹需补后端 |
| 3 | **组件是 private** | `WorkspaceFilesPage` 等位于 `WorkspaceDetailPage.kt` 内且 private → 侧边栏复用必须先抽成共享 composable（**主要工作量**） |

## 三、范围决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **先只做 `FILES` 区** | 用户日常关心的"工作区文件"即 `FILES`；`LINUX`（rootfs）是系统层、侧边栏展示意义低且危险面大。**区域维度先不开放**，详情页仍两区可选 |
| 2 | **内联浏览 + 常用管理**（档 2，见 §五） | 逻辑全现成，成本集中在 UI 抽取 |
| 3 | **重命名/移动/新建 分第二批** | 增量小但涉及后端改动（`mkdir`）与维护面扩大 |
| 4 | **抽取共享组件而非复制** | 见 §四——这是本设计最关键的一条 |

> **2026-09-26 进展**：§四 提的「共享组件抽取」**已落地**（为多选导出铺路，用户决议「先定边界」）：
> 组件在 `ui/components/workspace/WorkspaceFileBrowser.kt`，详情页已改为使用它，且**边界里已含选择态**
> （宿主持有 `selecting/selectedPaths/onSelectToggle/onLongPress`）。侧边栏落地时**直接复用**即可，
> 不必再搬一次状态。另：§八.4 那句「导出到目录（SAF 目录授权）」当时是错的（全仓无 `OpenDocumentTree`），
> 该能力现在**已经有了**（多选导出的「逐个导出到目录」）——侧边栏要不要给这个入口由你定（建议省略）。

## 四、核心设计：抽取共享文件浏览组件

**问题**：改造后详情页与侧边栏会渲染同一份文件列表。若复制一份 UI，两处必然漂移
（项目已有教训：`BackupItem` 枚举统一、`BackupArchiveEngine` 抽取、`ChatToolFactory` 集中装配
——都是为消除重复实现）。

**方案**：把详情页的文件浏览部分抽成**共享 composable**，两侧传入不同的"宿主策略"。

### 4.1 共享层（新）

```
WorkspaceFileBrowser(            // 无状态：列表 + 路径栏 + 空态/错误态
    entries, path, loading, error,
    onOpen, onDelete, onExport, onImport, onGoUp,
    compact: Boolean,            // 侧边栏紧凑模式（见 §四.3）
)
```

含：`WorkspaceFileCard`（把 `onExportToDir` 这类宿主专属能力做成**可选参数**）、
`WorkspacePathBar`、`EmptyDirectoryState`、`ErrorCard`、`WorkspaceAreaSelector`（侧边栏传
`areas = listOf(FILES)` 或隐藏）。

### 4.2 状态与动作层（两侧各自持有）

- 详情页：`WorkspaceDetailVM`（现有，含 `listFiles`/`delete`/`importFile`/`exportFile`/…）
- 侧边栏：**新增轻量 VM 或复用 repository 直调**——建议新建 `WorkspaceFileBrowserVM`（按
  workspaceId + area 持有 `entries/path/loading/error`），详情页与侧边栏**共用**它，
  避免又一份状态机。`WorkspaceDetailVM` 可保留其额外能力（终端/rootfs/工具审批）并委托浏览部分。

### 4.3 适配点（两侧差异）

| 差异 | 详情页 | 侧边栏 |
|---|---|---|
| 布局 | 全屏页 | `ModalBottomSheet`，空间紧 |
| 动作呈现 | 平铺按钮/下拉 | **`DropdownMenu` 收纳**（避免一行塞不下） |
| 区域切换 | 两区 | 隐藏（只 `FILES`） |
| 导出到目录 | 有（SAF 目录授权） | **可省略**（窄场景） |
| 打开文件 | 进编辑器/预览页 | **同一导航**（`Screen.WorkspaceFileEditor`）——侧边栏点开后应关闭 sheet 再跳转 |
| 导入 | SAF `OpenMultipleDocuments` | 复用同一 launcher（**必须带同一套 SAF 流程，不能只搬列表 UI**） |

## 五、分档成本

| 档 | 内容 | 难度 | 说明 |
|---|---|---|---|
| 0 | 现状（跳转详情页） | — | 已可用 |
| **1** | 侧边栏内联列表 + 浏览/打开 | **中** | 抽组件 + sheet 紧凑布局 |
| **2** | 档 1 + 删除/导入/导出 | **中** | 接线已有 VM 方法；删除复用现有确认弹窗 |
| 3 | 档 2 + 重命名/移动 | 中 | +`moveFile` 接线 + 命名对话框 |
| 4 | 档 3 + 新建文件/文件夹 | 中-高 | +后端 `mkdir`（workspace 模块）+ 命名对话框 |

**建议按档 2 做第一批**：逻辑零新增，成本集中在"抽共享组件 + sheet 适配"。
档 3/4 作为第二批。

**预估**：档 2 约 1–2 提交（大头是组件抽取与两侧接线）；档 3/4 各约 0.5–1 提交。

## 六、风险与注意点

1. **两份 UI 漂移**（最高优先）——**必须抽共享组件**，这是本次改造的核心价值之一；
   若图快复制一份，后续每次文件功能改动都要改两处。
2. **`deleteFile` 递归语义**：现有调用传 `recursive = entry.isDirectory`（删目录即级联）。
   侧边栏误触代价更直观 → **必须保留确认弹窗**，并在文案中写明目录将被递归删除。
3. **sheet 空间**：详情页那套平铺按钮在 sheet 里会挤；用 `DropdownMenu` 收纳次要动作。
4. **SAF 流程复用**：导入/导出依赖 `ActivityResultContracts`（含目录授权的持久化 URI），
   侧边栏复用须带上同一套 launcher 与 `onExportToDir` 的 URI 处理，不能只搬列表。
5. **点开文件后的 sheet 收尾**：进入编辑器/预览前应先 `onDismiss` 关闭 sheet，避免返回时叠层。
6. **缩略图成本**：详情页卡片对图片做 `exportToCacheDir` 生成缩略图（IO + 缓存目录）。
   sheet 中若列表较长，注意**只对可见项生成**（`LazyColumn` 已天然懒加载，但要避免在
   item 内同步 IO）。
7. **路径安全**：`WorkspaceFileSystem.resolvePath` 已有防逃逸；侧边栏走同一层，**不要绕开**。

## 七、不做（本次范围外）

- **`LINUX`（rootfs）区**在侧边栏的展示与编辑——危险面大、价值低；需要时仍走详情页。
- **文件内容在线编辑的增强**（多标签、语法高亮等）——`WorkspaceFileEditorPage` 现有能力不动。
- **跨工作区操作/拖拽移动**——`move` 支持同名区内移动；跨工作区不在本次范围。

## 八、待确认（实现前定）

1. **档位**：确认按档 2 起（浏览/打开/删除/导入/导出），档 3/4 第二批？
2. **`WorkspaceFileBrowserVM` 是否新建**（推荐）还是让 `WorkspaceDetailVM` 同时服务两侧？
3. **侧边栏是否保留"跳转到详情页"入口**（我建议保留——完整管理/终端/rootfs 仍需它）。
4. **导出到目录**在侧边栏是否保留（我建议省略）。⚠️ 2026-09-26 更正：本仓**没有**「导出到目录（SAF 目录授权）」这条路径（全仓零处 `OpenDocumentTree`，只有单文件 `CreateDocument`）——本行原先把「导出到目录」当成详情页已有能力，属事实错误。
