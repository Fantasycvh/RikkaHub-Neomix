# 工作区文件备份（B 方案）设计稿

> 状态：**方案定稿，未实现**（2026-09-13）。
> 需求来源：用户指出备份**不含工作区**，导致跨设备迁移时工作区里的数据会丢失——「其他设备上会有一些难以再次找到的数据（被下架仓库的克隆等），一旦忘记导出就会丢失内容」。

## 一、现状核实

### 1.1 备份包含什么

`S3Config.BackupItem` 只有两项（`S3Config.kt:37`）：

| 项 | 打包内容 | 恢复 |
|---|---|---|
| （恒含）`settings.json` | Settings | ✅ |
| `DATABASE` | `rikka_hub.db` + `-wal` + `-shm` | ✅ |
| `FILES` | `files/upload`、`files/skills`、`files/fonts` | ✅ |

### 1.2 缺口

工作区数据位于 `<filesDir>/workspaces/<root>/`，结构（`WorkspaceManager`）：

```
workspaces/<root>/
├── files/    ← 用户文件区（FILES 区；UI「文件」页与 AI workspace_* 工具的默认区域）
├── linux/    ← Ubuntu rootfs（含 bin/sh 判定「已安装」），30MB+，可重装
└── tmp/      ← PRoot 临时文件，可弃
```

**备份完全未覆盖该目录** ⇒ 恢复后工作区（含 rootfs 与工作区内文件）整体缺失，DB 里的 `WorkspaceEntity` 记录虽被恢复（在 DATABASE 内）却指向不存在的目录。

### 1.3 一个容易误解的相关事实

「工作区文件仍在」的错觉来源：工作区文件**发送到对话**时会被复制进 `files/upload`（在备份范围内），所以那份副本还在。但**工作区本体**（以及从未发送过的工作区文件）会丢。

另注：`files/tool_outputs`（工具输出外置目录，`FileFolders` 有定义）**也未纳入备份**——若启用外置工具输出，该目录同样丢。与本方案同源问题，见 §6 待决。

## 二、目标与范围

**目标**：备份包含**工作区文件区**（`workspaces/*/files/**`），**排除 rootfs**（`linux/`）与 `tmp/`。

**理由**：
- 用户真正不可再生的数据在 `files/`（下架仓库克隆、生成物等）
- `linux/` 是标准 Ubuntu rootfs，可用现有「安装 rootfs」重装 ⇒ 排除可让备份体积可控（否则至少 +30MB/工作区，压缩后仍大）
- `tmp/` 是 PRoot 运行期临时目录，无备份价值

## 三、格式设计

### 3.1 新增备份项

```kotlin
enum class BackupItem {
    DATABASE,
    FILES,
    WORKSPACE_FILES,   // 新增：工作区文件区（不含 rootfs）
}
```

**两处枚举需同步**：`PreferencesStore.kt:715`（设置持久化用）与 `S3Config.kt:37`（S3 配置用）。二者目前是重复定义，本方案**保持各改一处**（不顺手合并，避免扩大改动面；合并列为 §6 待决）。

**向后兼容**：`@Serializable` enum 新增成员后，旧备份/旧配置反序列化不受影响（未知成员才需 `ignoreUnknownKeys`；这里是新增可选项，旧数据无该值 → 视为未勾选）。默认值建议**不勾选**（体积考虑），由用户在备份设置中主动开启。

### 3.2 zip 内路径

沿用现有「以 `FileFolders` 名为前缀」的约定，工作区条目加独立前缀：

```
workspace_files/<root>/<相对路径>
```

例：`workspace_files/01w5enkg/myrepo/src/main.rs`

- `<root>` = `WorkspaceEntity.root`（目录名，非 UUID）——与磁盘结构一一对应
- 只遍历 `workspaces/<root>/files/`，**不遍历 `linux/`、`tmp/`**

### 3.3 恢复路径与安全

- 目标：`<filesDir>/workspaces/<root>/files/<相对路径>`（父目录按需 `mkdirs`）
- **必须做 zip-slip 防护**（与现有 `upload/` 分支同款，`S3Sync.kt:268` 已有先例）：
  - `resolve(...).canonicalFile` 后校验仍位于 `workspaces/<root>/files/` 内
  - 同时**校验 `<root>` 段合法**（复用 `WorkspaceManager.requireValidRoot` 的规则：拒绝 `/`、`..`、空等），防止用备份里的 `root` 逃逸到其他目录
- 覆盖语义：**合并写入**（已存在同名文件则覆盖，不删除未出现在备份中的文件）——避免「恢复旧备份」意外清空现有工作区内容

## 四、与 DB 中工作区记录的一致性

`WorkspaceEntity`（含 `id`、`root`、`name`）在 `DATABASE` 内，恢复后已存在；工作区文件的恢复按 `root` 对齐即可，**不需要新建实体**。

**三种不一致情形与处理**：

| 情形 | 处理 |
|---|---|
| DB 有该 `root`，备份也有 → 正常恢复文件 | 覆盖/合并写入 |
| 备份有文件但 DB 无对应 `root`（用户只勾了 `WORKSPACE_FILES` 未勾 `DATABASE`，或 DB 是另一份） | **仅落盘文件**，不创建 `WorkspaceEntity`；在恢复结果里提示「有 N 个工作区文件已落盘但无对应工作区记录」 |
| DB 有 `root` 但备份无文件 | 不动（工作区为空文件区） |

**rootfs 缺失的提示（重要）**：恢复后工作区**没有 rootfs**，AI 的 shell 工具与终端会用不了。需在恢复完成后提示：「工作区文件已恢复，rootfs 需重新安装」。可选增强：恢复后自动检测 `!hasRootfs(root)` 并在工作区详情页显示「安装 rootfs」引导（现有 UI 已有该入口）。

## 五、实施步骤（建议拆分）

| 步骤 | 内容 | 规模 |
|---|---|---|
| 1 | 两个 `BackupItem` 加 `WORKSPACE_FILES`；备份设置页加勾选项（6 语言） | 小 |
| 2 | 打包：遍历 `workspaces/*/files/**` → `workspace_files/<root>/...`（S3Sync + WebDavSync **两处都要改**） | 中 |
| 3 | 恢复：`workspace_files/` 分支 + root 校验 + zip-slip 防护（两处 Sync 都要改） | 中 |
| 4 | 恢复后提示「rootfs 需重装」；无对应实体时的提示 | 小 |
| 5 | 单测：路径映射、root 校验拒绝 `..`/绝对路径、zip-slip 拒绝、合并语义（不删现有文件） | 中 |
| 6 | 真机验证：备份→删工作区→恢复→文件齐全且 rootfs 提示正确 | 中 |

**注意**：备份逻辑目前**在 `S3Sync` 与 `WebDavSync` 各有一份独立实现**（几乎是复制粘贴），两处必须同步改，否则 WebDAV 用户得不到该功能。这本身是 §6 的重构隐患。

## 六、待决 / 不在本方案范围

1. ~~本地备份/导出是否是第三份实现~~ **已核实：不是**。`BackupVM` 的本地导出直接复用
   `webDavSync.prepareBackupFile`（`BackupVM.kt:86`）——因此**只需改 S3Sync + WebDavSync 两处**，本地导出自动获益。
2. **`files/tool_outputs` 未纳入备份** —— **已核实确实在用**：`GenerationHandler:696` 将被截断的
   工具输出落盘到该目录（工具结果里改存文件引用），且 `RepositoryModule:60` 将其作为可访问目录挂载。
   建议**本方案一并纳入**（同一修改点：`FILES` 分支加一个目录遍历，成本极低）—— 否则引用该文件的历史
   消息在恢复后会出现「附件无法打开」。
3. **S3Sync / WebDavSync 重复实现** —— 两套备份/恢复逻辑高度重复，理想是抽公共层；本方案不顺手做（避免与功能改动纠缠），列为独立重构项。
4. **备份体积与进度反馈** —— 工作区文件可能很大；是否需要在打包时显示进度/体积预估？建议首版先在 UI 提示「工作区文件可能较大」，不做进度条。
5. **是否可选「包含 rootfs」** —— 本方案排除 rootfs。若将来有用户希望连 rootfs 一起备份（离线迁移且无法联网重装），可加第三档选项；当前不做。
