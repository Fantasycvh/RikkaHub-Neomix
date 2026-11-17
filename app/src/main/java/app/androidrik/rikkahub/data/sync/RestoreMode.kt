package app.androidrik.rikkahub.data.sync

/**
 * 恢复模式（借鉴 Kelivo 的 overwrite/merge 双模式）。
 *
 * - [OVERWRITE]：默认，与历史行为一致——勾选 [BackupItem.DATABASE] 时整库替换（staged 换库），
 *   settings 按备份整份写入（受 scope 排除类别约束）；
 * - [MERGE]：幂等合并——数据库按会话对象「快照有、本地无 → 插入；同 id → 跳过保留本地」
 *   （与第三方导入的确定性 UUID 幂等判重口径一致）；settings 的 providers/assistants 按 id
 *   合并（本地已有的保留），其余字段仍按 scope 处理。仍走 staged 换库与 A 档安全备份，
 *   破坏性安全机制原样生效。
 */
enum class RestoreMode {
    OVERWRITE,
    MERGE,
}
